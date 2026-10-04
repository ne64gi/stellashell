package net.fuyumori.stellashell;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskSnapshotTest {
    private static TaskSnapshot.Task task(int id, String component) {
        return new TaskSnapshot.Task(id, component, 5, true, false, false,
                10, 20, 310, 420);
    }

    @Test public void snapshotDetachesAndFreezesTaskCollections() {
        List<TaskSnapshot.Task> tasks = new ArrayList<>();
        TaskSnapshot.Task first = task(17, "fixture/.Window");
        tasks.add(first);
        TaskSnapshot snapshot = new TaskSnapshot(8, 3, 42, tasks, tasks,
                true, true, true);
        tasks.clear();

        assertEquals(1, snapshot.tasks.size());
        assertSame(first, snapshot.tasks.get(0));
        try {
            snapshot.tasks.add(task(18, "fixture/.Other"));
            fail("snapshot task list is mutable");
        } catch (UnsupportedOperationException expected) { }
        try {
            snapshot.stack.clear();
            fail("snapshot stack is mutable");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test public void taskBoundsAreImmutableValuesAndIdentityIncludesComponent() {
        TaskSnapshot.Task task = task(17, "fixture/.Window");
        TaskSnapshot.Bounds first = task.bounds();
        TaskSnapshot.Bounds second = task.bounds();

        assertNotSame(first, second);
        assertEquals(10, first.left);
        assertEquals(20, first.top);
        assertEquals(310, first.right);
        assertEquals(420, first.bottom);
        assertEquals(new TaskSnapshot.Identity(17, "fixture/.Window"), task.identity());
        assertNotEquals(task.identity(), new TaskSnapshot.Identity(17, "fixture/.Reused"));
    }
}
