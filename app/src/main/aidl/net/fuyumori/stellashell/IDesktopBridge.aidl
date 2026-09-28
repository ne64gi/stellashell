package net.fuyumori.stellashell;
interface IDesktopBridge {
    void destroy() = 16777114;
    String settingsSnapshot() = 1;
    String applySettings(String desktop, String freeform) = 2;
    String launch(String component, int displayId, int mode) = 3;
    String taskSnapshot(int displayId) = 4;
    String taskOperation(int displayId, int taskId, String action, int left, int top, int right, int bottom) = 5;
    String launchProfile(String component, String resolved, int displayId, int mode, int left, int top, int right, int bottom, boolean newWindow) = 6;
    String back(int displayId) = 7;
    void setPrimaryMode(boolean enabled) = 8;
}
