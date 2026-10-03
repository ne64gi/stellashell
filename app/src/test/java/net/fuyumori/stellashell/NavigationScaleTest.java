package net.fuyumori.stellashell;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationScaleTest {
    @Test public void valuesAreBoundedAndSnapToSupportedSteps(){
        assertEquals(100,NavigationScale.percent(NavigationScale.DEFAULT));
        assertEquals(50,NavigationScale.percent(Integer.MIN_VALUE));
        assertEquals(200,NavigationScale.percent(Integer.MAX_VALUE));
        assertEquals(100,NavigationScale.percent(104));
        assertEquals(110,NavigationScale.percent(105));
        assertEquals(200,NavigationScale.percent(199));
    }
    @Test public void fourSurfacePreferencesNeverShareAKey(){
        assertEquals("phone_dock_scale",NavigationScale.key(true,false));
        assertEquals("desktop_dock_scale",NavigationScale.key(false,false));
        assertEquals("phone_taskbar_scale",NavigationScale.key(true,true));
        assertEquals("desktop_taskbar_scale",NavigationScale.key(false,true));
    }
    @Test public void sizingPreservesPaddingZeroAndIndependentSurfaceSizes(){
        assertEquals(0,NavigationScale.pixels(2,0,200));
        assertEquals(1,NavigationScale.pixels(.5f,1,50));
        assertEquals(60,NavigationScale.pixels(2,60,50));
        assertEquals(120,NavigationScale.pixels(2,60,100));
        assertEquals(240,NavigationScale.pixels(2,60,200));
        int taskbar=NavigationScale.pixels(1,60,200),dock=NavigationScale.pixels(1,52,50);
        assertArrayEquals(new int[]{400,554,600,580},DockPlacement.bounds(0,0,1000,700-taskbar,200,dock,50,100));
    }
}
