package net.fuyumori.stellashell;

import android.content.Context;
import android.provider.Settings;

/** Owns the lifecycle and routing policy for the independent Display-0 navigation surfaces. */
final class PhoneNavigationOwner implements AutoCloseable {
    private final Context context;
    private final Runnable navigationChanged;
    private final Runnable areaChanged;
    private final ConfigSource configSource;
    private final SurfaceFactory surfaceFactory;

    private SidebarSurface sidebar;
    private Surface taskbar;
    private boolean homeVisible;
    private boolean closed;
    private boolean published;
    private int publishedState;
    private long sidebarGeneration;
    private long taskbarGeneration;

    PhoneNavigationOwner(Context context, Runnable navigationChanged, Runnable areaChanged) {
        this(context, navigationChanged, areaChanged, productionConfig(context), new AndroidSurfaceFactory());
    }

    /** Injectable boundary used by lifecycle tests; production callers use the constructor above. */
    PhoneNavigationOwner(Context context, Runnable navigationChanged, Runnable areaChanged,
                         ConfigSource configSource, SurfaceFactory surfaceFactory) {
        this.context = context;
        this.navigationChanged = navigationChanged == null ? () -> {} : navigationChanged;
        this.areaChanged = areaChanged == null ? () -> {} : areaChanged;
        this.configSource = configSource;
        this.surfaceFactory = surfaceFactory;
    }

    private static ConfigSource productionConfig(Context context) {
        ShellSettings settings = ShellSettings.of(context);
        return () -> {
            ShellSettings.Snapshot snapshot = settings.snapshot();
            return new Desired(snapshot.phoneDock.enabled, snapshot.phoneTaskbar.enabled,
                    Settings.canDrawOverlays(context));
        };
    }

    /** Idempotently makes the attached surfaces match the latest settings and overlay permission. */
    void reconcile() {
        if (closed) return;
        Desired desired = configSource.current();
        boolean wantTaskbar = desired.overlaysAllowed && desired.taskbarEnabled;
        boolean wantSidebar = desired.overlaysAllowed && desired.sidebarEnabled;
        int before = state(wantSidebar, wantTaskbar);

        // Attach the Taskbar first so a newly-created Sidebar receives the actual Start owner.
        if (!wantTaskbar) closeTaskbar();
        else if (taskbar == null || !taskbar.ready()) {
            closeTaskbar();
            taskbar = createTaskbar();
        }

        if (!wantSidebar) closeSidebar();
        else if (sidebar == null || !sidebar.ready()) {
            closeSidebar();
            sidebar = createSidebar();
            if (sidebar != null) sidebar.homeVisible(homeVisible);
        }

        if (sidebar != null) try {
            sidebar.taskbarReady(taskbar != null && taskbar.ready());
        } catch (RuntimeException failure) {
            report(failure);
            closeSidebar();
        }
        publishIfChanged(before, state(wantSidebar, wantTaskbar));
    }

    private Surface createTaskbar() {
        long generation = ++taskbarGeneration;
        try {
            return surfaceFactory.createTaskbar(context, () -> onTaskbarAreaChanged(generation));
        } catch (RuntimeException failure) {
            ++taskbarGeneration;
            report(failure);
            return null;
        }
    }

    private SidebarSurface createSidebar() {
        long generation = ++sidebarGeneration;
        try {
            return surfaceFactory.createSidebar(context, () -> onSidebarAreaChanged(generation));
        } catch (RuntimeException failure) {
            ++sidebarGeneration;
            report(failure);
            return null;
        }
    }

    private void closeTaskbar() {
        Surface previous = taskbar;
        if (previous == null) return;
        taskbar = null;
        ++taskbarGeneration;
        closeSurface(previous);
    }

    private void closeSidebar() {
        SidebarSurface previous = sidebar;
        if (previous == null) return;
        sidebar = null;
        ++sidebarGeneration;
        closeSurface(previous);
    }

    private void closeSurface(Surface surface) {
        try {
            surface.close();
        } catch (RuntimeException failure) {
            report(failure);
        }
    }

    private void onSidebarAreaChanged(long generation) {
        if (closed || generation != sidebarGeneration) return;
        areaChanged.run();
    }

    private void onTaskbarAreaChanged(long generation) {
        if (closed || generation != taskbarGeneration) return;
        if (sidebar != null) sidebar.relayout();
        areaChanged.run();
    }

    /** Rebuilds existing views after a typed navigation-setting change without replacing owners. */
    void rebuild() {
        if (closed) return;
        int before = state(sidebar != null, taskbar != null);
        if (taskbar != null) try {
            taskbar.rebuild();
            taskbar.refreshArea();
        } catch (RuntimeException failure) {
            report(failure);
            closeTaskbar();
        }
        if (sidebar != null) try {
            sidebar.rebuild();
            sidebar.taskbarReady(taskbar != null && taskbar.ready());
        } catch (RuntimeException failure) {
            report(failure);
            closeSidebar();
        }
        publishIfChanged(before, state(sidebar != null, taskbar != null));
    }

    /** Refreshes both surfaces after the caller has established a real geometry change. */
    void geometryChanged() {
        rebuild();
    }

    /** Applies current work-area bounds without replacing the Start menu or either surface. */
    void relayout() {
        if (closed) return;
        int before = state(sidebar != null, taskbar != null);
        if (sidebar != null) try {
            sidebar.relayout();
        } catch (RuntimeException failure) {
            report(failure);
            closeSidebar();
        }
        if (taskbar != null) try {
            taskbar.relayout();
        } catch (RuntimeException failure) {
            report(failure);
            closeTaskbar();
            if (sidebar != null) try {
                sidebar.taskbarReady(false);
            } catch (RuntimeException sidebarFailure) {
                report(sidebarFailure);
                closeSidebar();
            }
        }
        publishIfChanged(before, state(sidebar != null, taskbar != null));
    }

