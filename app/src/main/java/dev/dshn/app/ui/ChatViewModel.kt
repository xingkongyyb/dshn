package dev.dshn.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dshn.app.acp.AcpSessionManager
import dev.dshn.app.acp.PermissionOption
import dev.dshn.app.acp.PermissionOutcome
import dev.dshn.app.acp.PermissionRequest
import dev.dshn.app.acp.SessionUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Role { USER, ASSISTANT }
data class ToolCallState(val id: String, val title: String, val status: String)
data class ChatMessage(val role: Role, val text: String, val tools: List<ToolCallState> = emptyList())

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val permission: PermissionRequest? = null,
    val status: String = "未连接",
    val ready: Boolean = false,
    val sessionId: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private var permissionDeferred: CompletableDeferred<PermissionOutcome>? = null

    private val manager = AcpSessionManager(app, viewModelScope) { req ->
        val d = CompletableDeferred<PermissionOutcome>()
        permissionDeferred = d
        _state.value = _state.value.copy(permission = req)
        d.await()
    }

    init { connect() }

    fun connect() {
        viewModelScope.launch {
            _state.value = _state.value.copy(status = "启动容器…")
            try {
                if (!manager.isReady()) {
                    _state.value = _state.value.copy(status = "容器未就绪（rootfs 缺失）")
                    return@launch
                }
                val client = manager.start()
                val session = client.newSession("/root")
                _state.value = _state.value.copy(ready = true, status = "已连接", sessionId = session.sessionId)
                launch {
                    manager.updates?.collect { onUpdate(it) }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "连接失败：${e.message}")
            }
        }
    }

    private fun onUpdate(u: SessionUpdate) {
        val msgs = _state.value.messages.toMutableList()
        when (u) {
            is SessionUpdate.AgentMessageChunk -> {
                val last = msgs.lastOrNull()
                if (last != null && last.role == Role.ASSISTANT) {
                    msgs[msgs.lastIndex] = last.copy(text = last.text + u.text)
                } else {
                    msgs.add(ChatMessage(Role.ASSISTANT, u.text))
                }
            }
            is SessionUpdate.ToolCall -> {
                val last = msgs.lastOrNull()
                val tool = ToolCallState(u.toolCallId, u.title, u.status)
                if (last != null && last.role == Role.ASSISTANT) {
                    msgs[msgs.lastIndex] = last.copy(tools = last.tools + tool)
                } else {
                    msgs.add(ChatMessage(Role.ASSISTANT, "", listOf(tool)))
                }
            }
            is SessionUpdate.ToolCallUpdate -> {
                val li = msgs.indexOfLast { it.role == Role.ASSISTANT }
                if (li >= 0) {
                    val m = msgs[li]
                    msgs[li] = m.copy(tools = m.tools.map {
                        if (it.id == u.toolCallId) it.copy(status = u.status) else it
                    })
                }
            }
            else -> {}
        }
        _state.value = _state.value.copy(messages = msgs)
    }

    fun send(text: String) {
        val sid = _state.value.sessionId ?: return
        if (text.isBlank() || _state.value.streaming) return
        val msgs = _state.value.messages + ChatMessage(Role.USER, text) + ChatMessage(Role.ASSISTANT, "")
        _state.value = _state.value.copy(messages = msgs, streaming = true)
        viewModelScope.launch {
            try {
                manager.client()?.prompt(sid, text)
            } catch (e: Exception) {
                val cur = _state.value.messages.toMutableList()
                val li = cur.indexOfLast { it.role == Role.ASSISTANT }
                if (li >= 0) cur[li] = cur[li].copy(text = cur[li].text + "\n[错误] ${e.message}")
                _state.value = _state.value.copy(messages = cur)
            } finally {
                _state.value = _state.value.copy(streaming = false)
            }
        }
    }

    fun cancel() {
        _state.value.sessionId?.let { manager.client()?.cancel(it) }
    }

    fun answerPermission(optionId: String) {
        permissionDeferred?.complete(PermissionOutcome(optionId))
        permissionDeferred = null
        _state.value = _state.value.copy(permission = null)
    }

    override fun onCleared() { manager.stop() }
}