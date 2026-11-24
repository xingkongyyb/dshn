package dev.dshn.app.runtime;

interface IRuntimeCallback {
    void onStateChanged(String state, String detail, int restartCount);
    void onSessionUpdate(String sessionId, String jsonUpdate);
    void onPermissionRequested(String requestId, String sessionId, String toolCallId, String title, String optionsJson);
    void onQuestionRequested(String questionRequestJson);
    void onQuestionCancelled(String requestId, String sessionId);
    void onPromptCompleted(String requestId, String sessionId, String stopReason, String errorMessage);
    void onInterruptedTurnUnknown(String sessionId, String message);
    void onStderrLine(String line);
}
