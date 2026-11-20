package dev.dshn.app.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.BatteryManager
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.security.AdbArgvPolicy
import dev.dshn.app.security.AuditLogger
import dev.dshn.app.security.RootCommandPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * App-process MCP server listening on a filesystem UDS inside `filesDir/bridge/device-mcp.sock`.
 * Container stdio->UDS relay (`dshn-mcp-relay.mjs`) connects directly to this socket.
 *
 * Security invariants:
 * - All capabilities are OFF by default in [SettingsStore].
 * - Fixed tool list; disabling toggles blocks execution cleanly with `[POLICY_BLOCKED]`.
 * - High-risk operations require per-call confirmation via [ConfirmationBroker].
 * - All device bridge invocations are logged to `filesDir/security/audit.jsonl`.
 */
class DeviceBridgeMcpServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val auditLogger: AuditLogger,
    val confirmationBroker: ConfirmationBroker,
    val adbClient: WirelessAdbClient,
    val rootAuthManager: RootAuthManager
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val rootFilesDir = File(context.filesDir.absolutePath.replaceFirst("/data/user/0/", "/data/data/"))
    private val bridgeDir = File(rootFilesDir, "bridge").apply {
        if (!exists()) mkdirs()
        setReadable(false, false); setReadable(true, true)
        setWritable(false, false); setWritable(true, true)
        setExecutable(false, false); setExecutable(true, true)
    }
    private val sockFile = File(bridgeDir, "device-mcp.sock")
    private val screensDir = File(bridgeDir, "screens")

    private var acceptJob: Job? = null
    private var serverSocket: LocalServerSocket? = null
    private var boundSocket: LocalSocket? = null

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch(Dispatchers.IO) {
            if (sockFile.exists()) sockFile.delete()
            val ls = LocalSocket(LocalSocket.SOCKET_STREAM)
            try {
                ls.bind(LocalSocketAddress(sockFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                Os.listen(ls.fileDescriptor, 16)
                sockFile.setReadable(false, false); sockFile.setReadable(true, true)
                sockFile.setWritable(false, false); sockFile.setWritable(true, true)
                boundSocket = ls
                val server = LocalServerSocket(ls.fileDescriptor)
                serverSocket = server
                while (isActive) {
                    val client = server.accept()
                    launch(Dispatchers.IO) { handleClient(client) }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { serverSocket?.close() }
                runCatching { boundSocket?.close() }
                serverSocket = null
                boundSocket = null
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        runCatching { boundSocket?.close() }
        serverSocket = null
        boundSocket = null
        if (sockFile.exists()) runCatching { sockFile.delete() }
    }

    private suspend fun handleClient(client: LocalSocket) {
        client.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val out = sock.outputStream
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val req = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val id = req["id"]
                val method = req["method"]?.jsonPrimitive?.contentOrNull ?: continue
                val params = runCatching { req["params"]?.jsonObject }.getOrNull() ?: buildJsonObject {}
                if (id == null) continue // notification (e.g. notifications/initialized)

                val response = try {
                    val result = dispatchRpc(method, params)
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", result)
                    }
                } catch (t: Throwable) {
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("error", buildJsonObject {
                            put("code", -32603)
                            put("message", t.message ?: "Device bridge error")
                        })
                    }
                }
                writeLine(out, response.toString())
            }
        }
    }

    private fun writeLine(out: OutputStream, line: String) {
        synchronized(out) {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    suspend fun invokeTrialTool(
        toolName: String,
        arguments: JsonObject = buildJsonObject {}
    ): JsonObject = dispatchRpc(
        "tools/call",
        buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }
    )

    internal suspend fun dispatchRpc(method: String, params: JsonObject): JsonObject = when (method) {
        "initialize" -> buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
            })
            put("serverInfo", buildJsonObject {
                put("name", "dshn-device-bridge")
                put("version", "0.2.0")
            })
        }
        "ping" -> buildJsonObject {}
        "tools/list" -> buildJsonObject { put("tools", toolDefinitionsJson()) }
        "tools/call" -> handleToolCall(params)
        else -> throw IllegalArgumentException("Unsupported MCP method: $method")
    }

    private suspend fun handleToolCall(params: JsonObject): JsonObject {
        val tool = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val args = runCatching { params["arguments"]?.jsonObject }.getOrNull() ?: buildJsonObject {}
        val settings = settingsStore.current()

        if (!settings.bridgeEnabled) {
            return blockedResult(tool, "Master Device Bridge toggle is OFF in Settings.")
        }

        return when (tool) {
            "device_info" -> {
                if (!settings.allowAccessibilityRead && !settings.allowAdbBridge && !settings.allowRootBridge) {
                    return blockedResult(tool, "Enable Device Read, ADB Bridge, or Root Bridge in Settings to query device info.")
                }
                auditLogger.log("device_bridge", tool, "auto_allowed", "Read device info")
                textResult(buildDeviceInfoJson())
            }

            "device_sensors" -> {
                if (!settings.allowAccessibilityRead) {
                    return blockedResult(tool, "Accessibility/Device Read toggle is OFF in Settings.")
                }
                auditLogger.log("device_bridge", tool, "auto_allowed", "Read device sensors snapshot")
                textResult(readSensorsSnapshot())
            }

            "device_screen_tree" -> {
                if (!settings.allowAccessibilityRead) {
                    return blockedResult(tool, "Accessibility Read toggle is OFF in Settings.")
                }
                val acc = DshnAccessibilityService.instance
                    ?: return textResult("[UNAVAILABLE] DSHN Accessibility Service is not enabled on Android Settings.")
                val maxNodes = args["max_nodes"]?.jsonPrimitive?.intOrNull?.coerceIn(20, 300) ?: 160
                auditLogger.log("device_bridge", tool, "auto_allowed", "Read screen accessibility tree")
                textResult(acc.readScreenTree(maxNodes = maxNodes))
            }

            "device_screenshot" -> {
                if (!settings.allowAccessibilityRead) {
                    return blockedResult(tool, "Accessibility Read toggle is OFF in Settings.")
                }
                val acc = DshnAccessibilityService.instance
                    ?: return textResult("[UNAVAILABLE] DSHN Accessibility Service is not enabled on Android Settings.")
                val displayId = args["display_id"]?.jsonPrimitive?.intOrNull ?: Display.DEFAULT_DISPLAY
                val cap = acc.takeScreenshotResult(displayId = displayId, screensDir = screensDir)
                auditLogger.log("device_bridge", tool, "auto_allowed", "Captured screenshot ${cap.width}x${cap.height}")
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image")
                            put("data", cap.jpegBase64)
                            put("mimeType", "image/jpeg")
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put(
                                "text",
                                "Screenshot captured (${cap.width}x${cap.height})" +
                                    (cap.savedContainerPath?.let { ", container path: $it" } ?: "")
                            )
                        })
                    }
                    put("isError", false)
                }
            }

            "device_tap" -> {
                if (!settings.allowAccessibilityControl) {
                    return blockedResult(tool, "Accessibility Control toggle is OFF in Settings.")
                }
                val acc = DshnAccessibilityService.instance
                    ?: return textResult("[UNAVAILABLE] DSHN Accessibility Service is not enabled on Android Settings.")
                val x = args["x"]?.jsonPrimitive?.floatOrNull
                    ?: return textResult("[INVALID_ARGS] Missing numeric x")
                val y = args["y"]?.jsonPrimitive?.floatOrNull
                    ?: return textResult("[INVALID_ARGS] Missing numeric y")
                val summary = "Tap screen at ($x, $y)"
                if (!confirmIfRequired(tool, summary, "Coordinates: ($x, $y)")) {
                    return deniedResult(tool, summary)
                }
                val ok = acc.tap(x, y)
                auditLogger.log("device_bridge", tool, "allowed_once", summary, "ok=$ok")
                textResult(if (ok) "Tapped ($x, $y)" else "Tap gesture cancelled by system")
            }

            "device_swipe" -> {
                if (!settings.allowAccessibilityControl) {
                    return blockedResult(tool, "Accessibility Control toggle is OFF in Settings.")
                }
                val acc = DshnAccessibilityService.instance
                    ?: return textResult("[UNAVAILABLE] DSHN Accessibility Service is not enabled on Android Settings.")
                val x1 = args["x1"]?.jsonPrimitive?.floatOrNull ?: return textResult("[INVALID_ARGS] Missing x1")
                val y1 = args["y1"]?.jsonPrimitive?.floatOrNull ?: return textResult("[INVALID_ARGS] Missing y1")
                val x2 = args["x2"]?.jsonPrimitive?.floatOrNull ?: return textResult("[INVALID_ARGS] Missing x2")
                val y2 = args["y2"]?.jsonPrimitive?.floatOrNull ?: return textResult("[INVALID_ARGS] Missing y2")
                val dur = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 320L
                val summary = "Swipe ($x1,$y1)->($x2,$y2) ${dur}ms"
                if (!confirmIfRequired(tool, summary, summary)) {
                    return deniedResult(tool, summary)
                }
                val ok = acc.swipe(x1, y1, x2, y2, dur)
                auditLogger.log("device_bridge", tool, "allowed_once", summary, "ok=$ok")
                textResult(if (ok) "Swiped $summary" else "Swipe gesture cancelled by system")
            }

            "device_input" -> {
                if (!settings.allowAccessibilityControl) {
                    return blockedResult(tool, "Accessibility Control toggle is OFF in Settings.")
                }
                val acc = DshnAccessibilityService.instance
                    ?: return textResult("[UNAVAILABLE] DSHN Accessibility Service is not enabled on Android Settings.")
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                    ?: return textResult("[INVALID_ARGS] Missing text")
                val summary = "Input text (${text.length} chars)"
                if (!confirmIfRequired(tool, summary, "Text preview: ${text.take(80)}")) {
                    return deniedResult(tool, summary)
                }
                val ok = acc.inputText(text)
                auditLogger.log("device_bridge", tool, "allowed_once", summary, "ok=$ok")
                textResult(if (ok) "Entered text into focused field" else "No editable field found")
            }

            "device_global_action" -> {
                if (!settings.allowAccessibilityControl) {
                    return blockedResult(tool, "Accessibility Control toggle is OFF in Settings.")
                }
                val acc = DshnAccessibilityService.instance
                    ?: return textResult("[UNAVAILABLE] DSHN Accessibility Service is not enabled on Android Settings.")
                val action = args["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val summary = "System global action: $action"
                if (!confirmIfRequired(tool, summary, summary)) {
                    return deniedResult(tool, summary)
                }
                val ok = acc.performGlobal(action)
                auditLogger.log("device_bridge", tool, "allowed_once", summary, "ok=$ok")
                textResult(if (ok) "Performed $action" else "Unsupported or failed action: $action")
            }

            "device_adb_exec" -> {
                if (!settings.allowAdbBridge) {
                    return blockedResult(tool, "Wireless ADB Bridge toggle is OFF in Settings.")
                }
                val rawArgv = runCatching { args["argv"]?.jsonArray }.getOrNull()
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: return textResult("[INVALID_ARGS] argv array of strings is required")
                when (val policy = AdbArgvPolicy.evaluate(rawArgv)) {
                    is AdbArgvPolicy.PolicyDecision.Blocked -> {
                        auditLogger.log("adb", tool, "policy_blocked", rawArgv.joinToString(" "), policy.reason)
                        return buildJsonObject {
                            putJsonArray("content") {
                                add(buildJsonObject { put("type", "text"); put("text", policy.reason) })
                            }
                            put("isError", true)
                        }
                    }
                    is AdbArgvPolicy.PolicyDecision.Allowed -> {
                        val endpoint = resolveAdbEndpoint(
                            args["host"]?.jsonPrimitive?.contentOrNull,
                            args["port"]?.jsonPrimitive?.intOrNull
                        ) ?: return textResult("[UNAVAILABLE] No Wireless ADB connect endpoint configured or discovered.")

                        val summary = "ADB: ${rawArgv.joinToString(" ")}"
                        if (!confirmIfRequired(tool, summary, "Endpoint ${endpoint.first}:${endpoint.second}\n$summary")) {
                            return deniedResult(tool, summary)
                        }
                        val exec = adbClient.executeAllowed(
                            host = endpoint.first,
                            port = endpoint.second,
                            allowed = policy,
                            transportMode = settings.adbTransportMode
                        )
                        auditLogger.log(
                            "adb",
                            tool,
                            "allowed_once",
                            summary,
                            "exit=${exec.exitCode} outLen=${exec.stdout.length}"
                        )
                        textResult(
                            buildJsonObject {
                                put("exitCode", exec.exitCode)
                                put("stdout", exec.stdout)
                                put("stderr", exec.stderr)
                            }.toString(),
                            isError = exec.exitCode != 0
                        )
                    }
                }
            }

            "device_root_status" -> {
                if (!settings.allowRootBridge) {
                    return blockedResult(tool, "Root Bridge toggle is OFF in Settings.")
                }
                val st = rootAuthManager.probeEnvironment(settings.customSuBinaryPath)
                val active = rootAuthManager.isAuthorized(settings.rootSessionTtlMinutes)
                auditLogger.log("root", tool, "auto_allowed", "Read root authorization status (authorized=$active)")
                textResult(
                    buildJsonObject {
                        put("status", st.status.name)
                        put("authorized", active)
                        put("suDetected", st.suBinaryPath != null)
                        put("suBinaryPath", st.suBinaryPath ?: "")
                        put("suManagerHint", st.suManagerHint)
                        put("suVersion", st.suVersion)
                        put("selinuxMode", st.selinuxMode)
                        put("policyMode", settings.rootPolicyMode.name)
                        put("sessionTtlMinutes", settings.rootSessionTtlMinutes)
                    }.toString()
                )
            }

            "device_root_exec" -> {
                if (!settings.allowRootBridge) {
                    return blockedResult(tool, "Root Bridge toggle is OFF in Settings.")
                }
                if (!rootAuthManager.isAuthorized(settings.rootSessionTtlMinutes)) {
                    auditLogger.log("root", tool, "unauthorized", "Root command blocked: user has not authorized root")
                    return textResult(
                        "[ROOT_UNAUTHORIZED] Root access is not currently authorized in DSHN. Ask the user to grant root in Settings or Device Lab.",
                        isError = true
                    )
                }
                val rawArgv = runCatching { args["argv"]?.jsonArray }.getOrNull()
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: return textResult("[INVALID_ARGS] argv array of strings is required", isError = true)
                val timeoutSec = args["timeout_sec"]?.jsonPrimitive?.longOrNull?.coerceIn(2L, 120L) ?: 30L

                when (val policy = RootCommandPolicy.evaluate(rawArgv, settings.rootPolicyMode)) {
                    is RootCommandPolicy.PolicyDecision.Blocked -> {
                        auditLogger.log("root", tool, "policy_blocked", rawArgv.joinToString(" "), policy.reason)
                        return textResult(policy.reason, isError = true)
                    }
                    is RootCommandPolicy.PolicyDecision.Allowed -> {
                        val summary = "ROOT (uid=0): ${rawArgv.joinToString(" ")}"
                        val mustConfirm = policy.requiresMandatoryConfirmation || settings.requirePerCallConfirmation
                        if (mustConfirm) {
                            val approved = confirmationBroker.requestConfirmation(
                                tool = tool,
                                summary = summary,
                                detail = "Policy: ${settings.rootPolicyMode.name}\nCommand: ${policy.shellCommand}\nTimeout: ${timeoutSec}s",
                                isRootAction = true
                            )
                            if (!approved) {
                                return deniedResult(tool, summary)
                            }
                        }
                        val exec = runCatching {
                            rootAuthManager.executeAllowed(
                                allowed = policy,
                                timeoutSec = timeoutSec,
                                ttlMinutes = settings.rootSessionTtlMinutes
                            )
                        }.getOrElse { err ->
                            val msg = err.message ?: err.javaClass.simpleName
                            auditLogger.log("root", tool, "error", summary, msg)
                            return textResult(msg, isError = true)
                        }
                        auditLogger.log(
                            "root",
                            tool,
                            if (mustConfirm) "allowed_once" else "auto_allowed",
                            summary,
                            "exit=${exec.exitCode} timedOut=${exec.timedOut} outLen=${exec.stdout.length}"
                        )
                        textResult(
                            buildJsonObject {
                                put("exitCode", exec.exitCode)
                                put("stdout", exec.stdout)
                                put("stderr", exec.stderr)
                                put("durationMs", exec.durationMs)
                                put("timedOut", exec.timedOut)
                            }.toString(),
                            isError = exec.exitCode != 0 || exec.timedOut
                        )
                    }
                }
            }

            else -> throw IllegalArgumentException("Unknown tool: $tool")
        }
    }

    private fun resolveAdbEndpoint(argHost: String?, argPort: Int?): Pair<String, Int>? {
        if (!argHost.isNullOrBlank() && argPort != null && argPort > 0) {
            return argHost to argPort
        }
        val saved = settingsStore.current().lastAdbEndpoint
        if (!saved.isNullOrBlank() && saved.contains(':')) {
            val parts = saved.split(':')
            val p = parts.getOrNull(1)?.toIntOrNull()
            if (p != null && p > 0) return parts[0] to p
        }
        val discovered = adbClient.endpoints.value.firstOrNull {
            it.serviceType == WirelessAdbClient.SERVICE_CONNECT
        } ?: return null
        return discovered.host to discovered.port
    }

    private suspend fun confirmIfRequired(tool: String, summary: String, detail: String): Boolean {
        if (!settingsStore.current().requirePerCallConfirmation) return true
        return confirmationBroker.requestConfirmation(tool, summary, detail)
    }

    private fun blockedResult(tool: String, reason: String): JsonObject {
        auditLogger.log("device_bridge", tool, "policy_blocked", reason)
        return textResult("[POLICY_BLOCKED] $reason", isError = true)
    }

    private fun deniedResult(tool: String, summary: String): JsonObject {
        auditLogger.log("device_bridge", tool, "denied", summary)
        return textResult("[USER_DENIED] User denied device bridge action: $summary", isError = true)
    }

    private fun textResult(text: String, isError: Boolean = false): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        put("isError", isError)
    }

    private fun buildDeviceInfoJson(): String {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val densityDpi = context.resources.displayMetrics.densityDpi

        return buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("abis", Build.SUPPORTED_ABIS.joinToString())
            put("screenWidthPx", bounds.width())
            put("screenHeightPx", bounds.height())
            put("densityDpi", densityDpi)
            put("batteryPercent", batteryPct)
            put("accessibilityConnected", DshnAccessibilityService.connected.value)
            put("rootSuDetected", rootAuthManager.state.value.suBinaryPath != null)
            put("rootAuthorized", rootAuthManager.isAuthorized(settingsStore.current().rootSessionTtlMinutes))
        }.toString()
    }

    private suspend fun readSensorsSnapshot(): String {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return "SensorManager unavailable"
        val targets = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_LIGHT to "light_lux",
            Sensor.TYPE_PRESSURE to "pressure_hpa",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetic_ut"
        )
        val out = buildJsonObject {
            for ((type, label) in targets) {
                val sensor = sm.getDefaultSensor(type) ?: continue
                val sample = readSingleSensor(sm, sensor)
                if (sample != null) {
                    putJsonArray(label) { sample.forEach { add(it) } }
                }
            }
        }
        return out.toString()
    }

    private suspend fun readSingleSensor(sm: SensorManager, sensor: Sensor): List<Float>? {
        val deferred = CompletableDeferred<List<Float>>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                deferred.complete(event.values.toList())
                sm.unregisterListener(this)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        return try {
            withTimeoutOrNull(450L) { deferred.await() }
        } finally {
            sm.unregisterListener(listener)
        }
    }

    companion object {
        fun toolDefinitionsJson(): JsonArray = JsonArray(
            listOf(
                tool("device_info", "Read Android device metadata, screen bounds, battery, and accessibility status.") { },
                tool("device_sensors", "Read a brief snapshot of available hardware sensors (accelerometer, light, pressure).") { },
                tool("device_screen_tree", "Read structured UI hierarchy from active window (wrapped in <untrusted_screen_content>).") {
                    put("max_nodes", buildJsonObject { put("type", "integer") })
                },
                tool("device_screenshot", "Capture current screen via AccessibilityService (Android 11+).") {
                    put("display_id", buildJsonObject { put("type", "integer") })
                },
                tool("device_tap", "Tap screen coordinates (requires Accessibility control toggle + user approval).", listOf("x", "y")) {
                    put("x", buildJsonObject { put("type", "number") })
                    put("y", buildJsonObject { put("type", "number") })
                },
                tool("device_swipe", "Swipe between two coordinates (requires user approval).", listOf("x1", "y1", "x2", "y2")) {
                    put("x1", buildJsonObject { put("type", "number") })
                    put("y1", buildJsonObject { put("type", "number") })
                    put("x2", buildJsonObject { put("type", "number") })
                    put("y2", buildJsonObject { put("type", "number") })
                    put("duration_ms", buildJsonObject { put("type", "integer") })
                },
                tool("device_input", "Set text on currently focused editable field (requires user approval).", listOf("text")) {
                    put("text", buildJsonObject { put("type", "string") })
                },
                tool("device_global_action", "Perform system action: back, home, recents, notifications, quick_settings.", listOf("action")) {
                    put("action", buildJsonObject { put("type", "string") })
                },
                tool("device_adb_exec", "Run allowlisted Wireless ADB command via structured argv (never raw shell).", listOf("argv")) {
                    put("argv", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("host", buildJsonObject { put("type", "string") })
                    put("port", buildJsonObject { put("type", "integer") })
                },
                tool("device_root_status", "Read Android host root (`su`) detection, authorization status, SELinux mode, and policy mode.") { },
                tool("device_root_exec", "Run a policy-checked Android host root (`su`) command via structured argv (requires user root authorization + confirmation).", listOf("argv")) {
                    put("argv", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("timeout_sec", buildJsonObject { put("type", "integer") })
                }
            )
        )

        private fun tool(
            name: String,
            description: String,
            required: List<String> = emptyList(),
            propsBuilder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
        ): JsonObject = buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject(propsBuilder))
                if (required.isNotEmpty()) {
                    putJsonArray("required") { required.forEach { add(it) } }
                }
            })
        }
    }
}
