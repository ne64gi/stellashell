package net.fuyumori.stellashell.core.launch;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class PolicyTest {
    @Test public void noMonitorNeverRoutesToPhone(){assertEquals(-1,Policy.selectDisplay(7,Arrays.asList(0)));}
    @Test public void reconnectDoesNotReuseStaleId(){assertEquals(9,Policy.selectDisplay(7,Arrays.asList(0,9)));}
    @Test public void preservesSelectedExternalMonitor(){assertEquals(7,Policy.selectDisplay(7,Arrays.asList(2,7)));}
    @Test public void unpluggedTargetIsRejected(){assertThrows(IllegalArgumentException.class,()->Policy.requireTarget(7,Arrays.asList(0,9)));}
    @Test public void phoneTargetIsRejected(){assertThrows(IllegalArgumentException.class,()->Policy.requireTarget(0,Arrays.asList(0,9)));}
    @Test public void launchNeverUsesShellInterpolation(){
        String[] args=Policy.launchCommand("com.example/.Main",7,5);
        assertEquals("/system/bin/am",args[0]);assertEquals("7",args[5]);assertTrue(Arrays.asList(args).contains("com.example/.Main"));
        assertFalse(Arrays.asList(args).contains("-c"));
    }
    @Test public void rejectsCommandInjection(){assertThrows(IllegalArgumentException.class,()->Policy.launchCommand("com.example/.Main;reboot",7,1));}
    @Test public void rejectsPhoneInPrivilegedLaunch(){assertThrows(IllegalArgumentException.class,()->Policy.launchCommand("com.example/.Main",0,1));}
    @Test public void rejectsUnsupportedWindowMode(){assertThrows(IllegalArgumentException.class,()->Policy.launchCommand("com.example/.Main",7,99));}
    @Test public void supportsAbsentSettingRestoration(){Policy.setting("null");Policy.setting("0");Policy.setting("1");}
    @Test public void rejectsArbitrarySettingValue(){assertThrows(IllegalArgumentException.class,()->Policy.setting("1;reboot"));}
    @Test public void recentListIsBoundedAndDeduplicated(){
        List<String> old=new ArrayList<>();for(int i=0;i<8;i++)old.add("com.example/App"+i);
        List<String> recent=Policy.recent(old,"com.example/App3");
        assertEquals(6,recent.size());assertEquals("com.example/App3",recent.get(0));assertEquals(6,new HashSet<>(recent).size());
    }
    @org.junit.Test public void backIsExplicitlyScopedToAnAvailableExternalDisplay(){
        org.junit.Assert.assertArrayEquals(new String[]{"/system/bin/input","-d","9","keyevent","4"},Policy.backCommand(9,java.util.Arrays.asList(9,43)));
        for(int id:new int[]{0,-1,7}){
            try{Policy.backCommand(id,java.util.Arrays.asList(9,43));org.junit.Assert.fail("Rejected display expected");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void primarySelectionRequiresExplicitOptIn(){
        assertEquals(0,Policy.selectDisplay(9,Arrays.asList(0,9),true));
        assertEquals(-1,Policy.selectDisplay(9,Arrays.asList(9),true));
        assertEquals(-1,Policy.selectDisplay(0,Arrays.asList(0),false));
    }
    @Test public void primaryCommandsAreRevocable(){
        Policy.requireTarget(0,Arrays.asList(0),true);
        assertEquals("0",Policy.launchCommand("com.example/.Main",0,5,true)[5]);
        assertEquals("0",Policy.backCommand(0,Arrays.asList(0),true)[2]);
        assertThrows(IllegalArgumentException.class,()->Policy.launchCommand("com.example/.Main",0,5,false));
        assertThrows(IllegalArgumentException.class,()->Policy.backCommand(0,Arrays.asList(0),false));
        assertThrows(IllegalArgumentException.class,()->Policy.requireTarget(-1,Arrays.asList(-1,0),true));
        assertThrows(IllegalArgumentException.class,()->Policy.requireTarget(0,Arrays.asList(9),true));
    }
}
