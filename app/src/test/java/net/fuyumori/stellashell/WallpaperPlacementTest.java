package net.fuyumori.stellashell;
import org.junit.Test;
import static org.junit.Assert.*;
public class WallpaperPlacementTest {
    @Test public void fillNeverExposesBackgroundAtEdges(){
        for(float x:new float[]{0,.25f,.5f,1})for(float y:new float[]{0,.5f,1}){
            float[] m=WallpaperPlacement.transform(1600,900,400,800,false,x,y);
            assertTrue(m[1]<=0);assertTrue(m[2]<=0);
            assertTrue(m[1]+1600*m[0]>=400-.001f);assertTrue(m[2]+900*m[0]>=800-.001f);
        }
    }
    @Test public void phoneAndDesktopCropIndependently(){
        float[] phone=WallpaperPlacement.transform(1600,900,400,800,false,.65f,.45f);
        float[] desktop=WallpaperPlacement.transform(1600,900,1600,900,false,.5f,.5f);
        assertTrue(phone[1]<0);assertEquals(0,desktop[1],.001f);assertEquals(0,desktop[2],.001f);
    }
    @Test public void fitCentersEntireImageAndIgnoresFocal(){
        assertArrayEquals(new float[]{.25f,0,287.5f},WallpaperPlacement.transform(1600,900,400,800,true,1,1),.001f);
    }
    @Test public void invalidInputsRemainFinite(){
        assertArrayEquals(new float[]{1,0,0},WallpaperPlacement.transform(0,0,400,800,false,0,0),0);
        assertEquals(.5f,WallpaperPlacement.clamp(Float.NaN),0);
    }
}
