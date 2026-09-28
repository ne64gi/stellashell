package net.fuyumori.stellashell;
import org.junit.Test;
import static org.junit.Assert.*;
public class WidgetGeometryTest {
    @Test public void scaleFitsWithoutDistortingOrCropping(){
        assertEquals(2f,WidgetGeometry.scale(800,300,200,150),0.0001f);
        assertEquals(0.5f,WidgetGeometry.scale(100,300,200,150),0.0001f);
    }
    @Test public void tinyViewportNeverProducesZeroOrInvalidScale(){
        assertTrue(Float.isFinite(WidgetGeometry.scale(0,0,0,0)));
        assertTrue(WidgetGeometry.scale(0,0,200,150)>0);
    }
    @Test public void resizeHonorsProviderAndScreenBounds(){
        assertEquals(200,WidgetGeometry.resize(50,1000,200,600));
        assertEquals(600,WidgetGeometry.resize(800,1000,200,600));
        assertEquals(100,WidgetGeometry.resize(800,100,200,600));
        assertEquals(800,WidgetGeometry.resize(800,1000,80,0));
        assertEquals(800,WidgetGeometry.resize(800,1000,200,100));
    }
    @Test public void unknownStoredModeFallsBackToNormal(){
        assertEquals(WidgetGeometry.NORMAL,WidgetGeometry.mode(-1));
        assertEquals(WidgetGeometry.NORMAL,WidgetGeometry.mode(3));
        assertEquals(WidgetGeometry.SCALE,WidgetGeometry.mode(2));
    }
}
