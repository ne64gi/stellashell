package net.fuyumori.stellashell;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShellPanelsTest {
    @Test public void replacementClosesOnlyPreviousAndIgnoresItsLateRelease(){
        Object first=new Object(),second=new Object();int[] closed={0};
        try{
            ShellPanels.activate(101,first,()->{closed[0]++;ShellPanels.release(101,first);});
            ShellPanels.activate(101,second,()->closed[0]++);
            assertEquals(1,closed[0]);assertTrue(ShellPanels.isOpen(101));
            ShellPanels.release(101,first);assertTrue(ShellPanels.isOpen(101));
            ShellPanels.dismiss(101);assertEquals(2,closed[0]);assertFalse(ShellPanels.isOpen(101));
        }finally{ShellPanels.dismiss(101);}
    }
    @Test public void resumeIsIdempotentAndOtherDisplayIsUntouched(){
        Object first=new Object(),other=new Object();int[] closed={0};
        try{
            ShellPanels.activate(102,first,()->closed[0]++);
            ShellPanels.activate(103,other,()->closed[0]++);
            ShellPanels.activate(102,first,()->closed[0]++);assertEquals(0,closed[0]);
            ShellPanels.dismiss(102);assertTrue(ShellPanels.isOpen(103));assertEquals(1,closed[0]);
        }finally{ShellPanels.dismiss(102);ShellPanels.dismiss(103);}
    }
    @Test public void onlyKnownTranslucentShellActivitiesAreExempt(){
        assertTrue(ShellPanels.panelTask("net.fuyumori.stellashell/.HubActivity"));
        assertTrue(ShellPanels.panelTask("net.fuyumori.stellashell/net.fuyumori.stellashell.QuickSettingsActivity"));
        assertFalse(ShellPanels.panelTask("net.fuyumori.stellashell/.SetupActivity"));
        assertFalse(ShellPanels.panelTask("example/.HubActivity"));
    }
}
