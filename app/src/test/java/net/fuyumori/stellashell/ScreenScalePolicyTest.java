package net.fuyumori.stellashell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenScalePolicyTest {
    private static ScreenScalePolicy.State state(String identity,int physical,int override){return new ScreenScalePolicy.State(identity,new ScreenScalePolicy.Density(physical,override));}
    private static final class Fake implements ScreenScalePolicy.Store {
        final Map<Integer,ScreenScalePolicy.State> states=new HashMap<>();final List<String[]> commands=new ArrayList<>();
        String failure="";
        public ScreenScalePolicy.State read(int id)throws Exception{ScreenScalePolicy.State state=states.get(id);if(state==null)throw new IOException("Screen disconnected");return state;}
        public void write(int id,int override)throws Exception{
            commands.add(ScreenScalePolicy.command(id,override));ScreenScalePolicy.State old=read(id);
            if(commands.size()==1){
                if("noop".equals(failure))return;
                if("replace".equals(failure)){states.put(id,state("replacement",old.density.physical,override));return;}
                if("disconnect".equals(failure)){states.remove(id);return;}
                if("corrupt".equals(failure)){states.put(id,state(old.identity,old.density.physical,480));return;}
            }
            states.put(id,state(old.identity,old.density.physical,override));
            if(commands.size()==1&&"throwAfterWrite".equals(failure))throw new IOException("Process reported failure after changing density");
        }
    }
    private static Fake fixture(int override){Fake store=new Fake();store.states.put(7,state("owned-virtual",400,override));store.states.put(0,state("main",480,420));return store;}
    private static Exception rejected(Fake store,ScreenScalePolicy.State before,int percent){
        try{ScreenScalePolicy.apply(store,7,before,percent);fail("Expected a fail-closed rejection");return null;}catch(Exception failure){return failure;}
    }
    private static void mainUnchanged(Fake store){assertEquals(new ScreenScalePolicy.Density(480,420),store.states.get(0).density);for(String[] command:store.commands){assertEquals("-d",command[3]);assertEquals("7",command[4]);}}
    @Test public void parsesPhysicalAndExactOverrideWithoutInventingStandard(){
        assertEquals(new ScreenScalePolicy.Density(400,0),ScreenScalePolicy.parse("Physical density: 400"));
        ScreenScalePolicy.Density density=ScreenScalePolicy.parse(" Physical density: 400\r\nOverride density: 438\n");
        assertEquals(438,density.current());assertEquals(110,density.percent());assertEquals(438,density.override);
        assertEquals(400,ScreenScalePolicy.parse("Physical density: 400\nOverride density: 400").override);
    }
    @Test public void malformedOrPartialOutputNeverFallsBackToMainOrDefault(){
        for(String output:new String[]{"","Override density: 320","Physical density: 0","Physical density: -1","Physical density: 400\nError: invalid display","Physical density: 400\nPhysical density: 480","Physical density: 400\nOverride density: 438\nOverride density: 440","Physical density: 999999999999"}){
            assertThrows(IllegalArgumentException.class,()->ScreenScalePolicy.parse(output));
        }
    }
    @Test public void physicalBaselineAndBoundsAreIndependentOfExistingOverride(){
        assertEquals(401,ScreenScalePolicy.targetDensity(321,125));assertEquals(200,ScreenScalePolicy.targetDensity(400,50));assertEquals(800,ScreenScalePolicy.targetDensity(400,200));
        assertThrows(IllegalArgumentException.class,()->ScreenScalePolicy.percent(49));assertThrows(IllegalArgumentException.class,()->ScreenScalePolicy.percent(201));
        assertThrows(IllegalArgumentException.class,()->ScreenScalePolicy.targetDensity(120,50));
        assertArrayEquals(new String[]{"/system/bin/wm","density","500","-d","7"},ScreenScalePolicy.command(7,500));
        assertArrayEquals(new String[]{"/system/bin/wm","density","reset","-d","0"},ScreenScalePolicy.command(0,0));
        assertThrows(IllegalArgumentException.class,()->ScreenScalePolicy.command(-1,400));
    }
    @Test public void applyUsesPhysicalBaselineAndResetClearsOnlySelectedOverride()throws Exception{
        Fake store=fixture(438);ScreenScalePolicy.State after=ScreenScalePolicy.apply(store,7,store.read(7),125);
        assertEquals(new ScreenScalePolicy.Density(400,500),after.density);assertEquals(1,store.commands.size());mainUnchanged(store);
        after=ScreenScalePolicy.apply(store,7,after,100);assertEquals(new ScreenScalePolicy.Density(400,0),after.density);
        assertEquals("reset",store.commands.get(1)[2]);mainUnchanged(store);
    }
    @Test public void staleIdentityOrDensityRejectsBeforeAnyCommand(){
        Fake store=fixture(438);rejected(store,state("previous-virtual",400,438),125);assertTrue(store.commands.isEmpty());
        rejected(store,state("owned-virtual",400,440),125);assertTrue(store.commands.isEmpty());mainUnchanged(store);
    }
    @Test public void failedVerificationRestoresExact438NotRounded110Percent(){
        for(String mode:new String[]{"noop","corrupt","throwAfterWrite"}){
            Fake store=fixture(438);store.failure=mode;Exception failure=rejected(store,store.states.get(7),125);
            assertNotNull(failure);assertEquals(2,store.commands.size());assertEquals("438",store.commands.get(1)[2]);
            assertEquals(new ScreenScalePolicy.Density(400,438),store.states.get(7).density);mainUnchanged(store);
        }
    }
    @Test public void priorStandardIsRestoredWithResetNotAnAddedPhysicalOverride(){
        Fake store=fixture(0);store.failure="corrupt";rejected(store,store.states.get(7),150);
        assertEquals("reset",store.commands.get(1)[2]);assertEquals(0,store.states.get(7).density.override);mainUnchanged(store);
    }
    @Test public void replacedOrDisconnectedIdNeverReceivesRollbackOrMainFallback(){
        for(String mode:new String[]{"replace","disconnect"}){
            Fake store=fixture(438);store.failure=mode;Exception failure=rejected(store,store.states.get(7),125);
            assertEquals(1,store.commands.size());assertEquals(1,failure.getSuppressed().length);mainUnchanged(store);
            if("replace".equals(mode)){assertEquals("replacement",store.states.get(7).identity);assertEquals(500,store.states.get(7).density.override);}
            else assertNull(store.states.get(7));
        }
    }
}
