package dev.dshn.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.app.runtime.EnvInstallProgress
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.core.ui.BundledEnvProgressCard
import dev.dshn.core.ui.ConfirmDialog
import dev.dshn.core.ui.InfoBanner
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.StatusDot
import dev.dshn.core.ui.tr
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenTerminal: () -> Unit,
    onOpenDeviceBridge: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenEnvironmentSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    onComposerHeight: (Dp) -> Unit = {}
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val hasApiKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val runtimeStatus by vm.runtimeStatus.collectAsStateWithLifecycle()
    val sessionId by vm.activeSessionId.collectAsStateWithLifecycle()
    val sessionBusy by vm.sessionBusy.collectAsStateWithLifecycle()
    val sessionAttached by vm.sessionAttached.collectAsStateWithLifecycle()
    val readOnly by vm.activeSessionReadOnly.collectAsStateWithLifecycle()
    val configOptions by vm.configOptions.collectAsStateWithLifecycle()
    val usageUsed by vm.usageUsed.collectAsStateWithLifecycle()
    val usageSize by vm.usageSize.collectAsStateWithLifecycle()
    val slashCommands by vm.slashCommands.collectAsStateWithLifecycle()
    val interruptedBanner by vm.interruptedTurnBanner.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val sessionsAwaitingInput by vm.sessionsAwaitingInput.collectAsStateWithLifecycle()
    val envManifest by vm.envManifest.collectAsStateWithLifecycle()
    val envInstall by vm.envInstallProgress.collectAsStateWithLifecycle()
    val activeCwd by vm.activeCwd.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    val draftsBySession = remember { mutableStateMapOf<String, String>() }
    var lastDraftSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetOption by remember { mutableStateOf<AcpConfigOption?>(null) }
    var showDirDialog by remember { mutableStateOf(false) }
    var pendingEditDraft by remember { mutableStateOf<String?>(null) }
    var scrollToBottomNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(sessionId) {
        val prev = lastDraftSessionId
        if (prev != null && prev != sessionId) {
            if (input.isEmpty()) draftsBySession.remove(prev) else draftsBySession[prev] = input
            input = sessionId?.let { draftsBySession[it] }.orEmpty()
        }
        lastDraftSessionId = sessionId
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) vm.refreshSessions()
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val title = sessions.firstOrNull { it.id == sessionId }?.title?.takeIf { it.isNotBlank() }
        ?: messages.firstOrNull { it.role == "user" }?.text?.lineSequence()?.firstOrNull()?.take(40)
        ?: tr("新对话", "New chat")

    val usable = runtimeStatus.state == RuntimeLifecycleState.HEALTHY || runtimeStatus.state == RuntimeLifecycleState.DEGRADED
    val canChat = hasApiKey && usable && sessionId != null && sessionAttached && !readOnly && !sessionBusy

    fun closeDrawer() = scope.launch { drawerState.close() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                vm = vm,
                activeId = sessionId,
                activeTitle = title,
                sessionBusy = sessionBusy,
                sessionsAwaitingInput = sessionsAwaitingInput,
                onNewChat = {
                    closeDrawer()
                    vm.startNewChat()
                },
                onNewChatInDir = {
                    closeDrawer()
                    showDirDialog = true
                },
                onOpenSession = {
                    closeDrawer()
                    vm.switchToSession(it)
                },
                onOpenTerminal = {
                    closeDrawer()
                    onOpenTerminal()
                },
                onOpenDeviceBridge = {
                    closeDrawer()
                    onOpenDeviceBridge()
                },
                onOpenSettings = {
                    closeDrawer()
                    onOpenSettings()
                }
            )
        },
        modifier = modifier
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("chat_drawer_button")
                        ) {
                            Icon(Icons.Rounded.Menu, contentDescription = tr("对话列表", "Chats"))
                        }
                    },
                    title = {
                        ChatTitle(
                            title = title,
                            runtimeState = runtimeStatus.state,
                            streaming = streaming,
                            sessionBusy = sessionBusy,
                            hasApiKey = hasApiKey,
                            configOptions = configOptions
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { vm.startNewChat() },
                            enabled = !sessionBusy,
                            modifier = Modifier.testTag("chat_new_button")
                        ) {
                            Icon(Icons.Rounded.AddComment, contentDescription = tr("新对话", "New chat"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .consumeWindowInsets(inner)
                    .imePadding()
            ) {
                ChatBanners(
                    hasApiKey = hasApiKey,
                    envReady = envManifest.rootfsReady,
                    envInstall = envInstall,
                    runtimeState = runtimeStatus.state,
                    runtimeDetail = runtimeStatus.detail,
                    sessionBusy = sessionBusy,
                    readOnly = readOnly,
                    interrupted = interruptedBanner,
                    onOpenModelSettings = onOpenModelSettings,
                    onOpenEnvironmentSettings = onOpenEnvironmentSettings,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onStartRuntime = { vm.startRuntimeAndAttach() },
                    onRestartRuntime = { vm.restartRuntime() },
                    onRetryLastPrompt = { vm.retryLastPrompt() }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (messages.isEmpty()) {
                        EmptyChatState(
                            cwd = activeCwd,
                            enabled = canChat,
                            onPick = { prompt ->
                                if (canChat) vm.sendPromptOrCommand(prompt) else input = prompt
                            }
                        )
                    } else {
                        MessageList(
                            sessionId = sessionId,
                            messages = messages,
                            streaming = streaming,
                            canChat = canChat,
                            scrollToBottomNonce = scrollToBottomNonce,
                            onCopy = { vm.copyToClipboard("message", it) },
                            onRegenerate = { vm.retryLastPrompt() },
                            onEdit = { text ->
                                if (input.isNotBlank() && input != text) {
                                    pendingEditDraft = text
                                } else {
                                    input = text
                                }
                            }
                        )
                    }
                }

                SlashSuggestions(
                    input = input,
                    commands = slashCommands,
                    onPick = { input = "/$it " }
                )

                Composer(
                    onHeight = onComposerHeight,
                    value = input,
                    onValueChange = { input = it },
                    enabled = !readOnly,
                    canSend = canChat && input.isNotBlank(),
                    streaming = streaming,
                    readOnly = readOnly,
                    configOptions = configOptions,
                    usageUsed = usageUsed,
                    usageSize = usageSize,
                    onOpenOption = { if (!readOnly) sheetOption = it },
                    onSend = {
                        if (vm.sendPromptOrCommand(input)) {
                            input = ""
                            sessionId?.let { draftsBySession.remove(it) }
                            scrollToBottomNonce++
                        }
                    },
                    onStop = { vm.cancelActivePrompt() }
                )
            }
        }
    }

    sheetOption?.let { opt ->
        val live = configOptions.firstOrNull { it.id == opt.id } ?: opt
        ConfigOptionSheet(
            option = live,
            onSelect = { value ->
                vm.setConfigOption(live.id, value)
                sheetOption = null
            },
            onDismiss = { sheetOption = null }
        )
    }

    if (showDirDialog) {
        NewChatInDirDialog(
            initial = activeCwd,
            hasWorkspaceBind = !settings.extraWorkspaceBindHostPath.isNullOrBlank(),
            onConfirm = { dir ->
                showDirDialog = false
                vm.startNewChat(dir)
            },
            onDismiss = { showDirDialog = false }
        )
    }

    pendingEditDraft?.let { replacement ->
        ConfirmDialog(
            title = tr("替换当前草稿？", "Replace current draft?"),
            text = tr(
                "输入框里还有未发送的内容，继续操作会用所选消息替换当前草稿。",
                "Your composer already has unsent text. Continuing will replace it with the selected message."
            ),
            confirmLabel = tr("替换", "Replace"),
            onConfirm = {
                input = replacement
                pendingEditDraft = null
            },
            onDismiss = { pendingEditDraft = null }
        )
    }
}

