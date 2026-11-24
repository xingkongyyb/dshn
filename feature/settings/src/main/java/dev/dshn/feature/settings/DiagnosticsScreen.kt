package dev.dshn.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.core.ui.ConfirmDialog
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsDivider
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.SettingsRow
import dev.dshn.core.ui.tr

@Composable
fun DiagnosticsScreen(
    vm: EnvironmentDiagnosticsViewModel,
    onBack: () -> Unit
) {
    val logs by vm.runtimeLogs.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val semantic = LocalDshnColors.current

    LaunchedEffect(Unit) {
        vm.onDiagnosticsEntered()
    }

    SecondaryScaffold(title = tr("日志与诊断", "Logs & diagnostics"), onBack = onBack) { inner ->
        SettingsList(inner) {
            item {
                SettingsGroup(
                    title = null,
                    footer = tr("诊断信息会自动去掉 API Key 等敏感内容，可直接发给开发者排查问题。", "Diagnostics are sanitized (API keys removed) and safe to share for troubleshooting.")
                ) {
                    SettingsRow(
                        tr("复制脱敏诊断信息", "Copy sanitized diagnostics"),
                        icon = Icons.Rounded.ContentCopy,
                        onClick = { vm.exportSanitizedDiagnosticsToClipboard() },
                        showChevron = false
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("刷新日志", "Refresh logs"),
                        icon = Icons.Rounded.Refresh,
                        onClick = { vm.refreshLogsAndStatus() },
                        showChevron = false
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("清空日志", "Clear logs"),
                        icon = Icons.Rounded.CleaningServices,
                        onClick = { confirmClear = true },
                        showChevron = false
                    )
                }
            }
            item {
                Text(
                    tr("运行日志（最近 ${logs.size} 行）", "Runtime log (last ${logs.size} lines)"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = semantic.terminalBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                ) {
                    SelectionContainer {
                        Text(
                            if (logs.isEmpty()) tr("暂无日志", "No logs yet") else logs.takeLast(150).joinToString("\n"),
                            color = semantic.terminalFg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = tr("清空运行日志？", "Clear runtime logs?"),
            text = tr("当前保存的运行日志将被删除，之后排查问题时将无法查看。", "Saved runtime logs will be deleted and can't be used for troubleshooting afterwards."),
            confirmLabel = tr("清空", "Clear"),
            destructive = true,
            onConfirm = { vm.clearRuntimeLogs() },
            onDismiss = { confirmClear = false }
        )
    }
}
