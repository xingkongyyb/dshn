package dev.dshn.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalScreen(
    vm: MainViewModel,
    strings: UiStrings,
    modifier: Modifier = Modifier
) {
    val semantic = LocalDshnColors.current
    val output by vm.terminalOutput.collectAsState()
    val running by vm.terminalRunning.collectAsState()

    var ctrlSticky by remember { mutableStateOf(false) }
    var rawKeys by remember { mutableStateOf(true) }
    var lineDraft by remember { mutableStateOf("") }
    var lastRawField by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }
    val vScroll = rememberScrollState()

    LaunchedEffect(output.length) {
        vScroll.scrollTo(vScroll.maxValue)
    }

    fun sendKey(raw: String) {
        if (!running) return
        val toSend = if (ctrlSticky && raw.length == 1) {
            val ch = raw[0].lowercaseChar()
            ctrlSticky = false
            if (ch in 'a'..'z') String(charArrayOf((ch - 'a' + 1).toChar())) else raw
        } else {
            raw
        }
        vm.sendTerminalInput(toSend)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                strings.terminalTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            StatusPill(
                                text = if (running) "RUNNING" else "STOPPED",
                                tone = if (running) PillTone.SUCCESS else PillTone.NEUTRAL,
                                showDot = true
                            )
                        }
                        ThemeQuickToggleButton(strings = strings, onToggle = { vm.toggleLightDarkTheme(it) })
                    }
                    Text(
                        "DSH_HOME=/root/.dsh-terminal",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(strings.rawKeysLabel, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(6.dp))
                            Switch(checked = rawKeys, onCheckedChange = { rawKeys = it })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.startTerminal() }) {
                                Text(if (running) "Restart" else strings.terminalStartShell)
                            }
                            OutlinedButton(onClick = { vm.clearTerminal() }) {
                                Text("Clear")
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = semantic.cardBorder)
            }
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            shape = RoundedCornerShape(14.dp),
            color = semantic.terminalBg,
            border = BorderStroke(1.dp, semantic.cardBorder)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .verticalScroll(vScroll)
            ) {
                SelectionContainer {
                    Text(
                        text = output.ifEmpty {
                            if (running) "PTY active. Type commands or launch vim / dsh below..."
                            else "Tap '${strings.terminalStartShell}' to spawn an isolated /bin/bash PTY inside bxroot."
                        },
                        color = semantic.terminalFg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(semantic.subtleBg)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ExtraKeyButton("ESC") { sendKey("\u001B") }
            ExtraKeyButton("TAB") { sendKey("\t") }
            FilterChip(
                selected = ctrlSticky,
                onClick = { ctrlSticky = !ctrlSticky },
                label = { Text("CTRL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            )
            ExtraKeyButton("^C") { vm.sendTerminalInput("\u0003") }
            ExtraKeyButton("^D") { vm.sendTerminalInput("\u0004") }
            ExtraKeyButton("↑") { sendKey("\u001B[A") }
            ExtraKeyButton("↓") { sendKey("\u001B[B") }
            ExtraKeyButton("←") { sendKey("\u001B[D") }
            ExtraKeyButton("→") { sendKey("\u001B[C") }
            ExtraKeyButton("|") { sendKey("|") }
            ExtraKeyButton("/") { sendKey("/") }
            ExtraKeyButton("~") { sendKey("~") }
            ExtraKeyButton("-") { sendKey("-") }
            ExtraKeyButton(":wq") { sendKey("\u001B:wq\r") }
        }

        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (rawKeys) {
                    OutlinedTextField(
                        value = lastRawField,
                        onValueChange = { next ->
                            if (!running) {
                                lastRawField = TextFieldValue(" ", selection = TextRange(1))
                                return@OutlinedTextField
                            }
                            val text = next.text
                            when {
                                text.isEmpty() -> sendKey("\u007F")
                                text.length > 1 -> {
                                    val delta = if (text.startsWith(" ")) text.substring(1) else text
                                    for (c in delta) {
                                        if (c == '\n') sendKey("\r") else sendKey(c.toString())
                                    }
                                }
                                text != " " -> sendKey(text)
                            }
                            lastRawField = TextFieldValue(" ", selection = TextRange(1), composition = null)
                        },
                        enabled = running,
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("Raw keystroke capture active (ideal for vim / bash)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        visualTransformation = VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { sendKey("\r") })
                    )
                } else {
                    OutlinedTextField(
                        value = lineDraft,
                        onValueChange = { lineDraft = it },
                        enabled = running,
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("Enter shell line...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val cmd = lineDraft
                            lineDraft = ""
                            sendKey("$cmd\r")
                        })
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = running,
                        onClick = {
                            val cmd = lineDraft
                            lineDraft = ""
                            sendKey("$cmd\r")
                        }
                    ) { Text("Enter") }
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(label: String, onClick: () -> Unit) {
    val semantic = LocalDshnColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, semantic.cardBorder)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
