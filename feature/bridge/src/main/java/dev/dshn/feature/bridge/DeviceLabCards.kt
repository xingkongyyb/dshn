package dev.dshn.feature.bridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshn.app.bridge.RootAuthState
import dev.dshn.app.bridge.RootAuthStatus
import dev.dshn.app.data.AppSettings
import dev.dshn.app.security.AdbArgvPolicy
import dev.dshn.app.security.AuditEntry
import dev.dshn.app.security.RootCommandPolicy
import dev.dshn.app.security.RootPolicyMode
import dev.dshn.core.ui.DshnSectionCard
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.UiStrings
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceLabRootCard(
    settings: AppSettings,
    rootAuth: RootAuthState,
    rootAuthorizing: Boolean,
    labRunning: Boolean,
    strings: UiStrings,
    customRootArgvDraft: String,
    onCustomRootArgvChange: (String) -> Unit,
    parsedCustomRootArgv: List<String>,
    customRootPreflight: RootCommandPolicy.PolicyDecision,
    onRequestRoot: () -> Unit,
    onProbeRoot: () -> Unit,
    onRevokeRoot: () -> Unit,
    onRunRootStatus: () -> Unit,
    onSelectPolicyMode: (RootPolicyMode) -> Unit,
    onDispatchRootExec: (List<String>) -> Unit
) {
    val rootTone = when (rootAuth.status) {
        RootAuthStatus.AUTHORIZED -> PillTone.SUCCESS
        RootAuthStatus.DENIED, RootAuthStatus.ERROR -> PillTone.ERROR
        RootAuthStatus.NOT_AUTHORIZED, RootAuthStatus.REVOKED -> PillTone.WARNING
        else -> PillTone.NEUTRAL
    }
    DshnSectionCard(
        title = "2b. Root 授权与特权策略试验台 (su)",
        subtitle = "通过 Magisk / KernelSU / SukiSU / APatch 申请标准 su 授权，所有命令经 RootCommandPolicy + 逐次弹窗 + 审计校验",
        icon = Icons.Default.Security,
        trailing = {
            StatusPill(rootAuth.status.name, rootTone, showDot = true)
        }
    ) {
        Text(
            buildString {
                append("su=${rootAuth.suBinaryPath ?: "未检测到"}")
                if (rootAuth.suManagerHint.isNotBlank()) append(" · ${rootAuth.suManagerHint}")
                if (rootAuth.uidOutput.isNotBlank()) append("\n${rootAuth.uidOutput}")
                if (rootAuth.selinuxMode.isNotBlank()) append(" · SELinux=${rootAuth.selinuxMode}")
                if (!rootAuth.lastError.isNullOrBlank()) append("\n${rootAuth.lastError}")
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        if (rootAuthorizing) LinearProgressIndicator(Modifier.fillMaxWidth())

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                enabled = !rootAuthorizing,
                onClick = onRequestRoot
            ) { Text(if (rootAuthorizing) "等待授权弹窗..." else strings.rootGrantAction) }

            OutlinedButton(
                enabled = !rootAuthorizing,
                onClick = onProbeRoot
            ) { Text(strings.rootProbeAction) }

            OutlinedButton(onClick = onRevokeRoot) { Text(strings.rootRevokeAction) }

            OutlinedButton(
                enabled = !labRunning,
                onClick = onRunRootStatus
            ) { Text("调用 device_root_status") }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = settings.rootPolicyMode == RootPolicyMode.ALLOWLIST_ONLY,
                onClick = { onSelectPolicyMode(RootPolicyMode.ALLOWLIST_ONLY) },
                label = { Text("白名单模式", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = settings.rootPolicyMode == RootPolicyMode.CUSTOM_WITH_CONFIRM,
                onClick = { onSelectPolicyMode(RootPolicyMode.CUSTOM_WITH_CONFIRM) },
                label = { Text("自定义 + 强制确认", style = MaterialTheme.typography.labelSmall) }
            )
        }

        Text("A. 白名单 Root 命令试运行", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        val rootAllowedPresets = listOf(
            "id" to listOf("id"),
            "getenforce" to listOf("getenforce"),
            "uname -a" to listOf("uname", "-a"),
            "cat /proc/loadavg" to listOf("cat", "/proc/loadavg"),
            "dumpsys thermalservice" to listOf("dumpsys", "thermalservice")
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((label, argv) in rootAllowedPresets) {
                Button(
                    enabled = !labRunning,
                    onClick = { onDispatchRootExec(argv) }
                ) {
                    Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Text("B. Root 安全隔离与灾难拦截验证（应返回 [POLICY_BLOCKED]）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        val rootBlockedPresets = listOf(
            "rm -rf /" to listOf("rm", "-rf", "/"),
            "cat .../secrets.enc" to listOf("cat", "/data/data/dev.dshn.app/files/security/secrets.enc"),
            "sh -c id" to listOf("sh", "-c", "id")
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((label, argv) in rootBlockedPresets) {
                OutlinedButton(
                    enabled = !labRunning,
                    onClick = { onDispatchRootExec(argv) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        OutlinedTextField(
            value = customRootArgvDraft,
            onValueChange = onCustomRootArgvChange,
            label = { Text("自定义 Root argv（空格分隔）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (customRootPreflight) {
                is RootCommandPolicy.PolicyDecision.Allowed -> {
                    StatusPill(
                        if (customRootPreflight.requiresMandatoryConfirmation) "预检: ALLOWED (强制确认)" else "预检: ALLOWED",
                        PillTone.SUCCESS,
                        showDot = true
                    )
                }
                is RootCommandPolicy.PolicyDecision.Blocked -> {
                    StatusPill("预检: BLOCKED", PillTone.ERROR, showDot = true)
                }
            }
            Button(
                enabled = !labRunning && parsedCustomRootArgv.isNotEmpty(),
                onClick = { onDispatchRootExec(parsedCustomRootArgv) }
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("运行 Root 命令", maxLines = 1)
            }
        }
        if (customRootPreflight is RootCommandPolicy.PolicyDecision.Blocked) {
            Text(
                customRootPreflight.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceLabTrialCard(
    settings: AppSettings,
    labRunning: Boolean,
    labResult: LabTrialResult?,
    customArgvDraft: String,
    onCustomArgvChange: (String) -> Unit,
    parsedCustomArgv: List<String>,
    customPreflight: AdbArgvPolicy.PolicyDecision,
    timeFmt: SimpleDateFormat,
    onRunDeviceInfo: () -> Unit,
    onDispatchAdbExec: (List<String>) -> Unit
) {
    val semantic = LocalDshnColors.current
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
                onClick = onRunDeviceInfo
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
                    onClick = { onDispatchAdbExec(argv) }
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
                    onClick = { onDispatchAdbExec(argv) },
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
            onValueChange = onCustomArgvChange,
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
                onClick = { onDispatchAdbExec(parsedCustomArgv) }
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

@Composable
internal fun DeviceLabAuditCard(
    auditEntries: List<AuditEntry>,
    timeFmt: SimpleDateFormat,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    val semantic = LocalDshnColors.current
    DshnSectionCard(
        title = "4. 设备安全审计记录 (${auditEntries.size})",
        subtitle = "记录来自 files/security/audit.jsonl",
        icon = Icons.Default.History,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClear) {
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
