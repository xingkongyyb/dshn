package dev.dshn.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.app.runtime.ContextInspectSnapshot
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.app.runtime.SlashCommandDescriptor
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.tr

@Composable
internal fun runtimeStateLabel(state: RuntimeLifecycleState): Pair<String, PillTone> = when (state) {
    RuntimeLifecycleState.HEALTHY -> tr("就绪", "Ready") to PillTone.SUCCESS
    RuntimeLifecycleState.STARTING -> tr("正在启动", "Starting") to PillTone.WARNING
    RuntimeLifecycleState.DEGRADED -> tr("运行不稳定", "Degraded") to PillTone.WARNING
    RuntimeLifecycleState.STOPPING -> tr("正在停止", "Stopping") to PillTone.NEUTRAL
    RuntimeLifecycleState.STOPPED -> tr("未启动", "Stopped") to PillTone.NEUTRAL
    RuntimeLifecycleState.ERROR -> tr("出错", "Error") to PillTone.ERROR
}

internal fun optionKind(opt: AcpConfigOption): String {
    val key = "${opt.id} ${opt.category} ${opt.name}".lowercase()
    return when {
        "model" in key -> "model"
        "effort" in key || "reason" in key || "think" in key || "thought" in key -> "effort"
        else -> "other"
    }
}

internal fun currentChoiceName(opt: AcpConfigOption): String =
    opt.choices.firstOrNull { it.value == opt.currentValue }?.name ?: opt.currentValue

@Composable
internal fun SlashSuggestions(input: String, commands: List<SlashCommandDescriptor>, onPick: (String) -> Unit) {
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
internal fun Composer(
    onHeight: (Dp) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    streaming: Boolean,
    readOnly: Boolean,
    sessionAttached: Boolean,
    configOptions: List<AcpConfigOption>,
    usageUsed: Long,
    usageSize: Long,
    contextInspect: ContextInspectSnapshot?,
    contextInspectLoading: Boolean,
    contextInspectError: String?,
    onInspectContext: () -> Unit,
    onCopy: (label: String, text: String) -> Unit,
    onSetOption: SetConfigOption,
    configKey: Any?,
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
                    .padding(start = 4.dp, top = 8.dp, bottom = 6.dp, end = 8.dp)
                    .testTag("chat_composer_input"),
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
                        // Keyed by chat so an open menu or a pending switch never carries over to another chat.
                        key(configKey) {
                            val groups = groupComposerConfigOptions(configOptions)
                            groups.model?.let { ModelEffortMenuChip(it, groups.effort, onSetOption) }
                            for (opt in groups.others) {
                                key(opt.id) { OptionMenuChip(opt, onSetOption) }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (!readOnly && (usageSize > 0L || sessionAttached)) {
                    key(configKey) {
                        ContextUsageMenuChip(
                            used = usageUsed,
                            size = usageSize,
                            snapshot = contextInspect,
                            loading = contextInspectLoading,
                            error = contextInspectError,
                            onInspect = onInspectContext,
                            onCopy = onCopy
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                val sendEnabled = streaming || canSend
                Surface(
                    onClick = { if (streaming) onStop() else if (canSend) onSend() },
                    enabled = sendEnabled,
                    shape = CircleShape,
                    color = if (sendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(if (streaming) "chat_stop_button" else "chat_send_button")
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
