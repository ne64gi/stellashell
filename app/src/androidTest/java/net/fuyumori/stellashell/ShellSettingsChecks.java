package net.fuyumori.stellashell;

import android.app.Instrumentation;
import net.fuyumori.stellashell.core.layout.EdgeDockReveal.Method;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies the typed owner preserves the installed preference contract and isolates domains. */
final class ShellSettingsChecks {
    private ShellSettingsChecks() {}

    static void run(Instrumentation instrumentation) throws Exception {
        Throwable[] failure = {null};
        instrumentation.runOnMainSync(() -> {
            try { verify(instrumentation); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] instanceof Error) throw (Error) failure[0];
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static void verify(Instrumentation instrumentation) throws Exception {
        Context base = instrumentation.getTargetContext();
        String store = "shell_settings_contract_" + System.nanoTime();
        SharedPreferences prefs = base.getSharedPreferences(store, Context.MODE_PRIVATE);
        ShellSettings owner = ShellSettings.isolated(prefs);
        AutoCloseable subscription = null;
        try {
            prefs.edit().clear().commit();
            ShellSettings.Snapshot defaults = owner.snapshot();
            check(defaults.phoneDock.enabled && defaults.phoneDock.overApps
                    && defaults.phoneDock.phoneSide == ShellSettings.PhoneSide.BOTH
                    && defaults.phoneDock.triggerPercent == 80
                    && defaults.phoneDock.scalePercent == 100, "phone Dock defaults changed");
            check(defaults.phoneLandscapeDock.edge == ShellSettings.DockEdge.BOTTOM
                    && defaults.phoneLandscapeDock.positionPercent == 50,
                    "landscape handle defaults must be independent of portrait sides/height");
            check(defaults.phoneDock.openMethod == Method.SWIPE && defaults.externalDock.openMethod == Method.SWIPE,
                    "missing opening preferences must retain swipe on both screens");
            check(!defaults.externalDock.revealByHandle, "existing external Dock must remain always visible by default");
            check(!defaults.externalDock.enabled && defaults.externalDock.edge == ShellSettings.DockEdge.BOTTOM
                    && defaults.externalDock.xPercent == 50 && defaults.externalDock.yPercent == 100
                    && defaults.externalDock.scalePercent == 100, "external Dock defaults changed");
            check(!defaults.phoneTaskbar.enabled && defaults.phoneTaskbar.scalePercent == 100
                    && defaults.externalTaskbar.enabled && defaults.externalTaskbar.alwaysShown
                    && defaults.externalTaskbar.scalePercent == 100, "Taskbar defaults changed");
            check(defaults.shellLayout == ShellSettings.Layout.AUTO && !defaults.compactWorkspace
                    && !defaults.workspaceAuto && !defaults.primaryMode && defaults.preferredDisplayId == -1,
                    "layout/display defaults changed");

            // Seed representative values using the exact old key and type; reading is lossless.
            prefs.edit().putBoolean("phone_sidebar", false)
                    .putString("phone_sidebar_side", "right")
                    .putBoolean("phone_sidebar_over_apps", false)
                    .putInt("sidebar_height", 64)
                    .putInt("phone_dock_scale", 145)
                    .putBoolean("desktop_dock", true)
                    .putString("dock_edge", "left")
                    .putInt("dock_x", 23).putInt("dock_y", 42)
                    .putInt("desktop_dock_scale", 50)
                    .putBoolean("phone_taskbar", true)
                    .putInt("phone_taskbar_scale", 120)
                    .putInt("desktop_taskbar_scale", 180)
                    .putString("shell_layout", "desktop")
                    .putBoolean("compact_workspace", true)
                    .putBoolean("workspace_auto", false)
                    .putBoolean("primary_mode", true)
                    .putInt("preferred_display", 7).commit();
            ShellSettings.Snapshot seeded = owner.snapshot();
            check(!seeded.phoneDock.enabled && seeded.phoneDock.phoneSide == ShellSettings.PhoneSide.RIGHT
                    && !seeded.phoneDock.overApps && seeded.phoneDock.triggerPercent == 64
                    && seeded.phoneDock.scalePercent == 150, "legacy phone Dock values not restored");
            check(seeded.externalDock.enabled && seeded.externalDock.edge == ShellSettings.DockEdge.LEFT
                    && seeded.externalDock.xPercent == 23 && seeded.externalDock.yPercent == 42
                    && seeded.externalDock.scalePercent == 50, "legacy external Dock values not restored");
            check(seeded.phoneLandscapeDock.edge == ShellSettings.DockEdge.BOTTOM
                    && seeded.phoneLandscapeDock.positionPercent == 50 && !seeded.externalDock.revealByHandle,
                    "legacy installs must not infer new reveal settings from existing geometry");
            check(!prefs.contains("phone_dock_landscape_edge") && !prefs.contains("phone_dock_landscape_position")
                    && !prefs.contains("desktop_dock_by_handle")
                    && !prefs.contains("phone_dock_open_method") && !prefs.contains("desktop_dock_open_method"),
                    "snapshot unexpectedly migrated preferences");
            check(seeded.phoneTaskbar.enabled && seeded.phoneTaskbar.scalePercent == 120
                    && seeded.externalTaskbar.scalePercent == 180, "legacy Taskbar values not restored");
            check(seeded.shellLayout == ShellSettings.Layout.DESKTOP && seeded.compactWorkspace
                    && !seeded.workspaceAuto && seeded.primaryMode && seeded.preferredDisplayId == 7,
                    "legacy layout/display values not restored");

            owner.setPhoneLandscapeDockEdge(ShellSettings.DockEdge.LEFT);
            owner.setPhoneLandscapeDockPosition(140);
            owner.setExternalDockRevealByHandle(true);
            check("left".equals(prefs.getString("phone_dock_landscape_edge", null))
                    && prefs.getInt("phone_dock_landscape_position", -1) == 100
                    && prefs.getBoolean("desktop_dock_by_handle", false), "new reveal keys/type or bounds changed");
            owner.setPhoneLandscapeDockPosition(-20);
            check(owner.snapshot().phoneLandscapeDock.positionPercent == 0, "landscape position minimum not clamped");
            prefs.edit().putString("phone_dock_landscape_edge", "unknown")
                    .putInt("phone_dock_landscape_position", 150).commit();
            check(owner.snapshot().phoneLandscapeDock.edge == ShellSettings.DockEdge.BOTTOM
                    && owner.snapshot().phoneLandscapeDock.positionPercent == 100,
                    "invalid landscape values were not normalized at read boundary");
            owner.setPhoneLandscapeDockEdge(ShellSettings.DockEdge.LEFT);
            owner.setPhoneLandscapeDockPosition(61);
            owner.setExternalDockRevealByHandle(false);
            owner.setPhoneDockEnabled(true);
            owner.setPhoneDockSide(ShellSettings.PhoneSide.LEFT);
            owner.setPhoneDockOverApps(true);
            owner.setPhoneDockTriggerPercent(130);
            owner.setPhoneDockScalePercent(54);
            owner.setExternalDockEnabled(false);
            owner.setExternalDockPosition(-4, 104);
            owner.setExternalDockScalePercent(201);
            owner.setPhoneTaskbarEnabled(false);
            owner.setPhoneTaskbarScalePercent(56);
            owner.setExternalTaskbarScalePercent(194);
            owner.setShellLayout(ShellSettings.Layout.COMPACT);
            owner.setCompactWorkspace(false);
            owner.setWorkspaceAuto(true);
            owner.setPrimaryMode(false);
            owner.setPreferredDisplay(8);
            check(prefs.getBoolean("phone_sidebar", false)
                    && "left".equals(prefs.getString("phone_sidebar_side", null))
                    && prefs.getBoolean("phone_sidebar_over_apps", false)
                    && prefs.getInt("sidebar_height", -1) == 100
                    && prefs.getInt("phone_dock_scale", -1) == 50,
                    "phone Dock writer changed legacy key/type or bounds");
            check(!prefs.getBoolean("desktop_dock", true)
                    && prefs.getInt("dock_x", -1) == 0 && prefs.getInt("dock_y", -1) == 100
                    && prefs.getInt("desktop_dock_scale", -1) == 200,
                    "external Dock writer changed legacy key/type or bounds");
            check(!prefs.getBoolean("phone_taskbar", true)
                    && prefs.getInt("phone_taskbar_scale", -1) == 60
                    && prefs.getInt("desktop_taskbar_scale", -1) == 190,
                    "Taskbar writer changed legacy key/type or scale bounds");
            check("compact".equals(prefs.getString("shell_layout", null))
                    && !prefs.getBoolean("compact_workspace", true)
                    && prefs.getBoolean("workspace_auto", false)
                    && !prefs.getBoolean("primary_mode", true)
                    && prefs.getInt("preferred_display", -1) == 8,
                    "layout/display writer changed legacy key or type");
            assertTypes(prefs.getAll(), new String[]{
                    "phone_sidebar", "phone_sidebar_side", "phone_sidebar_over_apps", "sidebar_height",
                    "phone_dock_landscape_edge", "phone_dock_landscape_position", "desktop_dock_by_handle",
                    "phone_dock_scale", "desktop_dock", "dock_edge", "dock_x", "dock_y",
                    "desktop_dock_scale", "phone_taskbar", "phone_taskbar_scale", "desktop_taskbar_scale",
                    "shell_layout", "compact_workspace", "workspace_auto", "primary_mode", "preferred_display"});

            // Scale and geometry remain separate by surface; no current-session/task key joins this snapshot.
            owner.setPhoneDockScalePercent(50);
            owner.setExternalDockScalePercent(200);
            owner.setPhoneTaskbarScalePercent(120);
            owner.setExternalTaskbarScalePercent(180);
            owner.setExternalDockPosition(13, 37);
            owner.setExternalDockEdge(ShellSettings.DockEdge.RIGHT);
            ShellSettings.Snapshot separated = owner.snapshot();
            check(separated.phoneDock.scalePercent == 50 && separated.externalDock.scalePercent == 200
                    && separated.phoneTaskbar.scalePercent == 120 && separated.externalTaskbar.scalePercent == 180,
                    "Dock/Taskbar scales crossed domains");
            check(separated.externalDock.xPercent == 100 && separated.externalDock.yPercent == 37,
                    "edge update must snap only its perpendicular coordinate");
            owner.setExternalDockPosition(13, 37);
            owner.setExternalDockEdge(ShellSettings.DockEdge.TOP);
            check(owner.snapshot().externalDock.xPercent == 13 && owner.snapshot().externalDock.yPercent == 0,
                    "top edge update lost the parallel coordinate");
            check("top".equals(prefs.getString("dock_edge", null)), "Dock edge legacy string changed");

            // Handle mode must preserve the old floating coordinates and every other navigation domain.
            owner.setExternalDockPosition(13, 37);
            prefs.edit().putString("phone_pinned", "phone-dock-fixture")
                    .putString("dock_pinned", "external-dock-fixture")
                    .putString("phone_taskbar_pinned", "phone-taskbar-fixture")
                    .putString("pinned", "external-taskbar-fixture").commit();
            Map<String, ?> beforeReveal = prefs.getAll();
            EnumSet<ShellSettings.Change> revealChanges = EnumSet.noneOf(ShellSettings.Change.class);
            AutoCloseable revealProbe = owner.observe((changes, snapshot) -> revealChanges.addAll(changes));
            try {
                owner.setExternalDockRevealByHandle(true);
                check(revealChanges.equals(EnumSet.of(ShellSettings.Change.EXTERNAL_DOCK_REVEAL)),
                        "reveal toggle emitted another domain's change");
                revealChanges.clear();
                owner.setExternalDockEdge(ShellSettings.DockEdge.LEFT);
                check(revealChanges.equals(EnumSet.of(ShellSettings.Change.EXTERNAL_DOCK_EDGE)),
                        "handle edge choice overwrote the saved floating position");
                check(owner.snapshot().externalDock.xPercent == 13 && owner.snapshot().externalDock.yPercent == 37,
                        "handle edge choice lost floating coordinates");
                revealChanges.clear();
                owner.setPhoneLandscapeDockEdge(ShellSettings.DockEdge.RIGHT);
                check(revealChanges.equals(EnumSet.of(ShellSettings.Change.PHONE_DOCK_LANDSCAPE_EDGE)),
                        "landscape edge emitted portrait/external changes");
                revealChanges.clear();
                owner.setPhoneLandscapeDockPosition(27);
                check(revealChanges.equals(EnumSet.of(ShellSettings.Change.PHONE_DOCK_LANDSCAPE_POSITION)),
                        "landscape position emitted portrait/external changes");
                revealChanges.clear();
                owner.setExternalDockRevealByHandle(false);
                check(revealChanges.equals(EnumSet.of(ShellSettings.Change.EXTERNAL_DOCK_REVEAL)),
                        "return to always-visible rewrote old position");
            } finally {
                revealProbe.close();
            }
            Map<String, ?> afterReveal = prefs.getAll();
            for (String key : beforeReveal.keySet()) {
                if (key.equals("dock_edge") || key.equals("phone_dock_landscape_edge")
                        || key.equals("phone_dock_landscape_position")) continue;
                check(beforeReveal.get(key).equals(afterReveal.get(key)), "handle changes crossed domain: " + key);
            }
            ShellSettings.Snapshot afterHandles = owner.snapshot();
            check(afterHandles.phoneDock.phoneSide == ShellSettings.PhoneSide.LEFT
                    && afterHandles.phoneDock.triggerPercent == 100
                    && afterHandles.externalDock.xPercent == 13 && afterHandles.externalDock.yPercent == 37,
                    "new handle preferences corrupted portrait/always-visible geometry");

            verifyOpenMethods(prefs, owner);
            owner.setPhoneDockSide(ShellSettings.PhoneSide.GESTURE);
            check(owner.snapshot().phoneDock.phoneSide==ShellSettings.PhoneSide.GESTURE
                    && "gesture".equals(prefs.getString("phone_sidebar_side","")),"gesture selection did not round-trip");
            check(owner.snapshot().phoneLandscapeDock.positionPercent==27
                    && owner.snapshot().externalDock.xPercent==13,"gesture changed independent edge positions");
            owner.setPhoneDockSide(ShellSettings.PhoneSide.LEFT);

            owner.clearPreferredDisplay();
            check(!prefs.contains("preferred_display") && owner.snapshot().preferredDisplayId == -1,
                    "preferred output reset must preserve legacy remove semantics");
            prefs.edit().remove("workspace_auto").commit();
            owner.initializeWorkspaceAuto();
            check(owner.snapshot().workspaceAuto, "missing workspace_auto was not initialized");
            owner.setWorkspaceAuto(false);
            owner.initializeWorkspaceAuto();
            check(!owner.snapshot().workspaceAuto, "workspace_auto initialization overwrote an existing choice");
            prefs.edit().putBoolean("enabled", true).putInt("active_display", 3)
                    .putInt("workspace_display", 4).commit();
            ShellSettings.Snapshot isolatedState = owner.snapshot();
            check(isolatedState.preferredDisplayId == -1 && !isolatedState.workspaceAuto,
                    "runtime/session/task state leaked into ShellSettings");

            // Provider contexts reuse exactly their injected owner rather than the process singleton.
            class ProvidedContext extends ContextWrapper implements ShellSettings.Provider {
                ProvidedContext() { super(base); }
                @Override public Context getApplicationContext() { return this; }
                @Override public ShellSettings shellSettings() { return owner; }
            }
            check(ShellSettings.of(new ProvidedContext()) == owner, "fixture settings provider was bypassed");

            AtomicInteger calls = new AtomicInteger();
            CountDownLatch first = new CountDownLatch(1);
            subscription = owner.observe((changes, snapshot) -> {
                calls.incrementAndGet();
                if (changes.contains(ShellSettings.Change.PHONE_DOCK_ENABLED)
                        && !snapshot.phoneDock.enabled) first.countDown();
            });
            owner.setPhoneDockEnabled(false);
            check(first.await(2, TimeUnit.SECONDS), "typed observer missed a Dock update");
            int notified = calls.get();
            subscription.close();
            subscription = null;
            CountDownLatch barrier = new CountDownLatch(1);
            AtomicInteger positionCalls = new AtomicInteger();
            AutoCloseable barrierSubscription = owner.observe((changes, snapshot) -> {
                if (changes.contains(ShellSettings.Change.EXTERNAL_DOCK_POSITION)) positionCalls.incrementAndGet();
                if (changes.contains(ShellSettings.Change.EXTERNAL_DOCK_POSITION)
                        && snapshot.externalDock.xPercent == 17 && snapshot.externalDock.yPercent == 23)
                    barrier.countDown();
            });
            try {
                owner.setExternalDockPosition(17, 23);
                check(barrier.await(2, TimeUnit.SECONDS), "two-key settings update was not delivered");
                check(positionCalls.get() == 1, "two-key setter must publish one coherent snapshot");
                check(calls.get() == notified, "closed settings observer kept receiving updates");
                int beforeUnrelated = positionCalls.get();
                prefs.edit().putString("unrelated_fixture_key", "ignored").commit();
                check(positionCalls.get() == beforeUnrelated, "unrelated key emitted a shell-settings change");
            } finally {
                barrierSubscription.close();
            }
            int beforeClosedOwner = positionCalls.get();
            owner.setExternalDockPosition(19, 29);
            check(positionCalls.get() == beforeClosedOwner, "closed settings observer kept receiving updates");
            AtomicInteger runtimeCalls = new AtomicInteger();
            AutoCloseable runtimeProbe = owner.observe((changes, snapshot) -> runtimeCalls.incrementAndGet());
            try {
                prefs.edit().putBoolean("enabled", false).putInt("active_display", 4)
                        .putInt("workspace_display", 5).commit();
                check(runtimeCalls.get() == 0, "runtime/session/task keys emitted a shell-settings change");
            } finally {
                runtimeProbe.close();
            }
        } finally {
            if (subscription != null) subscription.close();
            owner.close();
            base.deleteSharedPreferences(store);
        }
    }

    private static void verifyOpenMethods(SharedPreferences prefs, ShellSettings owner) throws Exception {
        prefs.edit().putString("phone_dock_open_method", "obsolete")
                .putString("desktop_dock_open_method", "invalid").commit();
        check(owner.snapshot().phoneDock.openMethod == Method.SWIPE
                && owner.snapshot().externalDock.openMethod == Method.SWIPE,
                "unknown opening methods did not normalize to swipe");
        check("obsolete".equals(prefs.getString("phone_dock_open_method", null)),
                "reading opening methods rewrote installed preferences");
        owner.setPhoneDockOpenMethod(Method.SWIPE);
        owner.setExternalDockOpenMethod(Method.SWIPE);
        EnumSet<ShellSettings.Change> events = EnumSet.noneOf(ShellSettings.Change.class);
        AutoCloseable observer = owner.observe((changes, snapshot) -> events.addAll(changes));
        try {
            // Exercise each method, independently, against the seeded portrait/landscape, pin and scale domains.
            for (Method method : new Method[]{Method.SINGLE_TAP, Method.DOUBLE_TAP, Method.SWIPE}) {
                Map<String, ?> beforePhone = prefs.getAll();
                Method externalBefore = owner.snapshot().externalDock.openMethod;
                events.clear();
                owner.setPhoneDockOpenMethod(method);
                check(events.equals(EnumSet.of(ShellSettings.Change.PHONE_DOCK_OPEN_METHOD)),
                        "phone opening method emitted another domain's typed event");
                check(owner.snapshot().phoneDock.openMethod == method
                        && owner.snapshot().externalDock.openMethod == externalBefore
                        && method.storedValue().equals(prefs.getString("phone_dock_open_method", null)),
                        "phone opening method persistence or screen independence changed");
                assertOnlyKeyChanged(beforePhone, prefs.getAll(), "phone_dock_open_method");
                Map<String, ?> beforeExternal = prefs.getAll();
                events.clear();
                Method externalMethod = Method.values()[(method.ordinal() + 1) % Method.values().length];
                owner.setExternalDockOpenMethod(externalMethod);
                check(events.equals(EnumSet.of(ShellSettings.Change.EXTERNAL_DOCK_OPEN_METHOD)),
                        "external opening method emitted another domain's typed event");
                check(owner.snapshot().externalDock.openMethod == externalMethod
                        && owner.snapshot().phoneDock.openMethod == method
                        && externalMethod.storedValue().equals(prefs.getString("desktop_dock_open_method", null)),
                        "external opening method persistence or screen independence changed");
                assertOnlyKeyChanged(beforeExternal, prefs.getAll(), "desktop_dock_open_method");
            }
            assertTypes(prefs.getAll(), new String[]{"phone_dock_open_method", "desktop_dock_open_method"});
        } finally {
            observer.close();
        }
    }

    private static void assertOnlyKeyChanged(Map<String, ?> before, Map<String, ?> after, String expected) {
        check(before.keySet().equals(after.keySet()), "opening method added unrelated preference keys");
        for (String key : before.keySet()) {
            if (!key.equals(expected)) check(before.get(key).equals(after.get(key)),
                    "opening method crossed preference domain: " + key);
        }
    }

    private static void assertTypes(Map<String, ?> values, String[] keys) {
        for (String key : keys) {
            Object value = values.get(key);
            Class<?> expected = key.equals("phone_sidebar_side") || key.equals("dock_edge")
                    || key.equals("shell_layout") || key.equals("phone_dock_landscape_edge")
                    || key.equals("phone_dock_open_method") || key.equals("desktop_dock_open_method") ? String.class
                    : key.equals("phone_sidebar") || key.equals("phone_sidebar_over_apps")
                    || key.equals("desktop_dock") || key.equals("desktop_dock_by_handle") || key.equals("phone_taskbar")
                    || key.equals("compact_workspace") || key.equals("workspace_auto")
                    || key.equals("primary_mode") ? Boolean.class : Integer.class;
            check(expected.isInstance(value), "legacy preference type changed for " + key);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
