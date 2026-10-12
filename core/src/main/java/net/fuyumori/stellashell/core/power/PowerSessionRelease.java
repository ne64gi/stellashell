package net.fuyumori.stellashell.core.power;

import java.util.ArrayList;
import java.util.List;

/** A retryable release pass over temporary owners, without changing persistent settings. */
public final class PowerSessionRelease {
    private PowerSessionRelease() {}
    public interface Owner {
        boolean release() throws Exception;
    }
    public static final class Step {
        final String name;
        final Owner owner;
        public Step(String name, Owner owner) { this.name=name; this.owner=owner; }
    }
    /** Every owner gets a release attempt even when an earlier owner still has debt. */
    public static String release(Step... steps) {
        List<String> pending=new ArrayList<>();
        for (Step step:steps) {
            try { if (!step.owner.release()) pending.add(step.name); }
            catch (Exception failure) { pending.add(step.name); }
        }
        return pending.isEmpty() ? "OK" : "ERROR: Power session cleanup pending: " + String.join(", ",pending);
    }
}
