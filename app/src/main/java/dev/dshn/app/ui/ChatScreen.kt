package dev.dshn.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.app.runtime.SessionSummaryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class Suggestion(val icon: ImageVector, val title: String, val subtitle: String, val prompt: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel, modifier: Modifier = Modifier, onComposerHeight: (Dp) -> Unit = {}) {
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val hasApiKey by vm.hasApiKey.collectAsState()
    val runtimeStatus by vm.runtimeStatus.collectAsState()
    val sessionId by vm.activeSessionId.collectAsState()
    val sessionBusy by vm.sessionBusy.collectAsState()
    val sessionAttached by vm.sessionAttached.collectAsState()
    val readOnly by vm.activeSessionReadOnly.collectAsState()
    val configOptions by vm.configOptions.collectAsState()
    val usageUsed by vm.usageUsed.collectAsState()
    val usageSize by vm.usageSize.collectAsState()
    val slashCommands by vm.slashCommands.collectAsState()
    val interruptedBanner by vm.interruptedTurnBanner.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val sessionsAwaitingInput by vm.sessionsAwaitingInput.collectAsState()
    val envManifest by vm.envManifest.collectAsState()
    val envInstall by vm.envInstallProgress.collectAsState()
    val activeCwd by vm.activeCwd.collectAsState()
    val settings by vm.settings.collectAsState()

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
                onNavigate = {
                    closeDrawer()
                    vm.navigate(it)
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
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
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
                        IconButton(onClick = { vm.startNewChat() }, enabled = !sessionBusy) {
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
                    vm = vm,
                    hasApiKey = hasApiKey,
                    envReady = envManifest.rootfsReady,
                    envInstall = envInstall,
                    runtimeState = runtimeStatus.state,
                    runtimeDetail = runtimeStatus.detail,
                    sessionBusy = sessionBusy,
                    readOnly = readOnly,
                    interrupted = interruptedBanner
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
                            vm = vm,
                            sessionId = sessionId,
                            messages = messages,
                            streaming = streaming,
                            canChat = canChat,
                            scrollToBottomNonce = scrollToBottomNonce,
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

// ---------------------------------------------------------------------------------------------
// Top bar title
// ---------------------------------------------------------------------------------------------

@Composable
private fun runtimeStateLabel(state: RuntimeLifecycleState): Pair<String, PillTone> = when (state) {
    RuntimeLifecycleState.HEALTHY -> tr("就绪", "Ready") to PillTone.SUCCESS
    RuntimeLifecycleState.STARTING -> tr("正在启动", "Starting") to PillTone.WARNING
    RuntimeLifecycleState.DEGRADED -> tr("运行不稳定", "Degraded") to PillTone.WARNING
    RuntimeLifecycleState.STOPPING -> tr("正在停止", "Stopping") to PillTone.NEUTRAL
    RuntimeLifecycleState.STOPPED -> tr("未启动", "Stopped") to PillTone.NEUTRAL
    RuntimeLifecycleState.ERROR -> tr("出错", "Error") to PillTone.ERROR
}

private fun optionKind(opt: AcpConfigOption): String {
    val key = "${opt.id} ${opt.category} ${opt.name}".lowercase()
    return when {
        "model" in key -> "model"
        "effort" in key || "reason" in key || "think" in key || "thought" in key -> "effort"
        else -> "other"
    }
}

private fun currentChoiceName(opt: AcpConfigOption): String =
    opt.choices.firstOrNull { it.value == opt.currentValue }?.name ?: opt.currentValue

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

// ---------------------------------------------------------------------------------------------
// Banners
// ---------------------------------------------------------------------------------------------

@Composable
private fun ChatBanners(
    vm: MainViewModel,
    hasApiKey: Boolean,
    envReady: Boolean,
    envInstall: dev.dshn.app.runtime.EnvInstallProgress,
    runtimeState: RuntimeLifecycleState,
    runtimeDetail: String,
    sessionBusy: Boolean,
    readOnly: Boolean,
    interrupted: String?
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            envInstall.inProgress -> BundledEnvProgressCard(envInstall)
            !hasApiKey -> InfoBanner(
                title = tr("还没有设置 API Key", "API key not set"),
                text = tr(
                    "DSH 需要 DeepSeek API Key 才能工作。密钥只加密保存在这台手机上。",
                    "DSH needs a DeepSeek API key. It is stored encrypted on this phone only."
                ),
                tone = PillTone.WARNING,
                actionLabel = tr("去设置", "Set up"),
                onAction = { vm.navigate(AppRoute.SETTINGS_MODEL) }
            )
            !envReady && runtimeState != RuntimeLifecycleState.HEALTHY && runtimeState != RuntimeLifecycleState.DEGRADED -> InfoBanner(
                title = tr("运行环境未就绪", "Runtime not ready"),
                text = envInstall.error ?: tr(
                    "请前往「运行环境」一键安装内置环境。",
                    "Open Runtime settings to install the bundled environment."
                ),
                tone = PillTone.WARNING,
                actionLabel = tr("前往安装", "Open"),
                onAction = { vm.navigate(AppRoute.SETTINGS_ENVIRONMENT) }
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
                onAction = { vm.startRuntimeAndAttach() }
            )
            runtimeState == RuntimeLifecycleState.ERROR -> InfoBanner(
                title = tr("运行环境出错", "Runtime error"),
                text = runtimeDetail.ifBlank { tr("未知错误", "Unknown error") },
                tone = PillTone.ERROR,
                actionLabel = tr("重试", "Retry"),
                onAction = { vm.startRuntimeAndAttach() },
                secondaryLabel = tr("查看日志", "Logs"),
                onSecondary = { vm.navigate(AppRoute.SETTINGS_DIAGNOSTICS) }
            )
            runtimeState == RuntimeLifecycleState.DEGRADED -> InfoBanner(
                text = tr("运行输出出现异常，已降级运行，建议重启运行环境。", "Output stream looks corrupted; running degraded. A restart is recommended."),
                tone = PillTone.WARNING,
                actionLabel = tr("重启", "Restart"),
                onAction = { vm.restartRuntime() }
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
                onAction = { vm.retryLastPrompt() }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------------------------

@Composable
private fun EmptyChatState(cwd: String, enabled: Boolean, onPick: (String) -> Unit) {
    val suggestions = listOf(
        Suggestion(
            Icons.Rounded.Memory,
            tr("看看环境", "Check the system"),
            tr("系统、Node 与 DSH 版本", "OS, Node and DSH versions"),
            tr(
                "检查当前运行环境：系统版本、Node 版本、DSH 版本和可用磁盘空间，用表格总结。",
                "Check the current environment: OS version, Node version, DSH version and free disk space. Summarize in a table."
            )
        ),
        Suggestion(
            Icons.Rounded.Terminal,
            tr("写个脚本", "Write a script"),
            tr("统计目录大小并排序", "Rank folders by size"),
            tr(
                "在当前目录写一个 Python 脚本，统计各子目录大小并按从大到小输出，然后运行它。",
                "Write a Python script in the current folder that ranks sub-folders by size, then run it."
            )
        ),
        Suggestion(
            Icons.Rounded.Folder,
            tr("理一理文件", "Explore files"),
            tr("列出目录并说明用途", "List and explain"),
            tr(
                "列出当前目录的文件结构，并简要说明每个文件和目录的用途。",
                "List the current folder structure and briefly explain what each item is for."
            )
        ),
        Suggestion(
            Icons.Rounded.School,
            tr("学点命令", "Learn commands"),
            tr("10 个常用 Linux 命令", "10 everyday Linux commands"),
            tr(
                "用简单易懂的方式介绍 10 个最常用的 Linux 命令，每个配一个例子。",
                "Explain the 10 most useful Linux commands simply, with one example each."
            )
        )
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BrandMark(size = 60.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            tr("今天想让我做点什么？", "What can I do for you today?"),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("命令会在手机本地的 Ubuntu 容器里执行", "Commands run in the Ubuntu container on this phone"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(4.dp))
            Text(cwd, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(24.dp))
        for (rowItems in suggestions.chunked(2)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (s in rowItems) {
                    SuggestionCard(
                        s = s,
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) { onPick(s.prompt) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val semantic = LocalDshnColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, semantic.cardBorder),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                s.icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                s.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Message list
// ---------------------------------------------------------------------------------------------

@Composable
private fun MessageList(
    vm: MainViewModel,
    sessionId: String?,
    messages: List<UiChatMessage>,
    streaming: Boolean,
    canChat: Boolean,
    scrollToBottomNonce: Int,
    onEdit: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 48)
        }
    }

    // Stop following when the user scrolls up; resume once they come back to the bottom.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = atBottom
        }
    }

    val lastIndex = messages.lastIndex
    LaunchedEffect(sessionId) {
        follow = true
    }
    LaunchedEffect(scrollToBottomNonce) {
        if (scrollToBottomNonce > 0 && lastIndex >= 0) {
            follow = true
            listState.animateScrollToItem(lastIndex, scrollOffset = 100_000)
        }
    }
    LaunchedEffect(messages.size) {
        if (lastIndex >= 0 && follow) {
            listState.animateScrollToItem(lastIndex, scrollOffset = 100_000)
        }
    }
    val last = messages.lastOrNull()
    val contentSignature = (last?.text?.length ?: 0) + (last?.thinking?.length ?: 0) * 3 + (last?.tools?.size ?: 0) * 7919
    val signatureState = rememberUpdatedState(contentSignature)
    val followState = rememberUpdatedState(follow)
    val lastIndexState = rememberUpdatedState(lastIndex)
    // Follow streaming output, at most every ~120 ms instead of on every token.
    LaunchedEffect(listState) {
        snapshotFlow { signatureState.value }.conflate().collect {
            val idx = lastIndexState.value
            if (followState.value && idx >= 0 && !listState.isScrollInProgress) {
                listState.scrollToItem(idx, scrollOffset = 100_000)
            }
            delay(120)
        }
    }

    val lastAssistantId = messages.lastOrNull { it.role == "assistant" }?.id

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                when (msg.role) {
                    "user" -> UserMessage(
                        msg = msg,
                        onCopy = { vm.copyToClipboard("message", msg.text) },
                        onEdit = { onEdit(msg.text) }
                    )
                    "assistant" -> AssistantMessage(
                        msg = msg,
                        isLast = msg.id == lastAssistantId,
                        canRegenerate = !streaming && canChat,
                        onCopy = { vm.copyToClipboard("message", it) },
                        onRegenerate = { vm.retryLastPrompt() }
                    )
                    else -> SystemNotice(msg)
                }
            }
        }

        AnimatedVisibility(
            visible = !atBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    follow = true
                    scope.launch { listState.animateScrollToItem(lastIndex.coerceAtLeast(0), scrollOffset = 100_000) }
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = tr("回到底部", "Scroll to bottom"))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserMessage(msg: UiChatMessage, onCopy: () -> Unit, onEdit: () -> Unit) {
    val semantic = LocalDshnColors.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.fillMaxWidth(0.86f), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = semantic.userBubble,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })
            ) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = semantic.onUserBubble,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(tr("复制", "Copy")) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { menu = false; onCopy() }
                )
                DropdownMenuItem(
                    text = { Text(tr("编辑后重发", "Edit & resend")) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onEdit() }
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    msg: UiChatMessage,
    isLast: Boolean,
    canRegenerate: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit
) {
    val nothingYet = msg.text.isEmpty() && msg.thinking.isEmpty() && msg.tools.isEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (msg.thinking.isNotEmpty()) {
            ThinkingBlock(
                id = msg.id,
                thinking = msg.thinking,
                live = msg.isStreaming && msg.text.isEmpty() && msg.tools.isEmpty()
            )
        }
        if (msg.tools.isNotEmpty()) {
            ToolCallsGroup(msg.tools)
        }
        if (msg.text.isNotEmpty()) {
            val rendered = rememberThrottledText(msg.text, msg.isStreaming)
            SelectionContainer {
                MarkdownText(
                    text = rendered,
                    onCopyCode = { onCopy(it) }
                )
            }
        }
        if (msg.isStreaming) {
            if (nothingYet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypingDots()
                    Spacer(Modifier.width(10.dp))
                    Text(
                        tr("正在思考", "Thinking"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                TypingDots(Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            }
        } else if (msg.text.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MessageActionIcon(Icons.Rounded.ContentCopy, tr("复制", "Copy")) { onCopy(msg.text) }
                if (isLast && canRegenerate) {
                    MessageActionIcon(Icons.Rounded.Refresh, tr("重新生成", "Regenerate")) { onRegenerate() }
                }
            }
        }
    }
}

/** While [active], returns [text] at most every [periodMs] so streaming replies are not re-parsed per token. */
@Composable
private fun rememberThrottledText(text: String, active: Boolean, periodMs: Long = 120): String {
    val latest = rememberUpdatedState(text)
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(active) {
        if (active) {
            snapshotFlow { latest.value }.conflate().collect {
                shown = it
                delay(periodMs)
            }
        }
    }
    return if (active) shown else text
}

@Composable
private fun MessageActionIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThinkingBlock(id: String, thinking: String, live: Boolean) {
    val semantic = LocalDshnColors.current
    var userToggled by remember(id) { mutableStateOf<Boolean?>(null) }
    val expanded = userToggled ?: live
    Column {
        Surface(
            onClick = { userToggled = !expanded },
            shape = RoundedCornerShape(10.dp),
            color = semantic.subtleBg
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (live) tr("思考中…", "Thinking…") else tr("已深度思考 · ${thinking.length} 字", "Thought · ${thinking.length} chars"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Row(
                Modifier
                    .padding(top = 8.dp, start = 4.dp)
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(semantic.thinkingBar, RoundedCornerShape(1.dp))
                )
                Spacer(Modifier.width(10.dp))
                SelectionContainer {
                    Text(
                        thinking,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallsGroup(tools: List<UiToolCall>) {
    val semantic = LocalDshnColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, semantic.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            tools.forEachIndexed { idx, tool ->
                if (idx > 0) HorizontalDivider(color = semantic.cardBorder)
                ToolCallRow(tool)
            }
        }
    }
}

private enum class ToolState { RUNNING, DONE, FAILED, UNKNOWN }

private fun toolState(tool: UiToolCall): ToolState = when {
    tool.outcomeUnknown -> ToolState.UNKNOWN
    tool.isError || tool.status.equals("failed", true) || tool.status.equals("error", true) -> ToolState.FAILED
    tool.status.equals("completed", true) -> ToolState.DONE
    else -> ToolState.RUNNING
}

@Composable
private fun ToolCallRow(tool: UiToolCall) {
    val semantic = LocalDshnColors.current
    var expanded by remember(tool.toolCallId) { mutableStateOf(false) }
    val state = toolState(tool)
    val (label, tone) = when (state) {
        ToolState.RUNNING -> tr("执行中", "Running") to PillTone.INFO
        ToolState.DONE -> tr("完成", "Done") to PillTone.SUCCESS
        ToolState.FAILED -> tr("失败", "Failed") to PillTone.ERROR
        ToolState.UNKNOWN -> tr("结果未知", "Unknown") to PillTone.WARNING
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    ToolState.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp)
                    ToolState.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = semantic.success, modifier = Modifier.size(18.dp))
                    ToolState.FAILED -> Icon(Icons.Rounded.Cancel, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    ToolState.UNKNOWN -> Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, tint = semantic.warning, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                tool.title.ifEmpty { tool.toolCallId },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = toneAccent(tone))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (tool.inputSummary.isNotEmpty()) ToolIoBox(tr("输入", "Input"), tool.inputSummary)
                if (tool.outputSummary.isNotEmpty()) ToolIoBox(tr("输出", "Output"), tool.outputSummary)
                if (tool.inputSummary.isEmpty() && tool.outputSummary.isEmpty()) {
                    Text(tr("暂无详细信息", "No details yet"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ToolIoBox(label: String, text: String) {
    val semantic = LocalDshnColors.current
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = RoundedCornerShape(10.dp), color = semantic.codeBg, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SystemNotice(msg: UiChatMessage) {
    val semantic = LocalDshnColors.current
    val error = msg.isError || msg.isUnknownOutcomeBanner
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = if (error) MaterialTheme.colorScheme.errorContainer else semantic.subtleBg,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(15.dp),
                    tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                SelectionContainer {
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------------------------

@Composable
private fun SlashSuggestions(input: String, commands: List<dev.dshn.app.runtime.SlashCommandDescriptor>, onPick: (String) -> Unit) {
    if (!input.startsWith("/") || input.contains(' ') || commands.isEmpty()) return
    val query = input.removePrefix("/").lowercase()
    val matches = commands.filter { it.name.lowercase().contains(query) }.take(6)
    if (matches.isEmpty()) return
    val semantic = LocalDshnColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, semantic.cardBorder),
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            for (cmd in matches) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(cmd.name) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "/${cmd.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (cmd.description.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    onHeight: (Dp) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    streaming: Boolean,
    readOnly: Boolean,
    configOptions: List<AcpConfigOption>,
    usageUsed: Long,
    usageSize: Long,
    onOpenOption: (AcpConfigOption) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val semantic = LocalDshnColors.current
    val density = LocalDensity.current
    Surface(
        modifier = Modifier
            .onSizeChanged { with(density) { onHeight(it.height.toDp() + 16.dp) } }
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        color = semantic.composerBg,
        border = BorderStroke(1.dp, semantic.cardBorder),
        shadowElevation = if (semantic.isDark) 0.dp else 3.dp
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp, bottom = 6.dp, end = 8.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                if (readOnly) tr("只读对话，不能发送", "Read-only chat") else tr("给 DSH 发消息，输入 / 使用命令", "Message DSH, or type / for commands"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!readOnly) {
                        for (opt in configOptions.filter { it.choices.isNotEmpty() }) {
                            ConfigChip(opt) { onOpenOption(opt) }
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (!readOnly && usageSize > 0) {
                    ContextUsage(usageUsed, usageSize)
                    Spacer(Modifier.width(6.dp))
                }
                val sendEnabled = streaming || canSend
                Surface(
                    onClick = { if (streaming) onStop() else if (canSend) onSend() },
                    enabled = sendEnabled,
                    shape = CircleShape,
                    color = if (sendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                            contentDescription = if (streaming) tr("停止", "Stop") else tr("发送", "Send"),
                            tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigChip(opt: AcpConfigOption, onClick: () -> Unit) {
    val semantic = LocalDshnColors.current
    val kind = optionKind(opt)
    val choice = currentChoiceName(opt)
    val label = when (kind) {
        "model" -> choice
        "effort" -> tr("思考·$choice", "Effort·$choice")
        else -> "${opt.name}·$choice"
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(999.dp), color = semantic.subtleBg) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (kind) {
                    "model" -> Icons.Rounded.Memory
                    "effort" -> Icons.Rounded.Lightbulb
                    else -> Icons.Rounded.Tune
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContextUsage(used: Long, size: Long) {
    val fraction = (used.toFloat() / size.toFloat()).coerceIn(0f, 1f)
    val color = when {
        fraction > 0.85f -> MaterialTheme.colorScheme.error
        fraction > 0.6f -> LocalDshnColors.current.warning
        else -> MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.5.dp,
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigOptionSheet(option: AcpConfigOption, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Text(
                when (optionKind(option)) {
                    "model" -> tr("选择模型", "Choose model")
                    "effort" -> tr("思考强度", "Reasoning effort")
                    else -> option.name
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            var lastGroup: String? = null
            for (choice in option.choices) {
                if (choice.group.isNotBlank() && choice.group != lastGroup) {
                    lastGroup = choice.group
                    Text(
                        choice.group,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                val selected = choice.value == option.currentValue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(choice.value) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            choice.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (choice.description.isNotBlank()) {
                            Text(choice.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (selected) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatInDirDialog(
    initial: String,
    hasWorkspaceBind: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var dir by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
        title = { Text(tr("在指定目录新建对话", "New chat in folder")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("DSH 会把这个容器内目录作为工作目录。", "DSH will use this container folder as its working directory."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = dir,
                    onValueChange = { dir = it },
                    singleLine = true,
                    label = { Text(tr("容器内路径", "Container path")) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = buildList {
                        add("/root")
                        if (hasWorkspaceBind) add("/mnt/workspace")
                        add("/tmp")
                    }
                    for (p in presets) {
                        AssistChip(onClick = { dir = p }, label = { Text(p, fontFamily = FontFamily.Monospace) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(dir.trim().ifEmpty { "/root" }) }) { Text(tr("新建", "Create")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } }
    )
}

// ---------------------------------------------------------------------------------------------
// Session drawer
// ---------------------------------------------------------------------------------------------

@Composable
private fun SessionDrawer(
    vm: MainViewModel,
    activeId: String?,
    activeTitle: String,
    sessionBusy: Boolean,
    sessionsAwaitingInput: Set<String>,
    onNewChat: () -> Unit,
    onNewChatInDir: () -> Unit,
    onOpenSession: (SessionSummaryItem) -> Unit,
    onNavigate: (AppRoute) -> Unit
) {
    val sessions by vm.sessions.collectAsState()
    val loading by vm.sessionsLoading.collectAsState()
    val error by vm.sessionsError.collectAsState()
    val runtimeStatus by vm.runtimeStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    val envManifest by vm.envManifest.collectAsState()
    val semantic = LocalDshnColors.current
    var query by remember { mutableStateOf("") }

    val filtered = remember(sessions, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) sessions
        else sessions.filter {
            (it.title ?: "").lowercase().contains(q) || it.id.lowercase().contains(q) || (it.cwd ?: "").lowercase().contains(q)
        }
    }
    val groups = groupSessionsByDay(filtered)

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(312.dp)
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark(size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("DSHN", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "DeepSeek Harness Native",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onNewChat,
                    enabled = !sessionBusy,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.AddComment, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("新对话", "New chat"))
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onNewChatInDir, enabled = !sessionBusy) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = tr("在指定目录新建", "New chat in folder"))
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                shape = RoundedCornerShape(14.dp),
                color = semantic.subtleBg
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(tr("搜索对话", "Search chats"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                }
                                inner()
                            }
                        }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                if (loading && sessions.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (sessions.isEmpty() && !loading) {
                    item {
                        Text(
                            when {
                                !error.isNullOrBlank() -> tr("对话列表暂时不可用", "Chat list unavailable right now")
                                runtimeStatus.state != RuntimeLifecycleState.HEALTHY && runtimeStatus.state != RuntimeLifecycleState.DEGRADED ->
                                    tr("运行环境启动后，这里会显示你的对话", "Your chats appear here once the runtime is running")
                                else -> tr("还没有对话", "No chats yet")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                        )
                    }
                }
                for ((label, items) in groups) {
                    item(key = "hdr-$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)
                        )
                    }
                    itemsIndexed(items, key = { _, s -> s.id }) { _, item ->
                        SessionRow(
                            item = item,
                            isActive = item.id == activeId,
                            activeTitle = activeTitle,
                            enabled = !sessionBusy,
                            awaitingInput = item.id in sessionsAwaitingInput,
                            onOpen = { onOpenSession(item) },
                            onCopyId = { vm.copyToClipboard("session id", item.id) },
                            onClose = { vm.closeSession(item.id) }
                        )
                    }
                }
            }

            HorizontalDivider(color = semantic.cardBorder)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val itemColors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                NavigationDrawerItem(
                    label = { Text(tr("终端", "Terminal")) },
                    icon = { Icon(Icons.Rounded.Terminal, null) },
                    selected = false,
                    onClick = { onNavigate(AppRoute.TERMINAL) },
                    colors = itemColors,
                    modifier = Modifier.height(48.dp)
                )
                NavigationDrawerItem(
                    label = { Text(tr("设备桥与 Root", "Device bridge & Root")) },
                    icon = { Icon(Icons.Rounded.DeveloperBoard, null) },
                    badge = {
                        if (settings.bridgeEnabled) StatusPill(tr("已开启", "On"), PillTone.SUCCESS)
                    },
                    selected = false,
                    onClick = { onNavigate(AppRoute.DEVICE_BRIDGE) },
                    colors = itemColors,
                    modifier = Modifier.height(48.dp)
                )
                NavigationDrawerItem(
                    label = { Text(tr("设置", "Settings")) },
                    icon = { Icon(Icons.Rounded.Settings, null) },
                    selected = false,
                    onClick = { onNavigate(AppRoute.SETTINGS) },
                    colors = itemColors,
                    modifier = Modifier.height(48.dp)
                )
                val (stateText, tone) = runtimeStateLabel(runtimeStatus.state)
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(tone, size = 7.dp, pulsing = runtimeStatus.state == RuntimeLifecycleState.STARTING)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildString {
                            append(tr("运行环境：", "Runtime: "))
                            append(stateText)
                            if (envManifest.dshVersion.isNotBlank()) append(" · DSH ${envManifest.dshVersion}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun groupSessionsByDay(items: List<SessionSummaryItem>): List<Pair<String, List<SessionSummaryItem>>> {
    val today = tr("今天", "Today")
    val yesterday = tr("昨天", "Yesterday")
    val week = tr("7 天内", "Previous 7 days")
    val earlier = tr("更早", "Earlier")
    return remember(items, today) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startToday = cal.timeInMillis
        val startYesterday = startToday - 86_400_000L
        val startWeek = startToday - 6 * 86_400_000L
        val buckets = linkedMapOf<String, MutableList<SessionSummaryItem>>(
            today to mutableListOf(), yesterday to mutableListOf(), week to mutableListOf(), earlier to mutableListOf()
        )
        for (s in items) {
            val key = when {
                s.createdAt >= startToday -> today
                s.createdAt >= startYesterday -> yesterday
                s.createdAt >= startWeek -> week
                else -> earlier
            }
            buckets.getValue(key).add(s)
        }
        buckets.filterValues { it.isNotEmpty() }.map { it.key to it.value.toList() }
    }
}

@Composable
private fun SessionRow(
    item: SessionSummaryItem,
    isActive: Boolean,
    activeTitle: String,
    enabled: Boolean,
    awaitingInput: Boolean,
    onOpen: () -> Unit,
    onCopyId: () -> Unit,
    onClose: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var menu by remember { mutableStateOf(false) }
    val title = item.title?.takeIf { it.isNotBlank() }
        ?: if (isActive) activeTitle else null
        ?: if (item.createdAt > 0) tr("对话 ", "Chat ") + timeFmt.format(Date(item.createdAt)) else tr("未命名对话", "Untitled chat")
    Surface(
        onClick = onOpen,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(item.cwd ?: "/root")
                        if (item.createdAt > 0) append(" · ").append(timeFmt.format(Date(item.createdAt)))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.error.isNullOrBlank()) {
                    Text(
                        tr("读取警告：", "Read warning: ") + item.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.corrupt || item.readOnly) MaterialTheme.colorScheme.error else LocalDshnColors.current.warning,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            when {
                item.corrupt || item.readOnly -> Icon(
                    Icons.Rounded.WarningAmber,
                    contentDescription = tr("只读", "Read-only"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                awaitingInput -> StatusDot(PillTone.WARNING, size = 7.dp, pulsing = true)
                !item.error.isNullOrBlank() -> Icon(
                    Icons.Rounded.WarningAmber,
                    contentDescription = tr("警告", "Warning"),
                    tint = LocalDshnColors.current.warning,
                    modifier = Modifier.size(16.dp)
                )
                item.live -> StatusDot(PillTone.SUCCESS, size = 6.dp)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = tr("更多", "More"), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("复制对话 ID", "Copy session ID")) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; onCopyId() }
                    )
                    if (item.live) {
                        DropdownMenuItem(
                            text = { Text(tr("关闭对话（释放资源）", "Close session (free resources)")) },
                            leadingIcon = { Icon(Icons.Rounded.Cancel, null) },
                            onClick = { menu = false; onClose() }
                        )
                    }
                }
            }
        }
    }
}
