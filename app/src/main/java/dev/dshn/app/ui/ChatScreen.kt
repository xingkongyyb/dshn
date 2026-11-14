package dev.dshn.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.app.runtime.RuntimeUiStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: MainViewModel,
    strings: UiStrings,
    modifier: Modifier = Modifier
) {
    val semantic = LocalDshnColors.current
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val hasApiKey by vm.hasApiKey.collectAsState()
    val runtimeStatus by vm.runtimeStatus.collectAsState()
    val sessionId by vm.activeSessionId.collectAsState()
    val readOnly by vm.activeSessionReadOnly.collectAsState()
    val configOptions by vm.configOptions.collectAsState()
    val usageUsed by vm.usageUsed.collectAsState()
    val usageSize by vm.usageSize.collectAsState()
    val slashCommands by vm.slashCommands.collectAsState()
    val interruptedBanner by vm.interruptedTurnBanner.collectAsState()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        ChatStatusHeader(
            runtimeStatus = runtimeStatus,
            sessionId = sessionId,
            readOnly = readOnly,
            usageUsed = usageUsed,
            usageSize = usageSize,
            configOptions = configOptions,
            strings = strings,
            onToggleTheme = { isDark -> vm.toggleLightDarkTheme(isDark) },
            onSessionBadgeClick = { vm.selectTab(MainTab.SESSIONS) },
            onStartRuntime = { vm.startRuntimeAndAttach() },
            onRestartRuntime = { vm.restartRuntime() },
            onSelectConfig = { cfgId, valStr -> vm.setConfigOption(cfgId, valStr) }
        )

        if (!hasApiKey) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                DshnSectionCard(
                    title = strings.apiKeyMissingTitle,
                    icon = Icons.Default.Warning,
                    highlighted = true
                ) {
                    Text(strings.apiKeyMissingBody, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.selectTab(MainTab.SETTINGS) }) {
                        Text(strings.goToSettings)
                    }
                }
            }
        }

        if (readOnly) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Read-only historical/corrupt preview. Source files on disk remain untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (interruptedBanner != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        interruptedBanner.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { vm.retryLastPrompt() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(strings.retryInterrupted)
                    }
                }
            }
        }

        if (messages.isEmpty() && hasApiKey) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                DshnSectionCard(
                    title = "DeepSeek Harness Native",
                    subtitle = if (sessionId != null) {
                        "Session #${sessionId!!.take(8)} ready on local bxroot Ubuntu container"
                    } else {
                        "Starting container session or create one in Sessions tab"
                    },
                    icon = Icons.Default.AutoAwesome
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("检查当前环境版本与工作区", "列出 /root 目录文件", "测试读取 device_info").forEach { starter ->
                            AssistChip(
                                onClick = { input = starter },
                                label = { Text(starter, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(msg = msg, strings = strings)
                }
            }
        }

        if (input.startsWith("/") && slashCommands.isNotEmpty()) {
            val query = input.removePrefix("/").substringBefore(' ').lowercase()
            val matches = slashCommands.filter { it.name.lowercase().contains(query) }.take(8)
            if (matches.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (cmd in matches) {
                        AssistChip(
                            onClick = { input = "/${cmd.name} " },
                            label = { Text("/${cmd.name}", style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = semantic.cardBorder)
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    enabled = !readOnly,
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 5,
                    placeholder = {
                        Text(
                            if (readOnly) strings.promptPlaceholderReadOnly else strings.promptPlaceholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                Spacer(Modifier.width(8.dp))
                if (streaming) {
                    Surface(
                        onClick = { vm.cancelActivePrompt() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Stop, contentDescription = strings.stop, tint = MaterialTheme.colorScheme.onError)
                        }
                    }
                } else {
                    val canSend = !readOnly && input.isNotBlank() && sessionId != null
                    Surface(
                        onClick = {
                            if (canSend) {
                                val text = input
                                input = ""
                                vm.sendPromptOrCommand(text)
                            }
                        },
                        enabled = canSend,
                        shape = CircleShape,
                        color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = strings.send,
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatStatusHeader(
    runtimeStatus: RuntimeUiStatus,
    sessionId: String?,
    readOnly: Boolean,
    usageUsed: Long,
    usageSize: Long,
    configOptions: List<AcpConfigOption>,
    strings: UiStrings,
    onToggleTheme: (Boolean) -> Unit,
    onSessionBadgeClick: () -> Unit,
    onStartRuntime: () -> Unit,
    onRestartRuntime: () -> Unit,
    onSelectConfig: (String, String) -> Unit
) {
    val semantic = LocalDshnColors.current
    val stateTone = when (runtimeStatus.state) {
        RuntimeLifecycleState.HEALTHY -> PillTone.SUCCESS
        RuntimeLifecycleState.DEGRADED, RuntimeLifecycleState.STARTING -> PillTone.WARNING
        RuntimeLifecycleState.ERROR -> PillTone.ERROR
        RuntimeLifecycleState.STOPPED, RuntimeLifecycleState.STOPPING -> PillTone.NEUTRAL
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatusPill(
                        text = runtimeStatus.state.name,
                        tone = stateTone,
                        showDot = true
                    )

                    Surface(
                        onClick = onSessionBadgeClick,
                        shape = RoundedCornerShape(8.dp),
                        color = semantic.subtleBg,
                        border = BorderStroke(1.dp, semantic.cardBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = buildString {
                                append(sessionId?.let { "#${it.take(8)}" } ?: "No session")
                                if (readOnly) append(" (Read-Only)")
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (usageSize > 0) {
                        StatusPill("${usageUsed / 1000}k/${usageSize / 1000}k", PillTone.NEUTRAL)
                    }

                    ThemeQuickToggleButton(strings = strings, onToggle = onToggleTheme)

                    if (runtimeStatus.state == RuntimeLifecycleState.STOPPED || runtimeStatus.state == RuntimeLifecycleState.ERROR) {
                        IconButton(onClick = onStartRuntime, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start Runtime")
                        }
                    } else {
                        IconButton(onClick = onRestartRuntime, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Restart Runtime")
                        }
                    }
                }

                if (configOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (opt in configOptions) {
                            var expanded by remember(opt.id) { mutableStateOf(false) }
                            val shortOptName = if (opt.name.equals("Reasoning effort", ignoreCase = true)) {
                                "Effort"
                            } else {
                                opt.name
                            }
                            Box {
                                AssistChip(
                                    onClick = { expanded = true },
                                    label = {
                                        val label = opt.choices.firstOrNull { it.value == opt.currentValue }?.name
                                            ?: opt.currentValue.ifEmpty { opt.name }
                                        Text("$shortOptName: $label", style = MaterialTheme.typography.labelSmall)
                                    },
                                    trailingIcon = {
                                        Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                )
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    for (choice in opt.choices) {
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        choice.name,
                                                        fontWeight = if (choice.value == opt.currentValue) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                    if (choice.group.isNotEmpty() || choice.description.isNotEmpty()) {
                                                        Text(
                                                            listOf(choice.group, choice.description).filter { it.isNotEmpty() }.joinToString(" · "),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                expanded = false
                                                onSelectConfig(opt.id, choice.value)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = semantic.cardBorder)
        }
    }
}

@Composable
private fun ChatMessageBubble(msg: UiChatMessage, strings: UiStrings) {
    val semantic = LocalDshnColors.current
    val isUser = msg.role == "user"
    val isSystem = msg.role == "system"

    val bubbleShape = when {
        isUser -> RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
        isSystem -> RoundedCornerShape(12.dp)
        else -> RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    val containerColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        msg.isUnknownOutcomeBanner -> MaterialTheme.colorScheme.errorContainer
        isSystem -> semantic.subtleBg
        else -> MaterialTheme.colorScheme.surface
    }

    val border = when {
        isUser -> null
        msg.isUnknownOutcomeBanner -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        else -> BorderStroke(1.dp, semantic.cardBorder)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = bubbleShape,
            color = containerColor,
            border = border
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                if (msg.thinking.isNotEmpty()) {
                    var showThink by remember(msg.id) { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showThink = !showThink }
                            .padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (showThink) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${strings.thinking} (${msg.thinking.length} chars)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AnimatedVisibility(visible = showThink) {
                        SelectionContainer {
                            Text(
                                msg.thinking,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .background(semantic.codeBg, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                for (tool in msg.tools) {
                    ToolCallCard(tool)
                    Spacer(Modifier.height(6.dp))
                }

                if (msg.text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                                msg.isUnknownOutcomeBanner -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                if (msg.isStreaming) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(tool: UiToolCall) {
    val semantic = LocalDshnColors.current
    var expanded by remember(tool.toolCallId) { mutableStateOf(false) }
    val tone = when {
        tool.outcomeUnknown || tool.isError -> PillTone.ERROR
        tool.status.equals("completed", ignoreCase = true) -> PillTone.SUCCESS
        else -> PillTone.INFO
    }

    Surface(
        color = semantic.codeBg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, semantic.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    tool.title.ifEmpty { tool.toolCallId },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                StatusPill(
                    text = if (tool.outcomeUnknown) "UNKNOWN" else tool.status.uppercase(),
                    tone = tone
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tool.inputSummary.isNotEmpty()) {
                        Text("Input", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(
                                tool.inputSummary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (tool.outputSummary.isNotEmpty()) {
                        Text("Output", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(
                                tool.outputSummary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
