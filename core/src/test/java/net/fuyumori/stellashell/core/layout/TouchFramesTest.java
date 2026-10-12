package net.fuyumori.stellashell.core.layout;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TouchFramesTest {
    private final List<String> events=new ArrayList<>();
    private float x,y;
    private TouchFrames frames(int rotation){return new TouchFrames(0,1000,0,2000,rotation,400,800,new TouchFrames.Listener(){
        public void down(float a,float b,long t){events.add("down");x=a;y=b;}
        public void move(float a,float b,long t){events.add("move");x=a;y=b;}
        public void up(float a,float b,long t){events.add("up");}
        public void cancel(){events.add("cancel");}
    });}
    private void contact(TouchFrames f,int slot,int id){f.event(3,0x2f,slot,0);f.event(3,0x39,id,0);f.event(3,0x35,250,0);f.event(3,0x36,600,0);f.event(0,0,0,100);}
    private void lift(TouchFrames f,int slot){f.event(3,0x2f,slot,0);f.event(3,0x39,-1,0);f.event(0,0,0,200);}
    @Test public void midTouchAttachmentAndKeyEventsCannotBegin(){
        TouchFrames f=frames(0);f.event(3,0x35,250,0);f.event(3,0x36,600,0);f.event(1,30,1,0);f.event(0,0,0,10);assertTrue(events.isEmpty());
        contact(f,0,7);lift(f,0);assertEquals(Arrays.asList("down","up"),events);
    }
    @Test public void rotationUsesLogicalDisplayCoordinates(){
        float[][] expected={{100,240},{120,600},{300,560},{280,200}};
        for(int r=0;r<4;r++){contact(frames(r),0,3);assertEquals(expected[r][0],x,.01f);assertEquals(expected[r][1],y,.01f);}
    }
    @Test public void screenUpThenSideRecognizesInBothLandscapesAndPortraits(){
        // Raw panel coordinates for the same screen-up leg at each device rotation.
        // Landscape really swaps the logical dimensions, unlike the point-only test.
        int[][] up={{500,1400,500,1200},{300,1000,500,1000},
                {500,600,500,800},{700,1000,500,1000}};
        int[][] rightEnd={{700,1200},{500,1200},{300,800},{500,800}};
        int[][] leftEnd={{300,1200},{500,800},{700,800},{500,1200}};
        for(int rotation=0;rotation<4;rotation++)for(boolean right:new boolean[]{true,false}){
            int width=rotation%2==0?400:800,height=rotation%2==0?800:400;
            CornerDockGesture gesture=new CornerDockGesture();CornerDockGesture.Result[] result={null};
            TouchFrames f=new TouchFrames(0,1000,0,2000,rotation,width,height,new TouchFrames.Listener(){
                public void down(float x,float y,long t){gesture.beginOnScreen(x,y,t,width,height);}
                public void move(float x,float y,long t){gesture.move(x,y,t);}
                public void up(float x,float y,long t){result[0]=gesture.finish(x,y,t);}
                public void cancel(){gesture.cancel();}
            });
            int[] path=up[rotation],end=(right?rightEnd:leftEnd)[rotation];
            f.event(3,0x39,7,100);point(f,path[0],path[1],100);
            for(int step=1;step<=10;step++)point(f,path[0]+(path[2]-path[0])*step/10,path[1]+(path[3]-path[1])*step/10,100+step*20);
            for(int step=1;step<=10;step++)point(f,path[2]+(end[0]-path[2])*step/10,path[3]+(end[1]-path[3])*step/10,300+step*20);
            f.event(3,0x39,-1,550);f.event(0,0,0,550);
            assertNotNull("rotation="+rotation+", right="+right,result[0]);
            assertEquals(right,result[0].right);
            assertEquals(width/2f+(right?80:-80),result[0].x,.01f);
            assertEquals(height*.7f-80,result[0].y,.01f);
        }
    }
    private void point(TouchFrames f,int x,int y,long time){
        f.event(3,0x35,x,time);f.event(3,0x36,y,time);f.event(0,0,0,time);
    }
    @Test public void secondFingerBlocksUntilAllLifted(){
        TouchFrames f=frames(0);contact(f,0,3);contact(f,1,4);lift(f,1);lift(f,0);
        assertFalse(events.contains("up"));events.clear();contact(f,0,5);lift(f,0);assertEquals(Arrays.asList("down","up"),events);
    }
    @Test public void droppedFramesRequireKnownAllUpBeforeNextTouch(){
        TouchFrames f=frames(0);contact(f,0,3);f.event(0,3,0,120);lift(f,0);contact(f,0,4);assertEquals(Arrays.asList("down","cancel"),events);
        f.event(1,0x14a,0,140);f.event(0,0,0,150);events.clear();contact(f,0,5);lift(f,0);assertEquals(Arrays.asList("down","up"),events);
    }
    @Test public void slotReplacementAndIncompleteCoordinatesCannotJoinGestures(){
        TouchFrames f=frames(0);contact(f,0,3);contact(f,0,4);lift(f,0);assertFalse(events.contains("up"));
        events.clear();f=frames(0);f.event(3,0x39,6,0);f.event(3,0x35,100,0);f.event(0,0,0,100);assertTrue(events.isEmpty());
    }
    @Test public void omittedUnchangedAxesOnReusedSlotRemainKnown(){
        TouchFrames f=frames(0);contact(f,0,3);lift(f,0);events.clear();
        f.event(3,0x39,8,0);f.event(3,0x36,800,0);f.event(0,0,0,300);
        assertEquals(Arrays.asList("down"),events);assertEquals(100,x,.01f);assertEquals(320,y,.01f);
    }
    @Test public void secondFingerEvenWithinOneFrameCancels(){
        TouchFrames f=frames(0);contact(f,0,3);
        f.event(3,0x2f,1,110);f.event(3,0x39,4,110);f.event(3,0x39,-1,110);f.event(0,0,0,120);
        lift(f,0);assertFalse(events.contains("up"));
    }
}
