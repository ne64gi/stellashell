package net.fuyumori.stellashell;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

/** Bounded event regressions; only the preflight-absent test Activity may be launched/closed. */
final class IdleEventChecks {
    private final Instrumentation test;
    IdleEventChecks(Instrumentation test) { this.test = test; }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private void main(Runnable action) {
        Throwable[] failure = {null};
        test.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    private void pause(long milliseconds) throws InterruptedException {
        check(Looper.myLooper() != Looper.getMainLooper(), "Idle wait must not block main");
        Thread.sleep(milliseconds);
        test.waitForIdleSync();
    }
    void run() throws Exception {
        check(Looper.myLooper() != Looper.getMainLooper(), "Run event checks off main");
        sameState();
        syntheticFeed();
        busyFailure();
        installedObserver();
    }

    private static final class FeedBackend implements PhoneRunningTasks.Backend {
        boolean awake = true;
        int requests, observed, removed;
        Runnable observer;
        Bridge.Reply pending;
        public boolean ready() { return true; }
        public boolean awake() { return awake; }
        public void observe(Runnable changed) {
            check(observer == null, "Duplicate feed observer");
            observer = changed; observed++;
        }
        public void remove(Runnable changed) {
            check(observer == changed, "Wrong feed observer removed");
            observer = null; removed++;
        }
        public void snapshot(Bridge.Reply reply) {
            check(pending == null, "Overlapping event snapshots");
            pending = reply; requests++;
        }
        public void focus(TaskSnapshot.Task task, Bridge.Reply reply) {
            throw new AssertionError("Idle check must not focus tasks");
        }
        public void operation(TaskSnapshot.Task task, String action, Rect bounds, Bridge.Reply reply) {
            throw new AssertionError("Synthetic idle check must not operate tasks");
        }
        void event() { check(observer != null, "Missing event observer"); observer.run(); }
        void answer(String result) {
            answer(result, null);
        }
        void answer(String result, String error) {
            check(pending != null, "Missing pending event snapshot");
            Bridge.Reply reply = pending; pending = null; reply.done(result, error);
        }
    }
    private void busyFailure() throws Exception {
        FeedBackend backend = new FeedBackend();
        PhoneRunningTasks[] feed = {null};
        String snapshot = phoneSnapshot(91004);
        try {
            main(() -> { feed[0] = new PhoneRunningTasks(backend, () -> {}); feed[0].start(); });
            test.waitForIdleSync();
            main(() -> {
                backend.answer(snapshot); backend.event(); backend.event();
                backend.answer(null, "synthetic transient read failure");
                check(feed[0].state() == PhoneRunningTasks.State.ERROR, "Read failure not distinguished");
            });
            test.waitForIdleSync();
            main(() -> {
                check(backend.requests == 3, "Busy invalidation was lost on failed read");
                backend.answer(snapshot);
                check(feed[0].state() == PhoneRunningTasks.State.READY, "Event follow-up did not recover");
            });
            pause(1400);
            main(() -> check(backend.requests == 3, "Read failure enabled a recurring retry"));
        } finally { main(() -> { if (feed[0] != null) feed[0].close(); }); }
    }
    private static String phoneSnapshot(int id) throws Exception {
        JSONObject task = new JSONObject().put("id", id).put("component", "fixture.idle/.Main")
                .put("mode", 1).put("visible", true).put("focused", true)
                .put("left", 0).put("top", 0).put("right", 360).put("bottom", 720);
        return new JSONObject().put("tasks", new JSONArray().put(task)).toString();
    }
    private void syntheticFeed() throws Exception {
        FeedBackend backend = new FeedBackend();
        int[] publications = {0};
        PhoneRunningTasks[] feed = {null};
        String first = phoneSnapshot(91001), next = phoneSnapshot(91002), late = phoneSnapshot(91003);
        try {
            main(() -> {
                feed[0] = new PhoneRunningTasks(backend, () -> publications[0]++);
                feed[0].refresh();
                check(backend.requests == 0, "Hidden feed queried tasks");
                feed[0].start(); feed[0].start();
            });
            test.waitForIdleSync();
            main(() -> {
                check(backend.requests == 1 && backend.observed == 1, "Initial read is not once-only");
                backend.answer(first);
                check(publications[0] == 1 && feed[0].state() == PhoneRunningTasks.State.READY,
                        "Initial read did not publish");
            });
            // Exceeds the former 1100 ms loop with the main Looper free to run it.
            pause(1400);
            main(() -> {
                check(backend.requests == 1 && publications[0] == 1, "Idle feed still polls/redraws");
                backend.event();
                check(backend.requests == 2, "Observer event did not request a fresh snapshot");
                backend.answer(first);
                check(publications[0] == 1, "Identical event result rebuilt the feed");
            });
            test.waitForIdleSync();
            main(() -> {
                check(backend.requests == 2, "One event made multiple completed reads");
                backend.event();
                backend.event(); backend.event(); backend.event();
                check(backend.requests == 3, "Busy events started overlapping reads");
                backend.answer(first);
            });
            test.waitForIdleSync();
            main(() -> {
                check(backend.requests == 4, "Busy invalidations were lost or not coalesced");
                backend.answer(first);
            });
            test.waitForIdleSync();
            main(() -> {
                check(backend.requests == 4, "Busy event burst scheduled more than one follow-up");
                backend.event();
                check(backend.requests == 5, "Pre-sleep event was lost");
                backend.awake = false;
                backend.answer(next);
                backend.event(); backend.event();
                check(backend.requests == 5 && publications[0] == 1
                                && feed[0].tasks().get(0).id == 91001,
                        "Sleeping feed queried or published a late snapshot");
            });
            pause(1400);
            main(() -> {
                check(backend.requests == 5 && publications[0] == 1, "Sleeping feed still polls");
                backend.awake = true; backend.event();
                check(backend.requests == 6, "Wake/unlock event did not read fresh state");
                backend.answer(next);
                check(publications[0] == 2 && feed[0].tasks().get(0).id == 91002,
                        "Wake event did not publish fresh tasks");
                backend.event();
                Runnable obsolete = backend.observer;
                feed[0].stop();
                int stopped = publications[0];
                backend.answer(late); obsolete.run();
                check(backend.requests == 7 && publications[0] == stopped
                                && feed[0].tasks().isEmpty() && backend.removed == 1,
                        "Stopped feed was resurrected by a late result/event");
                feed[0].start();
            });
            test.waitForIdleSync();
            main(() -> {
                check(backend.requests == 8 && backend.observed == 2, "Reopened feed did not read once");
                backend.answer(next); backend.event();
                Runnable obsolete = backend.observer;
                feed[0].close();
                int closed = publications[0];
                backend.answer(late); obsolete.run(); feed[0].start(); feed[0].refresh();
                check(backend.requests == 9 && publications[0] == closed
                                && feed[0].tasks().isEmpty() && backend.removed == 2,
                        "Closed feed was resurrected by a late result/event");
            });
            test.waitForIdleSync();
            main(() -> check(backend.requests == 9 && backend.pending == null,
                    "Disposed feed retained a queued refresh"));
        } finally {
            main(() -> { if (feed[0] != null) feed[0].close(); });
        }
    }

    private static TaskSnapshot.Task task(int id, String component, int mode, boolean visible,
            boolean focused, boolean pin, int left, int top, int right, int bottom) {
        return new TaskSnapshot.Task(id, component, mode, visible, focused, pin, left, top, right, bottom);
    }
    private static TaskSnapshot state(long generation, List<TaskSnapshot.Task> tasks,
            List<TaskSnapshot.Task> stack) {
        return new TaskSnapshot(generation, 17, 0, tasks, stack, true, true, true);
    }
    private static void sameState() {
        TaskSnapshot.Task first = task(92001, "fixture.idle/.First", 5, true, true, false, 1, 2, 301, 402);
        TaskSnapshot.Task second = task(92002, "fixture.idle/.Second", 1, false, false, true, 3, 4, 503, 604);
        List<TaskSnapshot.Task> tasks = Arrays.asList(first, second);
        TaskSnapshot baseline = state(1, tasks, tasks);
        check(baseline.sameState(state(999, new ArrayList<>(tasks), new ArrayList<>(tasks))),
                "Delivery generation caused an unchanged-state redraw");
        TaskSnapshot.Task[] variants = {
            task(93001, first.component, 5, true, true, false, 1, 2, 301, 402),
            task(first.id, "fixture.idle/.Changed", 5, true, true, false, 1, 2, 301, 402),
            task(first.id, first.component, 1, true, true, false, 1, 2, 301, 402),
            task(first.id, first.component, 5, false, true, false, 1, 2, 301, 402),
            task(first.id, first.component, 5, true, false, false, 1, 2, 301, 402),
            task(first.id, first.component, 5, true, true, true, 1, 2, 301, 402),
            task(first.id, first.component, 5, true, true, false, 11, 2, 301, 402),
            task(first.id, first.component, 5, true, true, false, 1, 12, 301, 402),
            task(first.id, first.component, 5, true, true, false, 1, 2, 311, 402),
            task(first.id, first.component, 5, true, true, false, 1, 2, 301, 412)
        };
        for (int i = 0; i < variants.length; i++) {
            check(!baseline.sameState(state(1, Arrays.asList(variants[i], second), tasks)),
                    "Task state change ignored at dimension " + i);
            check(!baseline.sameState(state(1, tasks, Arrays.asList(variants[i], second))),
                    "Ordered-stack state change ignored at dimension " + i);
        }
        check(!baseline.sameState(state(1, Arrays.asList(second, first), tasks)), "Task order ignored");
        check(!baseline.sameState(state(1, tasks, Arrays.asList(second, first))), "Stack order ignored");
        check(!baseline.sameState(state(1, Arrays.asList(first), tasks)), "Task removal ignored");
        check(!baseline.sameState(state(1, tasks, Arrays.asList(first))), "Stack removal ignored");
        check(!baseline.sameState(new TaskSnapshot(1, 18, 0, tasks, tasks, true, true, true)), "Output epoch ignored");
        check(!baseline.sameState(new TaskSnapshot(1, 17, 1, tasks, tasks, true, true, true)), "Display identity ignored");
        check(!baseline.sameState(new TaskSnapshot(1, 17, 0, tasks, tasks, false, true, true)), "Stack reliability ignored");
        check(!baseline.sameState(new TaskSnapshot(1, 17, 0, tasks, tasks, true, false, true)), "Arrange capability ignored");
        check(!baseline.sameState(new TaskSnapshot(1, 17, 0, tasks, tasks, true, true, false)), "Pin capability ignored");
    }

    private interface Condition { boolean ready() throws Exception; }
    private void await(Condition condition, String message) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.ready()) return;
            Thread.sleep(100);
        }
        check(condition.ready(), message);
    }
    private static JSONArray phoneTasks(IDesktopBridge server) throws Exception {
        return new JSONObject(server.phoneTaskSnapshot()).getJSONArray("tasks");
    }
    private static List<Integer> fixtureIds(JSONArray tasks, String component) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject row = tasks.getJSONObject(i);
            if (component.equals(row.getString("component"))) ids.add(row.getInt("id"));
        }
        return ids;
    }
    private static boolean contains(JSONArray tasks, int id, String component) throws Exception {
        return fixtureIds(tasks, component).contains(id);
    }
    private static Throwable combine(Throwable failure, Throwable next) {
        if (failure == null) return next;
        failure.addSuppressed(next); return failure;
    }

    private void installedObserver() throws Exception {
        Context target = test.getTargetContext(), fixtures = test.getContext();
        ComponentName activity = new ComponentName(fixtures.getPackageName(), PinInputActivity.class.getName());
        String component = activity.flattenToString();
        // Same installed service identity as Bridge, but no bind/connect/maintenance call.
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(target, DesktopBridgeService.class))
                .daemon(false).processNameSuffix("desktop_bridge").debuggable(false).version(33);
        CountDownLatch connected = new CountDownLatch(1), invalidation = new CountDownLatch(1);
        IDesktopBridge[] remote = {null};
        AtomicInteger callbacks = new AtomicInteger();
        ITaskChangeListener observer = new ITaskChangeListener.Stub() {
            @Override public void onChanged() { callbacks.incrementAndGet(); invalidation.countDown(); }
        };
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote[0] = IDesktopBridge.Stub.asInterface(binder); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        boolean[] peeked = {false};
        boolean registrationAttempted = false, removed = false, launchAttempted = false;
        Set<Integer> owned = new LinkedHashSet<>();
        Throwable failure = null;
        try {
            main(() -> {
                check(Shizuku.pingBinder() && Shizuku.getVersion() >= 13
                                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
                        "Requires existing Shizuku authorization");
                peeked[0] = true;
                check(Shizuku.peekUserService(args, connection) == 38,
                        "Requires already installed event-capable Shizuku36 server");
            });
            check(connected.await(10, TimeUnit.SECONDS), "Installed task-event server unavailable");
            IDesktopBridge server = remote[0];
            check("net.fuyumori.stellashell.IDesktopBridge".equals(server.asBinder().getInterfaceDescriptor()),
                    "Task event Binder descriptor changed");
            check(fixtureIds(phoneTasks(server), component).isEmpty(),
                    "Preexisting phone fixture: refusing to launch or clean it");
            for (Display display : target.getSystemService(DisplayManager.class).getDisplays()) {
                if (display.getDisplayId() == 0 || !display.isValid()
                        || (display.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
                JSONArray tasks = new JSONObject(server.taskSnapshot(display.getDisplayId())).getJSONArray("tasks");
                check(fixtureIds(tasks, component).isEmpty(),
                        "Preexisting external fixture: refusing to launch or clean it");
            }
            registrationAttempted = true;
            check("OK".equals(server.observeTaskChanges(observer)), "Framework task observer registration failed");
            launchAttempted = true;
            main(() -> {
                Intent intent = new Intent().setComponent(activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ActivityOptions options = ActivityOptions.makeBasic(); options.setLaunchDisplayId(0);
                fixtures.startActivity(intent, options.toBundle());
            });
            await(() -> !fixtureIds(phoneTasks(server), component).isEmpty(), "Owned event fixture was not created");
            owned.addAll(fixtureIds(phoneTasks(server), component));
            check(owned.size() == 1, "Fixture launch did not create one exact owned task");
            check(invalidation.await(10, TimeUnit.SECONDS), "Real framework launch produced no invalidation");
            server.removeTaskObserver(observer); removed = true;
            // Drain callbacks already sent before removal, then make a real framework close event.
            pause(600);
            int afterRemoval = callbacks.get();
            for (int id : owned) {
                if (contains(phoneTasks(server), id, component)) {
                    check("OK".equals(server.phoneTaskOperation(id, component, "close", 0, 0, 0, 0)),
                            "Owned task close failed");
                }
            }
            await(() -> fixtureIds(phoneTasks(server), component).isEmpty(), "Owned event fixture did not close");
            pause(600);
            check(callbacks.get() == afterRemoval, "Removed observer received fixture-close invalidation");
        } catch (Throwable error) {
            failure = error;
        } finally {
            IDesktopBridge server = remote[0];
            if (server != null && registrationAttempted && !removed) {
                try { server.removeTaskObserver(observer); }
                catch (Throwable error) { failure = combine(failure, error); }
            }
            if (server != null && launchAttempted) {
                // Launch may succeed before a later assertion fails. Only this prechecked-absent
                // component is reclaimed, and every removal revalidates both its id and component.
                try { owned.addAll(fixtureIds(phoneTasks(server), component)); }
                catch (Throwable error) { failure = combine(failure, error); }
                for (int id : owned) {
                    try {
                        if (contains(phoneTasks(server), id, component)) {
                            check("OK".equals(server.phoneTaskOperation(id, component, "close", 0, 0, 0, 0)),
                                    "Owned event-fixture cleanup failed");
                        }
                    } catch (Throwable error) { failure = combine(failure, error); }
                }
                try { await(() -> fixtureIds(phoneTasks(server), component).isEmpty(), "Event fixture cleanup left a task"); }
                catch (Throwable error) { failure = combine(failure, error); }
            }
            if (peeked[0]) {
                try { main(() -> Shizuku.unbindUserService(args, connection, false)); }
                catch (Throwable error) { failure = combine(failure, error); }
            }
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError(failure);
    }
}
