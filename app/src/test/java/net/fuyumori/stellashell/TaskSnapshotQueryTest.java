package net.fuyumori.stellashell;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskSnapshotQueryTest {
    private static final class Source implements TaskSnapshotQuery.Source {
        final List<TaskSnapshotQuery.Reply> calls = new ArrayList<>();
        public void read(TaskSnapshotQuery.Reply reply) { calls.add(reply); }
    }

    @Test public void overlappingReadersShareOneReadButCompletedResultsAreNotCached() {
        Source source = new Source(); TaskSnapshotQuery query = new TaskSnapshotQuery(source);
        List<String> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) query.read((result, error) -> results.add(result));
        assertEquals(1, source.calls.size());
        source.calls.get(0).done("first", null);
        assertEquals(20, results.size()); assertEquals("first", results.get(19));
        query.read((result, error) -> results.add(result));
        assertEquals(2, source.calls.size());
        source.calls.get(1).done("fresh", null);
        assertEquals("fresh", results.get(20));
    }

    @Test public void invalidationKeepsOldWaitersButPreventsJoiningTheirRead() {
        Source source = new Source(); TaskSnapshotQuery query = new TaskSnapshotQuery(source);
        List<String> old = new ArrayList<>(), fresh = new ArrayList<>();
        query.read((result, error) -> old.add(result));
        query.invalidate();
        query.read((result, error) -> fresh.add(result));
        source.calls.get(0).done("old", null);
        query.read((result, error) -> fresh.add(result));
        assertEquals(2, source.calls.size()); // Late old reply cannot clear the fresh flight.
        source.calls.get(1).done("new", null);
        assertEquals(List.of("old"), old); assertEquals(List.of("new", "new"), fresh);
    }

    @Test public void sharedFailureReachesEveryReaderAndNextReadRecovers() {
        Source source = new Source(); TaskSnapshotQuery query = new TaskSnapshotQuery(source);
        List<String> errors = new ArrayList<>(), results = new ArrayList<>();
        query.read((result, error) -> errors.add(error));
        query.read((result, error) -> errors.add(error));
        source.calls.get(0).done(null, "disconnected");
        assertEquals(List.of("disconnected", "disconnected"), errors);
        query.read((result, error) -> results.add(result));
        source.calls.get(1).done("reconnected", null);
        assertEquals(List.of("reconnected"), results);
    }

    @Test public void repeatedLateCompletionCannotDeliverTwice() {
        Source source = new Source(); TaskSnapshotQuery query = new TaskSnapshotQuery(source);
        List<String> results = new ArrayList<>();
        query.read((result, error) -> results.add(result));
        query.invalidate();
        source.calls.get(0).done("once", null);
        source.calls.get(0).done("twice", null);
        assertEquals(List.of("once"), results);
    }

    @Test public void sourceFailureDoesNotLeaveQueryPermanentlyBusy() {
        int[] reads = {0}; List<String> errors = new ArrayList<>();
        TaskSnapshotQuery query = new TaskSnapshotQuery(reply -> {
            if (reads[0]++ == 0) throw new IllegalStateException("submit failed");
            reply.done("recovered", null);
        });
        query.read((result, error) -> errors.add(error));
        List<String> results = new ArrayList<>();
        query.read((result, error) -> results.add(result));
        assertEquals(List.of("submit failed"), errors);
        assertEquals(List.of("recovered"), results);
    }

    @Test public void oneBrokenConsumerDoesNotSuppressOtherJoinedConsumers() {
        Source source = new Source(); TaskSnapshotQuery query = new TaskSnapshotQuery(source);
        List<String> results = new ArrayList<>();
        query.read((result, error) -> { throw new IllegalStateException("consumer failed"); });
        query.read((result, error) -> results.add(result));
        try { source.calls.get(0).done(null, "read failed"); fail("Consumer failure was hidden"); }
        catch (IllegalStateException expected) { assertEquals("consumer failed", expected.getMessage()); }
        assertEquals(1, results.size());
        query.read((result, error) -> results.add(result));
        assertEquals(2, source.calls.size());
    }
}
