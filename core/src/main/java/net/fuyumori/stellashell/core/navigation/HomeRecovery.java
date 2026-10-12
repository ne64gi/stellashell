package net.fuyumori.stellashell.core.navigation;

/** Two distinct HOME requests within 600 ms; no timer or background work. */
public final class HomeRecovery {
    public enum Action { HOME, DUPLICATE, OPEN_START }

    private long last = -1;
    private boolean pending;

    public Action press(long uptimeMillis) {
        // Keep the last delivery after a completed pair too: a duplicate must
        // neither close the newly opened menu nor begin a new pair.
        if (last >= 0 && uptimeMillis >= last && uptimeMillis - last < 120) {
            return Action.DUPLICATE;
        }
        boolean open = pending && uptimeMillis >= last && uptimeMillis - last <= 600;
        last = uptimeMillis;
        pending = !open;
        return open ? Action.OPEN_START : Action.HOME;
    }

    /** Menu attachment can block delivery; debounce queued HOME after it settles. */
    public void startShown(long uptimeMillis) {
        last = uptimeMillis;
        pending = false;
    }

    public void reset() {
        pending = false;
        last = -1;
    }
}
