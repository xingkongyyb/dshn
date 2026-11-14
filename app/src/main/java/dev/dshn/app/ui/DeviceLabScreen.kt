package dev.dshn.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshn.app.data.AdbTransportMode
import dev.dshn.app.security.AdbArgvPolicy
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceLabScreen(
    vm: MainViewModel,
    strings: UiStrings,
    modifier: Modifier = Modifier
) {
    val semantic = LocalDshnColors.current
    val settings by vm.settings.collectAsState()
    val accConnected by vm.accessibilityConnected.collectAsState()
    val adbEndpoints by vm.discoveredAdbEndpoints.collectAsState()
    val adbScanning by vm.adbScanning.collectAsState()
    val adbIdentity by vm.adbIdentity.collectAsState()
    val adbProbe by vm.adbProbeState.collectAsState()
    val labRunning by vm.labRunning.collectAsState()
    val labResult by vm.labLastResult.collectAsState()
    val auditEntries by vm.auditEntries.collectAsState()

    var endpointDraft by remember(settings.lastAdbEndpoint) {
        mutableStateOf(settings.lastAdbEndpoint ?: "127.0.0.1:5555")
    }
    var customArgvDraft by remember { mutableStateOf("getprop ro.build.version.release") }

    val parsedCustomArgv = remember(customArgvDraft) {
        customArgvDraft.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val customPreflight = remember(parsedCustomArgv) {
        AdbArgvPolicy.evaluate(parsedCustomArgv)
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun dispatchAdbExec(argv: List<String>) {
        val parts = endpointDraft.trim().split(':')
        val host = parts.getOrNull(0)?.trim().orEmpty()
        val port = parts.getOrNull(1)?.toIntOrNull()
        vm.runBridgeTrialTool(
            "device_adb_exec",
            buildJsonObject {
                putJsonArray("argv") { argv.forEach { add(it) } }
                if (host.isNotEmpty() && port != null && port > 0) {
                    put("host", host)
                    put("port", port)
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero header + live readiness summary
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
                        ThemeQuickToggleButton(
                            strings = strings,
                            onToggle = { isDark -> vm.toggleLightDarkTheme(isDark) }
                        )
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
                    }
                }
            }
        }

        // Card 1: Safety gates & quick presets
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
                    Button(onClick = { vm.enableAdbTrialPreset() }) {
                        Text("启用 ADB 试验开关组合")
                    }
                    OutlinedButton(onClick = { vm.lockAllDeviceBridgeSwitches() }) {
                        Text("全部锁定关闭")
                    }
                }

                HorizontalDivider(color = semantic.cardBorder)

                LabToggleRow(
                    title = "Enable Device Bridge MCP",
                    subtitle = "主总闸：允许 device-mcp.sock 响应设备桥工具",
                    checked = settings.bridgeEnabled
                ) { v -> vm.updateSettings { it.copy(bridgeEnabled = v) } }

                LabToggleRow(
                    title = "ADB Argv Bridge",
                    subtitle = "允许结构化白名单内的 device_adb_exec 调用（与 device_info）",
                    checked = settings.allowAdbBridge
                ) { v -> vm.updateSettings { it.copy(allowAdbBridge = v) } }

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

        // Card 2: PC-authorized ADB TCP & RSA identity setup
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
                            val parts = endpointDraft.trim().split(':')
                            val h = parts.getOrNull(0).orEmpty()
                            val p = parts.getOrNull(1)?.toIntOrNull() ?: -1
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

        // Card 3: Interactive MCP Trial & Per-Call Confirmation Tester
        item {
            DshnSectionCard(
                title = "3. 逐次确认弹窗与白名单工具手动点测台",
                subtitle = "所有按钮直接调用 DeviceBridgeMcpServer（完全走真实开关闸、argv 策略、逐次弹窗与审计）",
                icon = Icons.Default.Terminal,
                trailing = {
                    if (labRunning) StatusPill("RUNNING", PillTone.INFO, showDot = true)
                }
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = semantic.subtleBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (settings.requirePerCallConfirmation) {
                            "提示：当前“逐次确认”为开启状态。点击下方 ADB 白名单命令后会立即弹出确认对话框（Allow Once / Deny）。"
                        } else {
                            "提示：当前“逐次确认”已关闭，白名单命令将直接放行。建议试验时保持开启以验证弹窗。"
                        },
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text("A. 仅读取设备基础信息", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        enabled = !labRunning,
                        onClick = { vm.runBridgeTrialTool("device_info") }
                    ) { Text("调用 device_info") }
                }

                Text("B. 允许列表 ADB 命令（验证逐次弹窗 + 真机返回）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                val allowedPresets = listOf(
                    "getprop ro.product.model" to listOf("getprop", "ro.product.model"),
                    "wm size" to listOf("wm", "size"),
                    "dumpsys battery" to listOf("dumpsys", "battery"),
                    "pm list packages -3" to listOf("pm", "list", "packages", "-3"),
                    "settings get global adb_enabled" to listOf("settings", "get", "global", "adb_enabled")
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((label, argv) in allowedPresets) {
                        Button(
                            enabled = !labRunning,
                            onClick = { dispatchAdbExec(argv) }
                        ) {
                            Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Text("C. 黑名单安全拦截验证（应返回 [POLICY_BLOCKED]）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                val blockedPresets = listOf(
                    "sh -c id" to listOf("sh", "-c", "id"),
                    "su -c whoami" to listOf("su", "-c", "whoami"),
                    "rm -rf /tmp/test" to listOf("rm", "-rf", "/tmp/test")
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((label, argv) in blockedPresets) {
                        OutlinedButton(
                            enabled = !labRunning,
                            onClick = { dispatchAdbExec(argv) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                HorizontalDivider(color = semantic.cardBorder)
                Text("D. 自定义 argv 实时策略预检并试运行", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = customArgvDraft,
                    onValueChange = { customArgvDraft = it },
                    label = { Text("输入空格分隔的参数数组 argv") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (customPreflight) {
                        is AdbArgvPolicy.PolicyDecision.Allowed -> {
                            StatusPill("预检: ALLOWED", PillTone.SUCCESS, showDot = true)
                        }
                        is AdbArgvPolicy.PolicyDecision.Blocked -> {
                            StatusPill("预检: BLOCKED", PillTone.ERROR, showDot = true)
                        }
                    }
                    Button(
                        enabled = !labRunning && parsedCustomArgv.isNotEmpty(),
                        onClick = { dispatchAdbExec(parsedCustomArgv) }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("运行 ADB 命令", maxLines = 1)
                    }
                }
                if (customPreflight is AdbArgvPolicy.PolicyDecision.Blocked) {
                    Text(
                        customPreflight.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (labRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                labResult?.let { res ->
                    val pillTone = when (res.decisionLabel) {
                        "ALLOWED_OK" -> PillTone.SUCCESS
                        "POLICY_BLOCKED" -> PillTone.ERROR
                        "USER_DENIED" -> PillTone.WARNING
                        else -> if (res.isError) PillTone.ERROR else PillTone.INFO
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = semantic.codeBg,
                        border = BorderStroke(1.dp, semantic.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${res.tool} · ${res.durationMs}ms · ${timeFmt.format(Date(res.timestampMs))}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                StatusPill(res.decisionLabel, pillTone)
                            }
                            SelectionContainer {
                                Text(
                                    res.outputText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }

        // Card 4: Live Audit Trail
        item {
            DshnSectionCard(
                title = "4. 设备安全审计记录 (${auditEntries.size})",
                subtitle = "记录来自 files/security/audit.jsonl",
                icon = Icons.Default.History,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.refreshAuditEntries() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { vm.clearAuditEntries() }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Clear",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            ) {
                if (auditEntries.isEmpty()) {
                    Text(
                        "暂无审计记录。点击上方试验按钮后将在此实时显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (a in auditEntries.take(12)) {
                            val tone = when (a.decision) {
                                "allowed_once" -> PillTone.SUCCESS
                                "auto_allowed" -> PillTone.INFO
                                "denied" -> PillTone.WARNING
                                "policy_blocked", "error" -> PillTone.ERROR
                                else -> PillTone.NEUTRAL
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = semantic.subtleBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    StatusPill(a.decision, tone)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${a.tool} · ${timeFmt.format(Date(a.timestampMs))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            a.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
