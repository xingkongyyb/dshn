package dev.dshn.app.bridge.virtualdisplay;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Privileged Android 14+ (API 34+) background Virtual Display agent executed via `app_process`
 * under either `uid=2000` (Wireless/USB ADB shell) or `uid=0` (user-authorized Root `su`).
 *
 * Adapted from DSH Sugar's MIT-licensed `VirtualAgent`/`VirtualFrames`/`VirtualWindows`/`VirtualOperations`,
 * with DSHN's `DSHN_VD:` line-framed JSON protocol and zero-copy Rust `libdshn_core.so` JPEG frame encoding.
 */
public final class VirtualDisplayAgent {

    public static final String WIRE_PREFIX = "DSHN_VD:";
    private static final int DEFAULT_WIDTH = 720;
    private static final int DEFAULT_HEIGHT = 1280;
    private static final int DEFAULT_DPI = 300;

    private final Context shellContext;
    private final PrintStream wireOut;
    private final RustFrameBridge rustBridge;
    private final Handler mainHandler;

    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Display display;
    private int displayId = Display.INVALID_DISPLAY;
    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    private int dpi = DEFAULT_DPI;

    private final Object frameLock = new Object();
    private Image latestImage;
    private long latestImageUptimeMs = 0L;
    private volatile boolean closed = false;

    private HandlerThread uiThread;
    private UiAutomation uiAutomation;
    private InputManager inputManager;
    private Method injectInputMethod;

    private VirtualDisplayAgent(Context shellContext, RustFrameBridge rustBridge) {
        this.shellContext = shellContext;
        this.wireOut = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        this.rustBridge = rustBridge;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private void emit(JSONObject obj) {
        synchronized (wireOut) {
            wireOut.print(WIRE_PREFIX);
            wireOut.println(obj.toString());
            wireOut.flush();
        }
    }

    private static JSONObject fail(String code, Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isEmpty()) msg = cause.getClass().getSimpleName();
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", new JSONObject().put("code", code).put("message", msg));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void runCommandLoop() {
        try {
            JSONObject hello = new JSONObject()
                .put("event", "hello")
                .put("ok", true)
                .put("protocol", 1)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("runtime_uid", android.os.Process.myUid())
                .put("driver", android.os.Process.myUid() == 0 ? "root" : "adb")
                .put("rust_encoder", rustBridge.isAvailable());
            emit(hello);

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                JSONObject req;
                try {
                    req = new JSONObject(trimmed);
                } catch (Exception e) {
                    emit(fail("bad_json", e));
                    continue;
                }
                long reqId = req.optLong("id", 0L);
                String op = req.optString("op", "");
                JSONObject args = req.optJSONObject("args");
                if (args == null) args = new JSONObject();

                JSONObject result;
                try {
                    result = handleOp(op, args);
                } catch (Throwable t) {
                    result = fail("op_failed", t);
                }
                JSONObject envelope = new JSONObject()
                    .put("id", reqId)
                    .put("result", result);
                emit(envelope);
                if ("close".equals(op)) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            releaseResources();
            System.exit(0);
        }
    }

    private JSONObject handleOp(String op, JSONObject args) throws Exception {
        switch (op) {
            case "open":
                return openDisplay(args);
            case "launch":
                requireOpen();
                return launchApp(args);
            case "observe":
                requireOpen();
                return observeDisplay(args);
            case "tap":
                requireOpen();
                return tapDisplay(args);
            case "swipe":
                requireOpen();
                return swipeDisplay(args);
            case "input":
                requireOpen();
                return inputText(args);
            case "key":
                requireOpen();
                return sendKey(args);
            case "close":
                releaseResources();
                return new JSONObject().put("ok", true).put("closed", true);
            default:
                return fail("unknown_op", new IllegalArgumentException("Unsupported op: " + op));
        }
    }

    private void requireOpen() throws IOException {
        if (closed || virtualDisplay == null || display == null || displayId <= Display.DEFAULT_DISPLAY) {
            throw new IOException("Virtual display is not open.");
        }
    }

