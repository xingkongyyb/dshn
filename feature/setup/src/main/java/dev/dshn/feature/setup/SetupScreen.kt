package dev.dshn.feature.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.runtime.EnvManager
import dev.dshn.core.ui.ApiKeyField
import dev.dshn.core.ui.BrandMark
import dev.dshn.core.ui.BundledEnvProgressCard
import dev.dshn.core.ui.CheckRow
import dev.dshn.core.ui.DEEPSEEK_API_KEYS_URL
import dev.dshn.core.ui.InfoBanner
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PermissionTierCards
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.RootfsPrepGuide
import dev.dshn.core.ui.SettingsDivider
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.SettingsRow
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.buildEnvironmentChecks
import dev.dshn.core.ui.hasNotificationPermission
import dev.dshn.core.ui.isIgnoringBatteryOptimizations
import dev.dshn.core.ui.openNotificationSettings
import dev.dshn.core.ui.openUrl
import dev.dshn.core.ui.rememberResumeTick
import dev.dshn.core.ui.requestIgnoreBatteryOptimizations
import dev.dshn.core.ui.tierLabel
import dev.dshn.core.ui.tr

private enum class SetupStep { WELCOME, ENVIRONMENT, API_KEY, PERMISSION, BACKGROUND, DONE }

@Composable
fun SetupScreen(
    vm: SetupViewModel,
    reopened: Boolean,
    onCompleted: () -> Unit,
    onCancelReopened: () -> Unit
) {
    val steps = SetupStep.entries
    val initialStep = remember(reopened) { vm.initialWizardStep(reopened, steps.size) }
    var stepIndex by rememberSaveable(reopened) { mutableIntStateOf(initialStep) }
    val step = steps[stepIndex]
    val hasApiKey by vm.hasApiKey.collectAsStateWithLifecycle()
    var apiDraft by rememberSaveable { mutableStateOf("") }
    var savingKey by remember { mutableStateOf(false) }

    fun next() {
        if (stepIndex < steps.lastIndex) stepIndex++
    }

    fun finishSetup() {
        vm.completeSetup()
        onCompleted()
    }

    LaunchedEffect(reopened) {
        if (reopened) vm.onReopenedEntered()
    }

    BackHandler(enabled = stepIndex > 0 || reopened) {
        if (stepIndex > 0) stepIndex-- else onCancelReopened()
    }

    LaunchedEffect(stepIndex, reopened) {
        vm.recordSetupWizardStep(stepIndex, reopened)
    }

    LaunchedEffect(step) {
        when (step) {
            SetupStep.ENVIRONMENT -> vm.refreshEnvironment()
            SetupStep.API_KEY -> vm.refreshApiKeyMask()
            else -> {}
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(112.dp)) {
                    if ((stepIndex > 0 || reopened) && step != SetupStep.DONE) {
                        IconButton(
                            onClick = { if (stepIndex > 0) stepIndex-- else onCancelReopened() },
                            modifier = Modifier.testTag("setup_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("上一步", "Back"))
                        }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    StepDots(current = stepIndex, total = steps.size)
                }
                Box(Modifier.width(112.dp), contentAlignment = Alignment.CenterEnd) {
                    if (step != SetupStep.DONE) {
                        if (reopened) {
                            TextButton(
                                onClick = onCancelReopened,
                                modifier = Modifier.testTag("setup_close_button")
                            ) { Text(tr("关闭", "Close")) }
                        } else {
                            TextButton(
                                onClick = { finishSetup() },
                                modifier = Modifier.testTag("setup_skip_button")
                            ) { Text(tr("跳过设置", "Skip setup")) }
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = stepIndex,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "setupStep"
            ) { idx ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    when (steps[idx]) {
                        SetupStep.WELCOME -> WelcomeStep()
                        SetupStep.ENVIRONMENT -> EnvironmentStep(vm)
                        SetupStep.API_KEY -> ApiKeyStep(vm, apiDraft) { apiDraft = it }
                        SetupStep.PERMISSION -> PermissionStep(vm)
                        SetupStep.BACKGROUND -> BackgroundStep()
                        SetupStep.DONE -> DoneStep(vm)
                    }
                }
            }

            // Bottom action area
            val envManifest by vm.envManifest.collectAsStateWithLifecycle()
            val envInstall by vm.envInstallProgress.collectAsStateWithLifecycle()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (step) {
                    SetupStep.WELCOME -> PrimaryButton(tr("开始设置", "Get started"), tag = "setup_primary_button") { next() }
                    SetupStep.ENVIRONMENT -> {
                        when {
                            envManifest.rootfsReady -> PrimaryButton(tr("下一步", "Next"), tag = "setup_primary_button") { next() }
                            envInstall.inProgress -> PrimaryButton(tr("后台安装中，继续下一步", "Continue while installing"), tag = "setup_primary_button") { next() }
                            else -> PrimaryButton(tr("先跳过这一步", "Skip for now"), tag = "setup_primary_button") { next() }
                        }
                    }
                    SetupStep.API_KEY -> {
                        val draft = apiDraft.trim()
                        if (draft.isNotEmpty()) {
                            PrimaryButton(
                                text = if (savingKey) tr("正在保存…", "Saving…") else tr("保存并继续", "Save and continue"),
                                enabled = !savingKey,
                                tag = "setup_primary_button"
                            ) {
                                savingKey = true
                                vm.saveApiKey(draft) { ok ->
                                    savingKey = false
                                    if (ok) {
                                        apiDraft = ""
                                        next()
                                    }
                                }
                            }
                        } else if (hasApiKey) {
                            PrimaryButton(tr("下一步", "Next"), tag = "setup_primary_button") { next() }
                        } else {
                            PrimaryButton(tr("保存并继续", "Save and continue"), enabled = false, tag = "setup_primary_button") {}
                            TextButton(
                                onClick = { next() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("setup_api_key_later_button")
                            ) {
                                Text(tr("稍后再设置", "Set it later"))
                            }
                        }
                    }
                    SetupStep.PERMISSION, SetupStep.BACKGROUND ->
                        PrimaryButton(tr("下一步", "Next"), tag = "setup_primary_button") { next() }
                    SetupStep.DONE ->
                        PrimaryButton(tr("开始使用", "Start using DSHN"), tag = "setup_complete_button") { finishSetup() }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    tag: String = "setup_primary_button",
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag(tag)
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until total) {
            val w by animateDpAsState(if (i == current) 22.dp else 7.dp, label = "dot$i")
            Box(
                Modifier
                    .height(7.dp)
                    .width(w)
                    .clip(CircleShape)
                    .background(
                        if (i <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep() {
    Spacer(Modifier.height(28.dp))
    BrandMark(size = 84.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(22.dp))
    Text(
        tr("欢迎使用 DSHN", "Welcome to DSHN"),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Text(
        tr("把 DeepSeek Harness 编码智能体装进手机", "The DeepSeek Harness coding agent, native on your phone"),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(32.dp))
    FeatureRow(
        Icons.Rounded.Terminal,
        tr("本地 Ubuntu 容器", "Local Ubuntu container"),
        tr("免 Root、免 Termux，命令直接在手机上执行", "No root or Termux needed; commands run right on the phone")
    )
    FeatureRow(
        Icons.Rounded.Forum,
        tr("原生对话体验", "Native chat experience"),
        tr("流式回复、工具卡片，关键操作逐次由你确认", "Streaming replies, tool cards and per-step approvals")
    )
    FeatureRow(
        Icons.Rounded.Shield,
        tr("安全默认", "Safe by default"),
        tr("设备能力默认全部关闭，API Key 加密保存", "Device access is off by default; your API key is encrypted")
    )
}

@Composable
private fun EnvironmentStep(vm: SetupViewModel) {
    val manifest by vm.envManifest.collectAsStateWithLifecycle()
    val bundled by vm.bundledEnvManifest.collectAsStateWithLifecycle()
    val install by vm.envInstallProgress.collectAsStateWithLifecycle()
    val checking by vm.envChecking.collectAsStateWithLifecycle()
    var showGuide by rememberSaveable { mutableStateOf(false) }

    StepHeader(
        tr("准备运行环境", "Prepare the runtime"),
        if (bundled != null) {
            tr(
                "安装包已内置 Ubuntu ${bundled!!.ubuntuVersion}、Node.js ${bundled!!.nodeVersion} 与 DSH ${bundled!!.dshVersion}，首次启动会自动解压，无需电脑。",
                "This APK includes Ubuntu ${bundled!!.ubuntuVersion}, Node.js ${bundled!!.nodeVersion} and DSH ${bundled!!.dshVersion}. It unpacks automatically on-device — no PC needed."
            )
        } else {
            tr("DSHN 在手机里用一个 Ubuntu 容器运行 DSH，这里确认它已准备好。", "DSHN runs DSH inside an Ubuntu container on your phone. Let's make sure it's ready.")
        }
    )

    if (install.inProgress) {
        BundledEnvProgressCard(
            stageIndex = install.stageIndex,
            stageCount = install.stageCount,
            stageLabelZh = install.stageLabelZh,
            stageLabelEn = install.stageLabelEn,
            bytesDone = install.bytesDone,
            totalBytes = install.totalBytes,
            fraction = install.fraction
        )
        Spacer(Modifier.height(14.dp))
    }

    val checks = buildEnvironmentChecks(
        rootfsReady = manifest.rootfsReady,
        nodeVersion = manifest.nodeVersion,
        nodeCompatible = EnvManager.isNodeVersionCompatible(manifest.nodeVersion),
        dshVersion = manifest.dshVersion,
        dshAtTarget = EnvManager.isDshVersionAtTarget(manifest.dshVersion),
        targetDshVersion = EnvManager.TARGET_DSH_VERSION,
        assetBundleHash = manifest.assetBundleHash
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LocalDshnColors.current.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            checks.forEachIndexed { i, c ->
                if (i > 0) SettingsDivider(indent = 52.dp)
                CheckRow(c, checking || install.inProgress)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (!manifest.rootfsReady && bundled != null && !install.inProgress) {
        Button(
            onClick = { vm.installBundledEnvironment(reinstall = false) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("setup_install_bundled_button")
        ) {
            Text(
                if (install.error != null) tr("重试安装内置环境", "Retry bundled runtime install")
                else tr("安装内置运行环境", "Install bundled runtime")
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    OutlinedButton(
        onClick = { vm.refreshEnvironment() },
        enabled = !checking && !install.inProgress,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_recheck_env_button")
    ) {
        Text(if (checking) tr("正在检测…", "Checking…") else tr("重新检测", "Check again"))
    }

    if (!install.error.isNullOrBlank() && !install.inProgress) {
        Spacer(Modifier.height(14.dp))
        InfoBanner(
            title = tr("内置环境安装未完成", "Bundled install failed"),
            text = install.error.orEmpty(),
            tone = PillTone.ERROR
        )
    }

    if (!manifest.rootfsReady && !checking && !install.inProgress && bundled == null) {
        Spacer(Modifier.height(16.dp))
        InfoBanner(
            title = tr("还没有检测到运行环境", "Runtime environment not found"),
            text = tr(
                "当前安装包未内置运行环境，请用电脑推送 Ubuntu 运行环境（只需一次）。你也可以先跳过，之后在「设置 › 运行环境」里查看步骤。",
                "This build does not bundle a runtime. Push the Ubuntu environment with a computer once, or skip and check Settings › Runtime later."
            ),
            tone = PillTone.WARNING
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showGuide = !showGuide }) {
            Text(if (showGuide) tr("收起准备步骤", "Hide steps") else tr("查看准备步骤", "Show steps"))
            Icon(if (showGuide) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(visible = showGuide) {
            RootfsPrepGuide(
                targetDshVersion = EnvManager.TARGET_DSH_VERSION,
                requiredAllowScripts = EnvManager.REQUIRED_ALLOW_SCRIPTS,
                onCopy = { vm.copyToClipboard("commands", it) }
            )
        }
    }
}

@Composable
private fun ApiKeyStep(vm: SetupViewModel, draft: String, onDraft: (String) -> Unit) {
    val ctx = LocalContext.current
    val masked by vm.apiKeyMasked.collectAsStateWithLifecycle()
    val hasApiKey by vm.hasApiKey.collectAsStateWithLifecycle()

    StepHeader(
        tr("连接 DeepSeek", "Connect DeepSeek"),
        tr("输入你在 DeepSeek 开放平台创建的 API Key。", "Enter an API key created on the DeepSeek platform.")
    )

    if (hasApiKey) {
        InfoBanner(
            title = tr("已设置 API Key", "API key is set"),
            text = (masked ?: "sk-••••••••") + tr("。如需更换，在下方输入新的 Key。", ". Enter a new key below to replace it."),
            tone = PillTone.SUCCESS
        )
        Spacer(Modifier.height(16.dp))
    }

    ApiKeyField(draft, onDraft)

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { openUrl(ctx, DEEPSEEK_API_KEYS_URL) }) {
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr("还没有 Key？去 DeepSeek 开放平台创建", "No key yet? Create one on the DeepSeek platform"))
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(8.dp))
        Text(
            tr(
                "Key 用 AndroidKeyStore 加密保存在本机。为了让 DSH 使用，容器内也会保留一份副本（仅本应用可读）。建议为手机单独创建一个低额度的 Key。",
                "The key is encrypted with AndroidKeyStore. A copy is kept inside the container (readable only by this app) so DSH can use it. Consider a dedicated low-limit key for your phone."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionStep(vm: SetupViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    StepHeader(
        tr("DSH 可以做多少事？", "How much can DSH do?"),
        tr("选择智能体在容器里的执行权限，之后随时可以在设置里修改。", "Pick what the agent may do inside the container. You can change this later.")
    )
    PermissionTierCards(selected = settings.permissionMode, onSelect = { vm.setPermissionTier(it) })
}

@Composable
private fun BackgroundStep() {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val notifOk = remember(tick) { hasNotificationPermission(ctx) }
    val batteryOk = remember(tick) { isIgnoringBatteryOptimizations(ctx) }
    var notifAsked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifAsked = true }

    StepHeader(
        tr("保持后台运行", "Keep running in background"),
        tr("长任务需要在息屏或切到后台时继续。下面两项建议开启。", "Long tasks should keep going when the screen is off. These two are recommended.")
    )
    SettingsGroup(title = null) {
        SettingsRow(
            title = tr("通知权限", "Notifications"),
            subtitle = tr("显示运行状态，并在需要你确认时提醒你", "Shows runtime status and alerts you when approval is needed"),
            icon = Icons.Rounded.Notifications,
            showChevron = false,
            trailing = {
                if (notifOk) StatusPill(tr("已允许", "Allowed"), PillTone.SUCCESS)
                else FilledTonalButton(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifAsked) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings(ctx)
                    }
                }) { Text(tr("允许", "Allow")) }
            }
        )
        SettingsDivider()
        SettingsRow(
            title = tr("电池不受限制", "Unrestricted battery"),
            subtitle = tr("避免系统在后台把运行环境杀掉", "Prevents the system from killing the runtime"),
            icon = Icons.Rounded.BatteryChargingFull,
            showChevron = false,
            trailing = {
                if (batteryOk) StatusPill(tr("已设置", "Done"), PillTone.SUCCESS)
                else FilledTonalButton(onClick = { requestIgnoreBatteryOptimizations(ctx) }) { Text(tr("设置", "Set")) }
            }
        )
    }
    Spacer(Modifier.height(14.dp))
    InfoBanner(
        text = tr(
            "vivo、OPPO、小米等系统可能还需要在系统设置中允许「自启动」和「后台高耗电」。",
            "Some OEM systems also require allowing auto-start and background activity in system settings."
        ),
        tone = PillTone.NEUTRAL
    )
}

@Composable
private fun DoneStep(vm: SetupViewModel) {
    val hasApiKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val manifest by vm.envManifest.collectAsStateWithLifecycle()
    val install by vm.envInstallProgress.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val semantic = LocalDshnColors.current
    Spacer(Modifier.height(36.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = semantic.successContainer, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = semantic.success, modifier = Modifier.size(48.dp))
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    val allReady = hasApiKey && manifest.rootfsReady
    Text(
        if (allReady) tr("一切就绪", "You're all set") else tr("基本设置完成", "Basic setup done"),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Text(
        when {
            allReady -> tr("运行环境会在后台启动，稍等片刻就可以开始对话。", "The runtime will start in the background; you can chat in a moment.")
            install.inProgress -> tr("内置运行环境仍在后台解压，完成后会自动就绪。", "The bundled runtime is still unpacking in the background and will be ready shortly.")
            else -> tr("还有项目没完成，可以稍后在设置里补上。", "Some items are still missing; you can finish them later in Settings.")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(24.dp))
    SettingsGroup(title = null) {
        SummaryRow(tr("API Key", "API key"), if (hasApiKey) tr("已设置", "Set") else tr("未设置", "Missing"), hasApiKey)
        SettingsDivider(indent = 16.dp)
        SummaryRow(
            tr("运行环境", "Runtime"),
            when {
                manifest.rootfsReady -> tr("已就绪", "Ready")
                install.inProgress -> tr("正在安装 ${(install.fraction * 100).toInt()}%", "Installing ${(install.fraction * 100).toInt()}%")
                else -> tr("未就绪", "Not ready")
            },
            manifest.rootfsReady
        )
        SettingsDivider(indent = 16.dp)
        SummaryRow(tr("执行权限", "Permission"), tierLabel(settings.permissionMode), true)
    }
}

@Composable
private fun SummaryRow(title: String, value: String, ok: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        StatusPill(value, if (ok) PillTone.SUCCESS else PillTone.WARNING, showDot = true)
    }
}
