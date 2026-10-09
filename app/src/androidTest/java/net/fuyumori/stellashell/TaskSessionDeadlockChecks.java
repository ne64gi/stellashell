package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic selected-output resize regression; all tasks and backend replies are fixture-owned. */
final class TaskSessionDeadlockChecks {
    private static final int WIDTH = 900, HEIGHT = 700;
    private final Instrumentation test;
    private Context actual;
    private FixtureContext context;
    private ImageReader reader;
    private VirtualDisplay display;
    private int displayId = -1;
    private TaskState state;
    private TaskState.OutputLease lease;
    private FakeBackend backend;
    private TaskSnapshot.Task task;
    private boolean showedError;

    TaskSessionDeadlockChecks(Instrumentation test) { this.test = test; }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void main(Runnable action) {
        Throwable[] failure = {null};
        test.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    void run() throws Exception {
        check(Looper.myLooper() != Looper.getMainLooper(), "Run task-session checks off main");
        actual = test.getTargetContext();
        String prefix = "task_session_deadlock_" + Process.myPid() + "_" + SystemClock.uptimeMillis() + "_";
        context = new FixtureContext(actual, prefix);
        try {
            createOwnedDisplay();
            successThenFreshSnapshot();
            removedTaskErrorReleasesQueue();
        } finally {
            cleanup();
        }
    }

    private void createOwnedDisplay() {
        main(() -> {
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(source -> {
                try (Image ignored = source.acquireLatestImage()) { }
                catch (IllegalStateException closed) { }
            }, new Handler(Looper.getMainLooper()));
            display = actual.getSystemService(DisplayManager.class).createVirtualDisplay(
                    "StellaShell task-session deadlock fixture", WIDTH, HEIGHT, 160,
                    reader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            check(display != null, "Could not create owned public output display");
            Display owned = display.getDisplay();
            displayId = owned.getDisplayId();
            check(displayId > 0 && owned.isValid() && (owned.getFlags() & Display.FLAG_PRIVATE) == 0,
                    "Task-session fixture must use only its own public non-primary display");
            WorkArea.put(displayId, new WorkArea(new Rect(0, 0, WIDTH, HEIGHT),
                    new Rect(0, 0, WIDTH, HEIGHT), false, 32, 60));
        });
    }

    private void startSession(int taskId) throws Exception {
        task = new TaskSnapshot.Task(taskId, "net.fuyumori.fixture/.ResizeProbe", 5,
                true, false, false, 40, 80, 360, 440);
        backend = new FakeBackend();
        backend.addNative(task);
        state = TaskState.isolated(context);
        TaskSnapshot.Task seeded = task;
        main(() -> {
            context.shellSettings().setPrimaryMode(true);
            context.shellSettings().setCompactWorkspace(true);
            Launches.prefs(context).edit().putBoolean("enabled", true)
                    .putInt("workspace_display", displayId).commit();
            try {
                state.launched(context, new JSONObject().put("task", jsonTask(seeded)),
                        displayId, true);
            } catch (Exception error) { throw new AssertionError(error); }
            lease = state.openOutput(context, displayId, () -> {}, () -> false, backend);
            TaskSession session = ShellFixtureAccess.taskSession(state);
            check(session != null && session.displayId() == displayId,
                    "Task session did not attach to the owned display");
            state.publish(session, new TaskSnapshot(1, session.outputEpoch(), displayId,
                    Collections.singletonList(seeded), Collections.singletonList(seeded),
                    true, true, true));
            check(backend.pendingReads() == 1,
                    "Opening the output must leave its first fake snapshot reply held");
        });
    }

    private void successThenFreshSnapshot() throws Exception {
        startSession(93001);
        AtomicBoolean focused = new AtomicBoolean();
        main(() -> {
            state.resize(task, new Rect(100, 130, 540, 590));
            check(state.isBusy(), "Resize must own the workspace ticket while its read is in flight");
            state.focusForDrag(task, focused::set);
            check(backend.pendingOperations() == 0,
                    "Resize should wait for the in-flight snapshot instead of racing it");
        });

        main(() -> backend.answerNextRead(null));
        main(() -> {
            check(backend.pendingOperations() == 1
                            && "resize".equals(backend.operation(0).action),
                    "Held snapshot completion did not dispatch the resize that owns the busy ticket");
            check(state.isBusy(), "Workspace ticket must remain owned until resize callback completes");
        });

        main(() -> {
            check(backend.pendingOperations() == 1
                            && "resize".equals(backend.operation(0).action),
                    "A held backend resize must remain the sole active operation");
            backend.answerOperation(0, null);
        });
        main(() -> {
            check(backend.pendingOperations() == 1
                            && "focus".equals(backend.operation(0).action),
                    "Next deferred task command did not run after resize release");
            backend.answerOperation(0, null);
        });
        main(() -> {
            check(focused.get(), "Deferred focus command did not receive its verified reply");
            check(backend.pendingReads() == 1,
                    "Completed commands must request a fresh authoritative task snapshot");
            backend.removeNative(task.identity());
            backend.answerNextRead(null);
            check(state.snapshot().tasks.isEmpty(),
                    "Fresh empty snapshot did not clear the removed task from the published model");
            check(!state.isBusy(), "Workspace remained busy after successful resize and follow-up command");
            int[] nextCommandRuns = {0};
            state.enqueueOutput(ShellFixtureAccess.taskSession(state), () -> nextCommandRuns[0]++);
            check(nextCommandRuns[0] == 1,
                    "Workspace did not admit the next launch-equivalent queued operation");
        });
        closeSession();
    }

    private void removedTaskErrorReleasesQueue() throws Exception {
        startSession(93002);
        int[] nextCommandRuns = {0};
        main(() -> {
            state.resize(task, new Rect(120, 140, 520, 560));
            state.enqueueOutput(ShellFixtureAccess.taskSession(state), () -> nextCommandRuns[0]++);
            check(state.isBusy() && nextCommandRuns[0] == 0,
                    "Following output command should queue behind the pending resize");
            backend.removeNative(task.identity());
            backend.answerNextRead(null);
            check(backend.pendingOperations() == 1
                            && "resize".equals(backend.operation(0).action),
                    "Removed-task path did not dispatch the pending exact-identity resize check");
            check(!backend.operation(0).nativeIdentityPresent,
                    "Fake backend did not model the task disappearing before native dispatch");
            showedError = true;
            backend.answerOperation(0, "Task closed before resize dispatch");
            check(nextCommandRuns[0] == 1,
                    "Native callback error did not release the Workspace ticket and drain its queue");
            check(!state.isBusy(), "Workspace ticket remained busy after removed-task callback error");
            check(backend.pendingReads() == 1,
                    "Removed-task callback error did not request a fresh snapshot");
            backend.answerNextRead(null);
            check(state.snapshot().tasks.isEmpty(),
                    "Fresh snapshot after removed-task error did not clear the stale task ghost");
            check(!state.isBusy(), "Workspace did not become eligible for the next launch after error recovery");
        });
        closeSession();
    }

    private void closeSession() {
        if (state == null) return;
        main(() -> {
            if (lease != null) lease.close();
            state.closeOwner();
            if (backend != null) backend.discardPending();
        });
        state = null;
        lease = null;
        backend = null;
    }

    private void cleanup() {
        Throwable[] failure = {null};
        try { closeSession(); } catch (Throwable error) { failure[0] = error; }
        if (showedError) {
            try { waitForErrorToast(); }
            catch (Throwable error) { if (failure[0] == null) failure[0] = error; }
        }
        if (display != null) {
            try { main(() -> { display.release(); display = null; }); }
            catch (Throwable error) { if (failure[0] == null) failure[0] = error; }
        }
        if (reader != null) {
            try { main(() -> { reader.close(); reader = null; }); }
            catch (Throwable error) { if (failure[0] == null) failure[0] = error; }
        }
        if (displayId > 0) WorkArea.remove(displayId);
        try {
            context.getSharedPreferences("desktop", Context.MODE_PRIVATE).edit().clear().commit();
            context.shellSettings().close();
            actual.deleteSharedPreferences(context.prefix + "desktop");
        } catch (Throwable error) { if (failure[0] == null) failure[0] = error; }
        test.waitForIdleSync();
        if (failure[0] != null) throw new AssertionError("Task-session fixture cleanup failed", failure[0]);
    }

    private void waitForErrorToast() throws InterruptedException {
        check(Looper.myLooper() != Looper.getMainLooper(), "Error-toast wait must not block main");
        AccessibilityManager access = actual.getSystemService(AccessibilityManager.class);
        int timeout = access == null ? 3500 : access.getRecommendedTimeoutMillis(3500,
                AccessibilityManager.FLAG_CONTENT_TEXT);
        Thread.sleep(Math.max(3500, timeout) + 500L);
        test.waitForIdleSync();
    }

    private static JSONObject jsonTask(TaskSnapshot.Task task) throws Exception {
        return new JSONObject().put("id", task.id).put("component", task.component)
                .put("mode", task.mode).put("visible", task.visible).put("focused", task.focused)
                .put("alwaysOnTop", task.alwaysOnTop).put("left", task.left())
                .put("top", task.top()).put("right", task.right()).put("bottom", task.bottom());
    }

    private static final class PendingRead {
        final int displayId;
        final Bridge.Reply reply;
        PendingRead(int displayId, Bridge.Reply reply) { this.displayId = displayId; this.reply = reply; }
    }

    private static final class PendingOperation {
        final int displayId, taskId;
        final String component, action;
        final Rect bounds;
        final boolean nativeIdentityPresent;
        final Bridge.Reply reply;
        PendingOperation(int displayId, int taskId, String component, String action,
                Rect bounds, boolean nativeIdentityPresent, Bridge.Reply reply) {
            this.displayId = displayId;
            this.taskId = taskId;
            this.component = component;
            this.action = action;
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.nativeIdentityPresent = nativeIdentityPresent;
            this.reply = reply;
        }
    }

    private final class FakeBackend implements TaskSession.Backend {
        private final Map<Integer, TaskSnapshot.Task> nativeTasks = new LinkedHashMap<>();
        private final List<PendingRead> reads = new ArrayList<>();
        private final List<PendingOperation> operations = new ArrayList<>();

        @Override public boolean ready() { return true; }
        @Override public void readSnapshot(int targetDisplay, Bridge.Reply reply) {
            reads.add(new PendingRead(targetDisplay, reply));
        }
        @Override public void taskOperation(int targetDisplay, int id, String component,
                String action, Rect bounds, boolean syncWorkArea, Bridge.Reply reply) {
            TaskSnapshot.Task nativeTask = nativeTasks.get(id);
            boolean exact = nativeTask != null && component.equals(nativeTask.component);
            operations.add(new PendingOperation(targetDisplay, id, component, action,
                    bounds, exact, reply));
        }
        void addNative(TaskSnapshot.Task value) { nativeTasks.put(value.id, value); }
        void removeNative(TaskSnapshot.Identity identity) {
            TaskSnapshot.Task value = nativeTasks.get(identity.id);
            if (value != null && value.component.equals(identity.component)) nativeTasks.remove(identity.id);
        }
        int pendingReads() { return reads.size(); }
        int pendingOperations() { return operations.size(); }
        PendingOperation operation(int index) { return operations.get(index); }
        void answerNextRead(String error) {
            check(!reads.isEmpty(), "No fake task snapshot reply is pending");
            PendingRead request = reads.remove(0);
            request.reply.done(error == null ? snapshot(request.displayId) : null, error);
        }
        void answerOperation(int index, String error) {
            check(index >= 0 && index < operations.size(), "No fake task operation is pending");
            PendingOperation operation = operations.remove(index);
            check(error != null || operation.nativeIdentityPresent,
                    "Fake backend must reject a removed or component-mismatched native identity");
            if (error == null) apply(operation);
            operation.reply.done(error == null ? snapshot(operation.displayId) : null, error);
        }
        void discardPending() { reads.clear(); operations.clear(); }
        private void apply(PendingOperation operation) {
            TaskSnapshot.Task old = nativeTasks.get(operation.taskId);
            if (old == null || !old.component.equals(operation.component))
                throw new AssertionError("Exact fake task identity vanished before operation completion");
            Rect bounds = operation.bounds == null ? old.bounds().toRect() : operation.bounds;
            boolean focused = "focus".equals(operation.action) || old.focused;
            nativeTasks.put(old.id, new TaskSnapshot.Task(old.id, old.component, old.mode,
                    old.visible, focused, old.alwaysOnTop, bounds.left, bounds.top,
                    bounds.right, bounds.bottom));
        }
        private String snapshot(int targetDisplay) {
            try {
                JSONArray rows = new JSONArray();
                for (TaskSnapshot.Task value : nativeTasks.values()) rows.put(jsonTask(value));
                JSONObject caps = new JSONObject().put("stackOrder", true).put("alwaysOnTop", true)
                        .put("bounds", true).put("windowingMode", true).put("reorder", true);
                return new JSONObject().put("backend", "TaskSessionDeadlockChecks")
                        .put("display", targetDisplay).put("capabilities", caps)
                        .put("operations", new JSONArray()).put("tasks", rows)
                        .put("stack", new JSONArray(rows.toString())).toString();
            } catch (Exception error) { throw new AssertionError(error); }
        }
    }

    private static final class FixtureContext extends ContextWrapper implements ShellSettings.Provider {
        final String prefix;
        private ShellSettings settings;
        FixtureContext(Context base, String prefix) { super(base); this.prefix = prefix; }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getBaseContext().getSharedPreferences(prefix + name, mode);
        }
        @Override public ShellSettings shellSettings() {
            if (settings == null) settings = ShellSettings.isolated(getSharedPreferences("desktop", MODE_PRIVATE));
            return settings;
        }
    }
}