    /** HOME visibility is aggregated by ShellRuntime; this owner consumes that aggregate only. */
    void homeVisible(boolean visible) {
        if (closed || homeVisible == visible) return;
        homeVisible = visible;
        if (sidebar != null) try {
            sidebar.homeVisible(visible);
        } catch (RuntimeException failure) {
            report(failure);
            closeSidebar();
        }
        navigationChanged.run();
    }

    /** Routes phone Start to the attached Taskbar first, then the Sidebar. */
    boolean toggleStart() {
        if (closed) return false;
        Surface target = taskbar != null && taskbar.ready() ? taskbar
                : sidebar != null && sidebar.ready() ? sidebar : null;
        if (target == null) return false;
        if (sidebar != null && sidebar != target && sidebar.isStartOpen()) sidebar.closeStart();
        if (taskbar != null && taskbar != target && taskbar.isStartOpen()) taskbar.closeStart();
        try {
            target.toggleStart();
        } catch (RuntimeException failure) {
            report(failure);
            return false;
        }
        navigationChanged.run();
        return true;
    }

    boolean isStartOpen() {
        return !closed && (sidebar != null && sidebar.isStartOpen() || taskbar != null && taskbar.isStartOpen());
    }

    void closeStart() {
        if (closed) return;
        boolean wasOpen = isStartOpen();
        if (sidebar != null) try { sidebar.closeStart(); } catch (RuntimeException failure) { report(failure); }
        if (taskbar != null) try { taskbar.closeStart(); } catch (RuntimeException failure) { report(failure); }
        if (wasOpen) navigationChanged.run();
    }

    boolean ready() {
        return !closed && (sidebar != null && sidebar.ready() || taskbar != null && taskbar.ready());
    }

    @Override public void close() {
        if (closed) return;
        int before = state(false, false);
        closed = true;
        ++sidebarGeneration;
        ++taskbarGeneration;
        SidebarSurface oldSidebar = sidebar;
        Surface oldTaskbar = taskbar;
        sidebar = null;
        taskbar = null;
        if (oldSidebar != null) closeSurface(oldSidebar);
        if (oldTaskbar != null) closeSurface(oldTaskbar);
        publishIfChanged(before, state(false, false));
    }

    private int state(boolean wantSidebar, boolean wantTaskbar) {
        return (wantSidebar ? 1 : 0) | (wantTaskbar ? 2 : 0)
                | (sidebar != null && sidebar.ready() ? 4 : 0)
                | (taskbar != null && taskbar.ready() ? 8 : 0)
                | (isStartOpen() ? 16 : 0);
    }

    private void publishIfChanged(int before, int after) {
        if (!published || before != after || publishedState != after) {
            published = true;
            publishedState = after;
            navigationChanged.run();
        }
    }

    private void report(RuntimeException failure) {
        if (context != null) Launches.problem(context, failure.getMessage());
    }

    interface ConfigSource { Desired current(); }

    interface SurfaceFactory {
        SidebarSurface createSidebar(Context context, Runnable areaChanged);
        Surface createTaskbar(Context context, Runnable areaChanged);
    }

    interface Surface extends AutoCloseable {
        boolean ready();
        boolean isStartOpen();
        void toggleStart();
        void closeStart();
        void rebuild();
        void relayout();
        default void refreshArea() {}
        @Override void close();
    }

    interface SidebarSurface extends Surface {
        void homeVisible(boolean visible);
        void taskbarReady(boolean ready);
    }

    static final class Desired {
        final boolean sidebarEnabled, taskbarEnabled, overlaysAllowed;
        Desired(boolean sidebarEnabled, boolean taskbarEnabled, boolean overlaysAllowed) {
            this.sidebarEnabled = sidebarEnabled;
            this.taskbarEnabled = taskbarEnabled;
            this.overlaysAllowed = overlaysAllowed;
        }
    }

    private static final class AndroidSurfaceFactory implements SurfaceFactory {
        @Override public SidebarSurface createSidebar(Context context, Runnable areaChanged) {
            return new SidebarHandle(new PhoneSidebar(context, areaChanged));
        }

        @Override public Surface createTaskbar(Context context, Runnable areaChanged) {
            return new TaskbarHandle(new PhoneTaskbar(context, areaChanged));
        }
    }

    private static final class SidebarHandle implements SidebarSurface {
        private final PhoneSidebar value;
        SidebarHandle(PhoneSidebar value) { this.value = value; }
        @Override public boolean ready() { return value.ready(); }
        @Override public boolean isStartOpen() { return value.menu.isOpen(); }
        @Override public void toggleStart() { value.toggleStart(); }
        @Override public void closeStart() { value.menu.close(); }
        @Override public void rebuild() { value.rebuild(); }
        @Override public void relayout() { value.relayout(); }
        @Override public void homeVisible(boolean visible) { value.homeVisible(visible); }
        @Override public void taskbarReady(boolean ready) { value.taskbarReady(ready); }
        @Override public void close() { value.close(); }
    }

    private static final class TaskbarHandle implements Surface {
        private final PhoneTaskbar value;
        TaskbarHandle(PhoneTaskbar value) { this.value = value; }
        @Override public boolean ready() { return value.ready(); }
        @Override public boolean isStartOpen() { return value.menu.isOpen(); }
        @Override public void toggleStart() { value.toggleStart(); }
        @Override public void closeStart() { value.menu.close(); }
        @Override public void rebuild() { value.rebuild(); }
        @Override public void relayout() { value.relayout(); }
        @Override public void refreshArea() { value.refreshArea(); }
        @Override public void close() { value.close(); }
    }
}
