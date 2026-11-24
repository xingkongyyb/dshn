package dev.dshn.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.core.ui.PermissionTierCards
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.tr

@Composable
fun PermissionSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var cwd by remember(settings.defaultCwd) { mutableStateOf(settings.defaultCwd) }
    var bind by remember(settings.extraWorkspaceBindHostPath) { mutableStateOf(settings.extraWorkspaceBindHostPath.orEmpty()) }

    SecondaryScaffold(title = tr("执行权限与工作目录", "Permissions & workspace"), onBack = onBack) { inner ->
        SettingsList(inner) {
            item {
                Column {
                    Text(
                        tr("执行权限", "Execution tier"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                    PermissionTierCards(selected = settings.permissionMode, onSelect = { vm.setPermissionTier(it) })
                    Text(
                        tr("切换后需要重启运行环境才会生效。", "Restart the runtime for the change to take effect."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp)
                    )
                }
            }
            item {
                SettingsGroup(
                    tr("工作目录", "Workspace"),
                    footer = tr(
                        "宿主机目录会被映射到容器内的 /mnt/workspace。修改映射后需要重启运行环境。",
                        "The host folder is mounted at /mnt/workspace inside the container. Restart the runtime after changing it."
                    )
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = cwd,
                            onValueChange = { cwd = it },
                            label = { Text(tr("新对话的默认目录（容器内）", "Default folder for new chats (container)")) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = bind,
                            onValueChange = { bind = it },
                            label = { Text(tr("映射宿主机目录（可选）", "Mount a host folder (optional)")) },
                            placeholder = { Text(tr("留空表示不映射", "Leave empty for none")) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                            OutlinedButton(
                                onClick = { vm.restartRuntime() },
                                modifier = Modifier.testTag("settings_restart_runtime_button")
                            ) { Text(tr("重启运行环境", "Restart runtime")) }
                            Button(
                                onClick = { vm.saveWorkspaceSettings(cwd, bind) },
                                modifier = Modifier.testTag("settings_save_workspace_button")
                            ) { Text(tr("保存", "Save")) }
                        }
                    }
                }
            }
        }
    }
}
