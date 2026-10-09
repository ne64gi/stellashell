package net.fuyumori.stellashell.core.layout;

import org.junit.Test;
import static org.junit.Assert.*;

public class EdgeDockRevealTest {
    private EdgeDockReveal.Taps taps(EdgeDockReveal.Method method) { return new EdgeDockReveal.Taps(method,8,30,500,300); }
    @Test public void singleTapRequiresAStationaryShortRelease() {
        EdgeDockReveal.Taps tap=taps(EdgeDockReveal.Method.SINGLE_TAP);
        tap.begin(20,40,100);assertTrue(tap.finish(23,42,180));assertFalse(tap.finish(23,42,190));
        tap.begin(20,40,200);tap.move(50,40);assertFalse(tap.finish(20,40,280));
        tap.begin(20,40,300);assertFalse(tap.finish(20,40,800));
        tap.begin(20,40,900);assertFalse(tap.finish(Float.NaN,40,980));
        tap.begin(20,40,1000);tap.cancel();assertFalse(tap.finish(20,40,1080));
    }
    @Test public void doubleTapRequiresTwoNearbyTimelyReleases() {
        EdgeDockReveal.Taps tap=taps(EdgeDockReveal.Method.DOUBLE_TAP);
        tap.begin(20,40,100);assertFalse(tap.finish(20,40,180));
        tap.begin(22,40,380);assertTrue(tap.finish(22,40,450));
        assertFalse(tap.finish(22,40,460));
        tap.begin(20,40,500);assertFalse(tap.finish(20,40,580));
        tap.begin(20,40,881);assertFalse(tap.finish(20,40,950));
        tap.begin(60,40,1000);assertFalse(tap.finish(60,40,1080));
        tap.begin(60,40,1200);assertTrue(tap.finish(60,40,1280));
    }
    @Test public void interruptedTapSequencesNeverOpenOnALoneNextTap() {
        for(int interruption=0;interruption<3;interruption++) {
            EdgeDockReveal.Taps tap=taps(EdgeDockReveal.Method.DOUBLE_TAP);
            tap.begin(20,40,100);assertFalse(tap.finish(20,40,180));
            if(interruption==0)tap.cancel();
            else {
                tap.begin(20,40,200);
                if(interruption==1){tap.move(80,40);assertFalse(tap.finish(20,40,240));}
                else assertFalse(tap.finish(20,40,701));
            }
            tap.begin(20,40,750);assertFalse(tap.finish(20,40,780));
            tap.begin(20,40,850);assertTrue(tap.finish(20,40,880));
        }
    }
    @Test public void handleIsOneInsetSafePointOnChosenEdge() {
        assertArrayEquals(new int[]{28,258,44,322}, EdgeDockReveal.handle(20,30,980,550,"left",50,16,64,8));
        assertArrayEquals(new int[]{956,258,972,322}, EdgeDockReveal.handle(20,30,980,550,"right",50,16,64,8));
        assertArrayEquals(new int[]{468,38,532,54}, EdgeDockReveal.handle(20,30,980,550,"top",50,16,64,8));
        assertArrayEquals(new int[]{468,526,532,542}, EdgeDockReveal.handle(20,30,980,550,"bottom",50,16,64,8));
    }
    @Test public void panelAnchorsToEdgeWithoutReservingAnotherStrip() {
        assertArrayEquals(new int[]{20,120,96,420},EdgeDockReveal.panel(20,30,980,690,"left",25,76,300));
        assertArrayEquals(new int[]{210,30,410,90},EdgeDockReveal.panel(20,30,980,550,"top",25,200,60));
        assertArrayEquals(new int[]{210,490,410,550},EdgeDockReveal.panel(20,30,980,550,"bottom",25,200,60));
        assertArrayEquals(new int[]{904,120,980,420},EdgeDockReveal.panel(20,30,980,690,"right",25,76,300));
    }
    @Test public void allPositionsAndSizesStayWithinCrowdedSafeArea() {
        for(String edge:new String[]{"left","right","top","bottom"})for(int position:new int[]{-100,0,50,100,200}) {
            int[] handle=EdgeDockReveal.handle(19,23,26,34,edge,position,32,128,8);
            int[] panel=EdgeDockReveal.panel(19,23,26,34,edge,position,800,500);
            for(int[] value:new int[][]{handle,panel}) {
                assertTrue(value[0]>=19&&value[1]>=23&&value[2]<=26&&value[3]<=34);
                assertTrue(value[2]>value[0]&&value[3]>value[1]);
            }
        }
    }
    @Test public void physicalOrientationDoesNotDependOnImeViewport() {
        assertTrue(EdgeDockReveal.landscape(1600,900));
        assertFalse(EdgeDockReveal.landscape(900,1600));
        assertFalse(EdgeDockReveal.landscape(900,900));
        assertFalse(EdgeDockReveal.landscape(0,900));
    }
    @Test public void allFourEdgesRequireDeliberateHalfTravel() {
        String[] edges={"left","right","top","bottom"};
        float[][] direction={{1,0},{-1,0},{0,1},{0,-1}};
        for(int i=0;i<edges.length;i++) {
            EdgeDockReveal.Pull pull=new EdgeDockReveal.Pull(edges[i],16,300);
            assertEquals(-1,pull.update(direction[i][0]*15,direction[i][1]*15),0);
            assertFalse(pull.finish(false));
            assertEquals(.25f,pull.update(direction[i][0]*75,direction[i][1]*75),0);
            assertFalse(pull.finish(false));
            assertEquals(.5f,pull.update(direction[i][0]*150,direction[i][1]*150),0);
            assertTrue(pull.finish(false));
            assertFalse(pull.finish(true));
            assertEquals(1,pull.update(direction[i][0]*500,direction[i][1]*500),0);
            assertEquals(0,pull.update(-direction[i][0]*50,-direction[i][1]*50),0);
            assertFalse(pull.finish(false));
        }
    }
    @Test public void tapsOutwardDiagonalsAndLateSidewaysDriftNeverCommit() {
        EdgeDockReveal.Pull pull=new EdgeDockReveal.Pull("left",16,100);
        assertEquals(-1,pull.update(0,0),0);
        assertEquals(-1,pull.update(-80,0),0);
        assertEquals(-1,pull.update(80,80),0);
        assertFalse(pull.finish(false));
        assertEquals(.8f,pull.update(80,0),0);
        assertEquals(0,pull.update(80,100),0);
        assertFalse(pull.finish(false));
        assertEquals(.8f,pull.update(80,0),0);
        assertEquals(-1,pull.update(Float.NaN,0),0);
        assertFalse(pull.finish(false));
    }
}
