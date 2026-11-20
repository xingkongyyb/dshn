package dev.dshn.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dshn.app.bridge.RootAuthStatus

@Composable
fun TerminalScreen(vm: MainViewModel) {
    val semantic = LocalDshnColors.current
    val output by vm.terminalOutput.collectAsState()
    val terminalMode by vm.terminalMode.collectAsState()
    val containerRunning by vm.terminalRunning.collectAsState()
    val hostRootRunning by vm.hostRootTerminalRunning.collectAsState()
    val rootAuth by vm.rootAuthState.collectAsState()
    val isRoot = terminalMode == TerminalShellMode.HOST_ROOT_SU
    val running = if (isRoot) hostRootRunning else containerRunning
    val rootAuthorized = rootAuth.status == RootAuthStatus.AUTHORIZED

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

    SecondaryScaffold(
        title = tr("终端", "Terminal"),
        subtitle = if (isRoot) tr("宿主机 Root · uid=0", "Host Root · uid=0") else tr("Ubuntu 容器 · 独立 DSH_HOME", "Ubuntu container · separate DSH_HOME"),
        onBack = { vm.navigateBack() },
        actions = {
            IconButton(onClick = { vm.clearTerminal() }) {
                Icon(Icons.Rounded.CleaningServices, contentDescription = tr("清屏", "Clear"))
            }
            IconButton(onClick = { vm.startTerminal() }) {
                Icon(
                    if (running) Icons.Rounded.RestartAlt else Icons.Rounded.PlayArrow,
                    contentDescription = if (running) tr("重启 Shell", "Restart shell") else tr("启动 Shell", "Start shell")
                )
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !isRoot,
                    onClick = { vm.setTerminalMode(TerminalShellMode.CONTAINER_UBUNTU) },
                    label = { Text(tr("Ubuntu 容器", "Ubuntu")) }
                )
                FilterChip(
                    selected = isRoot,
                    enabled = rootAuthorized,
                    onClick = { vm.setTerminalMode(TerminalShellMode.HOST_ROOT_SU) },
                    label = { Text(if (rootAuthorized) tr("宿主机 Root", "Host Root") else tr("Root（未授权）", "Root (locked)")) }
                )
                Spacer(Modifier.weight(1f))
                StatusPill(
                    text = if (running) tr("运行中", "Running") else tr("未启动", "Stopped"),
                    tone = if (running) (if (isRoot) PillTone.ERROR else PillTone.SUCCESS) else PillTone.NEUTRAL,
                    showDot = true
                )
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = semantic.terminalBg,
                border = if (isRoot) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(vScroll)
                ) {
                    if (output.isEmpty() && !running) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                if (isRoot) tr("Root Shell 会以 uid=0 运行在安卓宿主机上，请谨慎操作。", "The Root shell runs as uid=0 on the Android host. Be careful.")
                                else tr("在容器里打开一个独立的 bash，与 DSH 的对话互不干扰。", "Open a separate bash in the container, isolated from DSH chats."),
                                color = semantic.terminalFg.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(onClick = { vm.startTerminal() }) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("启动 Shell", "Start shell"))
                            }
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                text = output.ifEmpty { tr("Shell 已启动，输入命令试试…", "Shell is running. Type a command…") },
                                color = semantic.terminalFg,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExtraKeyButton("ESC") { sendKey("\u001B") }
                ExtraKeyButton("TAB") { sendKey("\t") }
                ExtraKeyButton("CTRL", highlighted = ctrlSticky) { ctrlSticky = !ctrlSticky }
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                        placeholder = { Text(tr("实时按键模式（适合 vim）", "Live keys (good for vim)")) },
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
                        placeholder = { Text(tr("输入一行命令后回车", "Type a command, then Enter")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSend = {
                            val cmd = lineDraft
                            lineDraft = ""
                            sendKey("$cmd\r")
                        })
                    )
                    IconButton(
                        enabled = running,
                        onClick = {
                            val cmd = lineDraft
                            lineDraft = ""
                            sendKey("$cmd\r")
                        }
                    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, contentDescription = tr("回车", "Enter")) }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = rawKeys, onCheckedChange = { rawKeys = it })
                    Text(tr("实时", "Live"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    val semantic = LocalDshnColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary else semantic.cardBorder)
    ) {
        Text(
            text = label,
            modifier = Modifier
                .background(androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
