package dev.dshn.app.runtime

import dev.dshn.app.acp.PermissionOption

data class UiToolCall(
    val toolCallId: String,
    val title: String,
    val status: String,
    val inputSummary: String = "",
    val outputSummary: String = "",
    val isError: Boolean = false,
    val outcomeUnknown: Boolean = false
)

data class UiChatMessage(
    val id: String,
    val role: String, // "user", "assistant", "system"
    val text: String = "",
    val thinking: String = "",
    val tools: List<UiToolCall> = emptyList(),
    val isStreaming: Boolean = false,
    val isUnknownOutcomeBanner: Boolean = false,
    val isError: Boolean = false
)

data class PendingAcpPermissionUi(
    val requestId: String,
    val sessionId: String,
    val toolCallId: String?,
    val title: String?,
    val options: List<PermissionOption>
)
