package net.fuyumori.stellashell.core.tasks;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskPinSupportTest {
    @Test public void sonyFallbackIsBoundedToTheVerifiedModelAndOs(){
        assertEquals(TaskPinSupport.Route.SONY_ROOT,TaskPinSupport.select(34,"Sony","SOG06",true,true));
        assertEquals(TaskPinSupport.Route.UNSUPPORTED,TaskPinSupport.select(34,"Sony","SOG06",true,false));
        assertEquals(TaskPinSupport.Route.UNSUPPORTED,TaskPinSupport.select(34,"Sony","Other",true,true));
        assertEquals(TaskPinSupport.Route.UNSUPPORTED,TaskPinSupport.select(33,"Sony","SOG06",true,true));
        assertEquals(TaskPinSupport.Route.UNSUPPORTED,TaskPinSupport.select(34,"Other","SOG06",true,true));
    }
    @Test public void nxCrashGuardCannotBeBypassedByEitherMethod(){
        assertEquals(TaskPinSupport.Route.UNSUPPORTED,TaskPinSupport.select(36,"nubia","NX809J",true,true));
    }
    @Test public void sonyOnlyEnablesTheVisuallyVerifiedMainDisplay(){
        assertTrue(TaskPinSupport.supportsDisplay(TaskPinSupport.Route.SONY_ROOT,0));
        assertFalse(TaskPinSupport.supportsDisplay(TaskPinSupport.Route.SONY_ROOT,1));
        assertFalse(TaskPinSupport.supportsDisplay(TaskPinSupport.Route.SONY_ROOT,-1));
        assertFalse(TaskPinSupport.supportsDisplay(TaskPinSupport.Route.UNSUPPORTED,0));
        assertTrue(TaskPinSupport.supportsDisplay(TaskPinSupport.Route.WCT,0));
        assertTrue(TaskPinSupport.supportsDisplay(TaskPinSupport.Route.WCT,2));
    }
    @Test public void existingWctCapabilityStillRequiresVersionAndMethod(){
        assertEquals(TaskPinSupport.Route.WCT,TaskPinSupport.select(35,"Other","Other",true,false));
        assertEquals(TaskPinSupport.Route.UNSUPPORTED,TaskPinSupport.select(35,"Other","Other",false,true));
        assertEquals(TaskPinSupport.Route.UNSUPPORTED,TaskPinSupport.select(34,"Other","Other",true,true));
    }
}