    @SuppressLint("WrongConstant")
    private JSONObject openDisplay(JSONObject args) throws Exception {
        if (Build.VERSION.SDK_INT < 34) {
            throw new UnsupportedOperationException("Android 14+ (API 34+) is required for background virtual displays.");
        }
        if (virtualDisplay != null && displayId > Display.DEFAULT_DISPLAY) {
            return readyPayload();
        }
        width = clamp(args.optInt("width", DEFAULT_WIDTH), 360, 2560);
        height = clamp(args.optInt("height", DEFAULT_HEIGHT), 360, 2560);
        dpi = clamp(args.optInt("dpi", DEFAULT_DPI), 160, 480);

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(reader -> {
            synchronized (frameLock) {
                if (closed) return;
                Image next = reader.acquireLatestImage();
                if (next != null) {
                    if (latestImage != null) latestImage.close();
                    latestImage = next;
                    latestImageUptimeMs = SystemClock.uptimeMillis();
                    frameLock.notifyAll();
                }
            }
        }, mainHandler);

        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        for (String name : new String[]{"SUPPORTS_TOUCH", "TRUSTED", "OWN_DISPLAY_GROUP", "OWN_FOCUS"}) {
            try {
                flags |= DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_" + name).getInt(null);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        DisplayManager dm = shellContext.getSystemService(DisplayManager.class);
        if (dm == null) throw new IOException("DisplayManager unavailable.");

        virtualDisplay = dm.createVirtualDisplay(
            "DSHN Virtual",
            width,
            height,
            dpi,
            imageReader.getSurface(),
            flags,
            new VirtualDisplay.Callback() {
                @Override
                public void onStopped() {
                    closed = true;
                    try {
                        emit(new JSONObject().put("event", "stopped").put("display_id", displayId));
                    } catch (Exception ignored) {
                    }
                }
            },
            mainHandler
        );
        if (virtualDisplay == null) {
            releaseResources();
            throw new IOException("Android refused to create virtual display.");
        }
        display = virtualDisplay.getDisplay();
        if (display == null || display.getDisplayId() <= Display.DEFAULT_DISPLAY) {
            releaseResources();
            throw new IOException("Expected a separate non-default display ID.");
        }
        displayId = display.getDisplayId();
        closed = false;

        // Input injection handle (Android 14+)
        inputManager = shellContext.getSystemService(InputManager.class);
        if (inputManager != null) {
            try {
                injectInputMethod = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                injectInputMethod = null;
            }
        }

        // Optional UiAutomation connection scoped to reading virtual display UI trees without suppressing AccessibilityService.
        try {
            connectUiAutomation();
        } catch (Throwable ignored) {
            uiAutomation = null;
        }

        return readyPayload();
    }

    private JSONObject readyPayload() throws Exception {
        return new JSONObject()
            .put("ok", true)
            .put("display_id", displayId)
            .put("width", width)
            .put("height", height)
            .put("dpi", dpi)
            .put("driver", android.os.Process.myUid() == 0 ? "root" : "adb")
            .put("rust_encoder", rustBridge.isAvailable())
            .put("ui_tree_available", uiAutomation != null)
            .put("direct_touch_available", injectInputMethod != null);
    }

    private void connectUiAutomation() throws Exception {
        if (uiAutomation != null) return;
        uiThread = new HandlerThread("dshn-vd-ui");
        uiThread.start();
        UiAutomation created = null;
        try {
            Class<?> binderClass = Class.forName("android.app.IUiAutomationConnection");
            Object conn = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            try {
                Constructor<?> ctor = UiAutomation.class.getDeclaredConstructor(Looper.class, binderClass);
                created = (UiAutomation) ctor.newInstance(uiThread.getLooper(), conn);
            } catch (NoSuchMethodException e) {
                Constructor<?> ctor = UiAutomation.class.getDeclaredConstructor(Context.class, binderClass);
                created = (UiAutomation) ctor.newInstance(shellContext, conn);
            }
            UiAutomation.class.getMethod("connect", int.class)
                .invoke(created, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = created.getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                created.setServiceInfo(info);
            }
            uiAutomation = created;
        } catch (Exception e) {
            if (created != null) {
                try {
                    UiAutomation.class.getMethod("disconnect").invoke(created);
                } catch (Exception ignored) {
                }
            }
            if (uiThread != null) {
                uiThread.quitSafely();
                uiThread = null;
            }
            throw e;
        }
    }

    private JSONObject launchApp(JSONObject args) throws Exception {
        String pkg = args.optString("package", "").trim();
        if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("Invalid package name: " + pkg);
        }
        String activity = args.optString("activity", "").trim();
        if (activity.isEmpty()) {
            Intent launch = shellContext.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null && launch.getComponent() != null) {
                activity = launch.getComponent().getClassName();
            }
        }
        if (activity.startsWith(".")) activity = pkg + activity;
        if (!activity.matches("[a-zA-Z_$][a-zA-Z0-9_$.]*")) {
            throw new IOException("No launchable main activity found for package: " + pkg);
        }

        int targetUser = 0;
        try {
            targetUser = (Integer) ActivityManager.class.getMethod("getCurrentUser").invoke(null);
        } catch (ReflectiveOperationException ignored) {
        }

        ComponentName component = new ComponentName(pkg, activity);
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;

        List<String> cmd = Arrays.asList(
            "/system/bin/cmd", "activity", "start-activity",
            "--user", Integer.toString(targetUser),
            "--display", Integer.toString(displayId),
            "-f", Integer.toString(flags),
            "-n", component.flattenToString()
        );
        String out = runCli(cmd.toArray(new String[0]));

        // Brief settle poll waiting for first rendered frame on the virtual display
        long deadline = SystemClock.uptimeMillis() + 1800L;
        long beforeUptime = latestImageUptimeMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (latestImageUptimeMs > beforeUptime) break;
            SystemClock.sleep(60);
        }

