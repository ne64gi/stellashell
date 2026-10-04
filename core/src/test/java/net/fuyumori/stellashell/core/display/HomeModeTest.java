package net.fuyumori.stellashell.core.display;

import org.junit.Test;
import static org.junit.Assert.*;

public class HomeModeTest {
    @Test public void stoppedOrDisconnectedExtensionsNeverBlockBasicHome(){
        for(int flags=0;flags<16;flags++){
            boolean extended=HomeMode.extensions((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0);
            assertEquals("Capabilities "+flags,flags==15,extended);
        }
    }
}
