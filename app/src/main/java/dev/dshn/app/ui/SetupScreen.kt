package dev.dshn.app.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dshn.app.runtime.EnvInstallProgress
import dev.dshn.app.runtime.EnvManager

private enum class SetupStep { WELCOME, ENVIRONMENT, API_KEY, PERMISSION, BACKGROUND, DONE }

@Composable
fun SetupScreen(vm: MainViewModel) {
    val steps = SetupStep.entries
    val setupReopened by vm.setupReopened.collectAsState()
    val initialStep = remember(setupReopened) {
        if (setupReopened) 0
        else vm.settings.value.setupWizardStep.coerceIn(0, steps.lastIndex)
    }
    var stepIndex by rememberSaveable(setupReopened) { mutableIntStateOf(initialStep) }
    val step = steps[stepIndex]
    val hasApiKey by vm.hasApiKey.collectAsState()
    var apiDraft by rememberSaveable { mutableStateOf("") }
    var savingKey by remember { mutableStateOf(false) }

    fun next() {
        if (stepIndex < steps.lastIndex) stepIndex++
    }

    BackHandler(enabled = stepIndex > 0 || setupReopened) {
        if (stepIndex > 0) stepIndex-- else vm.cancelReopenedSetup()
    }

    LaunchedEffect(stepIndex) {
        vm.recordSetupWizardStep(stepIndex)
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
                    if ((stepIndex > 0 || setupReopened) && step != SetupStep.DONE) {
                        IconButton(onClick = { if (stepIndex > 0) stepIndex-- else vm.cancelReopenedSetup() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("上一步", "Back"))
                        }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    StepDots(current = stepIndex, total = steps.size)
                }
                Box(Modifier.width(112.dp), contentAlignment = Alignment.CenterEnd) {
                    if (step != SetupStep.DONE) {
                        if (setupReopened) {
                            TextButton(onClick = { vm.cancelReopenedSetup() }) { Text(tr("关闭", "Close")) }
                        } else {
                            TextButton(onClick = { vm.completeSetup() }) { Text(tr("跳过设置", "Skip setup")) }
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
            val envManifest by vm.envManifest.collectAsState()
            val envInstall by vm.envInstallProgress.collectAsState()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (step) {
                    SetupStep.WELCOME -> PrimaryButton(tr("开始设置", "Get started")) { next() }
                    SetupStep.ENVIRONMENT -> {
                        when {
                            envManifest.rootfsReady -> PrimaryButton(tr("下一步", "Next")) { next() }
                            envInstall.inProgress -> PrimaryButton(tr("后台安装中，继续下一步", "Continue while installing")) { next() }
                            else -> PrimaryButton(tr("先跳过这一步", "Skip for now")) { next() }
                        }
                    }
                    SetupStep.API_KEY -> {
                        val draft = apiDraft.trim()
                        if (draft.isNotEmpty()) {
                            PrimaryButton(
                                text = if (savingKey) tr("正在保存…", "Saving…") else tr("保存并继续", "Save and continue"),
                                enabled = !savingKey
                            ) {
                                savingKey = true
                                vm.saveApiKey(draft, autoStartRuntime = false) { ok ->
                                    savingKey = false
                                    if (ok) {
                                        apiDraft = ""
                                        next()
                                    }
                                }
                            }
                        } else if (hasApiKey) {
                            PrimaryButton(tr("下一步", "Next")) { next() }
                        } else {
                            PrimaryButton(tr("保存并继续", "Save and continue"), enabled = false) {}
                            TextButton(onClick = { next() }, modifier = Modifier.fillMaxWidth()) {
                                Text(tr("稍后再设置", "Set it later"))
                            }
                        }
                    }
                    SetupStep.PERMISSION, SetupStep.BACKGROUND -> PrimaryButton(tr("下一步", "Next")) { next() }
                    SetupStep.DONE -> PrimaryButton(tr("开始使用", "Start using DSHN")) { vm.completeSetup() }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
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
private fun EnvironmentStep(vm: MainViewModel) {
    val manifest by vm.envManifest.collectAsState()
    val bundled by vm.bundledEnvManifest.collectAsState()
    val install by vm.envInstallProgress.collectAsState()
    val checking by vm.envChecking.collectAsState()
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
        BundledEnvProgressCard(install)
        Spacer(Modifier.height(14.dp))
    }

    val checks = environmentChecks(manifest)
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
            modifier = Modifier.fillMaxWidth()
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
        modifier = Modifier.fillMaxWidth()
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
            RootfsPrepGuide(onCopy = { vm.copyToClipboard("commands", it) })
        }
    }
}

@Composable
fun BundledEnvProgressCard(progress: EnvInstallProgress, modifier: Modifier = Modifier) {
    val semantic = LocalDshnColors.current
    val pct = (progress.fraction * 100f).toInt().coerceIn(0, 100)
    val doneMb = progress.bytesDone / (1024f * 1024f)
    val totalMb = progress.totalBytes / (1024f * 1024f)
    val stageText = tr(progress.stageLabelZh, progress.stageLabelEn).ifBlank {
        tr("正在解压内置运行环境…", "Unpacking bundled runtime…")
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = semantic.heroBg,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("正在初始化内置环境", "Initializing bundled runtime"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$pct%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (progress.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    buildString {
                        if (progress.stageCount > 0) append("${progress.stageIndex}/${progress.stageCount} · ")
                        append(stageText)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (progress.totalBytes > 1024L * 1024L) {
                    Text(
                        String.format("%.0f / %.0f MB", doneMb, totalMb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun environmentChecks(manifest: dev.dshn.app.runtime.EnvManifest): List<EnvCheckUi> {
    val nodeOk = manifest.nodeVersion.isNotBlank() && EnvManager.isNodeVersionCompatible(manifest.nodeVersion)
    val dshInstalled = manifest.dshVersion.isNotBlank()
    val dshAtTarget = EnvManager.isDshVersionAtTarget(manifest.dshVersion)
    return listOf(
        EnvCheckUi(
            tr("Ubuntu 运行环境", "Ubuntu environment"),
            if (manifest.rootfsReady) tr("已就绪", "Ready") else tr("未找到", "Not found"),
            manifest.rootfsReady
        ),
        EnvCheckUi(
            "Node.js",
            when {
                manifest.nodeVersion.isBlank() -> tr("未检测到", "Not detected")
                nodeOk -> manifest.nodeVersion
                else -> tr("${manifest.nodeVersion}（需要 ≥ 24.9）", "${manifest.nodeVersion} (needs ≥ 24.9)")
            },
            nodeOk
        ),
        EnvCheckUi(
            "DeepSeek Harness",
            when {
                !dshInstalled -> tr("未安装", "Not installed")
                dshAtTarget -> manifest.dshVersion
                else -> tr("${manifest.dshVersion}（建议更新到 ${EnvManager.TARGET_DSH_VERSION}）", "${manifest.dshVersion} (update to ${EnvManager.TARGET_DSH_VERSION} recommended)")
            },
            dshInstalled,
            warn = dshInstalled && !dshAtTarget
        ),
        EnvCheckUi(
            tr("桥接组件", "Bridge components"),
            if (manifest.assetBundleHash.isNotBlank()) tr("已同步", "Synced") else tr("等待运行环境", "Waiting for runtime"),
            manifest.assetBundleHash.isNotBlank()
        )
    )
}

data class EnvCheckUi(val title: String, val detail: String, val ok: Boolean, val warn: Boolean = false)

@Composable
fun CheckRow(c: EnvCheckUi, checking: Boolean, multilineDetail: Boolean = false) {
    val semantic = LocalDshnColors.current
    val stackDetail = multilineDetail || c.detail.length > 28 || c.detail.contains('\n')
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = if (stackDetail) Alignment.Top else Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .padding(top = if (stackDetail) 2.dp else 0.dp)
                .size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                c.ok && c.warn -> Icon(Icons.Rounded.WarningAmber, null, tint = semantic.warning)
                c.ok -> Icon(Icons.Rounded.CheckCircle, null, tint = semantic.success)
                else -> Icon(Icons.Rounded.Cancel, null, tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.width(14.dp))
        if (stackDetail) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.title, style = MaterialTheme.typography.bodyLarge)
                if (c.detail.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            c.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                c.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Step-by-step instructions for pushing the Ubuntu rootfs with a computer (current bootstrap path). */
@Composable
fun RootfsPrepGuide(onCopy: (String) -> Unit) {
    val pkg = "dev.dshn.app"
    val push = "adb push ubuntu-24.04-server-cloudimg-arm64-root.tar /data/local/tmp/"
    val extract = "adb shell run-as $pkg mkdir -p files/linux/ubuntu\n" +
        "adb shell run-as $pkg tar -xf /data/local/tmp/ubuntu-24.04-server-cloudimg-arm64-root.tar -C files/linux/ubuntu"
    val dsh = "npm install -g --allow-scripts=${EnvManager.REQUIRED_ALLOW_SCRIPTS} @deepseek-ai/dsh@${EnvManager.TARGET_DSH_VERSION}"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GuideStep(1, tr("下载 Ubuntu 24.04 arm64 根文件系统，并核对官方 SHA256。", "Download the Ubuntu 24.04 arm64 root filesystem and verify its official SHA256."))
        GuideStep(2, tr("用数据线连接电脑，开启 USB 调试，推送到手机：", "Connect via USB with debugging on, then push it:"))
        CopyableCodeBox(push, onCopy = { onCopy(push) })
        GuideStep(3, tr("解压到 DSHN 的私有目录：", "Extract into DSHN's private folder:"))
        CopyableCodeBox(extract, onCopy = { onCopy(extract) })
        GuideStep(4, tr("把 Node.js 24 放到容器的 /opt/node24，然后在 DSHN 终端里安装 DSH：", "Place Node.js 24 at /opt/node24 in the container, then install DSH from the DSHN terminal:"))
        CopyableCodeBox(dsh, onCopy = { onCopy(dsh) })
        GuideStep(5, tr("回到这里点「重新检测」。", "Come back and tap \"Check again\"."))
    }
}

@Composable
private fun GuideStep(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(22.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ApiKeyStep(vm: MainViewModel, draft: String, onDraft: (String) -> Unit) {
    val ctx = LocalContext.current
    val masked by vm.apiKeyMasked.collectAsState()
    val hasApiKey by vm.hasApiKey.collectAsState()

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
fun ApiKeyField(value: String, onValueChange: (String) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    val trimmed = value.trim()
    val looksOdd = trimmed.isNotEmpty() && !trimmed.startsWith("sk-")
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("API Key") },
        placeholder = { Text("sk-…") },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        trailingIcon = {
            IconButton(onClick = { reveal = !reveal }) {
                Icon(
                    if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (reveal) tr("隐藏", "Hide") else tr("显示", "Show")
                )
            }
        },
        supportingText = if (looksOdd) {
            { Text(tr("DeepSeek 的 Key 通常以 sk- 开头，请确认是否粘贴正确", "DeepSeek keys usually start with sk-")) }
        } else null,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PermissionStep(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
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
private fun DoneStep(vm: MainViewModel) {
    val hasApiKey by vm.hasApiKey.collectAsState()
    val manifest by vm.envManifest.collectAsState()
    val install by vm.envInstallProgress.collectAsState()
    val settings by vm.settings.collectAsState()
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
