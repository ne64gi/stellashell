package net.fuyumori.stellashell.core.power;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PowerSessionTest {
    @Test public void migrationPreservesExplicitOffAndOnlyOptsExistingConfigurationIn(){
        assertFalse(PowerSession.migrate(null,false));
        assertTrue(PowerSession.migrate(null,true));
        assertFalse(PowerSession.migrate(false,true));
        assertTrue(PowerSession.migrate(true,false));
    }
    @Test public void disablingRevokesQueuedWorkBeforeCleanupAndDebtCannotReactivate(){
        PowerSession state=new PowerSession(true);
        state.connected(true);long queued=state.generation();
        assertTrue(state.active());assertTrue(state.permits(queued));
        assertTrue(state.request(false));
        assertFalse(state.active());assertFalse(state.permits(queued));assertTrue(state.transition());
        state.connected(true);state.released(false);
        assertFalse(state.request(true));assertFalse(state.requested());
        assertEquals(PowerSession.Phase.RETRY,state.phase());
        state.retry();state.released(true);
        assertFalse(state.transition());assertTrue(state.request(true));
        assertFalse(state.permits(queued));state.connected(true);assertTrue(state.active());
    }
    @Test public void offConnectionAndLateDisconnectCannotEnableCapability(){
        PowerSession state=new PowerSession(false);
        state.connected(true);assertFalse(state.active());assertFalse(state.transition());
        assertFalse(state.permits(state.generation()));
        state.request(true);state.connected(true);state.connected(false);
        assertTrue(state.requested());assertFalse(state.active());assertFalse(state.transition());
    }
    @Test public void restoredCleanupDebtNeedsExplicitRetryAndCannotBeReactivatedByLateConnect(){
        PowerSession state=new PowerSession(false,true);
        assertFalse(state.requested());assertTrue(state.transition());
        assertEquals(PowerSession.Phase.RETRY,state.phase());
        state.connected(true);assertFalse(state.active());assertFalse(state.request(true));
        state.retry();state.connected(false);state.released(false);
        assertTrue(state.transition());assertEquals(PowerSession.Phase.RETRY,state.phase());
        state.retry();state.released(true);assertFalse(state.transition());
        assertTrue(state.request(true));
    }
    @Test public void disconnectRevokesPreviousSessionEvenIfConnectionReturns(){
        PowerSession state=new PowerSession(true);
        long initial=state.generation();state.connected(true);
        assertTrue(state.permits(initial));
        state.connected(false);long disconnected=state.generation();
        assertFalse(state.permits(initial));
        state.connected(false);assertEquals(disconnected,state.generation());
        state.connected(true);assertTrue(state.active());
        assertFalse(state.permits(initial));assertTrue(state.permits(disconnected));
    }
}
