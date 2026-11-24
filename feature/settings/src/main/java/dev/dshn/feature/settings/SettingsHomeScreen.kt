package dev.dshn.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.data.AppLocaleMode
import dev.dshn.app.data.AppThemeMode
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.core.ui.BrandMark
import dev.dshn.core.ui.ChoiceDialog
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsDivider
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.SettingsRow
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.appVersionName
import dev.dshn.core.ui.tierLabel
import dev.dshn.core.ui.tr

@Composable
internal fun SettingsList(inner: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(inner),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        content = content
    )
}

@Composable
internal fun runtimeStateText(state: RuntimeLifecycleState): Pair<String, PillTone> = when (state) {
    RuntimeLifecycleState.HEALTHY -> tr("运行中", "Running") to PillTone.SUCCESS
    RuntimeLifecycleState.STARTING -> tr("正在启动", "Starting") to PillTone.WARNING
    RuntimeLifecycleState.DEGRADED -> tr("运行不稳定", "Degraded") to PillTone.WARNING
    RuntimeLifecycleState.STOPPING -> tr("正在停止", "Stopping") to PillTone.NEUTRAL
    RuntimeLifecycleState.STOPPED -> tr("未启动", "Stopped") to PillTone.NEUTRAL
    RuntimeLifecycleState.ERROR -> tr("出错", "Error") to PillTone.ERROR
}

@Composable
fun SettingsHomeScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenPermission: () -> Unit,
    onOpenDeviceBridge: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onReopenSetup: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasApiKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val manifest by vm.envManifest.collectAsStateWithLifecycle()
    val envInstall by vm.envInstallProgress.collectAsStateWithLifecycle()
    val runtime by vm.runtimeStatus.collectAsStateWithLifecycle()
    var showTheme by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.onSettingsHomeEntered()
    }

    val themeName = when (settings.themeMode) {
        AppThemeMode.LIGHT -> tr("浅色", "Light")
        AppThemeMode.DARK -> tr("深色", "Dark")
        AppThemeMode.SYSTEM -> tr("跟随系统", "System")
    }
    val langName = when (settings.localeMode) {
        AppLocaleMode.ZH -> "简体中文"
        AppLocaleMode.EN -> "English"
        AppLocaleMode.SYSTEM -> tr("跟随系统", "System")
    }

    SecondaryScaffold(title = tr("设置", "Settings"), onBack = onBack) { inner ->
        SettingsList(inner) {
            item {
                val semantic = LocalDshnColors.current
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = semantic.heroBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(size = 48.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("DSHN", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    buildString {
                                        append("v").append(appVersionName(ctx))
                                        if (manifest.dshVersion.isNotBlank()) append(" · DSH ").append(manifest.dshVersion)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(
                                if (hasApiKey) tr("API Key 已设置", "API key set") else tr("未设置 API Key", "No API key"),
                                if (hasApiKey) PillTone.SUCCESS else PillTone.WARNING,
                                showDot = true
                            )
                            val runtimeUp = runtime.state == RuntimeLifecycleState.HEALTHY || runtime.state == RuntimeLifecycleState.DEGRADED
                            val (rt, tone) = when {
                                envInstall.inProgress -> tr("正在初始化环境 ${(envInstall.fraction * 100).toInt()}%", "Unpacking ${(envInstall.fraction * 100).toInt()}%") to PillTone.INFO
                                !manifest.rootfsReady && !runtimeUp -> tr("环境未就绪", "Env not ready") to PillTone.WARNING
                                else -> runtimeStateText(runtime.state)
                            }
                            StatusPill(rt, tone, showDot = true)
                        }
                    }
                }
            }
            item {
                SettingsGroup(tr("常规", "General")) {
                    SettingsRow(tr("外观", "Appearance"), icon = Icons.Rounded.Palette, subtitle = themeName, onClick = { showTheme = true })
                    SettingsDivider()
                    SettingsRow(tr("语言", "Language"), icon = Icons.Rounded.Language, subtitle = langName, onClick = { showLang = true })
                }
            }
            item {
                SettingsGroup(tr("智能体", "Agent")) {
                    SettingsRow(
                        tr("模型与 API Key", "Model & API key"),
                        icon = Icons.Rounded.Key,
                        subtitle = if (hasApiKey) tr("已设置", "Configured") else tr("未设置", "Not set"),
                        onClick = onOpenModel
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("执行权限与工作目录", "Permissions & workspace"),
                        icon = Icons.Rounded.Security,
                        subtitle = tierLabel(settings.permissionMode) + " · " + settings.defaultCwd,
                        onClick = onOpenPermission
                    )
                }
            }
            item {
                SettingsGroup(tr("设备与系统", "Device & system")) {
                    SettingsRow(
                        tr("设备桥与 Root", "Device bridge & Root"),
                        icon = Icons.Rounded.DeveloperBoard,
                        subtitle = if (settings.bridgeEnabled) tr("已开启", "On") else tr("全部关闭", "All off"),
                        onClick = onOpenDeviceBridge
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("终端", "Terminal"),
                        icon = Icons.Rounded.Terminal,
                        subtitle = tr("直接操作容器里的 Shell", "Use the container shell directly"),
                        onClick = onOpenTerminal
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("后台运行", "Background"),
                        icon = Icons.Rounded.BatteryChargingFull,
                        subtitle = tr("保活、通知与电池", "Keep-alive, notifications, battery"),
                        onClick = onOpenBackground
                    )
                }
            }
            item {
                SettingsGroup(tr("维护", "Maintenance")) {
                    SettingsRow(
                        tr("运行环境", "Runtime environment"),
                        icon = Icons.Rounded.Storage,
                        subtitle = if (manifest.rootfsReady) tr("已就绪", "Ready") + if (manifest.dshVersion.isNotBlank()) " · DSH ${manifest.dshVersion}" else ""
                        else tr("未就绪", "Not ready"),
                        onClick = onOpenEnvironment
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("日志与诊断", "Logs & diagnostics"),
                        icon = Icons.Rounded.BugReport,
                        onClick = onOpenDiagnostics
                    )
                }
            }
            item {
                SettingsGroup(tr("其他", "Other")) {
                    SettingsRow(
                        tr("重新运行设置向导", "Run setup again"),
                        icon = Icons.Rounded.RocketLaunch,
                        onClick = onReopenSetup
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("关于 DSHN", "About DSHN"),
                        icon = Icons.Rounded.Info,
                        onClick = onOpenAbout
                    )
                }
            }
        }
    }

    if (showTheme) {
        ChoiceDialog(
            title = tr("外观", "Appearance"),
            options = listOf(
                AppThemeMode.LIGHT to tr("浅色", "Light"),
                AppThemeMode.DARK to tr("深色", "Dark"),
                AppThemeMode.SYSTEM to tr("跟随系统", "System")
            ),
            selected = settings.themeMode,
            onSelect = { vm.setThemeMode(it) },
            onDismiss = { showTheme = false }
        )
    }
    if (showLang) {
        ChoiceDialog(
            title = tr("语言", "Language"),
            options = listOf(
                AppLocaleMode.ZH to "简体中文",
                AppLocaleMode.EN to "English",
                AppLocaleMode.SYSTEM to tr("跟随系统", "System")
            ),
            selected = settings.localeMode,
            onSelect = { vm.setLocaleMode(it) },
            onDismiss = { showLang = false }
        )
    }
}
