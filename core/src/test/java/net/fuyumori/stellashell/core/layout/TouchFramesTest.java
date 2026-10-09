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
