package net.fuyumori.stellashell.core.layout;

import org.junit.Test;
import static org.junit.Assert.*;

public class HubPullAxisTest {
    @Test public void allEdgesExposeTheBoundaryFollowingInwardTravel() {
        for (String edge : new String[]{"left", "right", "top", "bottom"}) {
            HubPullAxis axis = new HubPullAxis(edge);
            assertEquals("top".equals(edge) || "bottom".equals(edge), axis.vertical);
            boolean reverse = "right".equals(edge) || "bottom".equals(edge);
            assertEquals(reverse, axis.reverse);
            assertEquals(axis.vertical, axis.vertical());assertEquals(reverse, axis.negative());
            float primary=reverse?-50:50;
            assertEquals(50,axis.inward(axis.vertical?7:primary,axis.vertical?primary:7),0);
            assertEquals(7,axis.cross(axis.vertical?7:primary,axis.vertical?primary:7),0);
            assertEquals(0,axis.inward(axis.vertical?100:0,axis.vertical?0:100),0);
            assertEquals(reverse ? 40 : 340, axis.leading(40, 300), 0);
            float range = axis.range(40, 300, 12, null);
            assertEquals(312, range, 0);
            float hidden = axis.translation(range, 0), middle = axis.translation(range, .5f);
            assertEquals(reverse ? 312 : -312, hidden, 0);
            assertEquals(hidden / 2, middle, 0);
            assertEquals(0, axis.translation(range, 1), 0);
        }
    }

    @Test public void floatingDockOriginRemainsOnTheSelectedAxis() {
        for (String edge : new String[]{"left", "right", "top", "bottom"}) {
            HubPullAxis axis = new HubPullAxis(edge);
            float origin = axis.reverse ? 510 : 170;
            float range = axis.range(40, 300, 12, origin);
            assertEquals(origin, axis.leading(40 + axis.translation(range, 0), 300), 0);
            float halfway = axis.leading(40 + axis.translation(range, .5f), 300);
            assertEquals((origin + axis.leading(40, 300)) / 2, halfway, 0);
        }
    }

    @Test public void retreatAndInvalidDistancesCannotEscapeTheViewport() {
        HubPullAxis axis = new HubPullAxis("bottom");
        assertEquals(0, axis.progress(-20, 300), 0);
        assertEquals(0, axis.progress(Float.NaN, 300), 0);
        assertEquals(0, axis.progress(Float.POSITIVE_INFINITY, 300), 0);
        assertEquals(1, axis.progress(900, 300), 0);
        assertEquals(1, axis.range(40, 300, 12, 10f), 0);
        assertEquals(312, axis.range(40, 300, 12, Float.NaN), 0);
    }
}
