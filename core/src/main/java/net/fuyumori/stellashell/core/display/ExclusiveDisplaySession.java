package net.fuyumori.stellashell.core.display;

/** Ephemeral ownership of one explicit external-app session, never a saved display choice. */
public final class ExclusiveDisplaySession {
    public static final class Lease {
        public final int displayId;
        private Lease(int displayId) { this.displayId = displayId; }
    }
    private Lease current;

    public Lease begin(int displayId) {
        if (displayId <= 0) throw new IllegalArgumentException("External display required");
        if (current != null) throw new IllegalStateException("A session is already active");
        current = new Lease(displayId);
        return current;
    }
    public boolean isCurrent(Lease lease) { return lease != null && current == lease; }
    public int displayId() { return current == null ? -1 : current.displayId; }
    public boolean end(Lease lease) {
        if (!isCurrent(lease)) return false;
        current = null;
        return true;
    }
}
