package net.fuyumori.stellashell;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualDisplaySessionTest {
    @Test public void creationRecordsKeepDisplayAndProcessIdentity(){
        String logs="--------- beginning of main\n 100.005 123 125 I scrcpy : New display: 800x600/160 (id=7)\n"
                +" 101.250 456 459 I scrcpy : New display: 1280x720/240 (id=9)\n"
                +" 102.000 456 459 I other : New display: 1280x720/240 (id=12)\n";
        assertEquals(2,VirtualDisplaySession.creations(logs).size());
        VirtualDisplaySession.Creation c=VirtualDisplaySession.creations(logs).get(1);
        assertEquals(456,c.pid);assertEquals(9,c.display);assertEquals(101.25,c.time,0.0001);
    }
    @Test public void rejectsReusedPidAndOtherDisplay(){
        VirtualDisplaySession.Creation c=new VirtualDisplaySession.Creation(123,7,104.5);
        assertTrue(VirtualDisplaySession.belongs(c,7,400,100,100));
        assertFalse(VirtualDisplaySession.belongs(c,7,500,100,100));
        assertFalse(VirtualDisplaySession.belongs(c,8,400,100,100));
        assertFalse(VirtualDisplaySession.belongs(c,0,400,100,100));
        assertFalse(VirtualDisplaySession.belongs(c,7,400,100,0));
    }
    @Test public void onlyNewDisplayServerProcessIsEligible(){
        assertTrue(VirtualDisplaySession.command(String.join("\0","app_process","/","com.genymobile.scrcpy.Server","3.3.1","new_display=800x600/160")));
        assertFalse(VirtualDisplaySession.command("sh\0-c\0app_process / com.genymobile.scrcpy.Server 3.3.1 new_display=\0"));
        assertFalse(VirtualDisplaySession.command(String.join("\0","app_process","/","com.genymobile.scrcpy.Server","3.3.1","display_id=0")));
        assertFalse(VirtualDisplaySession.command(String.join("\0","app_process","/","com.genymobile.scrcpy.CleanUp","-1","new_display=")));
    }
    @Test public void processNameWithSpacesDoesNotShiftStartTime(){
        String[] fields=new String[20];java.util.Arrays.fill(fields,"0");fields[0]="S";fields[19]="987654";
        assertEquals(987654,VirtualDisplaySession.startTicks("123 (app process (x)) "+String.join(" ",fields)));
    }
}
