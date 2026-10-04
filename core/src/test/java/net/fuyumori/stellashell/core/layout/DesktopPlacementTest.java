package net.fuyumori.stellashell.core.layout;
import org.junit.Test;
import static org.junit.Assert.*;
public class DesktopPlacementTest {
    @Test public void freePlacementDoesNotQuantize(){assertArrayEquals(new int[]{137,219},DesktopPlacement.fit(137,219,104,96,1280,656,false));}
    @Test public void keepsIconAboveDockAndInsideRightEdge(){assertArrayEquals(new int[]{1176,560},DesktopPlacement.fit(1500,900,104,96,1280,656,false));}
    @Test public void snapIsOptionalAndClampedAfterRounding(){assertArrayEquals(new int[]{144,216},DesktopPlacement.fit(137,219,104,96,1280,656,true));assertArrayEquals(new int[]{896,0},DesktopPlacement.fit(1000,-20,104,96,1000,656,true));}
    @Test public void tinyDisplayNeverProducesNegativeCoordinates(){assertArrayEquals(new int[]{0,0},DesktopPlacement.fit(400,300,104,96,80,60,false));}
}
