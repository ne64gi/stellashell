package net.fuyumori.stellashell.core.layout;

import org.junit.Test;
import static org.junit.Assert.*;

public class CornerDockGestureTest {
    private CornerDockGesture begin(){CornerDockGesture g=new CornerDockGesture();g.begin(160,300,100,400,800);return g;}
    @Test public void roundedDeliberateStrokeAllowsNaturalPaceAndVerticalDrift(){
        for(int sign:new int[]{-1,1}){
            CornerDockGesture g=begin();
            g.move(160+sign*4,270,300);g.move(160+sign*8,245,500);
            g.move(160+sign*20,225,650);g.move(160+sign*42,220,850);
            assertNotNull(g.finish(160+sign*64,220,1200));
        }
    }
    @Test public void localPracticeDoesNotHaveInvisibleSystemBarExclusionZones(){
        CornerDockGesture g=new CornerDockGesture();g.begin(100,205,100,300,220);
        g.move(100,145,300);assertNotNull(g.finish(156,145,550));
    }
    @Test public void largeRoundedStrokesFitObservedHumanMovementEnvelope(){
        // Synthetic paths cover the measured distance/time envelope; no user coordinates retained.
        for(int sign:new int[]{-1,1})for(int up:new int[]{150,220,340})for(int duration:new int[]{350,1100}){
            CornerDockGesture g=new CornerDockGesture();g.begin(400,700,100,800,1000);
            float[][] points={{400,700},{400+sign*16,700-up*.65f},{400,700-up},
                    {400+sign*45,700-up-14},{400+sign*150,700-up+40}};
            for(int segment=1;segment<points.length;segment++)for(int step=1;step<=12;step++){
                float f=step/12f;
                g.move(points[segment-1][0]+(points[segment][0]-points[segment-1][0])*f,
                        points[segment-1][1]+(points[segment][1]-points[segment-1][1])*f,
                        100+(long)(duration*((segment-1)+f)/4));
            }
            assertNotNull("up="+up+", duration="+duration,g.finish(400+sign*150,700-up+40,100+duration));
        }
    }
    @Test public void denseStraightScrollAndDiagonalStillDoNotOpen(){
        for(float slope:new float[]{0,.5f,1,2})for(int sign:new int[]{-1,1}){
            CornerDockGesture g=new CornerDockGesture();g.begin(400,700,100,800,1000);
            for(int i=1;i<=30;i++)g.move(400+sign*i*10*slope,700-i*10,100+i*15);
            assertNull(g.finish(400+sign*300*slope,400,600));
        }
    }
    @Test public void deliberateBothDirectionsCommitOnlyOnRelease(){
        for(int d:new int[]{-1,1}){
            CornerDockGesture g=begin();g.move(160,258,220);g.move(160+d*48,258,350);
            CornerDockGesture.Result r=g.finish(160+d*48,258,400);assertNotNull(r);assertEquals(d>0,r.right);
            assertNull(g.finish(160+d*48,258,450));
        }
    }
    @Test public void scrollDiagonalDownAndHorizontalDoNotOpen(){
        float[][] moves={{160,200,160,150},{200,258,240,220},{160,345,210,345},{205,300,250,300}};
        for(float[] p:moves){CornerDockGesture g=begin();g.move(p[0],p[1],220);assertNull(g.finish(p[2],p[3],400));}
    }
    @Test public void longHoldReverseCancelAndInvalidTimeReject(){
        CornerDockGesture g=begin();g.move(160,258,220);assertNull(g.finish(210,258,1800));
        g=begin();g.move(160,258,220);g.move(215,258,300);g.move(190,258,350);assertNull(g.finish(215,258,400));
        g=begin();g.move(160,258,220);g.cancel();assertNull(g.finish(210,258,400));
        g=begin();g.move(160,258,220);assertNull(g.finish(210,258,200));
        g=begin();g.move(160,258,220);assertNull(g.finish(Float.NaN,258,400));
    }
    @Test public void systemEdgesAndShortTurnsReject(){
        CornerDockGesture g=new CornerDockGesture();g.beginOnScreen(160,780,100,400,800);g.move(160,730,200);assertNull(g.finish(210,730,300));
        g=begin();g.move(160,258,220);assertNull(g.finish(180,258,400));
        g=begin();g.move(160,280,220);assertNull(g.finish(210,280,400));
    }
    @Test public void panelClampsAndExtendsTowardGesture(){
        assertArrayEquals(new int[]{80,80,240,140},FloatingDockPlacement.bounds(0,0,400,800,160,60,80,110,true));
        assertArrayEquals(new int[]{80,80,240,140},FloatingDockPlacement.bounds(0,0,400,800,160,60,240,110,false));
        assertArrayEquals(new int[]{180,670,380,750},FloatingDockPlacement.bounds(10,30,380,750,200,80,360,790,true));
        assertArrayEquals(new int[]{10,30,380,750},FloatingDockPlacement.bounds(10,30,380,750,800,900,0,0,false));
    }
}
