package net.fuyumori.stellashell;

import org.junit.Test;
import static org.junit.Assert.*;

public class DockPlacementTest {
    @Test public void percentagesTravelInsideInsetSafeArea(){
        assertArrayEquals(new int[]{380,688,780,740},DockPlacement.bounds(30,40,1130,740,400,52,50,100));
        assertArrayEquals(new int[]{30,40,430,92},DockPlacement.bounds(30,40,1130,740,400,52,-20,-1));
        assertArrayEquals(new int[]{730,688,1130,740},DockPlacement.bounds(30,40,1130,740,400,52,200,200));
    }
    @Test public void crowdedAxisCapsViewportAndDoesNotClipCrossAxis(){
        assertArrayEquals(new int[]{17,21,257,321},DockPlacement.bounds(17,21,257,321,1700,900,50,100));
        assertArrayEquals(new int[]{99,21,175,321},DockPlacement.bounds(17,21,257,321,76,1400,50,0));
        assertArrayEquals(new int[]{17,145,257,197},DockPlacement.bounds(17,21,257,321,1200,52,50,50));
    }
    @Test public void menuFollowsLeadingDockSideAndSelectedEdge(){
        assertArrayEquals(new int[]{300,232},DockPlacement.menu(0,0,1200,800,400,400,12,"bottom",new int[]{300,644,900,696}));
        assertArrayEquals(new int[]{300,164},DockPlacement.menu(0,0,1200,800,400,400,12,"top",new int[]{300,100,900,152}));
        assertArrayEquals(new int[]{188,100},DockPlacement.menu(0,0,1200,800,400,400,12,"left",new int[]{100,100,176,700}));
        assertArrayEquals(new int[]{488,100},DockPlacement.menu(0,0,1200,800,400,400,12,"right",new int[]{900,100,976,700}));
        assertArrayEquals(new int[]{4,8},DockPlacement.menu(4,8,5,9,1,1,12,"top",new int[]{4,8,5,9}));
    }
}
