package dev.dshn.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.bridge.RootAuthStatus
import dev.dshn.core.ui.CopyableCodeBox
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsDivider
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.SettingsRow
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.SwitchRow
import dev.dshn.core.ui.hasNotificationPermission
import dev.dshn.core.ui.isIgnoringBatteryOptimizations
import dev.dshn.core.ui.openNotificationSettings
import dev.dshn.core.ui.rememberResumeTick
import dev.dshn.core.ui.requestIgnoreBatteryOptimizations
import dev.dshn.core.ui.tr

@Composable
fun BackgroundSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rootAuth by vm.rootAuthState.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val notifOk = remember(tick) { hasNotificationPermission(ctx) }
    val batteryOk = remember(tick) { isIgnoringBatteryOptimizations(ctx) }
    var asked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true }
    val phantomCmd = "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""

    SecondaryScaffold(title = tr("后台运行", "Background"), onBack = onBack) { inner ->
        SettingsList(inner) {
            item {
                SettingsGroup(tr("运行策略", "Behaviour")) {
                    SwitchRow(
                        title = tr("任务进行时保持唤醒", "Stay awake during tasks"),
                        subtitle = tr("只在 DSH 回复期间持有唤醒锁", "Holds a wake lock only while DSH is responding"),
                        checked = settings.wakeLockDuringTurn,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(wakeLockDuringTurn = v) } }
                    )
                    SettingsDivider(indent = 14.dp)
                    SwitchRow(
                        title = tr("崩溃后自动重启", "Auto-restart after crashes"),
                        subtitle = tr("60 秒内最多自动重启 3 次", "Up to 3 restarts within 60 seconds"),
                        checked = settings.autoRestartRuntime,
                        onCheckedChange = { v -> vm.updateSettings { it.copy(autoRestartRuntime = v) } }
                    )
                }
            }
            item {
                SettingsGroup(tr("系统权限", "System permissions")) {
                    SettingsRow(
                        title = tr("通知权限", "Notifications"),
                        subtitle = tr("显示运行状态和授权提醒", "Runtime status and approval alerts"),
                        icon = Icons.Rounded.Notifications,
                        showChevron = false,
                        trailing = {
                            if (notifOk) StatusPill(tr("已允许", "Allowed"), PillTone.SUCCESS)
                            else FilledTonalButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else openNotificationSettings(ctx)
                            }) { Text(tr("允许", "Allow")) }
                        }
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = tr("电池不受限制", "Unrestricted battery"),
                        subtitle = tr("防止系统在后台杀掉运行环境", "Keeps the system from killing the runtime"),
                        icon = Icons.Rounded.BatteryChargingFull,
                        showChevron = false,
                        trailing = {
                            if (batteryOk) StatusPill(tr("已设置", "Done"), PillTone.SUCCESS)
                            else FilledTonalButton(onClick = { requestIgnoreBatteryOptimizations(ctx) }) { Text(tr("设置", "Set")) }
                        }
                    )
                }
            }
            item {
                SettingsGroup(
                    tr("幽灵进程限制（Android 12+）", "Phantom process limit (Android 12+)"),
                    footer = tr(
                        "Android 12 起系统会限制应用的子进程数量，可能导致长任务被杀。可以用电脑执行上面的命令解除，或在已授权 Root 时一键解除。",
                        "Android 12+ caps child processes, which can kill long tasks. Run the command above from a computer, or use Root if authorized."
                    )
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CopyableCodeBox(text = phantomCmd, onCopy = { vm.copyToClipboard("command", phantomCmd) })
                        if (rootAuth.status == RootAuthStatus.AUTHORIZED) {
                            Button(
                                onClick = { vm.applyPhantomProcessFixViaRoot() },
                                modifier = Modifier.testTag("settings_phantom_root_fix_button")
                            ) { Text(tr("用 Root 一键解除", "Fix with Root")) }
                        }
                    }
                }
            }
        }
    }
}
