package net.fuyumori.stellashell;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ChromeOcclusionTest {
    @Test public void middleOverlapLeavesTwoClickableIslands(){
        List<int[]> p=ChromeOcclusion.visible(new int[]{0,10,100,30},Collections.singletonList(new int[]{40,0,60,80}));
        assertEquals(2,p.size());assertArrayEquals(new int[]{0,10,40,30},p.get(0));assertArrayEquals(new int[]{60,10,100,30},p.get(1));
    }
    @Test public void fullyCoveredAndZeroHeightHaveNoInputWindow(){
        assertTrue(ChromeOcclusion.visible(new int[]{0,0,100,20},Collections.singletonList(new int[]{-1,-1,110,21})).isEmpty());
        assertTrue(ChromeOcclusion.visible(new int[]{0,0,100,0},Collections.emptyList()).isEmpty());
    }
    @Test public void overlappingBlockersNeverLeaveHiddenOrDuplicatePixels(){
        List<int[]> blockers=Arrays.asList(new int[]{20,10,60,40},new int[]{40,20,90,60},new int[]{0,50,100,80});
        List<int[]> p=ChromeOcclusion.visible(new int[]{0,0,100,80},blockers);
        for(int y=0;y<80;y++)for(int x=0;x<100;x++){
            boolean blocked=false;for(int[] b:blockers)blocked|=inside(x,y,b);
            int count=0;for(int[] r:p)if(inside(x,y,r))count++;
            assertEquals("pixel "+x+","+y,blocked?0:1,count);
        }
    }
    @Test public void touchingEdgesDoNotOcclude(){
        List<int[]> p=ChromeOcclusion.visible(new int[]{0,0,100,20},Collections.singletonList(new int[]{100,0,200,20}));
        assertEquals(1,p.size());assertArrayEquals(new int[]{0,0,100,20},p.get(0));
    }
    @Test public void widgetPanelClipsOnlyOverlappingCaptionPart(){
        List<int[]> panel=Collections.singletonList(new int[]{700,20,1000,750});
        List<int[]> untouched=ChromeOcclusion.visible(new int[]{20,40,600,72},panel);
        assertEquals(1,untouched.size());assertArrayEquals(new int[]{20,40,600,72},untouched.get(0));
        List<int[]> clipped=ChromeOcclusion.visible(new int[]{500,40,900,72},panel);
        assertEquals(1,clipped.size());assertArrayEquals(new int[]{500,40,700,72},clipped.get(0));
    }
    private static boolean inside(int x,int y,int[] r){return x>=r[0]&&x<r[2]&&y>=r[1]&&y<r[3];}
}
