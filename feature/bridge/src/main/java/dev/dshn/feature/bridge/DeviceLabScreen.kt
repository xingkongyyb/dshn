package dev.dshn.feature.bridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.bridge.RootAuthStatus
import dev.dshn.app.data.AdbTransportMode
import dev.dshn.app.security.AdbArgvPolicy
import dev.dshn.app.security.RootCommandPolicy
import dev.dshn.core.ui.AppStrings
import dev.dshn.core.ui.CopyableCodeBox
import dev.dshn.core.ui.DshnSectionCard
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.tr
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceLabScreen(
    vm: DeviceBridgeViewModel,
    onBack: () -> Unit
) {
    val semantic = LocalDshnColors.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accConnected by vm.accessibilityConnected.collectAsStateWithLifecycle()
    val adbEndpoints by vm.discoveredAdbEndpoints.collectAsStateWithLifecycle()
    val adbScanning by vm.adbScanning.collectAsStateWithLifecycle()
    val adbIdentity by vm.adbIdentity.collectAsStateWithLifecycle()
    val adbProbe by vm.adbProbeState.collectAsStateWithLifecycle()
    val rootAuth by vm.rootAuthState.collectAsStateWithLifecycle()
    val rootAuthorizing by vm.rootAuthorizing.collectAsStateWithLifecycle()
    val labRunning by vm.labRunning.collectAsStateWithLifecycle()
    val labResult by vm.labLastResult.collectAsStateWithLifecycle()
    val auditEntries by vm.auditEntries.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.onEntered()
    }

    val strings = AppStrings.resolve(settings.localeMode)

    var endpointDraft by remember(settings.lastAdbEndpoint) {
        mutableStateOf(settings.lastAdbEndpoint ?: "127.0.0.1:5555")
    }
    var customArgvDraft by remember { mutableStateOf("getprop ro.build.version.release") }
    var customRootArgvDraft by remember { mutableStateOf("id") }

    val parsedCustomArgv = remember(customArgvDraft) {
        customArgvDraft.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val customPreflight = remember(parsedCustomArgv) {
        AdbArgvPolicy.evaluate(parsedCustomArgv)
    }

    val parsedCustomRootArgv = remember(customRootArgvDraft) {
        customRootArgvDraft.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val customRootPreflight = remember(parsedCustomRootArgv, settings.rootPolicyMode) {
        RootCommandPolicy.evaluate(parsedCustomRootArgv, settings.rootPolicyMode)
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun dispatchRootExec(argv: List<String>) {
        vm.runBridgeTrialTool(
            "device_root_exec",
            buildJsonObject {
                putJsonArray("argv") { argv.forEach { add(it) } }
                put("timeout_sec", 20)
            }
        )
    }

    fun dispatchAdbExec(argv: List<String>) {
        val (host, port) = parseHostPort(endpointDraft)
        vm.runBridgeTrialTool(
            "device_adb_exec",
            buildJsonObject {
                putJsonArray("argv") { argv.forEach { add(it) } }
                if (host.isNotEmpty() && port > 0) {
                    put("host", host)
                    put("port", port)
                }
            }
        )
    }

    SecondaryScaffold(
        title = tr("设备桥调试台", "Device bridge lab"),
        onBack = onBack
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = semantic.heroBg,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.DeveloperBoard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    strings.labTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    strings.labSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StatusPill(
                                text = if (settings.bridgeEnabled) "MCP: ON" else "MCP: OFF",
                                tone = if (settings.bridgeEnabled) PillTone.SUCCESS else PillTone.NEUTRAL,
                                showDot = true
                            )
                            StatusPill(
                                text = when {
                                    adbProbe.authorized -> "ADB: AUTHORIZED"
                                    !settings.lastAdbEndpoint.isNullOrBlank() -> "ADB: ${settings.lastAdbEndpoint}"
                                    else -> "ADB: UNCONFIGURED"
                                },
                                tone = when {
                                    adbProbe.authorized -> PillTone.SUCCESS
                                    !settings.lastAdbEndpoint.isNullOrBlank() -> PillTone.INFO
                                    else -> PillTone.WARNING
                                },
                                showDot = true
                            )
                            StatusPill(
                                text = if (settings.requirePerCallConfirmation) "Confirm: ON" else "Confirm: OFF",
                                tone = if (settings.requirePerCallConfirmation) PillTone.INFO else PillTone.WARNING
                            )
                            StatusPill(
                                text = if (accConnected) "Acc: ON" else "Acc: OFF (Skipped)",
                                tone = PillTone.NEUTRAL
                            )
                            StatusPill(
                                text = when (rootAuth.status) {
                                    RootAuthStatus.AUTHORIZED -> "Root: AUTHORIZED"
                                    RootAuthStatus.NO_SU_BINARY -> "Root: NO SU"
                                    RootAuthStatus.DENIED -> "Root: DENIED"
                                    RootAuthStatus.REVOKED -> "Root: REVOKED"
                                    else -> "Root: LOCKED"
                                },
                                tone = when (rootAuth.status) {
                                    RootAuthStatus.AUTHORIZED -> PillTone.SUCCESS
                                    RootAuthStatus.DENIED, RootAuthStatus.ERROR -> PillTone.ERROR
                                    else -> PillTone.NEUTRAL
                                },
                                showDot = true
                            )
                        }
                    }
                }
            }

            item {
                DshnSectionCard(
                    title = "1. 安全开关与试验预设",
                    subtitle = "默认全关，可一键切换为 ADB 试验状态并在试毕锁定关闭",
                    icon = Icons.Default.Shield
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { vm.enableAdbTrialPreset() },
                            modifier = Modifier.testTag("lab_enable_adb_preset_button")
                        ) {
                            Text("启用 ADB 试验开关组合")
                        }
                        Button(
                            onClick = { vm.enableRootTrialPreset() },
                            modifier = Modifier.testTag("lab_enable_root_preset_button")
                        ) {
                            Text("启用 Root 试验开关组合")
                        }
                        OutlinedButton(
                            onClick = { vm.lockAllDeviceBridgeSwitches() },
                            modifier = Modifier.testTag("lab_lock_all_button")
                        ) {
                            Text("全部锁定关闭")
                        }
                    }

                    HorizontalDivider(color = semantic.cardBorder)

                    LabToggleRow(
                        title = "Enable Device Bridge MCP",
                        subtitle = "主总闸：允许 device-mcp.sock 响应设备桥工具",
                        checked = settings.bridgeEnabled
                    ) { v -> vm.setBridgeEnabled(v) }

                    LabToggleRow(
                        title = "ADB Argv Bridge",
                        subtitle = "允许结构化白名单内的 device_adb_exec 调用（与 device_info）",
                        checked = settings.allowAdbBridge
                    ) { v -> vm.updateSettings { it.copy(allowAdbBridge = v) } }

                    LabToggleRow(
                        title = "Host Root (`su`) Bridge",
                        subtitle = "允许已授权状态下调用 device_root_status / device_root_exec",
                        checked = settings.allowRootBridge
                    ) { v -> vm.updateSettings { it.copy(allowRootBridge = v) } }

                    LabToggleRow(
                        title = "Require Per-Call Confirmation",
                        subtitle = "每次执行允许列表中的 ADB 命令前均弹出逐次确认框",
                        checked = settings.requirePerCallConfirmation
                    ) { v -> vm.updateSettings { it.copy(requirePerCallConfirmation = v) } }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = semantic.subtleBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Accessibility 工具组（本轮试验暂跳过）",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "状态：${if (accConnected) "系统已连接" else "未连接"} · Read=${settings.allowAccessibilityRead} · Control=${settings.allowAccessibilityControl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                DshnSectionCard(
                    title = "2. 电脑 ADB 直连授权（无需无线配对码）",
                    subtitle = "由电脑执行 adb tcpip 5555 打开本机 TCP 端口，手机端弹出密钥授权后点允许即可",
                    icon = Icons.Default.Usb,
                    trailing = {
                        StatusPill(
                            text = if (adbProbe.authorized) "已授权" else "待连接",
                            tone = if (adbProbe.authorized) PillTone.SUCCESS else PillTone.WARNING,
                            showDot = true
                        )
                    }
                ) {
                    CopyableCodeBox(
                        label = "步骤 A · 电脑终端执行（USB 已连接状态下）",
                        text = "adb tcpip 5555",
                        onCopy = { vm.copyToClipboard("adb tcpip command", "adb tcpip 5555") }
                    )

                    adbIdentity?.let { id ->
                        CopyableCodeBox(
                            label = "DSHN RSA-2048 调试密钥指纹（弹窗核对）",
                            text = "MD5: ${id.md5Fingerprint}\n${id.sha256Fingerprint}",
                            onCopy = { vm.copyToClipboard("DSHN ADB public key", id.androidPublicKeyLine) }
                        )
                    }

                    Text("传输协议模式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            AdbTransportMode.AUTO to "AUTO (5555走TCP)",
                            AdbTransportMode.PLAIN_TCP to "PLAIN_TCP (pc tcpip)",
                            AdbTransportMode.TLS to "TLS (无线调试)"
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = settings.adbTransportMode == mode,
                                onClick = { vm.setAdbTransportMode(mode) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "快速填充 / NSD 端点 (${adbEndpoints.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedButton(onClick = { vm.toggleAdbDiscovery() }) {
                            Text(if (adbScanning) "停止扫描 NSD" else "扫描无线 NSD")
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = { endpointDraft = "127.0.0.1:5555" },
                            label = { Text("127.0.0.1:5555 (本机 tcpip)") }
                        )
                        for (ep in adbEndpoints) {
                            AssistChip(
                                onClick = { endpointDraft = "${ep.host}:${ep.port}" },
                                label = { Text("${ep.serviceName} · ${ep.host}:${ep.port}") }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = endpointDraft,
                        onValueChange = { endpointDraft = it },
                        label = { Text("目标 ADB 端点 host:port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            enabled = !adbProbe.probing && endpointDraft.contains(':'),
                            onClick = {
                                val (h, p) = parseHostPort(endpointDraft)
                                vm.testAdbConnection(h, p, settings.adbTransportMode)
                            }
                        ) {
                            Icon(Icons.Default.GppGood, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (adbProbe.probing) "握手中..." else "连接并验证 ADB 授权")
                        }
                    }

                    if (adbProbe.probing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }

                    if (adbProbe.summary.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                adbProbe.probing -> MaterialTheme.colorScheme.primaryContainer
                                adbProbe.isError -> MaterialTheme.colorScheme.errorContainer
                                adbProbe.authorized -> semantic.successContainer
                                else -> semantic.subtleBg
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                adbProbe.summary,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    adbProbe.probing -> MaterialTheme.colorScheme.onPrimaryContainer
                                    adbProbe.isError -> MaterialTheme.colorScheme.onErrorContainer
                                    adbProbe.authorized -> semantic.onSuccessContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }

            item {
                DeviceLabRootCard(
                    settings = settings,
                    rootAuth = rootAuth,
                    rootAuthorizing = rootAuthorizing,
                    labRunning = labRunning,
                    strings = strings,
                    customRootArgvDraft = customRootArgvDraft,
                    onCustomRootArgvChange = { customRootArgvDraft = it },
                    parsedCustomRootArgv = parsedCustomRootArgv,
                    customRootPreflight = customRootPreflight,
                    onRequestRoot = { vm.requestRootAuthorization() },
                    onProbeRoot = { vm.probeRootEnvironment() },
                    onRevokeRoot = { vm.revokeRootAuthorization() },
                    onRunRootStatus = { vm.runBridgeTrialTool("device_root_status") },
                    onSelectPolicyMode = { vm.setRootPolicyMode(it) },
                    onDispatchRootExec = { dispatchRootExec(it) }
                )
            }

            item {
                DeviceLabTrialCard(
                    settings = settings,
                    labRunning = labRunning,
                    labResult = labResult,
                    customArgvDraft = customArgvDraft,
                    onCustomArgvChange = { customArgvDraft = it },
                    parsedCustomArgv = parsedCustomArgv,
                    customPreflight = customPreflight,
                    timeFmt = timeFmt,
                    onRunDeviceInfo = { vm.runBridgeTrialTool("device_info") },
                    onDispatchAdbExec = { dispatchAdbExec(it) }
                )
            }

            item {
                DeviceLabAuditCard(
                    auditEntries = auditEntries,
                    timeFmt = timeFmt,
                    onRefresh = { vm.refreshAuditEntries() },
                    onClear = { vm.clearAuditEntries() }
                )
            }
        }
    }
}

@Composable
private fun LabToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
