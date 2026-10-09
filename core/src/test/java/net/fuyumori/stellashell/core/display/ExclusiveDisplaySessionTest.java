package net.fuyumori.stellashell.core.display;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ExclusiveDisplaySessionTest {
    @Test public void requiresAnExplicitExternalDisplay() {
        ExclusiveDisplaySession state = new ExclusiveDisplaySession();
        for (int id : new int[]{-1, 0}) {
            try { state.begin(id); fail("Accepted phone or unspecified display"); }
            catch (IllegalArgumentException expected) { assertEquals(-1, state.displayId()); }
        }
    }
    @Test public void aSecondStartCannotStealTheExistingWaitingScreen() {
        ExclusiveDisplaySession state = new ExclusiveDisplaySession();
        ExclusiveDisplaySession.Lease first = state.begin(4);
        try { state.begin(5); fail("Replaced an active session"); }
        catch (IllegalStateException expected) { assertTrue(state.isCurrent(first)); }
        assertEquals(4, state.displayId());
    }
    @Test public void delayedDestroyCannotEndAReconnectedSessionWithReusedDisplayId() {
        ExclusiveDisplaySession state = new ExclusiveDisplaySession();
        ExclusiveDisplaySession.Lease old = state.begin(4);
        assertTrue(state.end(old));
        ExclusiveDisplaySession.Lease next = state.begin(4);
        assertFalse(state.end(old));
        assertTrue(state.isCurrent(next));
        assertEquals(4, state.displayId());
    }
    @Test public void foreignAndNullLeasesCannotReleaseTheOwner() {
        ExclusiveDisplaySession one = new ExclusiveDisplaySession(), two = new ExclusiveDisplaySession();
        ExclusiveDisplaySession.Lease lease = one.begin(4), foreign = two.begin(4);
        assertFalse(one.end(foreign)); assertFalse(one.end(null));
        assertTrue(one.isCurrent(lease)); assertTrue(one.end(lease));
        assertEquals(-1, one.displayId()); assertFalse(one.end(lease));
    }
}
