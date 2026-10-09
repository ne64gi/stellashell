package net.fuyumori.stellashell;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StartMenuRequestsTest {
    @Test public void standardShowIsNotLauncherToggleOrOrdinaryHome(){
        assertEquals(StartMenuRequests.Operation.SHOW,StartMenuRequests.operation("android.intent.action.ALL_APPS"));
        assertEquals(StartMenuRequests.Operation.TOGGLE,StartMenuRequests.operation("launcher.intent_action_all_apps_toggle"));
        assertEquals(StartMenuRequests.Operation.TOGGLE,StartMenuRequests.operation("net.fuyumori.stellashell.TOGGLE_START"));
        assertEquals(StartMenuRequests.Operation.NONE,StartMenuRequests.operation("android.intent.action.MAIN"));
        assertEquals(StartMenuRequests.Operation.NONE,StartMenuRequests.operation(null));
    }
    @Test public void selectedOutputWinsButExplicitDisconnectedTargetNeverBecomesPhone(){
        assertEquals(42,StartMenuRequests.target(-1,42));
        assertEquals(0,StartMenuRequests.target(-1,-1));
        assertEquals(0,StartMenuRequests.target(0,42));
        assertEquals(41,StartMenuRequests.target(41,42));
    }
    @Test public void showAndToggleUseOnlyCurrentSession(){
        ShellRuntime.Registry registry=new ShellRuntime.Registry();
        assertFalse(registry.showStart(0));
        final boolean[] open={false};final int[] last={-1};
        ShellRuntime.Session session=new ShellRuntime.Session(){
            public boolean toggleStart(int display){last[0]=display;open[0]=!open[0];return true;}
            public boolean showStart(int display){last[0]=display;open[0]=true;return true;}
            public int[] navigationBounds(int display){return null;}
            public void homeVisible(boolean value){}
        };
        ShellRuntime.Registry.Token old=registry.bind(session);
        assertTrue(registry.showStart(42));assertTrue(open[0]);assertEquals(42,last[0]);
        assertTrue(registry.showStart(42));assertTrue(open[0]);
        assertTrue(registry.toggleStart(42));assertFalse(open[0]);
        registry.close(old);assertFalse(registry.showStart(42));assertFalse(registry.toggleStart(42));
    }
}
