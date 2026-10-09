package net.fuyumori.stellashell.core.navigation;

import org.junit.Test;
import static org.junit.Assert.*;

public class HomeRecoveryTest {
    @Test public void thirdDistinctHomeOpensOnce() {
        HomeRecovery recovery = new HomeRecovery();
        assertFalse(recovery.press(100));
        assertFalse(recovery.press(400));
        assertTrue(recovery.press(700));
        assertFalse(recovery.press(1000));
    }
    @Test public void duplicateDeliveriesDoNotCount() {
        HomeRecovery recovery = new HomeRecovery();
        assertFalse(recovery.press(0));
        assertFalse(recovery.press(20));
        assertFalse(recovery.press(400));
        assertFalse(recovery.press(420));
        assertTrue(recovery.press(800));
    }
    @Test public void SlowOrInterruptedHomesNeverAccumulate() {
        HomeRecovery recovery = new HomeRecovery();
        assertFalse(recovery.press(0));
        assertFalse(recovery.press(1000));
        assertFalse(recovery.press(1600));
        assertFalse(recovery.press(1900));
        recovery.reset();
        assertFalse(recovery.press(2000));
        assertFalse(recovery.press(1800));
        assertFalse(recovery.press(2200));
        assertTrue(recovery.press(2500));
    }
}
