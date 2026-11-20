package dev.dshn.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshn.app.bridge.RootAuthStatus
import dev.dshn.app.data.AppLocaleMode
import dev.dshn.app.data.AppThemeMode
import dev.dshn.app.data.PermissionTier
import dev.dshn.app.runtime.EnvManager
import dev.dshn.app.runtime.RuntimeLifecycleState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------------

@Composable
fun tierLabel(tier: PermissionTier): String = when (tier) {
    PermissionTier.READ_ONLY -> tr("只读", "Read only")
    PermissionTier.WORKSPACE_WRITE -> tr("工作区可写", "Workspace write")
    PermissionTier.DANGER_FULL_ACCESS -> tr("完全访问", "Full access")
}

@Composable
private fun tierDescription(tier: PermissionTier): String = when (tier) {
    PermissionTier.READ_ONLY -> tr(
        "只能查看文件，不能修改，也不能运行命令。适合让它帮你读代码、解释问题。",
        "Can read files only — no edits, no commands. Good for reviewing and explaining."
    )
    PermissionTier.WORKSPACE_WRITE -> tr(
        "可以在工作目录中读写文件，写入前会征求你的同意。此设备内核不支持 Landlock 沙箱，所以 bash 命令不可用。",
        "Can read and write files in the workspace with your approval. Bash is unavailable because this kernel lacks Landlock sandboxing."
    )
    PermissionTier.DANGER_FULL_ACCESS -> tr(
        "可以在容器里直接运行 bash 命令，能完成大多数编程任务。容器不是安全沙箱，请只交给它你信任的任务。",
        "Can run bash directly in the container — needed for most coding tasks. The container is not a security sandbox."
    )
}

