package net.fuyumori.stellashell.core.tasks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import net.fuyumori.stellashell.core.display.AutoOutputReconciler;
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
    @Test public void idleEventWaitsForQueuedCommandsToFinish(){
        WorkspaceOperations operations=new WorkspaceOperations();List<String> events=new ArrayList<>();
        long first=operations.begin();long[] queued={-1};
        operations.defer(()->{events.add("queued begins");queued[0]=operations.begin();});
        operations.whenIdle(()->events.add("idle"));
        assertTrue(operations.release(first));operations.drain();
        assertEquals(Arrays.asList("queued begins"),events);
        assertTrue(operations.release(queued[0]));operations.drain();operations.drain();
        assertEquals(Arrays.asList("queued begins","idle"),events);
    }
    @Test public void idleSubscriptionCanBeCancelledAndResetDropsIt() throws Exception {
        WorkspaceOperations operations=new WorkspaceOperations();int[] calls={0};
        long first=operations.begin();AutoCloseable cancelled=operations.whenIdle(()->calls[0]++);
        cancelled.close();assertTrue(operations.release(first));operations.drain();assertEquals(0,calls[0]);
        long previous=operations.begin();operations.whenIdle(()->calls[0]++);operations.reset();
        assertFalse(operations.release(previous));operations.drain();assertEquals(0,calls[0]);
        operations.whenIdle(()->calls[0]++);assertEquals(1,calls[0]);operations.drain();assertEquals(1,calls[0]);
    }
    @Test public void idleCallbackStartingACommandDelaysLaterIdleCallbacks(){
        WorkspaceOperations operations=new WorkspaceOperations();List<Integer> events=new ArrayList<>();
        long first=operations.begin();long[] next={-1};
        operations.whenIdle(()->{events.add(1);next[0]=operations.begin();});
        operations.whenIdle(()->events.add(2));
        assertTrue(operations.release(first));operations.drain();assertEquals(Arrays.asList(1),events);
        assertTrue(operations.release(next[0]));operations.drain();assertEquals(Arrays.asList(1,2),events);
    }
    @Test public void queuedTransferResolvesCurrentCommittedSourceAtExecution(){
        WorkspaceOperations operations=new WorkspaceOperations();int[] target={0};List<String> moves=new ArrayList<>();
        long automatic=operations.begin();
        operations.enqueueTransfer(()->target[0],source->{moves.add(source+"->0");target[0]=0;});
        assertTrue(moves.isEmpty());
        target[0]=4; // Earlier automatic transfer commits before releasing its command.
        assertTrue(operations.release(automatic));operations.drain();
        assertEquals(Arrays.asList("4->0"),moves);assertEquals(0,target[0]);
    }
    @Test public void busyCompletionResumesExistingAutoDecisionWithoutAnotherEvent(){
        WorkspaceOperations operations=new WorkspaceOperations();AutoOutputReconciler automatic=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket decision=automatic.request(operations.generation(),0);
        long unrelated=operations.begin();int[] destination={-1};
        // The one settle delay expired while an unrelated command remained busy.
        operations.whenIdle(()->destination[0]=automatic.begin(decision,operations.generation(),0,true,4,Arrays.asList(4)));
        assertEquals(-1,destination[0]);assertTrue(operations.release(unrelated));operations.drain();
        assertEquals(4,destination[0]);
    }
    @Test public void manualDuringAutomaticWaitsAndResolvesItsNewSource(){
        WorkspaceOperations operations=new WorkspaceOperations();AutoOutputReconciler automatic=new AutoOutputReconciler();
        int[] target={0};List<String> moves=new ArrayList<>();
        AutoOutputReconciler.Ticket decision=automatic.request(operations.generation(),0);
        assertEquals(4,automatic.begin(decision,operations.generation(),0,true,4,Arrays.asList(4)));
        long inFlight=operations.begin();
        automatic.cancel(); // Explicit manual-to-phone invalidates all queued AUTO decisions.
        operations.whenIdle(()->operations.enqueueTransfer(()->target[0],source->{moves.add(source+"->0");target[0]=0;}));
        target[0]=4;assertFalse(automatic.finished(decision));
        assertTrue(operations.release(inFlight));operations.drain();
        assertEquals(Arrays.asList("4->0"),moves);assertEquals(0,target[0]);
        assertEquals(-1,automatic.begin(null,operations.generation(),0,true,4,Arrays.asList(4)));
    }
    @Test public void latestManualChoiceCancelsEarlierPendingChoice() throws Exception {
        WorkspaceOperations operations=new WorkspaceOperations();int[] target={0};
        long inFlight=operations.begin();
        AutoCloseable earlier=operations.whenIdle(()->target[0]=4);earlier.close();
        operations.whenIdle(()->target[0]=7);
        assertTrue(operations.release(inFlight));operations.drain();assertEquals(7,target[0]);
    }
    @Test public void autoOffStopOrResetCancellationPreventsIdleHandoff() throws Exception {
        WorkspaceOperations operations=new WorkspaceOperations();AutoOutputReconciler automatic=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket decision=automatic.request(operations.generation(),0);
        long unrelated=operations.begin();int[] destination={-1};
        AutoCloseable subscription=operations.whenIdle(()->destination[0]=automatic.begin(decision,operations.generation(),0,true,4,Arrays.asList(4)));
        automatic.cancel();subscription.close();
        assertTrue(operations.release(unrelated));operations.drain();assertEquals(-1,destination[0]);
    }
    @Test public void queuedLossRecoveryDoesNotReplaceNewManualTarget(){
        WorkspaceOperations operations=new WorkspaceOperations();int[] target={4},skipped={0};
        long session=operations.generation(),manual=operations.begin();List<Integer> recovered=new ArrayList<>();
        operations.enqueueTransfer(()->target[0],()->operations.current(session)&&target[0]==4,
                source->{recovered.add(source);target[0]=0;},()->skipped[0]++);
        target[0]=7; // The already-running manual 4 -> 7 transfer commits first.
        assertTrue(operations.release(manual));operations.drain();
        assertEquals(7,target[0]);assertTrue(recovered.isEmpty());assertEquals(1,skipped[0]);
    }
    @Test public void queuedLossRecoveryStillRunsForItsOriginalMissingTarget(){
        WorkspaceOperations operations=new WorkspaceOperations();int[] target={4};
        long session=operations.generation(),unrelated=operations.begin();List<Integer> recovered=new ArrayList<>();
        operations.enqueueTransfer(()->target[0],()->operations.current(session)&&target[0]==4,
                source->{recovered.add(source);target[0]=0;},()->fail("Still-applicable recovery skipped"));
        assertTrue(operations.release(unrelated));operations.drain();
        assertEquals(Arrays.asList(4),recovered);assertEquals(0,target[0]);
    }
    @Test public void changedExplicitChoiceRejectsRecoveryEvenIfTargetHasNotCommittedYet(){
        WorkspaceOperations operations=new WorkspaceOperations();int[] target={4},choice={1},skipped={0};
        long unrelated=operations.begin();int originalChoice=choice[0];
        operations.enqueueTransfer(()->{fail("Rejected request resolved source");return target[0];},
                ()->target[0]==4&&choice[0]==originalChoice,
                source->fail("Old recovery replaced pending manual choice"),()->skipped[0]++);
        choice[0]=2;assertTrue(operations.release(unrelated));operations.drain();
        assertEquals(4,target[0]);assertEquals(1,skipped[0]);
    }
    @Test public void removalDeferredBeforeAutoCommitRecoversMissingTargetBeforeReplacement(){
        WorkspaceOperations operations=new WorkspaceOperations();AutoOutputReconciler automatic=new AutoOutputReconciler();
        int[] target={0},replacement={-1};long session=operations.generation();
        AutoOutputReconciler.Ticket transfer=automatic.request(session,0);
        assertEquals(4,automatic.begin(transfer,session,0,true,4,Arrays.asList(4)));
        long inFlight=operations.begin();
        assertNull(automatic.request(session,0)); // Display 4 removed, replacement 7 added, before commit.
        target[0]=4;automatic.routingChanged(session);
        assertTrue(automatic.finished(transfer));
        // Completion replays guarded loss recovery, rather than selecting while target remains positive.
        operations.enqueueTransfer(()->target[0],()->operations.current(session)&&target[0]==4,
                source->{
                    assertEquals(4,source);target[0]=0;automatic.routingChanged(session);
                    replacement[0]=automatic.begin(automatic.request(session,0),session,0,true,4,Arrays.asList(7));
                },()->fail("Missing selected output recovery skipped"));
        assertEquals(-1,replacement[0]);
        assertTrue(operations.release(inFlight));operations.drain();
        assertEquals(0,target[0]);assertEquals(7,replacement[0]);
    }
}