        return new JSONObject()
            .put("ok", true)
            .put("display_id", displayId)
            .put("package", pkg)
            .put("component", component.flattenToString())
            .put("top_component", findTopComponentOnDisplay())
            .put("detail", out);
    }

    private JSONObject observeDisplay(JSONObject args) throws Exception {
        boolean includeTree = args.optBoolean("include_tree", true);
        boolean includeImage = args.optBoolean("include_image", true);
        int quality = clamp(args.optInt("quality", 72), 35, 95);
        int maxWidth = clamp(args.optInt("max_width", width), 240, width);
        int maxNodes = clamp(args.optInt("max_nodes", 160), 20, 400);
        int waitFrameMs = clamp(args.optInt("wait_frame_ms", 900), 0, 3000);
        long prevHash = args.optLong("previous_hash", 0L);

        JSONObject out = new JSONObject()
            .put("ok", true)
            .put("display_id", displayId)
            .put("width", width)
            .put("height", height)
            .put("top_component", findTopComponentOnDisplay());

        if (includeTree) {
            out.put("tree", dumpDisplayTree(maxNodes));
        }

        if (includeImage) {
            FrameCapture cap = captureLatestJpeg(maxWidth, quality, prevHash, waitFrameMs);
            out.put("frame_uptime_ms", cap.uptimeMs);
            out.put("frame_hash", cap.hash);
            out.put("unchanged", cap.unchanged);
            out.put("encoder", cap.usedRust ? "rust" : "android");
            if (!cap.unchanged && cap.jpegBytes != null) {
                out.put("image_width", cap.outWidth);
                out.put("image_height", cap.outHeight);
                out.put("jpeg_base64", Base64.encodeToString(cap.jpegBytes, Base64.NO_WRAP));
            }
        }
        return out;
    }

    private static final class FrameCapture {
        final byte[] jpegBytes;
        final int outWidth;
        final int outHeight;
        final long hash;
        final long uptimeMs;
        final boolean unchanged;
        final boolean usedRust;

        FrameCapture(byte[] jpegBytes, int outWidth, int outHeight, long hash, long uptimeMs, boolean unchanged, boolean usedRust) {
            this.jpegBytes = jpegBytes;
            this.outWidth = outWidth;
            this.outHeight = outHeight;
            this.hash = hash;
            this.uptimeMs = uptimeMs;
            this.unchanged = unchanged;
            this.usedRust = usedRust;
        }
    }

    private FrameCapture captureLatestJpeg(int maxWidth, int quality, long previousHash, int waitFrameMs) throws Exception {
        int dstW = Math.min(width, maxWidth);
        int dstH = Math.max(1, (int) Math.round((double) height * dstW / width));

        synchronized (frameLock) {
            if (latestImage == null && waitFrameMs > 0) {
                long deadline = SystemClock.uptimeMillis() + waitFrameMs;
                while (latestImage == null && !closed && SystemClock.uptimeMillis() < deadline) {
                    long remaining = deadline - SystemClock.uptimeMillis();
                    if (remaining <= 0L) break;
                    frameLock.wait(Math.min(100L, remaining));
                }
            }
            if (latestImage == null) {
                return new FrameCapture(null, dstW, dstH, previousHash, 0L, true, rustBridge.isAvailable());
            }
            Image.Plane plane = latestImage.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            long uptime = latestImageUptimeMs;

            if (pixelStride == 4 && buffer.isDirect() && rustBridge.isAvailable()) {
                long[] outHash = new long[1];
                byte[] jpeg = rustBridge.encode(buffer, width, height, rowStride, dstW, dstH, quality, previousHash, outHash);
                if (jpeg == null) {
                    return new FrameCapture(null, dstW, dstH, previousHash, uptime, true, true);
                }
                return new FrameCapture(jpeg, dstW, dstH, outHash[0], uptime, false, true);
            }

            // Fallback Android Bitmap path (skip re-encoding if ImageReader timestamp is unchanged)
            if (previousHash != 0L && (previousHash >>> 16) == uptime) {
                return new FrameCapture(null, dstW, dstH, previousHash, uptime, true, false);
            }
            int rowPadding = rowStride - pixelStride * width;
            int bmpWidth = width + (pixelStride > 0 ? rowPadding / pixelStride : 0);
            Bitmap raw = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            raw.copyPixelsFromBuffer(buffer);
            Bitmap cropped = (bmpWidth == width) ? raw : Bitmap.createBitmap(raw, 0, 0, width, height);
            Bitmap scaled = (dstW == width && dstH == height)
                ? cropped
                : Bitmap.createScaledBitmap(cropped, dstW, dstH, true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            if (scaled != cropped) scaled.recycle();
            if (cropped != raw) cropped.recycle();
            raw.recycle();
            byte[] jpeg = baos.toByteArray();
            long fallbackHash = (uptime << 16) ^ jpeg.length;
            return new FrameCapture(jpeg, dstW, dstH, fallbackHash, uptime, false, false);
        }
    }

    private JSONObject dumpDisplayTree(int maxNodes) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray nodes = new JSONArray();
        if (uiAutomation == null) {
            return result.put("available", false).put("nodes", nodes);
        }
        AccessibilityWindowInfo win = findTargetWindow();
        if (win == null) {
            return result.put("available", true).put("nodes", nodes);
        }
        AccessibilityNodeInfo root = win.getRoot();
        if (root != null) {
            try {
                if (root.getPackageName() != null) {
                    result.put("package", root.getPackageName().toString());
                }
                visitNode(root, "0", nodes, 0, maxNodes);
            } finally {
                root.recycle();
            }
        }
        return result.put("available", true).put("nodes", nodes).put("truncated", nodes.length() >= maxNodes);
    }

    private AccessibilityWindowInfo findTargetWindow() {
        if (uiAutomation == null) return null;
        try {
            List<AccessibilityWindowInfo> windows = uiAutomation.getWindowsOnAllDisplays().get(displayId);
            if (windows == null) return null;
            AccessibilityWindowInfo best = null;
            for (AccessibilityWindowInfo w : windows) {
                if (w.getDisplayId() != displayId) continue;
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (w.isFocused()) return w;
                if (best == null || w.getLayer() > best.getLayer()) best = w;
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void visitNode(AccessibilityNodeInfo node, String id, JSONArray out, int depth, int maxNodes) throws Exception {
        if (out.length() >= maxNodes || depth > 28) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        String text = node.isPassword() ? "" : clip(node.getText(), 240);
        String desc = node.isPassword() ? "" : clip(node.getContentDescription(), 240);
        String resId = clip(node.getViewIdResourceName(), 120);
        String cls = clip(node.getClassName(), 120);
        boolean interesting = !text.isEmpty() || !desc.isEmpty() || node.isClickable() || node.isEditable() || node.isCheckable();
        if (interesting || depth <= 2) {
            JSONObject item = new JSONObject()
                .put("id", id)
                .put("class", cls)
                .put("bounds", new JSONArray(new int[]{r.left, r.top, r.right, r.bottom}))
                .put("clickable", node.isClickable())
                .put("editable", node.isEditable())
                .put("enabled", node.isEnabled());
            if (!text.isEmpty()) item.put("text", text);
            if (!desc.isEmpty()) item.put("desc", desc);
            if (!resId.isEmpty()) item.put("res_id", resId);
            out.put(item);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count && out.length() < maxNodes; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    visitNode(child, id + "." + i, out, depth + 1, maxNodes);
                } finally {
                    child.recycle();
                }
            }
        }
    }

    @TargetApi(34)
    private boolean injectTouchEvent(long downTime, int action, float x, float y) throws Exception {
        if (injectInputMethod == null || inputManager == null) return false;
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.size = 1f;
        pc.pressure = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? 0f : 1f;
        MotionEvent ev = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            1,
            new MotionEvent.PointerProperties[]{pp},
            new MotionEvent.PointerCoords[]{pc},
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN,
            displayId,
            0,
            MotionEvent.CLASSIFICATION_NONE
        );
        if (ev == null) return false;
        try {
            return Boolean.TRUE.equals(injectInputMethod.invoke(inputManager, ev, 1));
        } finally {
            ev.recycle();
        }
    }

    private JSONObject tapDisplay(JSONObject args) throws Exception {
        float x = (float) args.getDouble("x");
        float y = (float) args.getDouble("y");
        validatePoint(x, y);
        boolean usedDirect = false;
        if (injectInputMethod != null) {
            long down = SystemClock.uptimeMillis();
            if (injectTouchEvent(down, MotionEvent.ACTION_DOWN, x, y)) {
                SystemClock.sleep(35);
                injectTouchEvent(down, MotionEvent.ACTION_UP, x, y);
                usedDirect = true;
            }
        }
        if (!usedDirect) {
            runCli("/system/bin/cmd", "input", "-d", Integer.toString(displayId), "tap",
                Integer.toString((int) x), Integer.toString((int) y));
        }
        return new JSONObject().put("ok", true).put("display_id", displayId).put("direct", usedDirect);
    }

    private JSONObject swipeDisplay(JSONObject args) throws Exception {
        float x1 = (float) args.getDouble("x1");
        float y1 = (float) args.getDouble("y1");
        float x2 = (float) args.getDouble("x2");
        float y2 = (float) args.getDouble("y2");
        validatePoint(x1, y1);
        validatePoint(x2, y2);
        int durationMs = clamp(args.optInt("duration_ms", 300), 40, 2500);

        boolean usedDirect = false;
        if (injectInputMethod != null) {
            long down = SystemClock.uptimeMillis();
            if (injectTouchEvent(down, MotionEvent.ACTION_DOWN, x1, y1)) {
                int steps = Math.max(4, durationMs / 16);
                for (int i = 1; i <= steps; i++) {
                    SystemClock.sleep(durationMs / steps);
                    float t = (float) i / steps;
                    injectTouchEvent(down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
                }
                injectTouchEvent(down, MotionEvent.ACTION_UP, x2, y2);
                usedDirect = true;
            }
        }
        if (!usedDirect) {
            runCli("/system/bin/cmd", "input", "-d", Integer.toString(displayId), "swipe",
                Integer.toString((int) x1), Integer.toString((int) y1),
                Integer.toString((int) x2), Integer.toString((int) y2),
                Integer.toString(durationMs));
        }
        return new JSONObject().put("ok", true).put("display_id", displayId).put("direct", usedDirect);
    }

    private JSONObject inputText(JSONObject args) throws Exception {
        String text = args.optString("text", "");
        if (text.isEmpty()) throw new IllegalArgumentException("text is empty");

        // Prefer UiAutomation ACTION_SET_TEXT so Chinese & Unicode input works reliably.
        AccessibilityWindowInfo win = findTargetWindow();
        if (win != null) {
            AccessibilityNodeInfo root = win.getRoot();
            if (root != null) {
                try {
                    AccessibilityNodeInfo target = findEditableNode(root);
                    if (target != null) {
                        Bundle bundle = new Bundle();
                        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle);
                        target.recycle();
                        if (ok) {
                            return new JSONObject().put("ok", true).put("display_id", displayId).put("method", "uiautomation");
                        }
                    }
                } finally {
                    root.recycle();
                }
            }
        }

        String escaped = text.replace(" ", "%s");
        runCli("/system/bin/cmd", "input", "-d", Integer.toString(displayId), "text", escaped);
        return new JSONObject().put("ok", true).put("display_id", displayId).put("method", "cmd_input");
    }

    private AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node) {
        if (node.isEditable() && node.isFocused()) return AccessibilityNodeInfo.obtain(node);
        AccessibilityNodeInfo fallback = node.isEditable() ? AccessibilityNodeInfo.obtain(node) : null;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo found = findEditableNode(child);
                if (found != null) {
                    if (found.isFocused()) {
                        if (fallback != null) fallback.recycle();
                        return found;
                    }
                    if (fallback == null) {
                        fallback = found;
                    } else {
                        found.recycle();
                    }
                }
            } finally {
                child.recycle();
            }
        }
        return fallback;
    }

    private JSONObject sendKey(JSONObject args) throws Exception {
        String action = args.optString("action", "back").trim().toLowerCase();
        String keycode;
        switch (action) {
            case "back":
                keycode = "KEYCODE_BACK";
                break;
            case "home":
                keycode = "KEYCODE_HOME";
                break;
            case "enter":
                keycode = "KEYCODE_ENTER";
                break;
            case "del":
            case "backspace":
                keycode = "KEYCODE_DEL";
                break;
            case "recents":
                keycode = "KEYCODE_APP_SWITCH";
                break;
            default:
                if (action.matches("\\d+")) {
                    keycode = action;
                } else {
                    throw new IllegalArgumentException("Unsupported key action: " + action);
                }
        }
        runCli("/system/bin/cmd", "input", "-d", Integer.toString(displayId), "keyevent", keycode);
        return new JSONObject().put("ok", true).put("display_id", displayId).put("keycode", keycode);
    }

    @SuppressWarnings("deprecation")
    private String findTopComponentOnDisplay() {
        try {
            ActivityManager am = shellContext.getSystemService(ActivityManager.class);
            if (am == null) return "";
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(60);
            for (ActivityManager.RunningTaskInfo t : tasks) {
                int tid = ActivityManager.RunningTaskInfo.class.getField("displayId").getInt(t);
                if (tid == displayId && t.topActivity != null) {
                    return t.topActivity.flattenToShortString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void validatePoint(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0f || y < 0f || x >= width || y >= height) {
            throw new IllegalArgumentException("Point (" + x + ", " + y + ") out of bounds (" + width + "x" + height + ")");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String clip(CharSequence seq, int maxLen) {
        if (seq == null) return "";
        int end = Math.min(seq.length(), maxLen);
        return seq.subSequence(0, end).toString();
    }

    private static String runCli(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                throw new IOException("Shell command timed out.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            try (InputStream in = p.getInputStream()) {
                int n;
                while (out.size() < 16384 && (n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            String text = out.toString(StandardCharsets.UTF_8.name()).trim();
            if (p.exitValue() != 0 || text.startsWith("Error") || text.contains("Exception occurred")) {
                throw new IOException(text.isEmpty() ? "Command exited with " + p.exitValue() : text);
            }
            return text;
        } finally {
            p.destroy();
        }
    }

    private void releaseResources() {
        closed = true;
        synchronized (frameLock) {
            if (latestImage != null) {
                try { latestImage.close(); } catch (Exception ignored) {}
                latestImage = null;
            }
            frameLock.notifyAll();
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (uiAutomation != null) {
            try { UiAutomation.class.getMethod("disconnect").invoke(uiAutomation); } catch (Exception ignored) {}
            uiAutomation = null;
        }
        if (uiThread != null) {
            uiThread.quitSafely();
            uiThread = null;
        }
        display = null;
        displayId = Display.INVALID_DISPLAY;
    }

    /**
     * Reflective bridge to `dev.dshn.app.runtime.DshnNativeCore.encodeVirtualFrame` so `:core:bridge`
     * stays decoupled from `:core:runtime` while still sharing `libdshn_core.so` at runtime.
     */
    private static final class RustFrameBridge {
        private final Object instance;
        private final Method encodeMethod;

        private RustFrameBridge(Object instance, Method encodeMethod) {
            this.instance = instance;
            this.encodeMethod = encodeMethod;
        }

        boolean isAvailable() {
            return instance != null && encodeMethod != null;
        }

        byte[] encode(
            ByteBuffer rgba,
            int srcW,
            int srcH,
            int rowStride,
            int dstW,
            int dstH,
            int quality,
            long previousHash,
            long[] outHash
        ) {
            try {
                return (byte[]) encodeMethod.invoke(
                    instance, rgba, srcW, srcH, rowStride, dstW, dstH, quality, previousHash, outHash
                );
            } catch (Throwable ignored) {
                return null;
            }
        }

        static RustFrameBridge tryLoad(String nativeLibDir) {
            try {
                if (nativeLibDir != null && !nativeLibDir.isEmpty()) {
                    System.setProperty("dshn.native.lib.dir", nativeLibDir);
                }
                Class<?> cls = Class.forName("dev.dshn.app.runtime.DshnNativeCore");
                Object inst = cls.getField("INSTANCE").get(null);
                boolean avail = (Boolean) cls.getMethod("isAvailable").invoke(inst);
                if (!avail) return new RustFrameBridge(null, null);
                Method m = cls.getMethod(
                    "encodeVirtualFrame",
                    ByteBuffer.class,
                    int.class, int.class, int.class,
                    int.class, int.class, int.class,
                    long.class, long[].class
                );
                return new RustFrameBridge(inst, m);
            } catch (Throwable ignored) {
                return new RustFrameBridge(null, null);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int uid = android.os.Process.myUid();
        if (uid != 2000 && uid != 0) {
            throw new SecurityException("VirtualDisplayAgent requires shell (uid 2000) or root (uid 0), got uid=" + uid);
        }
        String nativeLibDir = null;
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--native-lib-dir".equals(args[i])) {
                nativeLibDir = args[i + 1];
            }
        }

        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext("com.android.shell", 0);

        RustFrameBridge rust = RustFrameBridge.tryLoad(nativeLibDir);
        VirtualDisplayAgent agent = new VirtualDisplayAgent(shell, rust);
        new Thread(agent::runCommandLoop, "dshn-virtual-agent").start();
        Looper.loop();
    }
}