@Composable
private fun ChatTitle(
    title: String,
    runtimeState: RuntimeLifecycleState,
    streaming: Boolean,
    sessionBusy: Boolean,
    hasApiKey: Boolean,
    configOptions: List<AcpConfigOption>
) {
    val (stateText, tone) = when {
        !hasApiKey -> tr("未设置 API Key", "No API key") to PillTone.WARNING
        streaming -> tr("正在回复…", "Responding…") to PillTone.INFO
        sessionBusy -> tr("正在加载…", "Loading…") to PillTone.INFO
        else -> runtimeStateLabel(runtimeState)
    }
    val model = configOptions.firstOrNull { optionKind(it) == "model" }?.let { currentChoiceName(it) }
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone, size = 7.dp, pulsing = streaming || sessionBusy || runtimeState == RuntimeLifecycleState.STARTING)
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (model.isNullOrBlank()) stateText else "$stateText · $model",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChatBanners(
    hasApiKey: Boolean,
    envReady: Boolean,
    envInstall: EnvInstallProgress,
    runtimeState: RuntimeLifecycleState,
    runtimeDetail: String,
    sessionBusy: Boolean,
    readOnly: Boolean,
    interrupted: String?,
    onOpenModelSettings: () -> Unit,
    onOpenEnvironmentSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onStartRuntime: () -> Unit,
    onRestartRuntime: () -> Unit,
    onRetryLastPrompt: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            envInstall.inProgress -> BundledEnvProgressCard(
                stageIndex = envInstall.stageIndex,
                stageCount = envInstall.stageCount,
                stageLabelZh = envInstall.stageLabelZh,
                stageLabelEn = envInstall.stageLabelEn,
                bytesDone = envInstall.bytesDone,
                totalBytes = envInstall.totalBytes,
                fraction = envInstall.fraction
            )
            !hasApiKey -> InfoBanner(
                title = tr("还没有设置 API Key", "API key not set"),
                text = tr(
                    "DSH 需要 DeepSeek API Key 才能工作。密钥只加密保存在这台手机上。",
                    "DSH needs a DeepSeek API key. It is stored encrypted on this phone only."
                ),
                tone = PillTone.WARNING,
                actionLabel = tr("去设置", "Set up"),
                onAction = onOpenModelSettings
            )
            !envReady && runtimeState != RuntimeLifecycleState.HEALTHY && runtimeState != RuntimeLifecycleState.DEGRADED -> InfoBanner(
                title = tr("运行环境未就绪", "Runtime not ready"),
                text = envInstall.error ?: tr(
                    "请前往「运行环境」一键安装内置环境。",
                    "Open Runtime settings to install the bundled environment."
                ),
                tone = PillTone.WARNING,
                actionLabel = tr("前往安装", "Open"),
                onAction = onOpenEnvironmentSettings
            )
            runtimeState == RuntimeLifecycleState.STARTING ||
                (sessionBusy && runtimeState != RuntimeLifecycleState.HEALTHY && runtimeState != RuntimeLifecycleState.DEGRADED) ->
                InfoBanner(
                    text = tr(
                        "正在启动本地运行环境，首次启动可能需要几十秒…",
                        "Starting the local runtime. The first start can take a little while…"
                    ),
                    tone = PillTone.INFO,
                    progress = true
                )
            runtimeState == RuntimeLifecycleState.STOPPED -> InfoBanner(
                text = tr("运行环境尚未启动", "The runtime is not running"),
                tone = PillTone.NEUTRAL,
                actionLabel = tr("启动", "Start"),
                onAction = onStartRuntime
            )
            runtimeState == RuntimeLifecycleState.ERROR -> InfoBanner(
                title = tr("运行环境出错", "Runtime error"),
                text = runtimeDetail.ifBlank { tr("未知错误", "Unknown error") },
                tone = PillTone.ERROR,
                actionLabel = tr("重试", "Retry"),
                onAction = onStartRuntime,
                secondaryLabel = tr("查看日志", "Logs"),
                onSecondary = onOpenDiagnostics
            )
            runtimeState == RuntimeLifecycleState.DEGRADED -> InfoBanner(
                text = tr("运行输出出现异常，已降级运行，建议重启运行环境。", "Output stream looks corrupted; running degraded. A restart is recommended."),
                tone = PillTone.WARNING,
                actionLabel = tr("重启", "Restart"),
                onAction = onRestartRuntime
            )
        }
        if (readOnly) {
            InfoBanner(
                text = tr("此对话只能查看，不能继续发送消息。磁盘上的原文件不会被改动。", "This chat is read-only. Files on disk are left untouched."),
                tone = PillTone.WARNING
            )
        }
        if (!interrupted.isNullOrBlank()) {
            InfoBanner(
                text = interrupted,
                tone = PillTone.ERROR,
                actionLabel = tr("重试", "Retry"),
                onAction = onRetryLastPrompt
            )
        }
    }
}
