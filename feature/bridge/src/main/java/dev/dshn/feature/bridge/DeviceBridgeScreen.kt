package dev.dshn.feature.bridge

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ScreenSearchDesktop
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.bridge.RootAuthStatus
import dev.dshn.app.data.VirtualDisplayResolutionPreset
import dev.dshn.app.security.RootPolicyMode
import dev.dshn.core.ui.ConfirmDialog
import dev.dshn.core.ui.InfoBanner
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsDivider
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.SettingsRow
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.SwitchRow
import dev.dshn.core.ui.openAccessibilitySettings
import dev.dshn.core.ui.openAppDetails
import dev.dshn.core.ui.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceBridgeScreen(
    vm: DeviceBridgeViewModel,
    onBack: () -> Unit,
    onOpenLab: () -> Unit
) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accConnected by vm.accessibilityConnected.collectAsStateWithLifecycle()
    val endpoints by vm.discoveredAdbEndpoints.collectAsStateWithLifecycle()
    val scanning by vm.adbScanning.collectAsStateWithLifecycle()
    val probe by vm.adbProbeState.collectAsStateWithLifecycle()
    val rootAuth by vm.rootAuthState.collectAsStateWithLifecycle()
    val rootAuthorizing by vm.rootAuthorizing.collectAsStateWithLifecycle()
    val vdState by vm.virtualDisplayState.collectAsStateWithLifecycle()
    val vdFrame by vm.virtualDisplayFrame.collectAsStateWithLifecycle()
    val audit by vm.auditEntries.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.onEntered()
    }
    LaunchedEffect(vdState.active) {
        while (vdState.active) {
            vm.pollVirtualDisplayFrameOnce()
            delay(450)
        }
    }

    var confirmDisableConfirm by remember { mutableStateOf(false) }
    var confirmCustomRoot by remember { mutableStateOf(false) }
    var confirmClearAudit by remember { mutableStateOf(false) }
    var endpointDraft by remember(settings.lastAdbEndpoint) { mutableStateOf(settings.lastAdbEndpoint ?: "127.0.0.1:5555") }
    var suDraft by remember(settings.customSuBinaryPath) { mutableStateOf(settings.customSuBinaryPath.orEmpty()) }
    var vdPkgDraft by remember { mutableStateOf("com.android.settings") }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    val master = settings.bridgeEnabled
    val anyOn = master || settings.allowAccessibilityRead || settings.allowAccessibilityControl ||
        settings.allowAdbBridge || settings.allowRootBridge || settings.allowVirtualDisplay
    val rootAuthorized = rootAuth.status == RootAuthStatus.AUTHORIZED
    val vdSupported = Build.VERSION.SDK_INT >= 34

    SecondaryScaffold(
        title = tr("设备桥与 Root", "Device bridge & Root"),
        onBack = onBack,
        actions = {
            TextButton(
                onClick = onOpenLab,
                modifier = Modifier.testTag("bridge_open_lab_action")
            ) { Text(tr("调试台", "Lab")) }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item {
                InfoBanner(
                    text = tr(
                        "设备桥让 DSH 能读取屏幕、点按操作、执行 ADB 或 Root 命令。所有能力默认关闭；高危操作每次都会弹窗请你确认，并记录审计日志。",
                        "The device bridge lets DSH read the screen, tap, and run ADB or Root commands. Everything is off by default; risky actions ask you every time and are audited."
                    ),
                    icon = Icons.Rounded.Shield,
                    tone = PillTone.INFO
                )
            }
            item {
                SettingsGroup(tr("能力开关", "Capabilities")) {
                    SwitchRow(
                        title = tr("启用设备桥", "Enable device bridge"),
                        subtitle = tr("总开关，关闭时会同时关闭下方全部子能力", "Master switch; turning it off resets all capabilities below"),
                        icon = Icons.Rounded.PowerSettingsNew,
                        checked = master,
                        onCheckedChange = { v -> vm.setBridgeEnabled(v) }
                    )
                    SettingsDivider()
                    SwitchRow(
                        title = tr("读取屏幕与传感器", "Read screen & sensors"),
                        subtitle = tr("界面结构、截图、设备信息", "UI tree, screenshots, device info"),
                        icon = Icons.Rounded.ScreenSearchDesktop,
                        checked = settings.allowAccessibilityRead,
                        enabled = master,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(allowAccessibilityRead = v) } }
                    )
                    SettingsDivider()
                    SwitchRow(
                        title = tr("操控屏幕", "Control the screen"),
                        subtitle = tr("点按、滑动、输入文字、返回/主页", "Tap, swipe, type, back/home"),
                        icon = Icons.Rounded.TouchApp,
                        checked = settings.allowAccessibilityControl,
                        enabled = master,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(allowAccessibilityControl = v) } }
                    )
                    SettingsDivider()
                    SwitchRow(
                        title = tr("ADB 命令", "ADB commands"),
                        subtitle = tr("仅限白名单内的结构化命令", "Allow-listed, structured commands only"),
                        icon = Icons.Rounded.Cable,
                        checked = settings.allowAdbBridge,
                        enabled = master,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(allowAdbBridge = v) } }
                    )
                    SettingsDivider()
                    SwitchRow(
                        title = tr("Root 命令", "Root commands"),
                        subtitle = if (rootAuthorized) tr("通过已授权的 su 执行", "Runs through authorized su") else tr("需要先在下方授权 Root", "Authorize Root below first"),
                        icon = Icons.Rounded.AdminPanelSettings,
                        iconTint = MaterialTheme.colorScheme.error,
                        checked = settings.allowRootBridge,
                        enabled = master,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(allowRootBridge = v) } }
                    )
                    SettingsDivider()
                    SwitchRow(
                        title = tr("后台虚拟屏（Android 14+）", "Background virtual display (Android 14+)"),
                        subtitle = if (vdSupported) {
                            tr("经 Root 或无线 ADB 独立建屏，不打扰主屏（含 Rust 帧编码）", "Off-screen virtual display via Root or Wireless ADB (Rust JPEG)")
                        } else {
                            tr("当前系统低于 Android 14，暂不支持", "Requires Android 14+ (API 34+)")
                        },
                        icon = Icons.Rounded.ScreenSearchDesktop,
                        checked = settings.allowVirtualDisplay,
                        enabled = master && vdSupported,
                        onCheckedChange = { v ->
                            vm.updateSettings { it.copy(allowVirtualDisplay = v) }
                            if (!v && vdState.active) vm.closeVirtualDisplay()
                        }
                    )
                    SettingsDivider()
                    SwitchRow(
                        title = tr("高危操作逐次确认", "Confirm risky actions"),
                        subtitle = tr("强烈建议保持开启", "Strongly recommended"),
                        icon = Icons.Rounded.Shield,
                        checked = settings.requirePerCallConfirmation,
                        onCheckedChange = { v ->
                            if (!v) confirmDisableConfirm = true
                            else vm.updateSettings { it.copy(requirePerCallConfirmation = true) }
                        }
                    )
                }
                if (anyOn) {
                    OutlinedButton(
                        onClick = { vm.lockAllDeviceBridgeSwitches() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .testTag("bridge_lock_all_button")
                    ) { Text(tr("一键全部关闭并撤销 Root", "Turn everything off & revoke Root")) }
                }
            }
            if (vdSupported) {
                item {
                    val vdTone = when {
                        vdState.active -> PillTone.SUCCESS
                        vdState.starting -> PillTone.INFO
                        !vdState.lastError.isNullOrBlank() -> PillTone.ERROR
                        else -> PillTone.NEUTRAL
                    }
                    val vdLabel = when {
                        vdState.active -> "Display #${vdState.displayId} · ${vdState.driver}"
                        vdState.starting -> tr("启动中…", "Starting…")
                        else -> tr("未启动", "Stopped")
                    }
                    SettingsGroup(
                        tr("后台虚拟屏（Android 14+）", "Background virtual display (Android 14+)"),
                        footer = tr(
                            "直接通过 DisplayManager 创建受信任独立虚拟屏，绕过厂商禁用 overlay_display_devices 的限制；需要开启并连接无线 ADB 或授权 Root。",
                            "Creates an independent trusted VirtualDisplay via DisplayManager (bypasses OEM overlay_display_devices blocks). Requires Wireless ADB or authorized Root."
                        )
                    ) {
                        SettingsRow(
                            title = if (vdState.active) {
                                tr("虚拟屏运行中", "Virtual display active")
                            } else {
                                tr("独立后台屏幕", "Off-screen virtual display")
                            },
                            subtitle = when {
                                vdState.active -> buildString {
                                    append("${vdState.width}×${vdState.height} @${vdState.dpi}dpi")
                                    append(if (vdState.rustEncoder) " · Rust JPEG" else " · Android JPEG")
                                    if (vdState.topComponent.isNotBlank()) append("\n${vdState.topComponent}")
                                }
                                !vdState.lastError.isNullOrBlank() -> vdState.lastError
                                else -> tr("启动后 Agent 可在后台独立打开和操作应用", "Lets the agent launch and control apps off-screen")
                            },
                            icon = Icons.Rounded.ScreenSearchDesktop,
                            showChevron = false,
                            trailing = { StatusPill(vdLabel, vdTone, showDot = true) }
                        )
                        Column(
                            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                tr("分辨率预设", "Resolution preset"),
                                style = MaterialTheme.typography.labelLarge
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                VirtualDisplayResolutionPreset.entries.forEach { preset ->
                                    FilterChip(
                                        selected = settings.virtualDisplayPreset == preset,
                                        enabled = !vdState.starting,
                                        onClick = { vm.setVirtualDisplayPreset(preset) },
                                        label = { Text(tr(preset.labelZh, preset.labelEn)) },
                                        modifier = Modifier.testTag("vd_preset_${preset.name}")
                                    )
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!vdState.active) {
                                    Button(
                                        enabled = !vdState.starting && (settings.allowRootBridge || settings.allowAdbBridge),
                                        onClick = { vm.openVirtualDisplay() }
                                    ) {
                                        if (vdState.starting) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(tr("启动虚拟屏", "Start virtual display"))
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { vm.closeVirtualDisplay() },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) { Text(tr("关闭虚拟屏", "Stop")) }
                                    FilledTonalButton(onClick = { vm.sendVirtualDisplayKey("back") }) {
                                        Text(tr("返回", "Back"))
                                    }
                                    FilledTonalButton(onClick = { vm.refreshVirtualDisplayFrame(skipUnchanged = false) }) {
                                        Text(tr("刷新", "Refresh"))
                                    }
                                }
                            }
                            if (vdState.active) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = vdPkgDraft,
                                        onValueChange = { vdPkgDraft = it },
                                        singleLine = true,
                                        label = { Text(tr("应用包名", "Package name")) },
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilledTonalButton(
                                        enabled = vdPkgDraft.contains('.'),
                                        onClick = { vm.launchAppOnVirtualDisplay(vdPkgDraft.trim()) }
                                    ) { Text(tr("打开", "Launch")) }
                                }
                                val previewBmp = remember(vdFrame?.timestampMs) {
                                    vdFrame?.jpegBytes?.let { bytes ->
                                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(vdState.width.toFloat() / vdState.height.toFloat())
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .pointerInput(vdState.width, vdState.height) {
                                            detectTapGestures { offset ->
                                                if (size.width > 0 && size.height > 0) {
                                                    val vx = (offset.x / size.width) * vdState.width
                                                    val vy = (offset.y / size.height) * vdState.height
                                                    vm.tapOnVirtualDisplay(vx, vy)
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (previewBmp != null) {
                                        Image(
                                            bitmap = previewBmp,
                                            contentDescription = tr("后台虚拟屏实时预览（点击可操控）", "Live virtual display preview (tap to interact)"),
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            tr("等待虚拟屏渲染首帧，点击上方「打开」启动一个应用试试", "Waiting for first frame; launch an app above to render"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup(
                    tr("无障碍服务", "Accessibility service"),
                    footer = tr(
                        "安卓 13 起，非应用商店安装的应用需要先在「应用信息 › 右上角菜单」中允许受限设置，然后再到无障碍里开启「DSHN Device Bridge」。",
                        "On Android 13+, sideloaded apps must first allow restricted settings (App info › top-right menu), then enable \"DSHN Device Bridge\" in Accessibility."
                    )
                ) {
                    SettingsRow(
                        title = tr("DSHN Device Bridge", "DSHN Device Bridge"),
                        subtitle = if (accConnected) tr("服务已连接", "Service connected") else tr("服务未开启", "Service not enabled"),
                        icon = Icons.Rounded.TouchApp,
                        showChevron = false,
                        trailing = {
                            StatusPill(
                                if (accConnected) tr("已连接", "Connected") else tr("未连接", "Off"),
                                if (accConnected) PillTone.SUCCESS else PillTone.NEUTRAL,
                                showDot = true
                            )
                        }
                    )
                    Row(
                        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(onClick = { openAccessibilitySettings(ctx) }) { Text(tr("打开无障碍设置", "Accessibility settings")) }
                        OutlinedButton(onClick = { openAppDetails(ctx) }) { Text(tr("应用信息", "App info")) }
                    }
                }
            }
            item {
                SettingsGroup(
                    tr("无线 ADB", "Wireless ADB"),
                    footer = tr(
                        "最简单的方式：电脑执行 adb tcpip 5555，然后在这里连接 127.0.0.1:5555，手机弹出授权时点允许。",
                        "Easiest: run `adb tcpip 5555` on a computer, then connect to 127.0.0.1:5555 here and accept the prompt."
                    )
                ) {
                    SettingsRow(
                        title = when {
                            probe.authorized -> tr("已连接", "Connected")
                            !settings.lastAdbEndpoint.isNullOrBlank() -> tr("上次连接：", "Last: ") + settings.lastAdbEndpoint
                            else -> tr("尚未连接", "Not connected")
                        },
                        subtitle = probe.summary.takeIf { it.isNotBlank() },
                        icon = Icons.Rounded.Cable,
                        iconTint = if (probe.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        showChevron = false,
                        trailing = { if (probe.probing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                    )
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = endpointDraft,
                                onValueChange = { endpointDraft = it },
                                singleLine = true,
                                label = { Text(tr("地址:端口", "host:port")) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("bridge_adb_endpoint_input")
                            )
                            Button(
                                enabled = endpointDraft.contains(':') && !probe.probing,
                                modifier = Modifier.testTag("bridge_adb_connect_button"),
                                onClick = {
                                    val (host, port) = parseHostPort(endpointDraft)
                                    vm.testAdbConnection(host, port, settings.adbTransportMode)
                                }
                            ) { Text(tr("连接", "Connect")) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr("局域网发现（${endpoints.size}）", "Discovered (${endpoints.size})"),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.toggleAdbDiscovery() }) {
                                if (scanning) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (scanning) tr("停止扫描", "Stop") else tr("扫描", "Scan"))
                            }
                        }
                        for (ep in endpoints) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        endpointDraft = "${ep.host}:${ep.port}"
                                        vm.testAdbConnection(ep.host, ep.port, settings.adbTransportMode)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Wifi, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ep.serviceName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${ep.host}:${ep.port}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(tr("连接", "Connect"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            item {
                val (rootText, rootTone) = when (rootAuth.status) {
                    RootAuthStatus.AUTHORIZED -> tr("已授权", "Authorized") to PillTone.SUCCESS
                    RootAuthStatus.NO_SU_BINARY -> tr("未检测到 su", "No su") to PillTone.NEUTRAL
                    RootAuthStatus.DENIED -> tr("被拒绝", "Denied") to PillTone.ERROR
                    RootAuthStatus.REVOKED -> tr("已撤销", "Revoked") to PillTone.WARNING
                    RootAuthStatus.ERROR -> tr("出错", "Error") to PillTone.ERROR
                    RootAuthStatus.NOT_AUTHORIZED -> tr("未授权", "Not authorized") to PillTone.WARNING
                    RootAuthStatus.UNKNOWN -> tr("未检测", "Unknown") to PillTone.NEUTRAL
                }
                SettingsGroup(
                    tr("Root 授权（可选）", "Root authorization (optional)"),
                    footer = tr(
                        "仅支持 Magisk / KernelSU / SukiSU / APatch 等标准 su 授权。任何模式下都禁止访问 DSHN 自身的密钥与审计目录。",
                        "Only standard su managers (Magisk / KernelSU / SukiSU / APatch) are supported. DSHN's own secrets and audit folder are always off-limits."
                    )
                ) {
                    SettingsRow(
                        title = tr("超级用户", "Superuser"),
                        subtitle = buildString {
                            append(rootAuth.suBinaryPath ?: tr("未检测到 su", "su not found"))
                            if (rootAuth.suManagerHint.isNotBlank()) append(" · ").append(rootAuth.suManagerHint)
                            if (rootAuth.selinuxMode.isNotBlank()) append(" · SELinux ").append(rootAuth.selinuxMode)
                        },
                        icon = Icons.Rounded.AdminPanelSettings,
                        iconTint = MaterialTheme.colorScheme.error,
                        showChevron = false,
                        trailing = { StatusPill(rootText, rootTone, showDot = true) }
                    )
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (rootAuthorized) {
                                OutlinedButton(
                                    onClick = { vm.revokeRootAuthorization() },
                                    modifier = Modifier.testTag("bridge_root_revoke_button"),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) { Text(tr("撤销授权", "Revoke")) }
                            } else {
                                Button(
                                    onClick = { vm.requestRootAuthorization() },
                                    enabled = !rootAuthorizing,
                                    modifier = Modifier.testTag("bridge_root_request_button")
                                ) {
                                    if (rootAuthorizing) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(tr("等待授权弹窗…", "Waiting for prompt…"))
                                    } else Text(tr("申请 Root 授权", "Request Root"))
                                }
                            }
                            TextButton(onClick = { vm.probeRootEnvironment() }) { Text(tr("重新检测", "Detect")) }
                        }
                        Text(tr("命令策略", "Command policy"), style = MaterialTheme.typography.labelLarge)
                        PolicyOption(
                            selected = settings.rootPolicyMode == RootPolicyMode.ALLOWLIST_ONLY,
                            title = tr("仅白名单（推荐）", "Allow-list only (recommended)"),
                            subtitle = tr("只能执行预设的安全命令", "Only predefined safe commands")
                        ) { vm.setRootPolicyMode(RootPolicyMode.ALLOWLIST_ONLY) }
                        PolicyOption(
                            selected = settings.rootPolicyMode == RootPolicyMode.CUSTOM_WITH_CONFIRM,
                            title = tr("自定义命令 + 强制逐次确认", "Custom commands + mandatory confirmation"),
                            subtitle = tr("每条命令都必须由你手动批准", "You must approve every command")
                        ) {
                            if (settings.rootPolicyMode != RootPolicyMode.CUSTOM_WITH_CONFIRM) confirmCustomRoot = true
                        }
                        Text(tr("授权有效期", "Authorization lifetime"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                15 to tr("15 分钟", "15 min"),
                                30 to tr("30 分钟", "30 min"),
                                60 to tr("1 小时", "1 hour"),
                                0 to tr("手动撤销", "Until revoked")
                            ).forEach { (mins, label) ->
                                FilterChip(
                                    selected = settings.rootSessionTtlMinutes == mins,
                                    onClick = { vm.setRootSessionTtl(mins) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = suDraft,
                                onValueChange = { suDraft = it },
                                singleLine = true,
                                label = { Text(tr("自定义 su 路径（留空自动检测）", "Custom su path (blank = auto)")) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                vm.updateSettings { it.copy(customSuBinaryPath = suDraft.trim().ifEmpty { null }) }
                                vm.probeRootEnvironment()
                            }) { Text(tr("保存", "Save")) }
                        }
                    }
                }
            }
            item {
                SettingsGroup(tr("最近的设备操作记录", "Recent device activity")) {
                    if (audit.isEmpty()) {
                        Text(
                            tr("暂无记录", "Nothing yet"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    audit.take(6).forEachIndexed { i, e ->
                        if (i > 0) SettingsDivider(indent = 16.dp)
                        val d = e.decision.lowercase()
                        val tone = when {
                            "block" in d || "denied" in d || "error" in d || "unauthorized" in d -> PillTone.ERROR
                            "allow" in d -> PillTone.SUCCESS
                            else -> PillTone.NEUTRAL
                        }
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${e.tool} · ${e.summary}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(timeFmt.format(Date(e.timestampMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusPill(e.decision, tone)
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (audit.isNotEmpty()) TextButton(onClick = { confirmClearAudit = true }) { Text(tr("清空", "Clear")) }
                        TextButton(onClick = onOpenLab) {
                            Icon(Icons.Rounded.Science, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("打开调试台", "Open lab"))
                        }
                    }
                }
            }
        }
    }

    if (confirmDisableConfirm) {
        ConfirmDialog(
            title = tr("关闭逐次确认？", "Turn off confirmations?"),
            text = tr(
                "关闭后，DSH 执行点按、输入、ADB 和 Root 等操作时将不再询问你。屏幕上的内容可能诱导智能体做出危险操作。",
                "DSH will tap, type and run ADB/Root commands without asking. On-screen content could trick the agent into harmful actions."
            ),
            confirmLabel = tr("仍然关闭", "Turn off"),
            destructive = true,
            onConfirm = { vm.updateSettings { it.copy(requirePerCallConfirmation = false) } },
            onDismiss = { confirmDisableConfirm = false }
        )
    }
    if (confirmClearAudit) {
        ConfirmDialog(
            title = tr("清空设备操作记录？", "Clear device activity log?"),
            text = tr(
                "所有设备桥与 Root 操作的审计记录将被永久删除，无法恢复。",
                "All device bridge and Root audit entries will be permanently deleted."
            ),
            confirmLabel = tr("清空", "Clear"),
            destructive = true,
            onConfirm = { vm.clearAuditEntries() },
            onDismiss = { confirmClearAudit = false }
        )
    }
    if (confirmCustomRoot) {
        ConfirmDialog(
            title = tr("允许自定义 Root 命令？", "Allow custom Root commands?"),
            text = tr(
                "DSH 将可以请求执行任意 Root 命令（危险的分区/系统命令仍会被拦截），每条都需要你手动确认。",
                "DSH may request arbitrary Root commands (destructive partition/system commands stay blocked). Each one needs your approval."
            ),
            confirmLabel = tr("允许", "Allow"),
            destructive = true,
            onConfirm = { vm.setRootPolicyMode(RootPolicyMode.CUSTOM_WITH_CONFIRM) },
            onDismiss = { confirmCustomRoot = false }
        )
    }
}

@Composable
private fun PolicyOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
