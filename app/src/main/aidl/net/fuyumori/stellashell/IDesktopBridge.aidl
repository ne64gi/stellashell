package net.fuyumori.stellashell;
import android.os.ParcelFileDescriptor;
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
    String syncMouseRouting(int displayId, IBinder owner) = 9;
    String syncVirtualKeyboard(int displayId, boolean hide, IBinder owner) = 10;
    String captureDisplay(int displayId, in ParcelFileDescriptor output) = 11;
    String syncPrimaryScreen(int displayId, boolean off, IBinder owner) = 12;
    String moveWorkspaceTask(int source, int destination, int taskId, String component) = 14;
    void setWorkArea(int displayId, int left, int top, int right, int bottom) = 13;
}
