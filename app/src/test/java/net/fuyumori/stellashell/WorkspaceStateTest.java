package net.fuyumori.stellashell;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceStateTest {
    @Test public void ownershipLivesInAnInstanceAndResetRejectsOldEpoch() {
        Workspace one = new Workspace();
        Workspace two = new Workspace();
        TaskSnapshot.Task floating = new TaskSnapshot.Task(24, "fixture/.Window", 5,
                true, false, false, 0, 0, 300, 400);
        long epoch = one.session();

        one.adoptFloating(floating, epoch);

        assertTrue(one.owns(floating));
        assertFalse(two.owns(floating));
        assertTrue(one.currentSession(epoch));

        one.reset();

        assertFalse(one.currentSession(epoch));
        assertFalse(one.owns(floating));
    }

    @Test public void reusedTaskIdReplacesDifferentComponentIdentity() {
        Workspace owner = new Workspace();
        long epoch = owner.session();
        TaskSnapshot.Task previous = new TaskSnapshot.Task(31, "fixture/.Previous", 5,
                true, false, false, 0, 0, 300, 400);
        TaskSnapshot.Task current = new TaskSnapshot.Task(31, "fixture/.Current", 5,
                true, false, false, 0, 0, 300, 400);

        owner.adoptFloating(previous, epoch);
        owner.adoptFloating(current, epoch);

        assertFalse(owner.owns(previous));
        assertTrue(owner.owns(current));
    }
}
