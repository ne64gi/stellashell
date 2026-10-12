package net.fuyumori.stellashell.core.power;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PowerSessionReleaseTest {
    @Test public void failedOwnerDoesNotPreventLaterSafetyCleanup() {
        List<String> calls=new ArrayList<>();
        String result=PowerSessionRelease.release(
            new PowerSessionRelease.Step("reader",()->{calls.add("reader");throw new Exception();}),
            new PowerSessionRelease.Step("screen",()->{calls.add("screen");return false;}),
            new PowerSessionRelease.Step("guardian",()->{calls.add("guardian");return true;}));
        assertEquals(Arrays.asList("reader","screen","guardian"),calls);
        assertEquals("ERROR: Power session cleanup pending: reader, screen",result);
    }
    @Test public void retryUsesSameOwnersAndOnlyReportsSuccessAfterDebtClears() {
        int[] attempts={0};
        PowerSessionRelease.Step debt=new PowerSessionRelease.Step("mouse",()->++attempts[0]>=2);
        assertEquals("ERROR: Power session cleanup pending: mouse",PowerSessionRelease.release(debt));
        assertEquals("OK",PowerSessionRelease.release(debt));
        assertEquals("OK",PowerSessionRelease.release(debt));
        assertEquals(3,attempts[0]);
    }
    @Test public void noLeasesIsAlreadyReleased() { assertEquals("OK",PowerSessionRelease.release()); }
}
