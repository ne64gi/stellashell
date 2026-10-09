package net.fuyumori.stellashell;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Set;
import net.fuyumori.stellashell.core.display.ExclusiveDisplaySession;

/** Main-thread process owner. The waiting Activity owns the lease; shells only observe it. */
final class ExternalAppMode {
    private static final ExclusiveDisplaySession session = new ExclusiveDisplaySession();
    private static final Set<Runnable> observers = new LinkedHashSet<>();
    private ExternalAppMode() {}
    static boolean active() { return session.displayId() > 0; }
    static ExclusiveDisplaySession.Lease begin(int displayId) {
        ExclusiveDisplaySession.Lease lease = session.begin(displayId);
        changed();
        return lease;
    }
    static boolean current(ExclusiveDisplaySession.Lease lease) { return session.isCurrent(lease); }
    static void end(ExclusiveDisplaySession.Lease lease) { if (session.end(lease)) changed(); }
    static void observe(Runnable observer) { observers.add(observer); }
    static void unobserve(Runnable observer) { observers.remove(observer); }
    private static void changed() {
        for (Runnable observer : new ArrayList<>(observers)) {
            try { observer.run(); }
            catch (RuntimeException failure) {
                // An optional shell surface must never strand the waiting Activity's lease.
                android.util.Log.w("StellaShell", "Could not update a dedicated-mode surface");
            }
        }
    }
}
