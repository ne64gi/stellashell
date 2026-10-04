package net.fuyumori.stellashell.core.tasks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceOperationsTest {
    @Test public void disconnectAndReconnectWaitForTheWholeTransfer(){
        WorkspaceOperations operations=new WorkspaceOperations();List<Integer> destinations=new ArrayList<>();
        long first=operations.begin();long[] next={-1};
        assertTrue(operations.defer(()->{destinations.add(0);next[0]=operations.begin();}));
        assertTrue(operations.defer(()->destinations.add(4)));
        operations.drain();assertTrue(destinations.isEmpty());
        assertTrue(operations.release(first));operations.drain();
        assertEquals(Arrays.asList(0),destinations);
        assertTrue(operations.release(next[0]));operations.drain();
        assertEquals(Arrays.asList(0,4),destinations);
    }
    @Test public void resetDropsQueuedRequestsAndRejectsOldReplies(){
        WorkspaceOperations operations=new WorkspaceOperations();long old=operations.begin();
        operations.defer(()->fail("Old session handoff resumed"));operations.reset();
        long current=operations.begin();assertFalse(operations.release(old));assertTrue(operations.busy());
        assertTrue(operations.release(current));operations.drain();assertFalse(operations.busy());
    }
    @Test public void rollbackAttemptsEveryMovedTaskAndRetainsOriginalFailure(){
        Exception original=new Exception("destination disappeared");List<Integer> attempted=new ArrayList<>();
        Exception result=WorkspaceOperations.rollback(Arrays.asList(1,2,3),id->{attempted.add(id);if(id!=2)throw new Exception("restore failed");},original);
        assertEquals(Arrays.asList(1,2,3),attempted);assertSame(original,result.getCause());
        assertEquals(2,result.getSuppressed().length);
        assertTrue(result.getSuppressed()[0].getMessage().contains("1"));
        assertTrue(result.getSuppressed()[1].getMessage().contains("3"));
    }
    @Test public void successfulRollbackPreservesOriginalError(){
        Exception original=new Exception("move failed");
        assertSame(original,WorkspaceOperations.rollback(Arrays.asList(1,2),id->{},original));
    }
}
