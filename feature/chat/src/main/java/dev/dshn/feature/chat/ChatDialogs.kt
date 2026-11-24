package dev.dshn.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.app.runtime.PendingAcpPermissionUi
import dev.dshn.app.runtime.UserQuestionAnswer
import dev.dshn.app.runtime.UserQuestionRequest
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.tr

@Composable
private fun permissionOptionLabel(kind: String?, fallback: String): String = when (kind) {
    "allow_once" -> tr("允许本次", "Allow once")
    "allow_always" -> tr("总是允许", "Always allow")
    "reject_once" -> tr("拒绝", "Reject")
    "reject_always" -> tr("总是拒绝", "Always reject")
    else -> fallback
}

@Composable
fun AcpPermissionDialog(
    req: PendingAcpPermissionUi,
    sessionLabel: String? = null,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit
) {
    val semantic = LocalDshnColors.current
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(tr("DSH 请求执行操作", "DSH wants to run an action")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!sessionLabel.isNullOrBlank()) {
                    StatusPill(
                        text = tr("对话：$sessionLabel", "Chat: $sessionLabel"),
                        tone = PillTone.INFO,
                        showDot = true
                    )
                }
                Text(
                    tr("请确认是否允许以下操作：", "Review the action below before allowing it:"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = semantic.codeBg,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, semantic.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            req.title ?: tr("工具调用", "Tool call") + " ${req.toolCallId.orEmpty()}",
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                val ordered = req.options.sortedBy {
                    when (it.kind) {
                        "allow_once" -> 0
                        "allow_always" -> 1
                        "reject_once" -> 2
                        "reject_always" -> 3
                        else -> 4
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (opt in ordered) {
                        val label = permissionOptionLabel(opt.kind, opt.name)
                        when {
                            opt.kind == "allow_once" -> Button(
                                onClick = { onSelect(opt.optionId) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("acp_permission_allow_once")
                            ) { Text(label) }
                            opt.kind?.startsWith("allow") == true -> FilledTonalButton(
                                onClick = { onSelect(opt.optionId) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(label) }
                            else -> OutlinedButton(
                                onClick = { onSelect(opt.optionId) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(label) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("acp_permission_cancel")) {
                Text(tr("取消本轮", "Cancel"))
            }
        }
    )
}

@Composable
fun UserQuestionDialog(
    req: UserQuestionRequest,
    sessionLabel: String? = null,
    onSubmit: (List<UserQuestionAnswer>) -> Unit,
    onCancel: () -> Unit
) {
    val selections = remember(req.requestId) { mutableStateMapOf<String, Set<String>>() }
    val customAnswers = remember(req.requestId) { mutableStateMapOf<String, String>() }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(tr("DSH 需要你的选择", "DSH has a question")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!sessionLabel.isNullOrBlank()) {
                    StatusPill(
                        text = tr("对话：$sessionLabel", "Chat: $sessionLabel"),
                        tone = PillTone.INFO,
                        showDot = true
                    )
                }
                for (q in req.questions) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!q.header.isNullOrBlank()) {
                            Text(q.header!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(q.question, style = MaterialTheme.typography.titleSmall)
                        if (!q.detail.isNullOrBlank()) {
                            Text(q.detail!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val cur = selections[q.id].orEmpty()
                        for (opt in q.options) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        selections[q.id] = if (q.multiSelect) {
                                            if (opt.label in cur) cur - opt.label else cur + opt.label
                                        } else {
                                            setOf(opt.label)
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (q.multiSelect) {
                                    Checkbox(checked = opt.label in cur, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                                } else {
                                    RadioButton(selected = opt.label in cur, onClick = null, modifier = Modifier.padding(8.dp))
                                }
                                Column {
                                    Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                                    if (!opt.description.isNullOrBlank()) {
                                        Text(
                                            opt.description!!,
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
                            label = { Text(tr("补充说明（可选）", "Custom answer (optional)")) },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val answers = req.questions.map { q ->
                        UserQuestionAnswer(
                            id = q.id,
                            selected = selections[q.id].orEmpty().toList(),
                            custom = customAnswers[q.id]?.takeIf { it.isNotBlank() }
                        )
                    }
                    onSubmit(answers)
                },
                modifier = Modifier.testTag("user_question_submit")
            ) { Text(tr("提交", "Submit")) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("user_question_skip")) {
                Text(tr("跳过", "Skip"))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigOptionSheet(option: AcpConfigOption, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
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
internal fun NewChatInDirDialog(
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
