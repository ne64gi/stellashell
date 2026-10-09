package net.fuyumori.stellashell.core.display;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoOutputReconcilerTest {
    @Test public void alreadyConnectedOutputCanBeSelectedAtStartOrAutoOn(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        assertEquals(4,flow.begin(flow.request(1,0),1,0,true,4,Arrays.asList(4,7)));
    }
    @Test public void reconnectEventBeforeDisconnectCompletionGetsFreshDecision(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket added=flow.request(1,3);
        assertEquals(-1,flow.begin(added,1,3,true,3,Collections.singletonList(4)));
        // Disconnect transfer has now committed phone, with no second DisplayAdded event.
        assertEquals(4,flow.begin(flow.request(1,0),1,0,true,3,Collections.singletonList(4)));
    }
    @Test public void eventBurstCoalescesAndChecksCurrentCandidates(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket first=flow.request(1,0),latest=flow.request(1,0);
        assertEquals(-1,flow.begin(first,1,0,true,3,Collections.singletonList(3)));
        assertEquals(4,flow.begin(latest,1,0,true,3,Collections.singletonList(4)));
    }
    @Test public void sessionAndTargetChangesRejectScheduledDecision(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        assertEquals(-1,flow.begin(flow.request(1,0),2,0,true,4,Collections.singletonList(4)));
        assertEquals(-1,flow.begin(flow.request(2,0),2,7,true,4,Collections.singletonList(4)));
    }
    @Test public void disabledAutoStoppedStaleRuntimeOrBusyWorkspaceCannotBegin(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        assertEquals(-1,flow.begin(flow.request(1,0),1,0,false,4,Collections.singletonList(4)));
        assertEquals(4,flow.begin(flow.request(1,0),1,0,true,4,Collections.singletonList(4)));
    }
    @Test public void manualHandoffResetOrCloseCancelsQueuedDecision(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket queued=flow.request(1,0);
        flow.cancel();
        assertEquals(-1,flow.begin(queued,1,0,true,4,Collections.singletonList(4)));
    }
    @Test public void livePreferredWinsAndStalePreferredFallsBackToPositiveExternal(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket first=flow.request(1,0);
        assertEquals(7,flow.begin(first,1,0,true,7,Arrays.asList(0,4,7)));
        assertFalse(flow.finished(first));
        assertEquals(4,flow.begin(flow.request(1,0),1,0,true,47,Arrays.asList(0,7,4)));
    }
    @Test public void noExternalMeansNoHandoff(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        assertEquals(-1,flow.begin(flow.request(1,0),1,0,true,4,Arrays.asList(-1,0)));
        assertEquals(-1,flow.begin(flow.request(1,0),1,0,true,4,Collections.emptyList()));
    }
    @Test public void inFlightTransferDoesNotDuplicateAndReplaysOnlyRealEvent(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket first=flow.request(1,0);
        assertEquals(4,flow.begin(first,1,0,true,4,Collections.singletonList(4)));
        assertNull(flow.request(1,0));
        assertNull(flow.request(1,0));
        assertTrue(flow.finished(first));
        assertFalse(flow.finished(first));
        assertEquals(7,flow.begin(flow.request(1,0),1,0,true,4,Collections.singletonList(7)));
    }
    @Test public void cancellationDoesNotReleaseInFlightTransferOrReplayOldEvent(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket first=flow.request(1,0);
        assertEquals(4,flow.begin(first,1,0,true,4,Collections.singletonList(4)));
        assertNull(flow.request(1,0));
        flow.cancel();
        // The original transfer still owns the gate until its completion.
        assertFalse(flow.finished(first));
        AutoOutputReconciler.Ticket second=flow.request(1,0);
        assertEquals(7,flow.begin(second,1,0,true,7,Collections.singletonList(7)));
        assertFalse(flow.finished(first));
        assertNull(flow.request(1,0));
    }
    @Test public void ordinaryCompletionDoesNotUndoManualPhoneSelection(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket first=flow.request(1,0);
        assertEquals(4,flow.begin(first,1,0,true,4,Collections.singletonList(4)));
        assertFalse(flow.finished(first));
        assertEquals(-1,flow.begin(null,1,0,true,4,Collections.singletonList(4)));
    }
    @Test public void resetSessionReleasesCancelledTransferWithoutNeedingOldCallback(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket previous=flow.request(1,0);
        assertEquals(4,flow.begin(previous,1,0,true,4,Collections.singletonList(4)));
        flow.cancel();
        AutoOutputReconciler.Ticket current=flow.request(2,0);
        assertNotNull(current);
        assertEquals(7,flow.begin(current,2,0,true,7,Collections.singletonList(7)));
        assertFalse(flow.finished(previous));
        assertNull(flow.request(2,0));
    }
    @Test public void ownTargetPersistencePreservesRealEventDeferredDuringAuto(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket transfer=flow.request(1,0);
        assertEquals(4,flow.begin(transfer,1,0,true,4,Collections.singletonList(4)));
        assertNull(flow.request(1,0)); // A real display event arrives before target persistence.
        flow.routingChanged(1);
        assertTrue(flow.finished(transfer));assertFalse(flow.finished(transfer));
        // Replay still obeys the freshly committed external target, without a second handoff.
        assertEquals(-1,flow.begin(flow.request(1,4),1,4,true,7,Collections.singletonList(7)));
    }
    @Test public void routingChangeCancelsQueuedDecisionButSessionResetClearsDeferredEvent(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket queued=flow.request(1,0);flow.routingChanged(1);
        assertEquals(-1,flow.begin(queued,1,0,true,4,Collections.singletonList(4)));
        AutoOutputReconciler.Ticket transfer=flow.request(1,0);
        assertEquals(4,flow.begin(transfer,1,0,true,4,Collections.singletonList(4)));
        assertNull(flow.request(1,0));flow.routingChanged(2);
        assertFalse(flow.inFlight());assertFalse(flow.finished(transfer));
        assertNotNull(flow.request(2,0));
    }
    @Test public void explicitCancellationAfterRoutingPersistenceDoesNotReplayDeferredEvent(){
        AutoOutputReconciler flow=new AutoOutputReconciler();
        AutoOutputReconciler.Ticket transfer=flow.request(1,0);
        assertEquals(4,flow.begin(transfer,1,0,true,4,Collections.singletonList(4)));
        assertNull(flow.request(1,0));flow.routingChanged(1);flow.cancel();
        assertFalse(flow.finished(transfer));
    }
}
