package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic HOME intents on the real attached HOME; no role changes or global key injection. */
final class HomeDoublePressChecks {
    static void run(Instrumentation test) throws Exception {
        Application app = (Application) test.getTargetContext().getApplicationContext();
        AtomicReference<HomeActivity> home = new AtomicReference<>();
        CountDownLatch resumed = new CountDownLatch(1);
        CountDownLatch recreated = new CountDownLatch(1);
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle state) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                if (a instanceof HomeActivity) {
                    HomeActivity previous = home.getAndSet((HomeActivity) a);
                    if (previous == null) resumed.countDown();
                    else if (previous != a) recreated.countDown();
                }
            }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            public void onActivityDestroyed(Activity a) {}
        };
        Instrumentation.ActivityMonitor settings = test.addMonitor(SetupActivity.class.getName(), null, false);
        test.runOnMainSync(() -> app.registerActivityLifecycleCallbacks(lifecycle));
        try {
            test.getTargetContext().startActivity(new Intent(test.getTargetContext(), HomeActivity.class)
                    .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            check(resumed.await(10, TimeUnit.SECONDS), "HOME did not resume");
            test.waitForIdleSync();
            test.runOnMainSync(() -> check(!ShellPanels.isOpen(0), "Existing panel must not be replaced by fixture"));
            Intent press = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            Intent internal = new Intent(press).putExtra(HomeRecoveryEntry.INTERNAL_HOME, true);
            Intent history = new Intent(press).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            spaced(test, () -> {
                home.get().onNewIntent(internal); // Reset without changing any stored settings.
                home.get().onNewIntent(press);
                check(!ShellPanels.isStartOpen(0), "Single HOME opened Start");
            }, () -> {
                home.get().onNewIntent(press);
                check(ShellPanels.isStartOpen(0), "Second HOME did not open local Start");
                home.get().onNewIntent(press);
                check(ShellPanels.isStartOpen(0), "Duplicate HOME closed Start");
            });
            test.waitForIdleSync();
            test.runOnMainSync(() -> check(ShellPanels.isStartOpen(0), "HOME refresh dismissed Start"));
            spaced(test, () -> {}, () -> {
                home.get().onNewIntent(press);
                check(!ShellPanels.isStartOpen(0), "Third HOME did not keep normal HOME behavior");
                check(settings.getHits() == 0, "HOME still opens Settings");
                home.get().onNewIntent(internal);
            });
            spaced(test, () -> home.get().onNewIntent(internal), () -> {
                home.get().onNewIntent(press);
                check(!ShellPanels.isStartOpen(0), "Internal HOME counted toward double press");
            });
            spaced(test, () -> home.get().onNewIntent(history), () -> {
                home.get().onNewIntent(press);
                check(!ShellPanels.isStartOpen(0), "History counted toward double press");
                home.get().recreate();
            });
            check(recreated.await(10, TimeUnit.SECONDS), "HOME did not recreate");
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                home.get().onNewIntent(press);
                check(!ShellPanels.isStartOpen(0), "Recreation counted retained HOME intent");
                check(settings.getHits() == 0, "Settings launched during recovery");
            });
        } finally {
            test.runOnMainSync(() -> {
                app.unregisterActivityLifecycleCallbacks(lifecycle);
                ShellPanels.closeStart(0);
            });
            test.removeMonitor(settings);
        }
    }

    private static void spaced(Instrumentation test, Runnable first, Runnable second) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> {
            first.run();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { second.run(); } catch (Throwable error) { failure.set(error); }
                finally { finished.countDown(); }
            }, 250);
        });
        check(finished.await(5, TimeUnit.SECONDS), "HOME sequence did not finish");
        if (failure.get() != null) throw new AssertionError("HOME sequence failed", failure.get());
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
