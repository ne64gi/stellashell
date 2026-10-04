package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.navigation.NavigationScale;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sole typed owner for durable shell navigation/layout preferences.
 *
 * The backing store and legacy key names are intentionally unchanged so installed
 * preferences remain compatible. Session and task/workspace state do not belong
 * here: in particular this class never reads or writes enabled, active_display,
 * or workspace_display. Mutations are issued by UI/service main-thread owners;
 * multi-key setters publish one coherent typed change set for those callers.
 */
final class ShellSettings implements AutoCloseable {
    private static final String STORE = "desktop";
    private static volatile ShellSettings shared;

    /** Context fixtures may inject the same typed settings owner as their other storage adapters. */
    interface Provider {
        ShellSettings shellSettings();
    }

    enum PhoneSide {
        BOTH("both"), LEFT("left"), RIGHT("right");
        final String value;
        PhoneSide(String value) { this.value = value; }
        String storedValue() { return value; }
        static PhoneSide from(String value) {
            for (PhoneSide side : values()) if (side.value.equals(value)) return side;
            return BOTH;
        }
    }

    enum DockEdge {
        LEFT("left"), RIGHT("right"), TOP("top"), BOTTOM("bottom");
        final String value;
        DockEdge(String value) { this.value = value; }
        String storedValue() { return value; }
        static DockEdge from(String value) {
            for (DockEdge edge : values()) if (edge.value.equals(value)) return edge;
            return BOTTOM;
        }
    }

    enum Layout {
        AUTO("auto"), DESKTOP("desktop"), COMPACT("compact");
        final String value;
        Layout(String value) { this.value = value; }
        String storedValue() { return value; }
        static Layout from(String value) {
            for (Layout layout : values()) if (layout.value.equals(value)) return layout;
            return AUTO;
        }
    }

    enum Change {
        PHONE_DOCK_ENABLED,
        PHONE_DOCK_SIDE,
        PHONE_DOCK_OVER_APPS,
        PHONE_DOCK_TRIGGER_POSITION,
        PHONE_DOCK_SCALE,
        EXTERNAL_DOCK_ENABLED,
        EXTERNAL_DOCK_EDGE,
        EXTERNAL_DOCK_POSITION,
        EXTERNAL_DOCK_SCALE,
        PHONE_TASKBAR_ENABLED,
        PHONE_TASKBAR_SCALE,
        EXTERNAL_TASKBAR_SCALE,
        SHELL_LAYOUT,
        COMPACT_WORKSPACE,
        WORKSPACE_AUTO,
        PRIMARY_MODE,
        PREFERRED_DISPLAY
    }

    static final class Dock {
        final boolean enabled;
        final boolean overApps;
        final PhoneSide phoneSide;
        final DockEdge edge;
        final int triggerPercent;
        final int xPercent;
        final int yPercent;
        final int scalePercent;

        private Dock(boolean enabled, boolean overApps, PhoneSide phoneSide, DockEdge edge,
                     int triggerPercent, int xPercent, int yPercent, int scalePercent) {
            this.enabled = enabled;
            this.overApps = overApps;
            this.phoneSide = phoneSide;
            this.edge = edge;
            this.triggerPercent = triggerPercent;
            this.xPercent = xPercent;
            this.yPercent = yPercent;
            this.scalePercent = scalePercent;
        }
    }

    static final class Taskbar {
        /** External taskbars are part of the presentation contract and are always shown. */
        final boolean enabled;
        final boolean alwaysShown;
        final int scalePercent;

        private Taskbar(boolean enabled, boolean alwaysShown, int scalePercent) {
            this.enabled = enabled;
            this.alwaysShown = alwaysShown;
            this.scalePercent = scalePercent;
        }
    }

    static final class Snapshot {
        final Dock phoneDock;
        final Dock externalDock;
        final Taskbar phoneTaskbar;
        final Taskbar externalTaskbar;
        final Layout shellLayout;
        final boolean compactWorkspace;
        final boolean workspaceAuto;
        final boolean primaryMode;
        /** Preferred next output; -1 means no saved external target. */
        final int preferredDisplayId;

        private Snapshot(Dock phoneDock, Dock externalDock, Taskbar phoneTaskbar,
                         Taskbar externalTaskbar, Layout shellLayout, boolean compactWorkspace,
                         boolean workspaceAuto, boolean primaryMode, int preferredDisplayId) {
            this.phoneDock = phoneDock;
            this.externalDock = externalDock;
            this.phoneTaskbar = phoneTaskbar;
            this.externalTaskbar = externalTaskbar;
            this.shellLayout = shellLayout;
            this.compactWorkspace = compactWorkspace;
            this.workspaceAuto = workspaceAuto;
            this.primaryMode = primaryMode;
            this.preferredDisplayId = preferredDisplayId;
        }
    }

