package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Sole process owner for task snapshots, task ownership/roles, and task commands. */
final class TaskState {
    private static volatile TaskState shared;

    interface Provider { TaskState taskState(); }

    static TaskState of(Context context) {
        if (context instanceof Provider) return Objects.requireNonNull(((Provider) context).taskState());
        Context app = context.getApplicationContext();
        if (app instanceof Provider) return Objects.requireNonNull(((Provider) app).taskState());
        TaskState current = shared;
        if (current != null) return current;
        synchronized (TaskState.class) {
            if (shared == null) shared = new TaskState(app);
            return shared;
        }
    }

    /** Explicit isolated owner for fixtures; callers must close it when the fixture ends. */
    static TaskState isolated(Context context) { return new TaskState(context.getApplicationContext()); }

    /** Opaque bind identity. Closing an older output can never close a newer one. */
    static final class OutputLease implements AutoCloseable {
        private final TaskState owner;
        private final TaskSession session;
        private final long epoch;

        private OutputLease(TaskState owner, TaskSession session, long epoch) {
            this.owner = owner;
            this.session = session;
            this.epoch = epoch;
        }

        @Override public void close() { owner.closeOutput(this); }
    }

    private final Context context;
    private final Workspace workspace = new Workspace();
    private TaskSession output;
    private OutputLease outputLease;
    private TaskSnapshot snapshot = TaskSnapshot.empty(0, -1);
    private long outputEpoch;
    private final Set<Runnable> routingObservers=new LinkedHashSet<>();

    private TaskState(Context context) { this.context = Objects.requireNonNull(context.getApplicationContext()); }

    void closeOwner() {
        closeOutput();
        workspace.reset();
    }

    OutputLease openOutput(Context displayContext, int displayId, Runnable changed,
            BooleanSupplier shellInput) {
        Objects.requireNonNull(displayContext, "displayContext");
        return openOutput(displayContext, displayId, changed, shellInput,
                TaskSession.bridgeBackend(displayContext.getApplicationContext()));
    }

    /** Backend seam for an isolated output fixture; production callers use the Bridge adapter. */
    OutputLease openOutput(Context displayContext, int displayId, Runnable changed,
            BooleanSupplier shellInput, TaskSession.Backend backend) {
        Objects.requireNonNull(displayContext, "displayContext");
        Objects.requireNonNull(changed, "changed");
        Objects.requireNonNull(shellInput, "shellInput");
        Objects.requireNonNull(backend, "backend");
        closeOutput();
        long epoch = ++outputEpoch;
        TaskSession session = new TaskSession(this, displayContext.getApplicationContext(), displayId,
                epoch, changed, shellInput, backend);
        OutputLease lease = new OutputLease(this, session, epoch);
        output = session;
        outputLease = lease;
        snapshot = TaskSnapshot.empty(epoch, displayId);
        session.start();
        return lease;
    }

    void closeOutput() {
        TaskSession current = output;
        output = null;
        outputLease = null;
        outputEpoch++;
        snapshot = TaskSnapshot.empty(outputEpoch, -1);
        if (current != null) current.close();
    }

    void closeOutput(OutputLease lease) {
        if (lease == null || lease.owner != this || lease != outputLease
                || lease.epoch != outputEpoch || lease.session != output) return;
        closeOutput();
    }

    TaskSnapshot snapshot() { return snapshot; }
    void refresh() { TaskSession current = output; if (current != null) current.refresh(); }
    void areaChanged() { TaskSession current = output; if (current != null) current.areaChanged(); }
    void action(TaskSnapshot.Task task, String action) {
        TaskSession current = output;
        if (current == null || snapshot.find(task.identity()) == null) return;
        workspace.enqueue(() -> {
            if (!isCurrent(current)) return;
            long ticket = workspace.beginCommand();
            TaskSnapshot.Task live = snapshot.find(task.identity());
            if (live == null) { workspace.finishCommand(ticket); return; }
            current.action(live, action, () -> workspace.finishCommand(ticket));
        });
    }
    void resize(TaskSnapshot.Task task, Rect bounds) {
        TaskSession current = output;
        if (current != null && snapshot.find(task.identity()) != null) current.resize(task, bounds);
    }
    void focusForDrag(TaskSnapshot.Task task, Consumer<Boolean> reply) {
        TaskSession current = output;
        if (current == null || snapshot.find(task.identity()) == null) { reply.accept(false); return; }
        workspace.enqueue(() -> {
            if (!isCurrent(current)) { reply.accept(false); return; }
            TaskSnapshot.Task live = snapshot.find(task.identity());
            if (live == null) { reply.accept(false); return; }
            long ticket = workspace.beginCommand();
            current.focusForDrag(live, focused -> {
                workspace.finishCommand(ticket);
                reply.accept(focused);
            });
        });
    }