/** Selectable cards for the three DSH execution tiers. */
@Composable
fun PermissionTierCards(selected: PermissionTier, onSelect: (PermissionTier) -> Unit) {
    val semantic = LocalDshnColors.current
    val order = listOf(PermissionTier.READ_ONLY, PermissionTier.WORKSPACE_WRITE, PermissionTier.DANGER_FULL_ACCESS)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (tier in order) {
            val isSel = selected == tier
            val danger = tier == PermissionTier.DANGER_FULL_ACCESS
            val accent = if (danger) semantic.warning else MaterialTheme.colorScheme.primary
            Surface(
                onClick = { onSelect(tier) },
                shape = RoundedCornerShape(18.dp),
                color = if (isSel) (if (danger) semantic.warningContainer else semantic.heroBg) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (isSel) 1.5.dp else 1.dp, if (isSel) accent else semantic.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(selected = isSel, onClick = null, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (tier) {
                                    PermissionTier.READ_ONLY -> Icons.Rounded.Visibility
                                    PermissionTier.WORKSPACE_WRITE -> Icons.Rounded.Folder
                                    PermissionTier.DANGER_FULL_ACCESS -> Icons.Rounded.Terminal
                                },
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tierLabel(tier), style = MaterialTheme.typography.titleMedium)
                            if (tier == PermissionTier.WORKSPACE_WRITE) {
                                Spacer(Modifier.width(8.dp))
                                StatusPill(tr("默认", "Default"), PillTone.INFO)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tierDescription(tier),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsList(inner: PaddingValues, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
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
private fun runtimeStateText(state: RuntimeLifecycleState): Pair<String, PillTone> = when (state) {
    RuntimeLifecycleState.HEALTHY -> tr("运行中", "Running") to PillTone.SUCCESS
    RuntimeLifecycleState.STARTING -> tr("正在启动", "Starting") to PillTone.WARNING
    RuntimeLifecycleState.DEGRADED -> tr("运行不稳定", "Degraded") to PillTone.WARNING
    RuntimeLifecycleState.STOPPING -> tr("正在停止", "Stopping") to PillTone.NEUTRAL
    RuntimeLifecycleState.STOPPED -> tr("未启动", "Stopped") to PillTone.NEUTRAL
    RuntimeLifecycleState.ERROR -> tr("出错", "Error") to PillTone.ERROR
}

// ---------------------------------------------------------------------------------------------
// Settings home
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsHomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val hasApiKey by vm.hasApiKey.collectAsState()
    val manifest by vm.envManifest.collectAsState()
    val envInstall by vm.envInstallProgress.collectAsState()
    val runtime by vm.runtimeStatus.collectAsState()
    var showTheme by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }

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

    SecondaryScaffold(title = tr("设置", "Settings"), onBack = { vm.navigateBack() }) { inner ->
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
                        onClick = { vm.navigate(AppRoute.SETTINGS_MODEL) }
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("执行权限与工作目录", "Permissions & workspace"),
                        icon = Icons.Rounded.Security,
                        subtitle = tierLabel(settings.permissionMode) + " · " + settings.defaultCwd,
                        onClick = { vm.navigate(AppRoute.SETTINGS_PERMISSION) }
                    )
                }
            }
            item {
                SettingsGroup(tr("设备与系统", "Device & system")) {
                    SettingsRow(
                        tr("设备桥与 Root", "Device bridge & Root"),
                        icon = Icons.Rounded.DeveloperBoard,
                        subtitle = if (settings.bridgeEnabled) tr("已开启", "On") else tr("全部关闭", "All off"),
                        onClick = { vm.navigate(AppRoute.DEVICE_BRIDGE) }
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("终端", "Terminal"),
                        icon = Icons.Rounded.Terminal,
                        subtitle = tr("直接操作容器里的 Shell", "Use the container shell directly"),
                        onClick = { vm.navigate(AppRoute.TERMINAL) }
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("后台运行", "Background"),
                        icon = Icons.Rounded.BatteryChargingFull,
                        subtitle = tr("保活、通知与电池", "Keep-alive, notifications, battery"),
                        onClick = { vm.navigate(AppRoute.SETTINGS_BACKGROUND) }
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
                        onClick = { vm.navigate(AppRoute.SETTINGS_ENVIRONMENT) }
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("日志与诊断", "Logs & diagnostics"),
                        icon = Icons.Rounded.BugReport,
                        onClick = { vm.navigate(AppRoute.SETTINGS_DIAGNOSTICS) }
                    )
                }
            }
            item {
                SettingsGroup(tr("其他", "Other")) {
                    SettingsRow(
                        tr("重新运行设置向导", "Run setup again"),
                        icon = Icons.Rounded.RocketLaunch,
                        onClick = { vm.reopenSetup() }
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("关于 DSHN", "About DSHN"),
                        icon = Icons.Rounded.Info,
                        onClick = { vm.navigate(AppRoute.ABOUT) }
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

// ---------------------------------------------------------------------------------------------
// Model & API key
// ---------------------------------------------------------------------------------------------

@Composable
fun ModelSettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val hasApiKey by vm.hasApiKey.collectAsState()
    val masked by vm.apiKeyMasked.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var savingKey by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var baseUrl by remember(settings.deepseekBaseUrl) { mutableStateOf(settings.deepseekBaseUrl.orEmpty()) }
    var searchUrl by remember(settings.deepseekSearchBaseUrl) { mutableStateOf(settings.deepseekSearchBaseUrl.orEmpty()) }
    var provider by remember(settings.webSearchProvider) { mutableStateOf(settings.webSearchProvider) }

    SecondaryScaffold(title = tr("模型与 API Key", "Model & API key"), onBack = { vm.navigateBack() }) { inner ->
        SettingsList(inner) {
            item {
                SettingsGroup(
                    tr("DeepSeek API Key", "DeepSeek API key"),
                    footer = tr(
                        "Key 用 AndroidKeyStore 加密保存。为了让 DSH 使用，容器内会保留一份仅本应用可读的副本。建议使用专门的低额度 Key。",
                        "Encrypted with AndroidKeyStore. A copy readable only by this app is kept in the container for DSH. Prefer a dedicated low-limit key."
                    )
                ) {
                    SettingsRow(
                        title = if (hasApiKey) tr("已设置", "Configured") else tr("未设置", "Not set"),
                        subtitle = masked ?: if (hasApiKey) "sk-••••••••" else tr("输入 Key 后才能开始对话", "Required before chatting"),
                        icon = Icons.Rounded.Key,
                        iconTint = if (hasApiKey) LocalDshnColors.current.success else LocalDshnColors.current.warning,
                        showChevron = false
                    )
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ApiKeyField(draft) { draft = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = draft.isNotBlank() && !savingKey,
                                onClick = {
                                    savingKey = true
                                    vm.saveApiKey(draft) { ok ->
                                        savingKey = false
                                        if (ok) draft = ""
                                    }
                                }
                            ) {
                                Text(
                                    when {
                                        savingKey -> tr("保存中…", "Saving…")
                                        hasApiKey -> tr("更换", "Replace")
                                        else -> tr("保存", "Save")
                                    }
                                )
                            }
                            if (hasApiKey) {
                                OutlinedButton(onClick = { confirmClear = true }) { Text(tr("清除", "Clear")) }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { openUrl(ctx, DEEPSEEK_API_KEYS_URL) }) {
                                Text(tr("获取 Key", "Get a key"))
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup(
                    tr("接口与联网搜索（高级）", "Endpoint & web search (advanced)"),
                    footer = tr("留空即使用 DeepSeek 官方接口。修改后需要重启运行环境。", "Leave empty for the official API. Restart the runtime after changing.")
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(tr("API 地址（DEEPSEEK_BASE_URL）", "API base URL (DEEPSEEK_BASE_URL)")) },
                            placeholder = { Text("https://api.deepseek.com") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = searchUrl,
                            onValueChange = { searchUrl = it },
                            label = { Text(tr("搜索网关地址（可选）", "Search gateway URL (optional)")) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(tr("联网搜索方式", "Web search provider"), style = MaterialTheme.typography.labelLarge)
                        val providers = listOf(
                            "" to tr("自动（推荐）", "Auto (recommended)"),
                            "deepseek-official" to tr("DeepSeek 官方消息搜索", "DeepSeek official (messages)"),
                            "deepseek-search-api" to tr("DeepSeek Search API", "DeepSeek Search API")
                        )
                        for ((value, label) in providers) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { provider = value }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = provider == value, onClick = null, modifier = Modifier.padding(8.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(
                            onClick = { vm.saveEndpointAndSearchConfig(baseUrl, searchUrl, provider) },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text(tr("保存", "Save")) }
                    }
                }
            }
            item {
                InfoBanner(
                    text = tr(
                        "模型和思考强度可以在对话输入框下方的按钮里随时切换，每个对话单独生效。",
                        "Switch the model and reasoning effort from the chips under the chat input; each chat keeps its own choice."
                    ),
                    tone = PillTone.NEUTRAL
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = tr("清除 API Key？", "Clear API key?"),
            text = tr("清除后 DSH 将无法调用模型，直到你重新设置。", "DSH won't be able to call the model until you set a new key."),
            confirmLabel = tr("清除", "Clear"),
            destructive = true,
            onConfirm = { vm.saveApiKey("") },
            onDismiss = { confirmClear = false }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Permissions & workspace
// ---------------------------------------------------------------------------------------------

@Composable
fun PermissionSettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    var cwd by remember(settings.defaultCwd) { mutableStateOf(settings.defaultCwd) }
    var bind by remember(settings.extraWorkspaceBindHostPath) { mutableStateOf(settings.extraWorkspaceBindHostPath.orEmpty()) }

    SecondaryScaffold(title = tr("执行权限与工作目录", "Permissions & workspace"), onBack = { vm.navigateBack() }) { inner ->
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
                            OutlinedButton(onClick = { vm.restartRuntime() }) { Text(tr("重启运行环境", "Restart runtime")) }
                            Button(onClick = { vm.saveWorkspaceSettings(cwd, bind) }) { Text(tr("保存", "Save")) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Background / keep-alive
// ---------------------------------------------------------------------------------------------

@Composable
fun BackgroundSettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsState()
    val rootAuth by vm.rootAuthState.collectAsState()
    val tick = rememberResumeTick()
    val notifOk = remember(tick) { hasNotificationPermission(ctx) }
    val batteryOk = remember(tick) { isIgnoringBatteryOptimizations(ctx) }
    var asked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true }
    val phantomCmd = "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""

    SecondaryScaffold(title = tr("后台运行", "Background"), onBack = { vm.navigateBack() }) { inner ->
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
                            Button(onClick = { vm.applyPhantomProcessFixViaRoot() }) { Text(tr("用 Root 一键解除", "Fix with Root")) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Runtime environment
// ---------------------------------------------------------------------------------------------

@Composable
fun EnvironmentSettingsScreen(vm: MainViewModel) {
    val manifest by vm.envManifest.collectAsState()
    val bundled by vm.bundledEnvManifest.collectAsState()
    val install by vm.envInstallProgress.collectAsState()
    val checking by vm.envChecking.collectAsState()
    val runtime by vm.runtimeStatus.collectAsState()
    val snapshots by vm.snapshots.collectAsState()
    val selfTest by vm.selfTestReport.collectAsState()
    val selfTestRunning by vm.selfTestRunning.collectAsState()
    val upgrading by vm.dshUpgrading.collectAsState()
    val upgradeReport by vm.dshUpgradeReport.collectAsState()
    val semantic = LocalDshnColors.current
    var confirmUpgrade by remember { mutableStateOf(false) }
    var confirmReinstall by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }
    var showManualGuide by rememberSaveable { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val usable = runtime.state == RuntimeLifecycleState.HEALTHY || runtime.state == RuntimeLifecycleState.DEGRADED
    val atTarget = EnvManager.isDshVersionAtTarget(manifest.dshVersion)

    SecondaryScaffold(title = tr("运行环境", "Runtime environment"), onBack = { vm.navigateBack() }) { inner ->
        SettingsList(inner) {
            if (install.inProgress) {
                item { BundledEnvProgressCard(install) }
            }
            if (!install.error.isNullOrBlank() && !install.inProgress) {
                item {
                    InfoBanner(
                        title = tr("内置环境安装失败", "Bundled install failed"),
                        text = install.error.orEmpty(),
                        tone = PillTone.ERROR,
                        actionLabel = if (bundled != null) tr("重试", "Retry") else null,
                        onAction = if (bundled != null) ({ vm.installBundledEnvironment(reinstall = false) }) else null
                    )
                }
            }
            if (!manifest.rootfsReady && !install.inProgress) {
                item {
                    if (bundled != null) {
                        InfoBanner(
                            title = tr("可一键安装内置运行环境", "Bundled runtime ready to install"),
                            text = tr(
                                "安装包已内置 Ubuntu ${bundled!!.ubuntuVersion} + Node.js ${bundled!!.nodeVersion} + DSH ${bundled!!.dshVersion}，点击下方按钮即可离线解压就绪。",
                                "This APK includes Ubuntu ${bundled!!.ubuntuVersion} + Node.js ${bundled!!.nodeVersion} + DSH ${bundled!!.dshVersion}. Tap below to unpack offline."
                            ),
                            tone = PillTone.INFO,
                            actionLabel = tr("立即安装", "Install now"),
                            onAction = { vm.installBundledEnvironment(reinstall = false) }
                        )
                    } else {
                        InfoBanner(
                            title = tr("还没有检测到运行环境", "Runtime environment not found"),
                            text = tr("按下面的步骤用电脑准备一次即可。", "Prepare it once with a computer using the steps below."),
                            tone = PillTone.WARNING
                        )
                    }
                }
                if (bundled == null || showManualGuide) {
                    item {
                        SettingsGroup(tr("手动准备步骤（ADB）", "Manual setup steps (ADB)")) {
                            Column(Modifier.padding(14.dp)) {
                                RootfsPrepGuide(onCopy = { vm.copyToClipboard("commands", it) })
                            }
                        }
                    }
                }
            }
            item {
                val checks = environmentChecks(manifest)
                SettingsGroup(tr("组件状态", "Components")) {
                    checks.forEachIndexed { i, c ->
                        if (i > 0) SettingsDivider(indent = 52.dp)
                        CheckRow(c, checking || install.inProgress)
                    }
                    SettingsDivider(indent = 16.dp)
                    val (stateText, tone) = runtimeStateText(runtime.state)
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("运行状态", "Status"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (runtime.restartCount > 0) {
                            Text(
                                tr("已重启 ${runtime.restartCount} 次", "${runtime.restartCount} restarts"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        StatusPill(stateText, tone, showDot = true)
                    }
                }
            }
            item {
                SettingsGroup(tr("操作", "Actions")) {
                    if (usable) {
                        SettingsRow(tr("重启运行环境", "Restart runtime"), icon = Icons.Rounded.RestartAlt, enabled = !install.inProgress, onClick = { vm.restartRuntime() }, showChevron = false)
                        SettingsDivider()
                        SettingsRow(tr("停止运行环境", "Stop runtime"), icon = Icons.Rounded.Stop, enabled = !install.inProgress, onClick = { vm.stopRuntime() }, showChevron = false)
                    } else {
                        SettingsRow(
                            tr("启动运行环境", "Start runtime"),
                            icon = Icons.Rounded.PlayArrow,
                            enabled = manifest.rootfsReady && !install.inProgress && runtime.state != RuntimeLifecycleState.STARTING,
                            onClick = { vm.startRuntimeAndAttach() },
                            showChevron = false
                        )
                    }
                    if (bundled != null) {
                        SettingsDivider()
                        SettingsRow(
                            if (manifest.rootfsReady) tr("修复 / 重装内置环境", "Repair / reinstall bundled runtime")
                            else tr("安装内置运行环境", "Install bundled runtime"),
                            subtitle = tr(
                                "离线解压 Ubuntu ${bundled!!.ubuntuVersion} · Node ${bundled!!.nodeVersion} · DSH ${bundled!!.dshVersion}（保留对话与 Key）",
                                "Offline Ubuntu ${bundled!!.ubuntuVersion} · Node ${bundled!!.nodeVersion} · DSH ${bundled!!.dshVersion} (keeps chats & key)"
                            ),
                            icon = Icons.Rounded.Storage,
                            enabled = !install.inProgress && !upgrading,
                            trailing = { if (install.inProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                            onClick = {
                                if (manifest.rootfsReady) confirmReinstall = true
                                else vm.installBundledEnvironment(reinstall = false)
                            },
                            showChevron = false
                        )
                    }
                    SettingsDivider()
                    SettingsRow(
                        tr("重新检测组件", "Re-check components"),
                        icon = Icons.Rounded.Refresh,
                        enabled = !checking && !install.inProgress,
                        onClick = { vm.refreshEnvironment() },
                        showChevron = false
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("运行自检", "Run self-test"),
                        icon = Icons.Rounded.Verified,
                        enabled = !selfTestRunning && !install.inProgress && manifest.rootfsReady,
                        trailing = { if (selfTestRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                        onClick = { vm.runSelfTest() },
                        showChevron = false
                    )
                    SettingsDivider()
                    SettingsRow(
                        if (atTarget) tr("通过 npm 更新/重装 DSH ${EnvManager.TARGET_DSH_VERSION}", "Update/reinstall DSH ${EnvManager.TARGET_DSH_VERSION} via npm")
                        else tr("更新 DSH 到 ${EnvManager.TARGET_DSH_VERSION}", "Update DSH to ${EnvManager.TARGET_DSH_VERSION}"),
                        subtitle = tr("更新前会自动创建快照，失败可恢复", "A snapshot is taken first so you can roll back"),
                        icon = Icons.Rounded.Download,
                        enabled = manifest.rootfsReady && !upgrading && !install.inProgress,
                        trailing = { if (upgrading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                        onClick = { confirmUpgrade = true },
                        showChevron = false
                    )
                }
            }
            selfTest?.let { report ->
                item {
                    SettingsGroup(
                        if (report.allPassed) tr("自检全部通过", "Self-test passed") else tr("自检发现问题", "Self-test found issues")
                    ) {
                        report.checks.forEachIndexed { i, c ->
                            if (i > 0) SettingsDivider(indent = 52.dp)
                            CheckRow(EnvCheckUi(c.name, c.detail, c.passed), checking = false, multilineDetail = true)
                        }
                    }
                }
            }
            upgradeReport?.let { up ->
                item {
                    InfoBanner(
                        title = tr("DSH 更新结果", "DSH update result"),
                        text = "${up.previousVersion.ifEmpty { "?" }} → ${up.installedVersion} · " +
                            (if (up.selfTestPassed) tr("自检通过", "self-test passed") else tr("自检未通过", "self-test failed")) +
                            (up.snapshotId?.let { tr("\n更新前快照：$it", "\nPre-update snapshot: $it") } ?: ""),
                        tone = if (up.selfTestPassed) PillTone.SUCCESS else PillTone.WARNING
                    )
                }
            }
            item {
                SettingsGroup(
                    tr("快照", "Snapshots"),
                    footer = tr("快照保存对话记录和 DSH 配置，不包含 API Key。恢复前会校验格式兼容性。", "Snapshots include chats and DSH config, never the API key. Compatibility is checked before restoring.")
                ) {
                    SettingsRow(
                        tr("立即创建快照", "Create snapshot now"),
                        icon = Icons.Rounded.CameraAlt,
                        enabled = manifest.rootfsReady,
                        onClick = { vm.createEnvSnapshot() },
                        showChevron = false
                    )
                    for (snap in snapshots.take(8)) {
                        SettingsDivider()
                        SettingsRow(
                            title = if (snap.createdAtMs > 0) timeFmt.format(Date(snap.createdAtMs)) else snap.id,
                            subtitle = tr("${snap.sessionCount} 个对话", "${snap.sessionCount} chats") +
                                if (snap.dshVersion.isNotBlank()) " · DSH ${snap.dshVersion}" else "",
                            icon = Icons.Rounded.Restore,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            showChevron = false,
                            trailing = {
                                TextButton(onClick = { confirmRestore = snap.id }) { Text(tr("恢复", "Restore")) }
                            }
                        )
                    }
                }
            }
        }
    }

    if (confirmReinstall) {
        ConfirmDialog(
            title = tr("重新安装内置运行环境？", "Reinstall bundled runtime?"),
            text = tr(
                "将停止运行环境并用安装包内置的 Ubuntu + Node.js + DSH 镜像重新解压覆盖（你在 ~/.dsh 中的对话记录、配置与 API Key 会自动保留）。",
                "The runtime will stop and re-unpack the bundled Ubuntu + Node.js + DSH image. Your chats, config and API key in ~/.dsh are preserved."
            ),
            confirmLabel = tr("开始重装", "Reinstall"),
            onConfirm = { vm.installBundledEnvironment(reinstall = true) },
            onDismiss = { confirmReinstall = false }
        )
    }
    if (confirmUpgrade) {
        ConfirmDialog(
            title = tr("更新 DSH？", "Update DSH?"),
            text = tr(
                "将停止运行环境，先创建快照，再通过 npm 安装 DSH ${EnvManager.TARGET_DSH_VERSION}，完成后自动自检并重启。需要联网，可能耗时几分钟。",
                "The runtime will stop, a snapshot will be taken, then DSH ${EnvManager.TARGET_DSH_VERSION} is installed via npm and self-tested. Needs network; may take a few minutes."
            ),
            confirmLabel = tr("开始更新", "Update"),
            onConfirm = { vm.upgradeDshToVersion() },
            onDismiss = { confirmUpgrade = false }
        )
    }
    confirmRestore?.let { id ->
        ConfirmDialog(
            title = tr("恢复这个快照？", "Restore this snapshot?"),
            text = tr("当前的对话记录和 DSH 配置会被快照内容替换（API Key 不受影响），运行环境会自动重启。", "Current chats and DSH config will be replaced by the snapshot (API key unaffected). The runtime restarts automatically."),
            confirmLabel = tr("恢复", "Restore"),
            destructive = true,
            onConfirm = { vm.restoreEnvSnapshot(id) },
            onDismiss = { confirmRestore = null }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------------------------

@Composable
fun DiagnosticsScreen(vm: MainViewModel) {
    val logs by vm.runtimeLogs.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val semantic = LocalDshnColors.current
    SecondaryScaffold(title = tr("日志与诊断", "Logs & diagnostics"), onBack = { vm.navigateBack() }) { inner ->
        SettingsList(inner) {
            item {
                SettingsGroup(
                    title = null,
                    footer = tr("诊断信息会自动去掉 API Key 等敏感内容，可直接发给开发者排查问题。", "Diagnostics are sanitized (API keys removed) and safe to share for troubleshooting.")
                ) {
                    SettingsRow(tr("复制脱敏诊断信息", "Copy sanitized diagnostics"), icon = Icons.Rounded.ContentCopy, onClick = { vm.exportSanitizedDiagnosticsToClipboard() }, showChevron = false)
                    SettingsDivider()
                    SettingsRow(tr("刷新日志", "Refresh logs"), icon = Icons.Rounded.Refresh, onClick = { vm.refreshLogsAndStatus() }, showChevron = false)
                    SettingsDivider()
                    SettingsRow(tr("清空日志", "Clear logs"), icon = Icons.Rounded.CleaningServices, onClick = { confirmClear = true }, showChevron = false)
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

// ---------------------------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------------------------

@Composable
fun AboutScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val manifest by vm.envManifest.collectAsState()
    SecondaryScaffold(title = tr("关于", "About"), onBack = { vm.navigateBack() }) { inner ->
        SettingsList(inner) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    BrandMark(size = 76.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("DSHN", style = MaterialTheme.typography.headlineSmall)
                    Text("DeepSeek Harness Native", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    StatusPill("v${appVersionName(ctx)}", PillTone.INFO)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        tr(
                            "在安卓上以原生应用运行完整的 DeepSeek Harness 智能体：本地 Ubuntu 容器、标准 ACP 协议、零 DSH 补丁。",
                            "Run the full DeepSeek Harness agent as a native Android app: a local Ubuntu container, the standard ACP protocol and zero DSH patches."
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                SettingsGroup(tr("组件", "Components")) {
                    AboutRow("DSH", manifest.dshVersion.ifBlank { tr("未安装", "Not installed") })
                    SettingsDivider(indent = 16.dp)
                    AboutRow("Node.js", manifest.nodeVersion.ifBlank { "—" })
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("容器运行时", "Container runtime"), "bxroot v0.2.5")
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("系统", "Rootfs"), "Ubuntu 24.04 arm64")
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("通信协议", "Protocol"), "ACP (Agent Client Protocol)")
                }
            }
            item {
                SettingsGroup(
                    tr("开源", "Open source"),
                    footer = tr(
                        "DSHN 是社区开源项目，与 DeepSeek 官方无隶属关系。DeepSeek 是其所有者的商标。",
                        "DSHN is a community project, not affiliated with DeepSeek. DeepSeek is a trademark of its owner."
                    )
                ) {
                    AboutRow(tr("许可证", "License"), "MIT")
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("第三方声明", "Third-party notices"), "THIRD_PARTY_NOTICES.md")
                }
            }
        }
    }
}

@Composable
private fun AboutRow(title: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
