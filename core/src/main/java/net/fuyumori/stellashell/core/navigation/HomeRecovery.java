package net.fuyumori.stellashell.core.navigation;

/** Three distinct HOME requests within 1.5 seconds; no timer or background work. */
public final class HomeRecovery {
    private long first, last;
    private int count;

    public boolean press(long uptimeMillis) {
        if (count > 0 && uptimeMillis >= last && uptimeMillis - last < 120) return false;
        if (count == 0 || uptimeMillis < last || uptimeMillis - first > 1500) {
            first = uptimeMillis;
            count = 0;
        }
        last = uptimeMillis;
        if (++count < 3) return false;
        reset();
        return true;
    }

    public void reset() { count = 0; }
}
