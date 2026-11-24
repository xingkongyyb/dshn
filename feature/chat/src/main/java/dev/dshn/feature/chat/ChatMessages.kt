package dev.dshn.feature.chat

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshn.app.runtime.UiChatMessage
import dev.dshn.app.runtime.UiToolCall
import dev.dshn.core.ui.BrandMark
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.MarkdownText
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.TypingDots
import dev.dshn.core.ui.toneAccent
import dev.dshn.core.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

private data class Suggestion(val icon: ImageVector, val title: String, val subtitle: String, val prompt: String)

@Composable
internal fun EmptyChatState(cwd: String, enabled: Boolean, onPick: (String) -> Unit) {
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

@Composable
internal fun MessageList(
    sessionId: String?,
    messages: List<UiChatMessage>,
    streaming: Boolean,
    canChat: Boolean,
    scrollToBottomNonce: Int,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
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
                        onCopy = { onCopy(msg.text) },
                        onEdit = { onEdit(msg.text) }
                    )
                    "assistant" -> AssistantMessage(
                        msg = msg,
                        isLast = msg.id == lastAssistantId,
                        canRegenerate = !streaming && canChat,
                        onCopy = onCopy,
                        onRegenerate = onRegenerate
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
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = { menu = false; onCopy() }
                )
                DropdownMenuItem(
                    text = { Text(tr("编辑后重发", "Edit & resend")) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
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
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
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
                    ToolState.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = semantic.success, modifier = Modifier.size(18.dp))
                    ToolState.FAILED -> Icon(Icons.Rounded.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    ToolState.UNKNOWN -> Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null, tint = semantic.warning, modifier = Modifier.size(18.dp))
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
