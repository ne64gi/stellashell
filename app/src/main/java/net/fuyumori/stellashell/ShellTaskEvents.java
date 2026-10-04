package net.fuyumori.stellashell;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import java.util.ArrayList;
import java.util.List;

/** Process-owned, demand-scoped task invalidations. No idle sampling or recurring retry. */
final class ShellTaskEvents {
    private static ShellTaskEvents shared;
    static synchronized ShellTaskEvents of(Context context) {
        if (shared == null) shared = new ShellTaskEvents(context.getApplicationContext());
        return shared;
    }
    static boolean awake(Context context, int id) {
        Display display = context.getSystemService(DisplayManager.class).getDisplay(id);
        if (display == null || !display.isValid()) return false;
        int state = display.getState();
        if (state != Display.STATE_ON && state != Display.STATE_VR && state != Display.STATE_UNKNOWN) return false;
        PowerManager power = context.getSystemService(PowerManager.class);
        if (!power.isInteractive()) return false;
        // Main-panel-off mode can still have a usable external display.
        if (id != 0) return true;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return !keyguard.isDeviceLocked();
    }

    private final Context context;
    private final Bridge bridge;
    private final DisplayManager displays;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Subscription> subscriptions = new ArrayList<>();
    private ITaskChangeListener remote;
    private boolean observing;
    private final Runnable connectionChanged = () -> { reconcile(); invalidate(); };
    private final Runnable dispatch = this::dispatchNow;
    private void dispatchNow() {
        for (Subscription subscription : new ArrayList<>(subscriptions))
            if (!subscription.closed && awake(context, subscription.display)) subscription.changed.run();
    }
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) { reconcile(); invalidate(); }
    };
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { displayChanged(id); }
        @Override public void onDisplayRemoved(int id) { displayChanged(id); }
        @Override public void onDisplayChanged(int id) { displayChanged(id); }
    };
    private ShellTaskEvents(Context context) {
        this.context = context;
        bridge = Bridge.get(context);
        displays = context.getSystemService(DisplayManager.class);
    }

    AutoCloseable observe(int display, Runnable changed) {
        Subscription subscription = new Subscription(display, changed);
        subscriptions.add(subscription);
        if (!observing) {
            observing = true;
            bridge.observe(connectionChanged);
            displays.registerDisplayListener(displayListener, main);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(screen, filter);
        }
        reconcile();
        return subscription;
    }

    private void displayChanged(int id) {
        boolean relevant = false;
        for (Subscription subscription : subscriptions) if (subscription.display == id) {
            boolean now = awake(context, id);
            if (subscription.wasAwake != now) { subscription.wasAwake = now; relevant = true; }
        }
        // Brightness/refresh-rate callbacks are not task invalidations. Actual
        // geometry has the insets/display owner, task changes the framework source.
        if (relevant) { reconcile(); invalidate(); }
    }

    private void reconcile() {
        boolean wanted = false;
        for (Subscription subscription : subscriptions) {
            subscription.wasAwake = awake(context, subscription.display);
            if (subscription.wasAwake) wanted = true;
        }
        wanted &= bridge.ready() && bridge.authorized();
        if (!wanted) { detach(); return; }
        if (remote != null) return;
        ITaskChangeListener candidate = new ITaskChangeListener.Stub() {
            @Override public void onChanged() {
                main.post(() -> { if (remote == this) invalidate(); });
            }
        };
        remote = candidate;
        bridge.read(server -> server.observeTaskChanges(candidate), (result, error) -> {
            if (remote != candidate) {
                bridge.read(server -> { server.removeTaskObserver(candidate); return "OK"; }, (r, e) -> {});
                return;
            }
            if (error != null) {
                remote = null;
                // Unsupported framework is not a reason to poll forever. A later
                // connection/screen/user event can retry; each UI open reads once.
                android.util.Log.w("StellaTasks", "Task event registration unavailable");
            } else invalidate();
        });
    }

    private void invalidate() {
        if (subscriptions.isEmpty()) return;
        // Finite event-storm debounce. No callback schedules itself.
        if (!main.hasCallbacks(dispatch)) main.postDelayed(dispatch, 100);
    }

    private void detach() {
        ITaskChangeListener previous = remote;
        remote = null;
        main.removeCallbacks(dispatch);
        if (previous != null && bridge.ready())
            bridge.read(server -> { server.removeTaskObserver(previous); return "OK"; }, (r, e) -> {});
    }

    private final class Subscription implements AutoCloseable {
        final int display;
        final Runnable changed;
        boolean closed;
        boolean wasAwake;
        Subscription(int display, Runnable changed) { this.display = display; this.changed = changed; wasAwake = awake(context, display); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            subscriptions.remove(this);
            if (!subscriptions.isEmpty()) { reconcile(); return; }
            detach();
            bridge.remove(connectionChanged);
            displays.unregisterDisplayListener(displayListener);
            if (observing) context.unregisterReceiver(screen);
            observing = false;
        }
    }
}
