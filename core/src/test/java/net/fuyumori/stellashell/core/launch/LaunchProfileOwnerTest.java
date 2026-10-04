package net.fuyumori.stellashell.core.launch;

import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class LaunchProfileOwnerTest {
    private static final String KEY="com.example/com.example.Main";
    private static final class MemoryStore implements LaunchProfileStore {
        final Map<String,LaunchProfileSnapshot> values=new HashMap<>();
        int writes;
        @Override public LaunchProfileSnapshot read(String component){
            LaunchProfileSnapshot found=values.get(component);
            return found==null?new LaunchProfileSnapshot(new AppLaunchProfile(component)):found;
        }
        @Override public void write(String component,LaunchProfileSnapshot value){values.put(component,value);writes++;}
        @Override public Set<String> storedComponents(){return Collections.unmodifiableSet(new HashSet<>(values.keySet()));}
    }
    @Test public void stalePopupCommandKeepsLatestTaskGeometryStateAndAlias(){
        MemoryStore store=new MemoryStore();LaunchProfileOwner owner=new LaunchProfileOwner(store);
        LaunchProfileSnapshot popup=owner.snapshot(KEY);
        owner.setResolvedComponent(KEY,"com.example/com.example.Resolved");
        owner.observe(KEY,AppLaunchProfile.Mode.WINDOWED,137,98,920,640);
        owner.observe(KEY,AppLaunchProfile.Mode.MAXIMIZED,0,0,1600,900);
        owner.setMode(KEY,AppLaunchProfile.Mode.RESTORE_LAST);
        LaunchProfileSnapshot saved=owner.snapshot(KEY);
        assertEquals(AppLaunchProfile.Mode.WINDOWED,popup.launchMode);assertFalse(popup.hasLastBounds);
        assertEquals(AppLaunchProfile.Mode.RESTORE_LAST,saved.launchMode);
        assertEquals(AppLaunchProfile.Mode.MAXIMIZED,saved.lastState);
        assertEquals("com.example/com.example.Resolved",saved.resolvedComponent);
        assertEquals(137,saved.x);assertEquals(98,saved.y);assertEquals(920,saved.lastWidth);assertEquals(640,saved.lastHeight);
    }
    @Test public void customSizeFromOldDialogKeepsTaskObservationAndOtherChoices(){
        LaunchProfileOwner owner=new LaunchProfileOwner(new MemoryStore());
        LaunchProfileSnapshot dialog=owner.snapshot(KEY);
        owner.setPosition(KEY,AppLaunchProfile.Position.LAST);owner.setMode(KEY,AppLaunchProfile.Mode.FULLSCREEN);
        owner.observe(KEY,AppLaunchProfile.Mode.WINDOWED,76,91,777,555);
        owner.setCustomSize(KEY,1200,700);
        LaunchProfileSnapshot saved=owner.snapshot(KEY);
        assertEquals(800,dialog.width);assertEquals(AppLaunchProfile.Size.SMALL,dialog.size);
        assertEquals(1200,saved.width);assertEquals(700,saved.height);assertEquals(AppLaunchProfile.Size.CUSTOM,saved.size);
        assertEquals(AppLaunchProfile.Position.LAST,saved.position);assertEquals(AppLaunchProfile.Mode.FULLSCREEN,saved.launchMode);
        assertEquals(76,saved.x);assertEquals(777,saved.lastWidth);
    }
    @Test public void eachPreferenceCommandPreservesObservedAndUnrelatedFields(){
        LaunchProfileOwner owner=new LaunchProfileOwner(new MemoryStore());
        owner.setCustomSize(KEY,1000,650);owner.setResolvedComponent(KEY,"com.example/.Alias");
        owner.observe(KEY,AppLaunchProfile.Mode.WINDOWED,22,33,660,440);
        owner.setMode(KEY,AppLaunchProfile.Mode.MAXIMIZED);owner.setSize(KEY,AppLaunchProfile.Size.LAST);
        owner.setPosition(KEY,AppLaunchProfile.Position.CENTER);owner.setRememberBounds(KEY,false);
        LaunchProfileSnapshot saved=owner.snapshot(KEY);
        assertEquals(AppLaunchProfile.Mode.MAXIMIZED,saved.launchMode);assertEquals(AppLaunchProfile.Size.LAST,saved.size);
        assertEquals(AppLaunchProfile.Position.CENTER,saved.position);assertFalse(saved.rememberBounds);
        assertEquals(1000,saved.width);assertEquals(650,saved.height);assertEquals("com.example/.Alias",saved.resolvedComponent);
        assertEquals(22,saved.x);assertEquals(33,saved.y);assertEquals(660,saved.lastWidth);assertEquals(440,saved.lastHeight);
    }
    @Test public void disablingRememberKeepsSavedGeometryButStillObservesLastState(){
        LaunchProfileOwner owner=new LaunchProfileOwner(new MemoryStore());
        owner.observe(KEY,AppLaunchProfile.Mode.WINDOWED,50,70,500,350);owner.setRememberBounds(KEY,false);
        owner.observe(KEY,AppLaunchProfile.Mode.WINDOWED,150,170,900,700);
        owner.observe(KEY,AppLaunchProfile.Mode.FULLSCREEN,0,0,1600,900);
        LaunchProfileSnapshot saved=owner.snapshot(KEY);
        assertEquals(AppLaunchProfile.Mode.FULLSCREEN,saved.lastState);assertEquals(50,saved.x);assertEquals(70,saved.y);
        assertEquals(500,saved.lastWidth);assertEquals(350,saved.lastHeight);assertTrue(saved.hasLastBounds);
    }
    @Test public void noBoundsAndMaximizedObservationCannotReplaceRestoreGeometry(){
        LaunchProfileOwner owner=new LaunchProfileOwner(new MemoryStore());
        owner.observe(KEY,AppLaunchProfile.Mode.WINDOWED,123,234,700,500);
        owner.observe(KEY,AppLaunchProfile.Mode.WINDOWED,0,0,0,0);
        owner.observe(KEY,AppLaunchProfile.Mode.MAXIMIZED,0,0,1600,900);
        LaunchProfileSnapshot saved=owner.snapshot(KEY);
        assertEquals(123,saved.x);assertEquals(234,saved.y);assertEquals(700,saved.lastWidth);assertEquals(500,saved.lastHeight);
    }
    @Test public void detachedMutableReadAndOldSnapshotCannotEditStore(){
        AppLaunchProfile input=new AppLaunchProfile(KEY);LaunchProfileSnapshot immutable=new LaunchProfileSnapshot(input);
        input.width=1700;assertEquals(800,immutable.width);
        MemoryStore store=new MemoryStore();store.values.put(KEY,immutable);LaunchProfileOwner owner=new LaunchProfileOwner(store);
        AppLaunchProfile detached=owner.snapshot(KEY).toMutable();detached.width=1500;detached.x=90;
        assertEquals(800,owner.snapshot(KEY).width);assertEquals(0,owner.snapshot(KEY).x);
        owner.setCustomSize(KEY,900,650);assertEquals(800,immutable.width);assertEquals(900,owner.snapshot(KEY).width);
    }
    @Test public void invalidCustomSizesLeaveStoreUntouched(){
        MemoryStore store=new MemoryStore();LaunchProfileOwner owner=new LaunchProfileOwner(store);
        for(int[] dimensions:new int[][]{{239,600},{800,159},{16385,600},{800,16385}}){
            try{owner.setCustomSize(KEY,dimensions[0],dimensions[1]);fail("Accepted invalid custom dimensions");}catch(IllegalArgumentException expected){}
        }
        assertEquals(0,store.writes);assertEquals(800,owner.snapshot(KEY).width);
        owner.setCustomSize(KEY,240,160);owner.setCustomSize(KEY,16384,16384);assertEquals(2,store.writes);
    }
    @Test public void commandsReloadStoreInsteadOfRetainingPopupOrOwnerCache(){
        MemoryStore store=new MemoryStore();LaunchProfileOwner first=new LaunchProfileOwner(store),second=new LaunchProfileOwner(store);
        first.snapshot(KEY);second.setCustomSize(KEY,950,670);second.observe(KEY,AppLaunchProfile.Mode.WINDOWED,18,49,600,400);
        first.setPosition(KEY,AppLaunchProfile.Position.LAST);
        LaunchProfileSnapshot saved=second.snapshot(KEY);
        assertEquals(950,saved.width);assertEquals(670,saved.height);assertEquals(18,saved.x);assertEquals(49,saved.y);
        assertEquals(AppLaunchProfile.Position.LAST,saved.position);
    }
    @Test public void componentProfilesRemainIndependent(){
        LaunchProfileOwner owner=new LaunchProfileOwner(new MemoryStore());String other="com.other/.Main";
        owner.setMode(KEY,AppLaunchProfile.Mode.FULLSCREEN);owner.observe(KEY,AppLaunchProfile.Mode.WINDOWED,12,34,500,350);
        LaunchProfileSnapshot saved=owner.snapshot(other);
        assertEquals(AppLaunchProfile.Mode.WINDOWED,saved.launchMode);assertFalse(saved.hasLastBounds);assertEquals(0,saved.x);
    }
    @Test public void distinctAdaptersSerializeCommandsAgainstTheirSharedPersistenceLock()throws Exception {
        MemoryStore backing=new MemoryStore();CountDownLatch start=new CountDownLatch(1);
        class Adapter implements LaunchProfileStore {
            @Override public Object lockIdentity(){return backing;}
            @Override public LaunchProfileSnapshot read(String component){
                assertTrue("Read escaped the shared persistence lock",Thread.holdsLock(backing));
                LaunchProfileSnapshot result=backing.read(component);Thread.yield();return result;
            }
            @Override public void write(String component,LaunchProfileSnapshot profile){
                assertTrue("Write escaped the shared persistence lock",Thread.holdsLock(backing));
                backing.write(component,profile);
            }
        }
        LaunchProfileOwner first=new LaunchProfileOwner(new Adapter()),second=new LaunchProfileOwner(new Adapter());
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try{
            Future<?> mode=threads.submit(()->{await(start);for(int i=0;i<250;i++)first.setMode(KEY,AppLaunchProfile.Mode.MAXIMIZED);});
            Future<?> dimensions=threads.submit(()->{await(start);for(int i=0;i<250;i++)second.setCustomSize(KEY,1000+i,650+i);});
            start.countDown();mode.get(5,TimeUnit.SECONDS);dimensions.get(5,TimeUnit.SECONDS);
            LaunchProfileSnapshot saved=first.snapshot(KEY);
            assertEquals(AppLaunchProfile.Mode.MAXIMIZED,saved.launchMode);assertEquals(1249,saved.width);assertEquals(899,saved.height);
            assertEquals(AppLaunchProfile.Size.CUSTOM,saved.size);assertEquals(500,backing.writes);
        }finally{start.countDown();threads.shutdownNow();}
    }
    @Test public void directRequestedProfileAndObservedAliasRetainDifferentPrecedence(){
        MemoryStore store=new MemoryStore();LaunchProfileOwner owner=new LaunchProfileOwner(store);String alias="com.example/com.example.Alias";
        owner.setResolvedComponent(alias,KEY);
        assertEquals(alias,owner.requestedComponent(KEY));
        owner.setMode(KEY,AppLaunchProfile.Mode.FULLSCREEN);
        assertEquals(KEY,owner.requestedComponent(KEY));assertEquals(alias,owner.observedComponent(KEY,KEY));
        assertEquals(KEY,owner.observedComponent(KEY,"com.example/.Main"));
    }
    private static void await(CountDownLatch latch){
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Parallel start timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
}
