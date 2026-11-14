package dev.dshn.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshn.app.bridge.PendingBridgeConfirmation
import dev.dshn.app.runtime.UserQuestionAnswer
import dev.dshn.app.runtime.UserQuestionRequest

enum class PillTone { NEUTRAL, INFO, SUCCESS, WARNING, ERROR }

@Composable
fun StatusPill(
    text: String,
    tone: PillTone = PillTone.NEUTRAL,
    showDot: Boolean = false,
    modifier: Modifier = Modifier
) {
    val semantic = LocalDshnColors.current
    val (bg, fg, dot) = when (tone) {
        PillTone.NEUTRAL -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outline
        )
        PillTone.INFO -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            MaterialTheme.colorScheme.primary
        )
        PillTone.SUCCESS -> Triple(
            semantic.successContainer,
            semantic.onSuccessContainer,
            semantic.success
        )
        PillTone.WARNING -> Triple(
            semantic.warningContainer,
            semantic.onWarningContainer,
            semantic.warning
        )
        PillTone.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.error
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
            }
            Text(
                text = text,
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ThemeQuickToggleButton(
    strings: UiStrings,
    onToggle: (currentlyDark: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalDshnColors.current
    val isDark = semantic.isDark
    val label = if (isDark) strings.themeDark else strings.themeLight
    val nextDesc = if (isDark) strings.themeLight else strings.themeDark
    Surface(
        onClick = { onToggle(isDark) },
        shape = RoundedCornerShape(999.dp),
        color = semantic.subtleBg,
        border = BorderStroke(1.dp, semantic.cardBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = if (isDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                contentDescription = nextDesc,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun DshnSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val semantic = LocalDshnColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) semantic.heroBg else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else semantic.cardBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (icon != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(7.dp)
                                .size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun CopyableCodeBox(
    text: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    onCopy: (() -> Unit)? = null
) {
    val semantic = LocalDshnColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = semantic.codeBg,
        border = BorderStroke(1.dp, semantic.cardBorder)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (!label.isNullOrBlank() || onCopy != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    if (onCopy != null) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun DeviceBridgeConfirmDialog(
    req: PendingBridgeConfirmation,
    onDecision: (Boolean) -> Unit
) {
    val semantic = LocalDshnColors.current
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                "高危操作逐次确认 · ${req.tool}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(req.summary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                if (req.detail.isNotBlank()) {
                    Surface(
                        color = semantic.codeBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, semantic.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            req.detail,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Surface(
                    color = semantic.warningContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        req.promptInjectionWarning,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.onWarningContainer
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDecision(true) }) {
                Text("Allow Once")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { onDecision(false) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Deny")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AcpPermissionDialog(
    req: PendingAcpPermissionUi,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Agent Permission Request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(req.title ?: "Tool call ${req.toolCallId ?: ""}", fontWeight = FontWeight.Medium)
                Text(
                    "Session: ${req.sessionId.take(12)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (opt in req.options) {
                    val isReject = opt.kind?.contains("reject") == true
                    if (isReject) {
                        OutlinedButton(onClick = { onSelect(opt.optionId) }) {
                            Text(opt.name)
                        }
                    } else {
                        Button(onClick = { onSelect(opt.optionId) }) {
                            Text(opt.name)
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

@Composable
fun UserQuestionDialog(
    req: UserQuestionRequest,
    onSubmit: (List<UserQuestionAnswer>) -> Unit,
    onCancel: () -> Unit
) {
    val selections = remember(req.requestId) { mutableStateMapOf<String, Set<String>>() }
    val customAnswers = remember(req.requestId) { mutableStateMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Agent Question") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (q in req.questions) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!q.header.isNullOrBlank()) {
                            Text(q.header, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(q.question, fontWeight = FontWeight.SemiBold)
                        val cur = selections[q.id].orEmpty()
                        for (opt in q.options) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selections[q.id] = if (q.multiSelect) {
                                            if (opt.label in cur) cur - opt.label else cur + opt.label
                                        } else {
                                            setOf(opt.label)
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (q.multiSelect) {
                                    Checkbox(
                                        checked = opt.label in cur,
                                        onCheckedChange = null
                                    )
                                } else {
                                    RadioButton(
                                        selected = opt.label in cur,
                                        onClick = null
                                    )
                                }
                                Column(Modifier.padding(start = 4.dp)) {
                                    Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                                    if (!opt.description.isNullOrBlank()) {
                                        Text(
                                            opt.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = customAnswers[q.id].orEmpty(),
                            onValueChange = { customAnswers[q.id] = it },
                            label = { Text("Custom note (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val answers = req.questions.map { q ->
                    UserQuestionAnswer(
                        id = q.id,
                        selected = selections[q.id].orEmpty().toList(),
                        custom = customAnswers[q.id]?.takeIf { it.isNotBlank() }
                    )
                }
                onSubmit(answers)
            }) {
                Text("Submit")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
