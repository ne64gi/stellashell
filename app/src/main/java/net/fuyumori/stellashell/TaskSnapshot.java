package net.fuyumori.stellashell;

import android.graphics.Rect;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable, detached read model for one selected-output task snapshot. */
final class TaskSnapshot {
    static final class Identity {
        final int id;
        final String component;

        Identity(int id, String component) {
            this.id = id;
            this.component = Objects.requireNonNull(component, "component");
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Identity)) return false;
            Identity identity = (Identity) other;
            return id == identity.id && component.equals(identity.component);
        }

        @Override public int hashCode() { return 31 * id + component.hashCode(); }
    }

    static final class Bounds {
        final int left, top, right, bottom;
        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
        Rect toRect() { return new Rect(left, top, right, bottom); }
        boolean isEmpty() { return left >= right || top >= bottom; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Bounds)) return false;
            Bounds bounds = (Bounds) other;
            return left == bounds.left && top == bounds.top
                    && right == bounds.right && bottom == bounds.bottom;
        }
        @Override public int hashCode() {
            int result = left;
            result = 31 * result + top;
            result = 31 * result + right;
            return 31 * result + bottom;
        }
    }

    static final class Task {
        final int id, mode;
        final String component;
        final boolean visible, focused, alwaysOnTop;
        private final int left, top, right, bottom;

        Task(JSONObject json) throws JSONException {
            this(json.getInt("id"), json.getString("component"), json.getInt("mode"),
                    json.getBoolean("visible"), json.getBoolean("focused"),
                    json.optBoolean("alwaysOnTop"), json.getInt("left"), json.getInt("top"),
                    json.getInt("right"), json.getInt("bottom"));
        }

        Task(int id, String component, int mode, boolean visible, boolean focused,
                boolean alwaysOnTop, int left, int top, int right, int bottom) {
            this.id = id;
            this.component = Objects.requireNonNull(component, "component");
            this.mode = mode;
            this.visible = visible;
            this.focused = focused;
            this.alwaysOnTop = alwaysOnTop;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        Identity identity() { return new Identity(id, component); }
        Bounds bounds() { return new Bounds(left, top, right, bottom); }
        int left() { return left; }
        int top() { return top; }
        int right() { return right; }
        int bottom() { return bottom; }
        String packageName() { return component.substring(0, component.indexOf('/')); }
    }

    final long generation;
    final long outputEpoch;
    final int displayId;
    final List<Task> tasks;
    final List<Task> stack;
    final boolean stackReliable, canArrange, canPin;

    TaskSnapshot(long generation, long outputEpoch, int displayId, List<Task> tasks,
            List<Task> stack, boolean stackReliable, boolean canArrange, boolean canPin) {
        this.generation = generation;
        this.outputEpoch = outputEpoch;
        this.displayId = displayId;
        this.tasks = immutableCopy(tasks);
        this.stack = immutableCopy(stack);
        this.stackReliable = stackReliable;
        this.canArrange = canArrange;
        this.canPin = canPin;
    }

    static TaskSnapshot empty(long outputEpoch, int displayId) {
        return new TaskSnapshot(0, outputEpoch, displayId, Collections.emptyList(),
                Collections.emptyList(), false, false, false);
    }

    private static List<Task> immutableCopy(List<Task> tasks) {
        return Collections.unmodifiableList(new ArrayList<>(tasks));
    }

    Task find(Identity identity) {
        for (Task task : tasks) if (task.identity().equals(identity)) return task;
        return null;
    }

    /** Generation is delivery bookkeeping, not a reason to redraw unchanged windows. */
    boolean sameState(TaskSnapshot other) {
        return outputEpoch == other.outputEpoch && displayId == other.displayId
                && stackReliable == other.stackReliable && canArrange == other.canArrange
                && canPin == other.canPin && sameTasks(tasks, other.tasks) && sameTasks(stack, other.stack);
    }
    private static boolean sameTasks(List<Task> left, List<Task> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            Task a = left.get(i), b = right.get(i);
            if (!a.identity().equals(b.identity()) || a.mode != b.mode || a.visible != b.visible
                    || a.focused != b.focused || a.alwaysOnTop != b.alwaysOnTop
                    || !a.bounds().equals(b.bounds())) return false;
        }
        return true;
    }
}
