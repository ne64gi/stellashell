package net.fuyumori.stellashell;
import org.junit.Test;
import static org.junit.Assert.*;
public class WindowGeometryTest {
 @Test public void taskbarRemainsOutsideWorkArea(){assertArrayEquals(new int[]{0,0,1280,660},WindowGeometry.area(1280,720,60));}
 @Test public void movingPastEdgePreservesSize(){assertArrayEquals(new int[]{680,260,1280,660},WindowGeometry.clamp(1000,500,1600,900,1280,660,240,160));}
 @Test public void resizeCannotCollapseBelowMinimum(){assertArrayEquals(new int[]{10,20,250,180},WindowGeometry.clamp(10,20,11,21,1280,660,240,160));}
 @Test public void oversizedWindowFitsSmallDisplay(){assertArrayEquals(new int[]{0,0,180,100},WindowGeometry.clamp(-90,-80,900,800,180,100,240,160));}
 @Test public void rejectsInvertedBounds(){assertThrows(IllegalArgumentException.class,()->WindowGeometry.clamp(100,0,20,100,1000,700,240,160));}
}
