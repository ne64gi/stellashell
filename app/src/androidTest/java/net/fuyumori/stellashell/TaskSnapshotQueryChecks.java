package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/** Main-thread synthetic feeds only: no device queries, preferences or polling. */
final class TaskSnapshotQueryChecks {
    private final Instrumentation test;
    TaskSnapshotQueryChecks(Instrumentation test) { this.test = test; }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static String snapshot(String component) {
        return "{\"tasks\":[{\"id\":801,\"component\":\"" + component
                + "\",\"mode\":1,\"visible\":true,\"focused\":true,\"left\":0,\"top\":0,\"right\":360,\"bottom\":720}]}";
    }
    void run() {
        Throwable[] failure = {null};
        test.runOnMainSync(() -> {
            try { overlapAndLifecycle(); failureAndReconnect(); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void overlapAndLifecycle() {
        Fixture fixture = new Fixture(); int[] firstChanges = {0}, secondChanges = {0};
        PhoneRunningTasks first = new PhoneRunningTasks(fixture.backend(), () -> firstChanges[0]++);
        PhoneRunningTasks second = new PhoneRunningTasks(fixture.backend(), () -> secondChanges[0]++);
        try {
            first.start(); second.start(); first.refresh(); second.refresh();
            check(fixture.calls.size() == 1, "Overlapping feeds read twice");
            for (int i = 0; i < 20; i++) fixture.event();
            check(fixture.calls.size() == 1, "Busy event burst started extra reads");
            fixture.complete(0, snapshot("fixture/.First"), null);
            first.refresh(); second.refresh();
            check(fixture.calls.size() == 2, "Burst follow-up was lost or duplicated");
            fixture.complete(1, snapshot("fixture/.First"), null);
            check(firstChanges[0] == 1 && secondChanges[0] == 1, "Unchanged snapshot redrew feeds");

            TaskSnapshot.Task oldIdentity = second.tasks().get(0);
            fixture.event();
            check(fixture.calls.size() == 3, "Completed result was cached");
            first.close();
            check(fixture.listeners.size() == 1, "One close removed the overlapping subscriber");
            fixture.complete(2, snapshot("fixture/.ReusedId"), null);
            check(first.tasks().isEmpty() && second.tasks().get(0).component.equals("fixture/.ReusedId"),
                    "Late reply reached stopped feed or lost component identity");
            second.focus(oldIdentity, () -> { throw new AssertionError("Stale identity focused"); }, error -> {});
            check(fixture.focuses == 0 && fixture.calls.size() == 4, "Reused task id passed stale identity guard");

            fixture.awake = false; fixture.event();
            int beforeSleep = secondChanges[0];
            fixture.complete(3, snapshot("fixture/.Asleep"), null);
            check(secondChanges[0] == beforeSleep, "Screen-off reply updated feed");
            fixture.awake = true; fixture.event();
            check(fixture.calls.size() == 5, "Wake did not fetch fresh tasks");
            second.close();
            check(fixture.listeners.isEmpty(), "Last unsubscribe retained listeners");
            fixture.complete(4, snapshot("fixture/.Late"), null);
            check(second.tasks().isEmpty(), "Last-unsubscribe late callback populated feed");

            PhoneRunningTasks reopened = new PhoneRunningTasks(fixture.backend(), () -> {});
            try {
                reopened.start(); reopened.refresh();
                check(fixture.calls.size() == 6, "Reopened feed reused last-unsubscribe read");
                fixture.complete(5, snapshot("fixture/.Reopened"), null);
                check(reopened.state() == PhoneRunningTasks.State.READY, "Reopened feed failed to recover");
            } finally { reopened.close(); }
        } finally { first.close(); second.close(); }
    }

    private void failureAndReconnect() {
        Fixture fixture = new Fixture();
        PhoneRunningTasks first = new PhoneRunningTasks(fixture.backend(), () -> {});
        PhoneRunningTasks second = new PhoneRunningTasks(fixture.backend(), () -> {});
        try {
            first.start(); second.start(); first.refresh(); second.refresh();
            fixture.complete(0, null, "read failed");
            check(first.state() == PhoneRunningTasks.State.ERROR && second.state() == PhoneRunningTasks.State.ERROR,
                    "Shared failure was not delivered to both feeds");
            fixture.event(); check(fixture.calls.size() == 2, "Failure blocked later event recovery");
            fixture.ready = false; fixture.event();
            check(first.state() == PhoneRunningTasks.State.UNAVAILABLE && second.state() == PhoneRunningTasks.State.UNAVAILABLE,
                    "Disconnected feeds retained ready/error state");
            fixture.ready = true; fixture.event();
            fixture.complete(1, snapshot("fixture/.OldEpoch"), "connection changed");
            check(fixture.calls.size() == 3, "Old epoch completion did not share a fresh read");
            fixture.complete(2, snapshot("fixture/.NewEpoch"), null);
            check(first.state() == PhoneRunningTasks.State.READY && second.state() == PhoneRunningTasks.State.READY
                    && first.tasks().get(0).component.equals("fixture/.NewEpoch")
                    && second.tasks().get(0).component.equals("fixture/.NewEpoch"), "Reconnect kept old tasks or failed recovery");
        } finally { first.close(); second.close(); }
    }

    private static final class Fixture {
        final List<TaskSnapshotQuery.Reply> calls = new ArrayList<>();
        final List<Runnable> listeners = new ArrayList<>();
        final TaskSnapshotQuery query = new TaskSnapshotQuery(calls::add);
        boolean awake = true, ready = true;
        int focuses;
        PhoneRunningTasks.Backend backend() {
            return new PhoneRunningTasks.Backend() {
                public boolean ready() { return ready; }
                public boolean awake() { return awake; }
                public void observe(Runnable listener) { listeners.add(listener); }
                public void remove(Runnable listener) { listeners.remove(listener); if (listeners.isEmpty()) query.invalidate(); }
                public void snapshot(Bridge.Reply reply) { query.read(reply::done); }
                public void focus(TaskSnapshot.Task task, Bridge.Reply reply) { focuses++; }
                public void operation(TaskSnapshot.Task task, String action, Rect bounds, Bridge.Reply reply) {
                    throw new AssertionError("Fixture must not operate tasks");
                }
            };
        }
        void event() { query.invalidate(); for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
        void complete(int index, String result, String error) { calls.get(index).done(result, error); }
    }
}
