package dev.dshn.app.runtime;

import dev.dshn.app.runtime.IRuntimeCallback;

interface IRuntimeService {
    void registerCallback(IRuntimeCallback cb);
    void unregisterCallback(IRuntimeCallback cb);
    void startRuntime();
    void stopRuntime();
    void restartRuntime();
    String getStatusJson();
    String getRecentLogsJson(int maxLines);
    void clearLogs();

    String newSession(String cwd);
    String resumeSession(String sessionId, String cwd);
    String closeSession(String sessionId);
    String setConfigOption(String sessionId, String configId, String value);
    String listSessionsFromBridge();
    String readSessionSurfaceFromBridge(String sessionId);
    String listCommandsFromBridge(String sessionId);
    String executeCommandFromBridge(String sessionId, String line);

    void sendPrompt(String requestId, String sessionId, String text);
    void cancelPrompt(String sessionId);
    void replyPermission(String requestId, String optionId, boolean cancelled);
    void replyQuestion(String requestId, String answersJson, boolean cancelled);

    void reloadSettings();
}