    void enqueueOutput(TaskSession session, Runnable operation) {
        workspace.enqueue(() -> { if (isCurrent(session)) operation.run(); });
    }
    long beginOutputCommand() { return workspace.beginCommand(); }
    void finishOutputCommand(long ticket) { workspace.finishCommand(ticket); }

    boolean isCurrent(TaskSession session) {
        return output == session && outputEpoch == session.outputEpoch();
    }

    TaskSnapshot.Task currentTask(TaskSession session, TaskSnapshot.Identity identity) {
        if (!isCurrent(session) || snapshot.outputEpoch != outputEpoch
                || snapshot.displayId != session.displayId()) return null;
        return snapshot.find(identity);
    }

    void publish(TaskSession session, TaskSnapshot next) {
        if (!isCurrent(session) || next.outputEpoch != outputEpoch
                || next.displayId != session.displayId()) return;
        snapshot = next;
    }

    void observe(Context source, int displayId, List<TaskSnapshot.Task> observed) {
        workspace.observe(source, displayId, observed, enabled(source), target(source));
    }

    boolean isBusy() { return workspace.isBusy(); }
    AutoCloseable whenIdle(Runnable callback) { return workspace.whenIdle(callback); }
    AutoCloseable observeRouting(Runnable observer) {
        routingObservers.add(observer);
        return () -> routingObservers.remove(observer);
    }
    private void routingChanged() {
        for (Runnable observer : new java.util.ArrayList<>(routingObservers)) observer.run();
    }
    long session() { return workspace.session(); }
    boolean currentSession(long token) { return workspace.currentSession(token); }
    boolean owns(TaskSnapshot.Task task) { return task != null && workspace.owns(task); }
    int primary() { return workspace.primary(); }
    String label(Context source, TaskSnapshot.Task task) { return workspace.label(source, task); }
    boolean needsPrimary() { return workspace.needsPrimary(); }
    boolean secondaryLaunch(String requested, String resolved, boolean newWindow) {
        return workspace.secondaryLaunch(requested, resolved, newWindow);
    }
    boolean compact(Context source, int displayId) { return workspace.compact(source, displayId); }
    void desktopShown(Context source, int displayId) { workspace.desktopShown(source, displayId); }
    void focused(Context source, TaskSnapshot.Task task, int displayId) {
        if (task != null) workspace.focused(source, task, displayId);
    }
    void launched(Context source, JSONObject data, int displayId, boolean floating, Runnable done)
            throws JSONException {
        workspace.launched(source, data, displayId, floating, enabled(source), done);
    }
    void launched(Context source, JSONObject data, int displayId, boolean floating)
            throws JSONException {
        launched(source, data, displayId, floating, () -> {});
    }
    void role(Context source, TaskSnapshot.Task task, int displayId, boolean promote, Runnable done) {
        if (task == null) { done.run(); return; }
        workspace.role(source, task.identity(), displayId, promote, done);
    }
    void role(Context source, TaskSnapshot.Task task, int displayId, boolean promote) {
        role(source, task, displayId, promote, () -> {});
    }
    void transfer(Context source, int destination, Runnable done) {
        if (!enabled(source)) { done.run(); return; }
        workspace.transfer(source, () -> target(source), () -> enabled(source),
                destination, this::persistTarget, done);
    }
    /** A delayed loss recovery cannot redirect a newer session or a newly selected live output. */
    void recoverDisconnectedOutput(Context source, int removedTarget, long expectedSession,
            BooleanSupplier permitted, Runnable done) {
        workspace.transfer(source, () -> target(source),
                () -> currentSession(expectedSession) && enabled(source) && target(source) == removedTarget
                        && !Displays.ids(source).contains(removedTarget) && permitted.getAsBoolean(),
                0, this::persistTarget, done);
    }

