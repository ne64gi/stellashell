package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Internal selected-display poller. TaskState owns its lifetime and published read model. */
final class TaskSession {
    private final TaskState owner;
    private final Context context;
    private final int displayId;
    private final long outputEpoch;
    private final Runnable changed;
    private final BooleanSupplier shellInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<TaskSnapshot.Task> tasks = new ArrayList<>();
    private final List<TaskSnapshot.Task> stack = new ArrayList<>();
    private boolean stackReliable;
    private boolean closed, busy, canArrange, canPin;
    private String lastDiagnostic = "";
    private long snapshotGeneration;
    private WorkArea previousArea;
    private final Map<TaskSnapshot.Identity, Rect> reflow = new HashMap<>();
    private final Map<TaskSnapshot.Identity, Rect> beforeIme = new HashMap<>(), imeAdjusted = new HashMap<>();
    private TaskSnapshot.Task dragTask;
    private Rect pendingBounds;
    private boolean resizeQueued;
    private long resizeOperation = -1;
    private final Set<TaskSnapshot.Identity> normalized = new HashSet<>();

    TaskSession(TaskState owner, Context context, int displayId, long outputEpoch,
            Runnable changed, BooleanSupplier shellInput) {
        this.owner = owner;
        this.context = context;
        this.displayId = displayId;
        this.outputEpoch = outputEpoch;
        this.changed = changed;
        this.shellInput = shellInput;
        previousArea = WorkArea.get(context, displayId);
    }

