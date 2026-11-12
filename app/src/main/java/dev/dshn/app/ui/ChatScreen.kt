package dev.dshn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text("DSHN · ${state.status}", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { msg -> MessageBubble(msg) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("描述你想构建的内容…") },
                    enabled = state.ready && !state.streaming,
                )
                if (state.streaming) {
                    OutlinedButton(onClick = { vm.cancel() }) { Text("取消") }
                } else {
                    Button(
                        onClick = { vm.send(input); input = "" },
                        enabled = state.ready && input.isNotBlank(),
                    ) { Text("发送") }
                }
            }
        }
    }

    state.permission?.let { req ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("工具审批") },
            text = { Text(req.title ?: req.toolCallId ?: "请求执行工具") },
            confirmButton = {
                req.options.forEach { opt ->
                    TextButton(onClick = { vm.answerPermission(opt.optionId) }) { Text(opt.name) }
                }
            },
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(if (isUser) "你" else "DSH", style = MaterialTheme.typography.labelMedium)
            if (msg.text.isNotEmpty()) Text(msg.text, modifier = Modifier.padding(top = 4.dp))
            msg.tools.forEach { tool ->
                Text(
                    "🔧 ${tool.title} · ${tool.status}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}