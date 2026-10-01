package net.fuyumori.stellashell;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceLaunchTest {
    @Test public void firstAppAndAppAfterDesktopArePrimary(){
        assertFalse(Workspace.defaultSecondary(true,null,"a/Main","",false));
        assertFalse(Workspace.defaultSecondary(true,"a/Main","b/Main","",false));
    }
    @Test public void followingAppIsSecondaryButReopeningMainPreservesItsRole(){
        assertTrue(Workspace.defaultSecondary(false,"a/Main","b/Main","",false));
        assertFalse(Workspace.defaultSecondary(false,"a/Main","a/Main","",false));
        assertFalse(Workspace.defaultSecondary(false,"a/Resolved","a/Launcher","a/Resolved",false));
    }
    @Test public void newWindowIsSecondaryAndClosedMainAllowsReplacement(){
        assertTrue(Workspace.defaultSecondary(false,"a/Main","a/Main","",true));
        assertFalse(Workspace.defaultSecondary(false,null,"b/Main","",false));
    }
}