    /** Minimizes only exact live identities from this same pre-operation snapshot. */
    void minimizeVisible(IDesktopBridge service, int displayId) throws Exception {
        org.json.JSONArray rows = new JSONObject(service.taskSnapshot(displayId)).getJSONArray("tasks");
        for (int i = 0; i < rows.length(); i++) {
            TaskSnapshot.Task task = new TaskSnapshot.Task(rows.getJSONObject(i));
            if (!task.visible) continue;
            String answer = service.checkedTaskOperation(displayId, task.id, task.component,
                    "minimize", 0, 0, 0, 0);
            if (answer == null || answer.startsWith("ERROR:")) throw new IllegalStateException(answer);
        }
    }
    void reset(Context source) {
        workspace.reset();
        Launches.prefs(source).edit().remove("workspace_display").apply();
        routingChanged();
    }

    /** Display-0 operations reconcile workspace ownership only after verified OS success. */
    void phoneTaskCompleted(Context source, TaskSnapshot.Task actual, long workspaceEpoch,
            String action) {
        if (actual == null || !currentSession(workspaceEpoch) || !ShellRuntime.enabled(source)
                || ShellRuntime.selectedDisplay() != 0) return;
        if ("float".equals(action)) workspace.adoptFloating(actual, workspaceEpoch);
        else if ("fullscreen".equals(action)) workspace.forgetFloating(actual, workspaceEpoch);
    }

    void phoneTaskClosed(TaskSnapshot.Identity identity, long workspaceEpoch) {
        workspace.forgetClosedTask(identity, workspaceEpoch);
    }

    void outputTaskClosed(TaskSnapshot.Identity identity, long workspaceEpoch) {
        workspace.forgetClosedTask(identity, workspaceEpoch);
    }

    void focusPhoneTask(TaskSnapshot.Task task, Bridge.Reply reply) {
        Bridge.get(context).call(service -> service.focusPhoneTask(task.id, task.component), reply);
    }

    /** Phone-feed commands also pass through the task owner; the feed itself stays read-only. */
    void phoneOperation(Context source, TaskSnapshot.Task task, String action, Rect bounds,
            Bridge.Reply reply) {
        long workspaceEpoch = session();
        Bridge.get(source).call(service -> {
            if (!currentSession(workspaceEpoch)) throw new IllegalStateException("Workspace session ended");
            String result = service.phoneTaskOperation(task.id, task.component, action,
                    bounds.left, bounds.top, bounds.right, bounds.bottom);
            if ("close".equals(action)) {
                JSONArray rows = new JSONObject(service.phoneTaskSnapshot()).getJSONArray("tasks");
                for (int i = 0; i < rows.length(); i++) {
                    TaskSnapshot.Task live = new TaskSnapshot.Task(rows.getJSONObject(i));
                    if (live.identity().equals(task.identity()))
                        throw new IllegalStateException("The selected task is still running");
                }
            }
            return result;
        }, (result,error)->{
            if(error==null)try{
                if("float".equals(action)||"fullscreen".equals(action)){
                    TaskSnapshot.Task actual=PhoneRunningTasks.verifiedTask(result,task,
                            "float".equals(action)?5:1);
                    phoneTaskCompleted(source,actual,workspaceEpoch,action);
                }else if("close".equals(action))phoneTaskClosed(task.identity(),workspaceEpoch);
            }catch(Exception failure){reply.done(null,failure.toString());return;}
            reply.done(result,error);
        });
    }

    boolean enabled(Context source) {
        return Displays.primary(source) && (WorkspaceProfile.standard(source, 0)
                || ShellSettings.of(source).snapshot().compactWorkspace);
    }

    int target(Context source) {
        if (!enabled(source)) return 0;
        return Math.max(0, savedTarget(source));
    }

    /** Saved target distinct from the effective output; -1 means no explicit value exists. */
    int savedTarget(Context source) {
        return Launches.prefs(source).contains("workspace_display")
                ? Launches.prefs(source).getInt("workspace_display", -1) : -1;
    }

    /** Initializes a missing saved target only; never overwrites a user's selection. */
    void initializeTarget(Context source, int displayId) {
        if (displayId < 0 || Launches.prefs(source).contains("workspace_display")) return;
        persistTarget(displayId);
    }

    private void persistTarget(int displayId) {
        if (displayId < 0) return;
        Launches.prefs(context).edit().putInt("workspace_display", displayId).apply();
        routingChanged();
    }
}