    interface Listener {
        void onChanged(EnumSet<Change> changes, Snapshot snapshot);
    }

    private interface Edit {
        void apply(SharedPreferences.Editor editor);
    }

    private final SharedPreferences preferences;
    private final boolean isolated;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final EnumSet<Change> pendingChanges = EnumSet.noneOf(Change.class);
    private int editDepth;
    private volatile boolean closed;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        Change changed = changeFor(key);
        if (changed == null || closed) return;
        if (editDepth > 0) pendingChanges.add(changed);
        else publish(EnumSet.of(changed));
    };

    private ShellSettings(SharedPreferences preferences, boolean isolated) {
        this.preferences = preferences;
        this.isolated = isolated;
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    /** Returns the process-wide settings owner backed by the existing desktop preference file. */
    static ShellSettings of(Context context) {
        if (context instanceof Provider) return ((Provider) context).shellSettings();
        Context app = context.getApplicationContext();
        if (app instanceof Provider) return ((Provider) app).shellSettings();
        ShellSettings current = shared;
        if (current != null) return current;
        synchronized (ShellSettings.class) {
            if (shared == null) {
                if (app == null) app = context;
                shared = new ShellSettings(app.getSharedPreferences(STORE, Context.MODE_PRIVATE), false);
            }
            return shared;
        }
    }

    /** Isolated backing store seam for instrumentation compatibility checks. */
    static ShellSettings isolated(Context context, String preferenceFile) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        return new ShellSettings(app.getSharedPreferences(preferenceFile, Context.MODE_PRIVATE), true);
    }

    static ShellSettings isolated(SharedPreferences preferences) {
        return new ShellSettings(preferences, true);
    }

    Snapshot snapshot() {
        return new Snapshot(
                new Dock(preferences.getBoolean("phone_sidebar", true),
                        preferences.getBoolean("phone_sidebar_over_apps", true),
                        PhoneSide.from(preferences.getString("phone_sidebar_side", "both")),
                        DockEdge.BOTTOM,
                        percentRange(preferences.getInt("sidebar_height", 80), 0, 100),
                        50, 100, NavigationScale.percent(preferences.getInt("phone_dock_scale", NavigationScale.DEFAULT))),
                new Dock(preferences.getBoolean("desktop_dock", false), false, PhoneSide.BOTH,
                        DockEdge.from(preferences.getString("dock_edge", "bottom")), 80,
                        percentRange(preferences.getInt("dock_x", 50), 0, 100),
                        percentRange(preferences.getInt("dock_y", 100), 0, 100),
                        NavigationScale.percent(preferences.getInt("desktop_dock_scale", NavigationScale.DEFAULT))),
                new Taskbar(preferences.getBoolean("phone_taskbar", false), false,
                        NavigationScale.percent(preferences.getInt("phone_taskbar_scale", NavigationScale.DEFAULT))),
                new Taskbar(true, true,
                        NavigationScale.percent(preferences.getInt("desktop_taskbar_scale", NavigationScale.DEFAULT))),
                Layout.from(preferences.getString("shell_layout", "auto")),
                preferences.getBoolean("compact_workspace", false),
                preferences.getBoolean("workspace_auto", false),
                preferences.getBoolean("primary_mode", false),
                Math.max(-1, preferences.getInt("preferred_display", -1)));
    }

    AutoCloseable observe(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        if (closed) throw new IllegalStateException("Settings owner is closed");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void setPhoneDockEnabled(boolean enabled) {
        write(e -> e.putBoolean("phone_sidebar", enabled));
    }

    void setPhoneDockSide(PhoneSide side) {
        if (side == null) throw new IllegalArgumentException("Phone Dock side is required");
        write(e -> e.putString("phone_sidebar_side", side.value));
    }

    void setPhoneDockOverApps(boolean enabled) {
        write(e -> e.putBoolean("phone_sidebar_over_apps", enabled));
    }

    void setPhoneDockTriggerPercent(int percent) {
        int value = percentRange(percent, 0, 100);
        write(e -> e.putInt("sidebar_height", value));
    }

    void setPhoneDockScalePercent(int percent) {
        int value = NavigationScale.percent(percent);
        write(e -> e.putInt("phone_dock_scale", value));
    }

    void setExternalDockEnabled(boolean enabled) {
        write(e -> e.putBoolean("desktop_dock", enabled));
    }

    void setExternalDockEdge(DockEdge edge) {
        if (edge == null) throw new IllegalArgumentException("External Dock edge is required");
        write(e -> {
            e.putString("dock_edge", edge.value);
            switch (edge) {
                case LEFT: e.putInt("dock_x", 0); break;
                case RIGHT: e.putInt("dock_x", 100); break;
                case TOP: e.putInt("dock_y", 0); break;
                case BOTTOM: e.putInt("dock_y", 100); break;
            }
        });
    }

    void setExternalDockPosition(int xPercent, int yPercent) {
        int x = percentRange(xPercent, 0, 100), y = percentRange(yPercent, 0, 100);
        write(e -> e.putInt("dock_x", x).putInt("dock_y", y));
    }

    void setExternalDockScalePercent(int percent) {
        int value = NavigationScale.percent(percent);
        write(e -> e.putInt("desktop_dock_scale", value));
    }

    void setPhoneTaskbarEnabled(boolean enabled) {
        write(e -> e.putBoolean("phone_taskbar", enabled));
    }

    void setPhoneTaskbarScalePercent(int percent) {
        int value = NavigationScale.percent(percent);
        write(e -> e.putInt("phone_taskbar_scale", value));
    }

    void setExternalTaskbarScalePercent(int percent) {
        int value = NavigationScale.percent(percent);
        write(e -> e.putInt("desktop_taskbar_scale", value));
    }

    void setShellLayout(Layout layout) {
        if (layout == null) throw new IllegalArgumentException("Shell layout is required");
        write(e -> e.putString("shell_layout", layout.value));
    }

    void setCompactWorkspace(boolean enabled) {
        write(e -> e.putBoolean("compact_workspace", enabled));
    }

    void setWorkspaceAuto(boolean enabled) {
        write(e -> e.putBoolean("workspace_auto", enabled));
    }

    void initializeWorkspaceAuto() {
        if (preferences.contains("workspace_auto")) return;
        write(e -> e.putBoolean("workspace_auto", true));
    }

    void setWorkspaceOptions(boolean compact, boolean auto) {
        write(e -> e.putBoolean("compact_workspace", compact).putBoolean("workspace_auto", auto));
    }

    void setPrimaryMode(boolean enabled) {
        write(e -> e.putBoolean("primary_mode", enabled));
    }

    void setPreferredDisplay(int displayId) {
        int value = Math.max(-1, displayId);
        write(e -> {
            if (value < 0) e.remove("preferred_display");
            else e.putInt("preferred_display", value);
        });
    }

    void clearPreferredDisplay() {
        write(e -> e.remove("preferred_display"));
    }

    private void write(Edit edit) {
        if (closed) throw new IllegalStateException("Settings owner is closed");
        editDepth++;
        try {
            SharedPreferences.Editor editor = preferences.edit();
            edit.apply(editor);
            editor.apply();
        } finally {
            editDepth--;
            if (editDepth == 0 && !pendingChanges.isEmpty()) {
                EnumSet<Change> actual = EnumSet.copyOf(pendingChanges);
                pendingChanges.clear();
                publish(actual);
            }
        }
    }

    private void publish(EnumSet<Change> changes) {
        if (changes.isEmpty()) return;
        Snapshot current = snapshot();
        for (Listener listener : listeners) listener.onChanged(EnumSet.copyOf(changes), current);
    }

    private static int percentRange(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static Change changeFor(String key) {
        if (key == null) return null;
        switch (key) {
            case "phone_sidebar": return Change.PHONE_DOCK_ENABLED;
            case "phone_sidebar_side": return Change.PHONE_DOCK_SIDE;
            case "phone_sidebar_over_apps": return Change.PHONE_DOCK_OVER_APPS;
            case "sidebar_height": return Change.PHONE_DOCK_TRIGGER_POSITION;
            case "phone_dock_scale": return Change.PHONE_DOCK_SCALE;
            case "desktop_dock": return Change.EXTERNAL_DOCK_ENABLED;
            case "dock_edge": return Change.EXTERNAL_DOCK_EDGE;
            case "dock_x":
            case "dock_y": return Change.EXTERNAL_DOCK_POSITION;
            case "desktop_dock_scale": return Change.EXTERNAL_DOCK_SCALE;
            case "phone_taskbar": return Change.PHONE_TASKBAR_ENABLED;
            case "phone_taskbar_scale": return Change.PHONE_TASKBAR_SCALE;
            case "desktop_taskbar_scale": return Change.EXTERNAL_TASKBAR_SCALE;
            case "shell_layout": return Change.SHELL_LAYOUT;
            case "compact_workspace": return Change.COMPACT_WORKSPACE;
            case "workspace_auto": return Change.WORKSPACE_AUTO;
            case "primary_mode": return Change.PRIMARY_MODE;
            case "preferred_display": return Change.PREFERRED_DISPLAY;
            default: return null;
        }
    }

    /** Test-only cleanup for isolated stores. The process owner remains application-lived. */
    @Override public void close() {
        if (!isolated) throw new IllegalStateException("The process settings owner is application-lived");
        if (closed) return;
        closed = true;
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        listeners.clear();
    }
}
