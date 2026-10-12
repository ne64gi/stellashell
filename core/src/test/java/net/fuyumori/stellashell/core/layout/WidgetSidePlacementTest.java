package net.fuyumori.stellashell.core.layout;

import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetSidePlacementTest {
    @Test public void railNeighborMovesToOppositeSide(){
        int left=WidgetSidePlacement.x(72,240,360,false),right=WidgetSidePlacement.x(72,240,360,true);
        assertEquals(72,left);assertEquals(48,right);
        // Both positions leave room for a 56dp rail on the opening edge.
        assertTrue(left>=56);assertTrue(right+240<=360-56);
    }
    @Test public void editingRoundTripsWithoutProgressiveDrift(){
        for(int width:new int[]{80,240,360})for(int x=0;x<=360-width;x++){
            int display=WidgetSidePlacement.x(x,width,360,true);
            assertEquals(x,WidgetSidePlacement.x(display,width,360,true));
        }
    }
    @Test public void fullWidthAndClippedItemsStayInsideCanvas(){
        assertEquals(0,WidgetSidePlacement.x(24,360,360,true));
        assertEquals(0,WidgetSidePlacement.x(24,500,360,true));
        assertEquals(0,WidgetSidePlacement.x(24,80,0,true));
        assertEquals(280,WidgetSidePlacement.x(-40,80,360,true));
        assertEquals(0,WidgetSidePlacement.x(999,80,360,true));
    }
    @Test public void resizedWidgetKeepsItsVisibleLeftEdgeWhenSaved(){
        int visibleLeft=40,newWidth=180;
        int saved=WidgetSidePlacement.x(visibleLeft,newWidth,360,true);
        assertEquals(140,saved);
        assertEquals(visibleLeft,WidgetSidePlacement.x(saved,newWidth,360,true));
    }
}
