package net.fuyumori.stellashell;

import android.content.Context;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class PhoneNavigationOwnerTest {
    @Test public void reconcileIsIdempotentAndUsesTaskbarThenSidebarForStart() {
        MutableConfig config = new MutableConfig(true, true, true);
        FakeFactory factory = new FakeFactory();
        PhoneNavigationOwner owner = owner(config, factory);

        owner.reconcile();
        assertEquals(1, factory.sidebars.size());
        assertEquals(1, factory.taskbars.size());
        FakeSidebar sidebar = factory.sidebars.get(0);
        FakeSurface taskbar = factory.taskbars.get(0);
        assertTrue(sidebar.taskbarReady);
        assertTrue(owner.ready());

        owner.reconcile();
        assertEquals("unchanged settings must not replace either surface", 1, factory.sidebars.size());
        assertEquals(1, factory.taskbars.size());
        assertEquals(0, sidebar.closeCount);
        assertEquals(0, taskbar.closeCount);

        assertTrue(owner.toggleStart());
        assertEquals(1, taskbar.toggleCount);
        assertEquals(0, sidebar.toggleCount);

        config.taskbar = false;
        owner.reconcile();
        assertEquals(1, taskbar.closeCount);
        assertFalse(sidebar.taskbarReady);
        assertTrue(owner.toggleStart());
        assertEquals(1, sidebar.toggleCount);
        assertEquals(1, taskbar.toggleCount);

        owner.close();
        assertEquals(1, sidebar.closeCount);
        assertFalse(owner.ready());
    }

    @Test public void disabledNavigationClosesSurfacesAndRejectsLateCallbacks() {
        MutableConfig config = new MutableConfig(true, true, false);
        FakeFactory factory = new FakeFactory();
        int[] areaChanges = {0};
        PhoneNavigationOwner owner = new PhoneNavigationOwner(null, () -> {}, () -> areaChanges[0]++, config, factory);
        owner.reconcile();

        assertTrue(factory.sidebars.isEmpty());
        assertTrue(factory.taskbars.isEmpty());
        assertFalse(owner.ready());

        config.overlays = true;
        owner.reconcile();
        Runnable oldSidebarCallback = factory.sidebars.get(0).areaChanged;
        Runnable oldTaskbarCallback = factory.taskbars.get(0).areaChanged;

        config.overlays = false;
        config.sidebar = false;
        config.taskbar = false;
        owner.reconcile();
        assertEquals(1, factory.sidebars.get(0).closeCount);
        assertEquals(1, factory.taskbars.get(0).closeCount);
        assertFalse(owner.ready());

        oldSidebarCallback.run();
        oldTaskbarCallback.run();
        assertEquals("callbacks from closed surfaces must be ignored", 0, areaChanges[0]);

        owner.close();
        owner.close();
        oldSidebarCallback.run();
        oldTaskbarCallback.run();
        assertEquals(0, areaChanges[0]);
        assertEquals(1, factory.sidebars.get(0).closeCount);
        assertEquals(1, factory.taskbars.get(0).closeCount);
    }

    @Test public void homeVisibilityIsReplayedAndGeometryRefreshDoesNotReplaceOwners() {
        MutableConfig config = new MutableConfig(true, true, true);
        FakeFactory factory = new FakeFactory();
        PhoneNavigationOwner owner = owner(config, factory);
        owner.homeVisible(true);
        owner.reconcile();

        FakeSidebar sidebar = factory.sidebars.get(0);
        FakeSurface taskbar = factory.taskbars.get(0);
        assertEquals(List.of(true), sidebar.homeVisibility);

        int sidebarRebuilds = sidebar.rebuildCount;
        int taskbarRebuilds = taskbar.rebuildCount;
        owner.geometryChanged();
        assertEquals(sidebarRebuilds + 1, sidebar.rebuildCount);
        assertEquals(taskbarRebuilds + 1, taskbar.rebuildCount);
        assertEquals(1, taskbar.refreshCount);
        assertSame(sidebar, factory.sidebars.get(0));
        assertSame(taskbar, factory.taskbars.get(0));

        owner.homeVisible(false);
        assertEquals(List.of(true, false), sidebar.homeVisibility);
        owner.close();
    }

    @Test public void replacementOwnerReplaysHomeVisibilityAndIgnoresPreviousGeneration() {
        MutableConfig config = new MutableConfig(true, false, true);
        FakeFactory oldFactory = new FakeFactory();
        int[] areaChanges = {0};
        PhoneNavigationOwner oldOwner = new PhoneNavigationOwner(null, () -> {}, () -> areaChanges[0]++, config, oldFactory);
        oldOwner.homeVisible(true);
        oldOwner.reconcile();
        Runnable stale = oldFactory.sidebars.get(0).areaChanged;
        oldOwner.close();

        FakeFactory newFactory = new FakeFactory();
        PhoneNavigationOwner newOwner = new PhoneNavigationOwner(null, () -> {}, () -> areaChanges[0]++, config, newFactory);
        newOwner.homeVisible(true);
        newOwner.reconcile();
        assertEquals(List.of(true), newFactory.sidebars.get(0).homeVisibility);

        stale.run();
        assertEquals("old service generation must not notify the new runtime", 0, areaChanges[0]);
        newFactory.sidebars.get(0).areaChanged.run();
        assertEquals(1, areaChanges[0]);
        oldOwner.close();
        newOwner.close();
    }

    @Test public void failedTaskbarAttachFallsBackToReadySidebarAndCanRetry() {
        MutableConfig config = new MutableConfig(true, true, true);
        FakeFactory factory = new FakeFactory();
        factory.failTaskbarCreations = 1;
        PhoneNavigationOwner owner = owner(config, factory);

        owner.reconcile();
        FakeSidebar sidebar = factory.sidebars.get(0);
        assertFalse(sidebar.taskbarReady);
        assertTrue(owner.ready());
        assertTrue(owner.toggleStart());
        assertEquals(1, sidebar.toggleCount);

        owner.closeStart();
        owner.reconcile();
        assertEquals(2, factory.taskbarAttempts);
        assertTrue(factory.taskbars.get(0).ready());
        assertTrue(factory.sidebars.get(0).taskbarReady);
        owner.close();
    }

    private static PhoneNavigationOwner owner(MutableConfig config, FakeFactory factory) {
        return new PhoneNavigationOwner(null, () -> {}, () -> {}, config, factory);
    }

    private static final class MutableConfig implements PhoneNavigationOwner.ConfigSource {
        boolean sidebar, taskbar, overlays;
        MutableConfig(boolean sidebar, boolean taskbar, boolean overlays) {
            this.sidebar = sidebar;
            this.taskbar = taskbar;
            this.overlays = overlays;
        }
        @Override public PhoneNavigationOwner.Desired current() {
            return new PhoneNavigationOwner.Desired(sidebar, taskbar, overlays);
        }
    }

    private static final class FakeFactory implements PhoneNavigationOwner.SurfaceFactory {
        final List<FakeSidebar> sidebars = new ArrayList<>();
        final List<FakeSurface> taskbars = new ArrayList<>();
        int taskbarAttempts;
        int failTaskbarCreations;
        @Override public PhoneNavigationOwner.SidebarSurface createSidebar(Context context, Runnable areaChanged) {
            FakeSidebar surface = new FakeSidebar(areaChanged);
            sidebars.add(surface);
            return surface;
        }
        @Override public PhoneNavigationOwner.Surface createTaskbar(Context context, Runnable areaChanged) {
            taskbarAttempts++;
            if (failTaskbarCreations > 0) {
                failTaskbarCreations--;
                throw new IllegalStateException("fixture taskbar attach failure");
            }
            FakeSurface surface = new FakeSurface(areaChanged);
            taskbars.add(surface);
            return surface;
        }
    }

    private static class FakeSurface implements PhoneNavigationOwner.Surface {
        final Runnable areaChanged;
        boolean ready = true;
        boolean startOpen;
        int toggleCount, closeCount, rebuildCount, relayoutCount, refreshCount;
        FakeSurface(Runnable areaChanged) { this.areaChanged = areaChanged; }
        @Override public boolean ready() { return ready && closeCount == 0; }
        @Override public boolean isStartOpen() { return startOpen; }
        @Override public void toggleStart() { toggleCount++; startOpen = !startOpen; }
        @Override public void closeStart() { startOpen = false; }
        @Override public void rebuild() { rebuildCount++; startOpen = false; }
        @Override public void relayout() { relayoutCount++; }
        @Override public void refreshArea() { refreshCount++; }
        @Override public void close() { closeCount++; startOpen = false; }
    }

    private static final class FakeSidebar extends FakeSurface implements PhoneNavigationOwner.SidebarSurface {
        boolean taskbarReady;
        final List<Boolean> homeVisibility = new ArrayList<>();
        FakeSidebar(Runnable areaChanged) { super(areaChanged); }
        @Override public void taskbarReady(boolean ready) {
            if (taskbarReady != ready) {
                taskbarReady = ready;
                rebuild();
            }
        }
        @Override public void homeVisible(boolean visible) { homeVisibility.add(visible); }
    }
}
