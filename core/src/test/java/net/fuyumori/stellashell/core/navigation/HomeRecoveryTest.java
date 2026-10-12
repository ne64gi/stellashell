package net.fuyumori.stellashell.core.navigation;

import org.junit.Test;
import static org.junit.Assert.*;
import static net.fuyumori.stellashell.core.navigation.HomeRecovery.Action.*;

public class HomeRecoveryTest {
    @Test public void secondDistinctHomeOpensAndThirdIsNormalHome() {
        HomeRecovery recovery = new HomeRecovery();
        assertEquals(HOME, recovery.press(100));
        assertEquals(OPEN_START, recovery.press(400));
        assertEquals(HOME, recovery.press(700));
        assertEquals(OPEN_START, recovery.press(1000));
    }
    @Test public void duplicateDeliveriesNeverCountOrCloseTheOpenedMenu() {
        HomeRecovery recovery = new HomeRecovery();
        assertEquals(HOME, recovery.press(0));
        assertEquals(DUPLICATE, recovery.press(20));
        assertEquals(DUPLICATE, recovery.press(119));
        assertEquals(OPEN_START, recovery.press(400));
        assertEquals(DUPLICATE, recovery.press(420));
        assertEquals(DUPLICATE, recovery.press(519));
        assertEquals(HOME, recovery.press(800));
    }
    @Test public void slowHomesStartFreshPairs() {
        HomeRecovery recovery = new HomeRecovery();
        assertEquals(HOME, recovery.press(0));
        assertEquals(HOME, recovery.press(601));
        assertEquals(OPEN_START, recovery.press(1201));
    }
    @Test public void minimumDistinctIntervalIsAccepted() {
        HomeRecovery recovery = new HomeRecovery();
        assertEquals(HOME, recovery.press(0));
        assertEquals(OPEN_START, recovery.press(120));
    }
    @Test public void queuedHomeAfterSlowMenuAttachmentCannotImmediatelyCloseIt() {
        HomeRecovery recovery = new HomeRecovery();
        assertEquals(HOME, recovery.press(0));
        assertEquals(OPEN_START, recovery.press(250));
        recovery.startShown(1000);
        assertEquals(DUPLICATE, recovery.press(1001));
        assertEquals(DUPLICATE, recovery.press(1119));
        assertEquals(HOME, recovery.press(1250));
        assertEquals(OPEN_START, recovery.press(1500));
    }
    @Test public void resetAndBackwardsClockDoNotCompleteOldPairs() {
        HomeRecovery recovery = new HomeRecovery();
        assertEquals(HOME, recovery.press(1000));
        recovery.reset();
        assertEquals(HOME, recovery.press(1200));
        assertEquals(HOME, recovery.press(1100));
        assertEquals(OPEN_START, recovery.press(1400));
        recovery.reset();
        assertEquals(HOME, recovery.press(1420));
    }
}
