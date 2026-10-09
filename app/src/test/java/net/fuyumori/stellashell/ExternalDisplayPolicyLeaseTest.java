package net.fuyumori.stellashell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Focused contract tests for the reconnect lease's pure transition state. */
public final class ExternalDisplayPolicyLeaseTest {
    private static final String DISPLAY_IDENTITY = "owned-physical-output";
    private static final int RECONNECTED_DISPLAY_ID = 42;

    private ExternalDisplayPolicy.LeaseState newLease() {
        return new ExternalDisplayPolicy.LeaseState();
    }

    private ExternalDisplayPolicy.LeaseState armedFromKnownRemoval() {
        ExternalDisplayPolicy.LeaseState lease = newLease();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.enable(true, true, true, "1", 1));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO,
                lease.onRemoved(7, DISPLAY_IDENTITY, 0));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.armFinished(true, true));
        return lease;
    }

    private ExternalDisplayPolicy.LeaseState armedWhileInitiallyUnplugged() {
        ExternalDisplayPolicy.LeaseState lease = newLease();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO,
                lease.enable(true, true, true, "1", 0));
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.ARMING, lease.phase());
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.armFinished(true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.ARMED, lease.phase());
        return lease;
    }

    private ExternalDisplayPolicy.LeaseState beginAddedCandidate(int displayId, String identity) {
        ExternalDisplayPolicy.LeaseState lease = armedFromKnownRemoval();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                lease.onAdded(displayId, identity, 1, 1, true));
        return lease;
    }

    @Test
    public void leaseOnlyArmsForAnApplicableLiveOwnerWithBaselineAndNoPublicDisplay() {
        assertNoArm(false, true, true, "1", 0);
        assertNoArm(true, false, true, "1", 0);
        assertNoArm(true, true, false, "1", 0);
        assertNoArm(true, true, true, "0", 0);
        assertNoArm(true, true, true, "1", 2);

        // A connected display is observed, not toggled. Its later removal is the event
        // that can establish a disconnected generation.
        ExternalDisplayPolicy.LeaseState connected = newLease();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                connected.enable(true, true, true, "1", 1));
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.WATCHING, connected.phase());
        connected.select(7);
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO,
                connected.onRemoved(7, DISPLAY_IDENTITY, 0));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, connected.armFinished(true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                connected.onAdded(RECONNECTED_DISPLAY_ID, "new-physical-output", 1, 1, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                connected.restoreFinished(true, true, true, true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.RESTORED_WAITING_SELECTION,
                connected.phase());
    }

    @Test
    public void removedSelectedIdCannotAuthorizePulseWhenTheNumericIdIsReused() {
        ExternalDisplayPolicy.LeaseState lease = newLease();
        lease.enable(true, true, true, "1", 1);
        lease.select(7);
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO,
                lease.onRemoved(7, DISPLAY_IDENTITY, 0));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, lease.armFinished(true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                lease.onAdded(7, "replacement-physical-output", 1, 1, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.restoreFinished(true, true, true, true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.RESTORED_WAITING_SELECTION,
                lease.phase());
    }

    private void assertNoArm(boolean applicable, boolean sessionEnabled, boolean ownerAlive,
            String forceValue, int publicCount) {
        ExternalDisplayPolicy.LeaseState lease = newLease();
        assertNotEquals(ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO,
                lease.enable(applicable, sessionEnabled, ownerAlive, forceValue, publicCount));
        if (applicable && sessionEnabled && ownerAlive) {
            assertEquals(ExternalDisplayPolicy.LeaseState.Phase.WATCHING, lease.phase());
            // An unsupported baseline may still observe attach/detach events, but
            // does not acquire ownership or authorize a settings mutation.
            assertEquals("1".equals(forceValue) ? "1" : null, lease.baseline());
            assertFalse(lease.ownsZero());
        } else {
            assertEquals(ExternalDisplayPolicy.LeaseState.Phase.INACTIVE, lease.phase());
        }
    }

    @Test
    public void initiallyUnpluggedStartupCanArmAndAcceptsOnlyOneSolePhysicalAttach() {
        ExternalDisplayPolicy.LeaseState lease = armedWhileInitiallyUnplugged();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                lease.onAdded(RECONNECTED_DISPLAY_ID, "fresh-physical-output", 1, 1, true));

        // An extra public display, a virtual/non-type-2 output, or multiple public
        // physical outputs are not pulse candidates, but force=1 is still restored.
        assertInvalidCandidateDoesNotPulse(2, 2, true);
        assertInvalidCandidateDoesNotPulse(1, 1, false);
        assertInvalidCandidateDoesNotPulse(1, 2, true);
    }

    private void assertInvalidCandidateDoesNotPulse(int physicalCount, int publicCount,
            boolean type2Physical) {
        ExternalDisplayPolicy.LeaseState lease = armedWhileInitiallyUnplugged();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                lease.onAdded(RECONNECTED_DISPLAY_ID, "fresh-physical-output", physicalCount,
                        publicCount, type2Physical));
        assertNotEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                lease.restoreFinished(true, true, true, true, true));
    }

    @Test
    public void knownReconnectRequiresTheLastPublicRemovalAndAllowsAReplacementMonitor() {
        ExternalDisplayPolicy.LeaseState wrongIdentity = newLease();
        wrongIdentity.enable(true, true, true, "1", 1);
        assertNotEquals(ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO,
                wrongIdentity.onRemoved(7, "different-physical-output", 1));

        ExternalDisplayPolicy.LeaseState anotherPublicDisplay = newLease();
        anotherPublicDisplay.enable(true, true, true, "1", 1);
        assertNotEquals(ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO,
                anotherPublicDisplay.onRemoved(7, DISPLAY_IDENTITY, 1));

        ExternalDisplayPolicy.LeaseState lease = armedFromKnownRemoval();
        // A different monitor may replace the removed one. The new Added identity
        // establishes its own exact generation; the removed identity is not reused.
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                lease.onAdded(RECONNECTED_DISPLAY_ID, "different-physical-output", 1, 1, true));
    }

    @Test
    public void baselineIsRestoredBeforeExactlyOneSelectedReconnectPulse() {
        ExternalDisplayPolicy.LeaseState lease = armedFromKnownRemoval();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, lease.select(RECONNECTED_DISPLAY_ID));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                lease.onAdded(RECONNECTED_DISPLAY_ID, DISPLAY_IDENTITY, 1, 1, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, lease.select(RECONNECTED_DISPLAY_ID));

        // The first effect after Added is restoring force=1; no pulse is available
        // until the writer stopped, the baseline read back, and the generation matched.
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                lease.restoreFinished(true, true, true, true, true));
        assertTrue(lease.navSuppressed());
        lease.pulseFinished(true, true, true);
        assertNotEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                lease.select(RECONNECTED_DISPLAY_ID));
    }

    @Test
    public void pulseWaitsForSelectionButNavSuppressionIsReportedSeparately() {
        ExternalDisplayPolicy.LeaseState waiting = beginAddedCandidate(
                RECONNECTED_DISPLAY_ID, DISPLAY_IDENTITY);
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                waiting.restoreFinished(true, true, true, true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.RESTORED_WAITING_SELECTION,
                waiting.phase());
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                waiting.select(RECONNECTED_DISPLAY_ID));
        waiting.pulseFinished(true, true, true);

        // A failed/unavailable native NAV observation must not strand mouse recovery.
        // The one-shot viewport pulse still runs, but is not reported as NAV success.
        ExternalDisplayPolicy.LeaseState navStillVisible = beginAddedCandidate(
                RECONNECTED_DISPLAY_ID, DISPLAY_IDENTITY);
        navStillVisible.select(RECONNECTED_DISPLAY_ID);
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                navStillVisible.restoreFinished(true, true, true, true, false));
        assertFalse(navStillVisible.navSuppressed());
        navStillVisible.pulseFinished(true, true, false);
        assertFalse(navStillVisible.navSuppressed());

        ExternalDisplayPolicy.LeaseState navUnavailable = beginAddedCandidate(
                RECONNECTED_DISPLAY_ID, DISPLAY_IDENTITY);
        navUnavailable.select(RECONNECTED_DISPLAY_ID);
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                navUnavailable.restoreFinished(true, true, true, false, false));
        assertFalse(navUnavailable.navSuppressed());
    }

    @Test
    public void failedRestoreOrStaleGenerationNeverProducesPulse() {
        assertRestoreDoesNotPulse(false, true, true);
        assertRestoreDoesNotPulse(true, false, true);
        assertRestoreDoesNotPulse(true, true, false);
    }

    private void assertRestoreDoesNotPulse(boolean writerQuiesced, boolean readbackBaseline,
            boolean exactGeneration) {
        ExternalDisplayPolicy.LeaseState lease = beginAddedCandidate(
                RECONNECTED_DISPLAY_ID, DISPLAY_IDENTITY);
        lease.select(RECONNECTED_DISPLAY_ID);
        assertNotEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                lease.restoreFinished(writerQuiesced, readbackBaseline, exactGeneration,
                        true, true));
    }

    @Test
    public void losingSelectionBeforeRestoreSuppressesPulseAndLaterMatchingSelectionMayReleaseIt() {
        ExternalDisplayPolicy.LeaseState lease = beginAddedCandidate(
                RECONNECTED_DISPLAY_ID, DISPLAY_IDENTITY);
        lease.select(RECONNECTED_DISPLAY_ID);
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, lease.select(-1));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.restoreFinished(true, true, true, true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                lease.select(RECONNECTED_DISPLAY_ID));
    }

    @Test
    public void unplugAgainWhilePhoneSelectedRearmsWithoutPulsingUntilExternalIsSelected() {
        ExternalDisplayPolicy.LeaseState lease = armedWhileInitiallyUnplugged();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                lease.onAdded(RECONNECTED_DISPLAY_ID, "first-physical-output", 1, 1, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, lease.select(0));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.restoreFinished(true, true, true, true, true));

        assertEquals(ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO,
                lease.onRemoved(RECONNECTED_DISPLAY_ID, "first-physical-output", 0));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, lease.armFinished(true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE,
                lease.onAdded(RECONNECTED_DISPLAY_ID + 1, "replacement-physical-output", 1, 1, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.restoreFinished(true, true, true, true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                lease.select(RECONNECTED_DISPLAY_ID + 1));
    }

    @Test
    public void recoveredGuardianTargetWaitsForFreshSelectionAndDoesNotInferNavSuccess() {
        ExternalDisplayPolicy.LeaseState lease = newLease();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.enable(true, true, true, "1", 1));
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.WATCHING, lease.phase());

        lease.recoveredTarget(RECONNECTED_DISPLAY_ID, "recovered-physical-output");
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.RESTORED_WAITING_SELECTION,
                lease.phase());
        assertFalse(lease.navSuppressed());
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, lease.select(-1));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE,
                lease.select(RECONNECTED_DISPLAY_ID + 1));
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                lease.select(RECONNECTED_DISPLAY_ID));

        // An unavailable NAV getter after orphan recovery is unknown, not success.
        lease.pulseFinished(true, false, false);
        assertFalse(lease.navSuppressed());
        assertNotEquals(ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE,
                lease.select(RECONNECTED_DISPLAY_ID));
    }

    @Test
    public void explicitSnapshotUsesTheCapturedBaselineOnlyForOwnedTemporaryZero() {
        assertEquals("1", ExternalDisplayPolicy.LeaseState.visibleDesktopValue("0", true, "1"));
        assertEquals("0", ExternalDisplayPolicy.LeaseState.visibleDesktopValue("0", false, "1"));
        assertEquals("1", ExternalDisplayPolicy.LeaseState.visibleDesktopValue("1", true, "0"));
        assertEquals("null", ExternalDisplayPolicy.LeaseState.visibleDesktopValue("0", true, "null"));
    }

    @Test
    public void disableRestoresAnOwnedTemporaryZeroInsteadOfLeavingTheLeaseArmed() {
        ExternalDisplayPolicy.LeaseState lease = armedWhileInitiallyUnplugged();
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE, lease.disable());
        assertTrue(lease.phase() == ExternalDisplayPolicy.LeaseState.Phase.RESTORING
                || lease.phase() == ExternalDisplayPolicy.LeaseState.Phase.RESTORE_PENDING);
        assertEquals(ExternalDisplayPolicy.LeaseState.Action.NONE, lease.disableFinished(true, true));
        assertEquals(ExternalDisplayPolicy.LeaseState.Phase.INACTIVE, lease.phase());
    }
}