    void start() { if (!closed) handler.post(poll); }
    int displayId() { return displayId; }
    long outputEpoch() { return outputEpoch; }
    boolean belongsTo(TaskState state) { return owner == state; }
    List<TaskSnapshot.Task> tasks() { return new ArrayList<>(tasks); }
    List<TaskSnapshot.Task> stack() { return new ArrayList<>(stack); }
    boolean stackReliable() { return stackReliable; }
    boolean canArrange() { return canArrange && Bridge.get(context).ready(); }
    boolean canPin() { return canPin && canArrange(); }
    boolean isClosed() { return closed; }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            int visible = 0;
            for (TaskSnapshot.Task task : tasks) if (task.visible) visible++;
            if (!closed) handler.postDelayed(this, stackReliable && visible > 1 ? 300 : 1100);
        }
    };

    void refresh() {
        if (closed || busy || !owner.isCurrent(this) || owner.isBusy() || Launches.pending()) return;
        if (!Bridge.get(context).ready()) {
            if (!tasks.isEmpty() || canArrange || canPin) {
                tasks.clear();
                stack.clear();
                stackReliable = false;
                canArrange = false;
                canPin = false;
                publish();
            }
            return;
        }
        busy = true;
        Bridge.get(context).call(service -> service.taskSnapshot(displayId), (result, error) -> {
            busy = false;
            if (closed || !owner.isCurrent(this) || owner.isBusy() || Launches.pending()) return;
            try {
                if (error != null) throw new IllegalStateException(error);
                JSONObject data = new JSONObject(result);
                JSONObject caps = data.getJSONObject("capabilities");
                List<TaskSnapshot.Task> observed = decode(data.getJSONArray("tasks"));
                owner.observe(context, displayId, observed);
                if (owner.isBusy()) return; // A just-started role change owns the transition.

                Set<TaskSnapshot.Identity> live = new HashSet<>();
                for (TaskSnapshot.Task task : observed) live.add(task.identity());
                normalized.retainAll(live);
                reflow.keySet().retainAll(live);
                beforeIme.keySet().retainAll(live);
                imeAdjusted.keySet().retainAll(live);

                tasks.clear();
                for (TaskSnapshot.Task task : observed) {
                    if (!WorkspaceProfile.standard(context, displayId)
                            || owner.owns(task) && task.mode == 5) tasks.add(task);
                }
                stack.clear();
                JSONArray layers = data.optJSONArray("stack");
                if (layers != null) {
                    List<TaskSnapshot.Task> observedStack = decode(layers);
                    for (TaskSnapshot.Task task : observedStack) {
                        if (!WorkspaceProfile.standard(context, displayId)
                                || owner.owns(task) && task.mode == 5) stack.add(task);
                    }
                }
                stackReliable = caps.optBoolean("stackOrder");
                canPin = caps.optBoolean("alwaysOnTop");
                canArrange = caps.optBoolean("bounds") && caps.optBoolean("windowingMode")
                        && caps.optBoolean("reorder");
                diagnostic(data.getString("backend") + " " + caps + " boundsDispatch="
                        + data.optString("boundsDispatch", "unknown") + "\n"
                        + data.getJSONArray("operations"));
            } catch (Exception failure) {
                tasks.clear();
                stack.clear();
                stackReliable = false;
                canArrange = false;
                canPin = false;
                diagnostic(failure.toString());
            }
            for (TaskSnapshot.Task task : tasks) {
                if (task.focused && task.visible) {
                    Profiles.observe(context, task, displayId);
                    break;
                }
            }
            publish();
            try {
                if (canArrange && !shellInput.getAsBoolean()) {
                    for (TaskSnapshot.Task task : tasks) {
                        if (!task.focused || !task.visible || task.mode != 5
                                || !normalized.add(task.identity())) continue;
                        Rect requested = reflow.remove(task.identity());
                        Rect taskBounds = task.bounds().toRect();
                        Rect safe = WorkArea.get(context, displayId)
                                .clamp(requested == null ? taskBounds : requested);
                        if (!safe.equals(taskBounds)) {
                            resize(task, safe);
                            break;
                        }
                    }
                }
            } catch (RuntimeException failure) {
                diagnostic(failure.toString());
            }
            flushDrag();
        });
    }

    private List<TaskSnapshot.Task> decode(JSONArray rows) throws JSONException {
        List<TaskSnapshot.Task> decoded = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) decoded.add(new TaskSnapshot.Task(rows.getJSONObject(i)));
        return decoded;
    }

    private void publish() {
        snapshotGeneration++;
        owner.publish(this, new TaskSnapshot(snapshotGeneration, outputEpoch, displayId,
                tasks, stack, stackReliable, canArrange, canPin));
        changed.run();
    }

    private void diagnostic(String value) {
        if (!value.equals(lastDiagnostic)) {
            lastDiagnostic = value;
            Launches.prefs(context).edit().putString("task_diagnostics", value).apply();
        }
    }

    void areaChanged() {
        if (closed || !owner.isCurrent(this)) return;
        WorkArea next = WorkArea.get(context, displayId), old = previousArea;
        previousArea = next;
        for (TaskSnapshot.Task task : tasks) if (task.mode == 5) {
            TaskSnapshot.Bounds value = task.bounds();
            Rect bounds = value.toRect();
            Rect wanted = new Rect(bounds);
            if (next.imeVisible && !old.imeVisible) beforeIme.put(task.identity(), bounds);
            if (!next.imeVisible && old.imeVisible) {
                Rect adjusted = imeAdjusted.remove(task.identity());
                Rect original = beforeIme.remove(task.identity());
                if (adjusted != null && original != null && adjusted.equals(bounds)) wanted = original;
                else if (old.maximized(bounds)) wanted = new Rect(next.content);
            } else if (old.maximized(bounds)) wanted = new Rect(next.content);
            else if (bounds.top == old.content.top && bounds.bottom == old.content.bottom) {
                if (bounds.left == old.content.left && bounds.right == old.content.centerX())
                    wanted = new Rect(next.content.left, next.content.top, next.content.centerX(), next.content.bottom);
                else if (bounds.left == old.content.centerX() && bounds.right == old.content.right)
                    wanted = new Rect(next.content.centerX(), next.content.top, next.content.right, next.content.bottom);
            }
            wanted = next.clamp(wanted);
            reflow.put(task.identity(), wanted);
            if (next.imeVisible) imeAdjusted.put(task.identity(), new Rect(wanted));
        }
        normalized.clear();
        refresh();
    }

    void action(TaskSnapshot.Task requested, String action, Runnable completed) {
        TaskSnapshot.Task live = current(requested.identity());
        if (live == null) {
            try { refresh(); } finally { completed.run(); }
            return;
        }
        String command;
        if ("togglePin".equals(action)) command = live.alwaysOnTop ? "unpin" : "pin";
        else if ("toggleMaximize".equals(action))
            command = WorkArea.get(context, displayId).maximized(live.bounds().toRect())
                    ? "restore" : "maximize";
        else command = action;
        long workspaceEpoch = owner.session();
        Bridge.get(context).call(service -> {
            WorkArea.get(context, displayId).sync(service, displayId);
            String answer = service.checkedTaskOperation(displayId, live.id, live.component,
                    command, 0, 0, 0, 0);
            if (answer == null || answer.startsWith("ERROR:")) throw new IllegalStateException(answer);
            // Publish only post-operation state, never the pre-command snapshot as success.
            return service.taskSnapshot(displayId);
        }, (result, error) -> {
            try {
                if (error == null) {
                    Profiles.rememberSnapshot(context, result, live.id, displayId);
                    if ("close".equals(command) && !contains(result, live.identity()))
                        owner.outputTaskClosed(live.identity(), workspaceEpoch);
                    else if ("focus".equals(command) && focused(result, live)
                            && owner.currentSession(workspaceEpoch))
                        owner.focused(context, focusedTask(result, live.identity()), displayId);
                } else if (!closed && owner.isCurrent(this)) Launches.problem(context, error);
            } finally {
                completed.run();
            }
            if (closed || !owner.isCurrent(this)) return;
            refresh();
        });
    }

    private TaskSnapshot.Task current(TaskSnapshot.Identity identity) {
        if (closed || !owner.isCurrent(this)) return null;
        return owner.currentTask(this, identity);
    }

    private static boolean focused(String result, TaskSnapshot.Task requested) {
        TaskSnapshot.Task actual = focusedTask(result, requested.identity());
        return actual != null && actual.focused;
    }

    private static TaskSnapshot.Task focusedTask(String result, TaskSnapshot.Identity identity) {
        try {
            for (TaskSnapshot.Task actual : decodeSnapshot(new JSONObject(result).getJSONArray("tasks")))
                if (actual.identity().equals(identity)) return actual;
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean contains(String result, TaskSnapshot.Identity identity) {
        try {
            for (TaskSnapshot.Task actual : decodeSnapshot(new JSONObject(result).getJSONArray("tasks")))
                if (actual.identity().equals(identity)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private static List<TaskSnapshot.Task> decodeSnapshot(JSONArray rows) throws JSONException {
        List<TaskSnapshot.Task> decoded = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) decoded.add(new TaskSnapshot.Task(rows.getJSONObject(i)));
        return decoded;
    }

    void resize(TaskSnapshot.Task task, Rect bounds) {
        TaskSnapshot.Task live = current(task.identity());
        if (live == null || bounds == null || bounds.isEmpty()) return;
        dragTask = live;
        pendingBounds = new Rect(bounds);
        if (resizeOperation >= 0) { flushDrag(); return; }
        if (resizeQueued) return;
        resizeQueued = true;
        owner.enqueueOutput(this, () -> {
            resizeQueued = false;
            if (closed || !owner.isCurrent(this) || pendingBounds == null) return;
            resizeOperation = owner.beginOutputCommand();
            flushDrag();
        });
    }

    void focusForDrag(TaskSnapshot.Task task, java.util.function.Consumer<Boolean> reply) {
        TaskSnapshot.Task live = current(task.identity());
        if (live == null) {
            reply.accept(false);
            return;
        }
        long workspaceEpoch = owner.session();
        Bridge.get(context).call(service -> {
            String answer = service.checkedTaskOperation(displayId, live.id, live.component,
                    "focus", 0, 0, 0, 0);
            if (answer == null || answer.startsWith("ERROR:")) throw new IllegalStateException(answer);
            return service.taskSnapshot(displayId);
        }, (result, error) -> {
            TaskSnapshot.Task actual = null;
            if (!closed && owner.isCurrent(this) && error == null) {
                actual = focusedTask(result, live.identity());
                if (actual != null && actual.focused && owner.currentSession(workspaceEpoch))
                    owner.focused(context, actual, displayId);
            }
            if (error != null && !closed && owner.isCurrent(this)) Launches.problem(context, error);
            reply.accept(actual != null && actual.focused);
            refresh();
        });
    }

    private void flushDrag() {
        if (closed || busy || pendingBounds == null || !owner.isCurrent(this)
                || (resizeOperation < 0 && owner.isBusy())
                || (resizeOperation < 0 && Launches.pending())) return;
        Rect bounds = new Rect(pendingBounds);
        TaskSnapshot.Task requested = dragTask;
        pendingBounds = null;
        TaskSnapshot.Task live = requested == null ? null : current(requested.identity());
        if (live == null) {
            finishResizeOperation();
            return;
        }
        final TaskSnapshot.Task task = live;
        busy = true;
        Bridge.get(context).call(service -> {
            WorkArea.get(context, displayId).sync(service, displayId);
            String answer = service.checkedTaskOperation(displayId, task.id, task.component,
                    "resize", bounds.left, bounds.top, bounds.right, bounds.bottom);
            if (answer == null || answer.startsWith("ERROR:")) throw new IllegalStateException(answer);
            return service.taskSnapshot(displayId);
        }, (result, error) -> {
            busy = false;
            if (closed || !owner.isCurrent(this)) {
                finishResizeOperation();
                return;
            }
            if (error != null) {
                pendingBounds = null;
                Launches.problem(context, error);
                finishResizeOperation();
                refresh();
            } else if (pendingBounds != null) flushDrag();
            else {
                finishResizeOperation();
                refresh();
            }
        });
    }

    private void finishResizeOperation() {
        long ticket = resizeOperation;
        resizeOperation = -1;
        if (ticket >= 0) owner.finishOutputCommand(ticket);
    }

    void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        pendingBounds = null;
        finishResizeOperation();
        tasks.clear();
        stack.clear();
    }
}
