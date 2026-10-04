package net.fuyumori.stellashell.core.display;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeOutputRecoveryTest {
    @Test public void synchronousPreferenceRefreshDoesNotDispatchNestedStarts(){
        HomeOutputRecovery recovery=new HomeOutputRecovery();int[] requests={0};
        recovery.refresh(()->{
            recovery.refresh(()->requests[0]++);
            requests[0]++;
        });
        assertEquals(1,requests[0]);
        recovery.refresh(()->requests[0]++);
        assertEquals(2,requests[0]);
    }
    @Test public void failedRefreshDoesNotBlockNextResume(){
        HomeOutputRecovery recovery=new HomeOutputRecovery();int[] requests={0};
        IllegalStateException failure=new IllegalStateException("start failed");
        try{recovery.refresh(()->{throw failure;});fail("Failure swallowed");}
        catch(IllegalStateException error){assertSame(failure,error);}
        recovery.refresh(()->requests[0]++);
        assertEquals(1,requests[0]);
    }
    @Test public void phoneWorkspaceDoesNotHideConnectedExternalPreference(){
        assertEquals(4,HomeOutputRecovery.target(0,4,Arrays.asList(4,7)));
    }
    @Test public void connectedWorkspaceWinsOverOlderPreferredOutput(){
        assertEquals(7,HomeOutputRecovery.target(7,4,Arrays.asList(4,7)));
    }
    @Test public void staleWorkspaceCanFallBackToConnectedPreference(){
        assertEquals(4,HomeOutputRecovery.target(8,4,Arrays.asList(4,7)));
    }
    @Test public void disconnectedSavedOutputsReturnHomeNotAnUnrelatedDisplay(){
        assertEquals(0,HomeOutputRecovery.target(8,4,Collections.singletonList(7)));
        assertEquals(0,HomeOutputRecovery.target(-1,-1,Collections.emptyList()));
    }
}
