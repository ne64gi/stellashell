package net.fuyumori.stellashell.core.layout;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.fuyumori.stellashell.core.layout.WidgetLayoutProfiles.Profile.*;

public class WidgetLayoutProfilesTest {
    private WidgetLayoutProfiles.Geometry geometry(int x,int mode){return new WidgetLayoutProfiles.Geometry(x,24,320,180,mode,640,360);}
    private Map<Integer,WidgetLayoutProfiles.Geometry> catalog(int x){
        Map<Integer,WidgetLayoutProfiles.Geometry> result=new LinkedHashMap<>();result.put(42,geometry(x,WidgetGeometry.NORMAL));return result;
    }
    @Test public void firstMigrationSeedsAllProfilesBeforeEdits(){
        WidgetLayoutProfiles layouts=new WidgetLayoutProfiles(null,catalog(24));
        layouts.capture(PHONE_PORTRAIT,catalog(80));
        assertEquals(80,layouts.snapshot(PHONE_PORTRAIT).get(42).x);
        assertEquals(24,layouts.snapshot(PHONE_LANDSCAPE).get(42).x);
        assertEquals(24,layouts.snapshot(EXTERNAL).get(42).x);
    }
    @Test public void roundTripPreservesIndependentSizeAndRenderMode(){
        WidgetLayoutProfiles layouts=new WidgetLayoutProfiles(null,catalog(24));
        Map<Integer,WidgetLayoutProfiles.Geometry> landscape=catalog(120);
        landscape.put(42,new WidgetLayoutProfiles.Geometry(120,36,480,240,WidgetGeometry.SCALE,800,600));
        layouts.capture(PHONE_LANDSCAPE,landscape);layouts.capture(EXTERNAL,catalog(200));
        // Legacy items contain whichever profile was saved last; they must not overwrite others.
        WidgetLayoutProfiles restored=new WidgetLayoutProfiles(layouts.encode(),catalog(200));
        assertEquals(24,restored.snapshot(PHONE_PORTRAIT).get(42).x);
        WidgetLayoutProfiles.Geometry g=restored.snapshot(PHONE_LANDSCAPE).get(42);
        assertEquals(120,g.x);assertEquals(36,g.y);assertEquals(480,g.w);assertEquals(240,g.h);
        assertEquals(WidgetGeometry.SCALE,g.mode);assertEquals(800,g.baseW);assertEquals(600,g.baseH);
        assertEquals(200,restored.snapshot(EXTERNAL).get(42).x);
        assertEquals(layouts.encode(),restored.encode());
    }
    @Test public void newContentUsesSameIdentityWithoutChangingExistingGeometry(){
        WidgetLayoutProfiles layouts=new WidgetLayoutProfiles(null,catalog(24));
        layouts.capture(PHONE_LANDSCAPE,catalog(120));
        Map<Integer,WidgetLayoutProfiles.Geometry> added=catalog(24);added.put(-1,geometry(8,WidgetGeometry.FORCE));
        layouts.capture(PHONE_PORTRAIT,added);
        for(WidgetLayoutProfiles.Profile profile:WidgetLayoutProfiles.Profile.values()){
            assertEquals(2,layouts.snapshot(profile).size());assertEquals(8,layouts.snapshot(profile).get(-1).x);
        }
        assertEquals(120,layouts.snapshot(PHONE_LANDSCAPE).get(42).x);
    }
    @Test public void deletingLastItemPersistsEmptyAllProfilesAndDoesNotResurrect(){
        WidgetLayoutProfiles layouts=new WidgetLayoutProfiles(null,catalog(24));
        layouts.capture(PHONE_PORTRAIT,new LinkedHashMap<>());
        WidgetLayoutProfiles restored=new WidgetLayoutProfiles(layouts.encode(),new LinkedHashMap<>());
        for(WidgetLayoutProfiles.Profile profile:WidgetLayoutProfiles.Profile.values())assertTrue(restored.snapshot(profile).isEmpty());
        assertEquals(layouts.encode(),restored.encode());
    }
    @Test public void deletedIdsCannotSurviveInInactiveProfiles(){
        WidgetLayoutProfiles layouts=new WidgetLayoutProfiles(null,catalog(24));
        Map<Integer,WidgetLayoutProfiles.Geometry> replacement=new LinkedHashMap<>();replacement.put(-2,geometry(12,0));
        layouts.capture(EXTERNAL,replacement);
        for(WidgetLayoutProfiles.Profile profile:WidgetLayoutProfiles.Profile.values())assertEquals(replacement.keySet(),layouts.snapshot(profile).keySet());
    }
    @Test public void corruptProfileFallsBackWithoutLosingOtherProfiles(){
        WidgetLayoutProfiles layouts=new WidgetLayoutProfiles(null,catalog(24));layouts.capture(EXTERNAL,catalog(200));
        String damaged=layouts.encode().replace("phone_landscape|42,24,", "phone_landscape|42,invalid,");
        WidgetLayoutProfiles restored=new WidgetLayoutProfiles(damaged,catalog(72));
        assertEquals(24,restored.snapshot(PHONE_PORTRAIT).get(42).x);
        assertEquals(72,restored.snapshot(PHONE_LANDSCAPE).get(42).x);
        assertEquals(200,restored.snapshot(EXTERNAL).get(42).x);
    }
    @Test public void capturedAndReturnedMapsCannotMutateOwner(){
        WidgetLayoutProfiles layouts=new WidgetLayoutProfiles(null,catalog(24));Map<Integer,WidgetLayoutProfiles.Geometry> edited=catalog(80);
        layouts.capture(PHONE_PORTRAIT,edited);edited.clear();assertEquals(1,layouts.snapshot(PHONE_PORTRAIT).size());
        try{layouts.snapshot(PHONE_PORTRAIT).clear();fail("Snapshot should be immutable");}catch(UnsupportedOperationException expected){}
    }
    @Test public void externalDisplayOrientationIsNotAnotherLayout(){
        assertEquals(PHONE_PORTRAIT,WidgetLayoutProfiles.resolve(true,false));
        assertEquals(PHONE_LANDSCAPE,WidgetLayoutProfiles.resolve(true,true));
        assertEquals(EXTERNAL,WidgetLayoutProfiles.resolve(false,false));
        assertEquals(EXTERNAL,WidgetLayoutProfiles.resolve(false,true));
    }
    @Test public void unknownFutureProfileIsRetainedWithoutAffectingKnownLayouts(){
        WidgetLayoutProfiles original=new WidgetLayoutProfiles(null,catalog(24));
        WidgetLayoutProfiles restored=new WidgetLayoutProfiles(original.encode()+"\nfuture_profile|opaque-data",catalog(24));
        restored.capture(PHONE_PORTRAIT,catalog(80));
        assertTrue(restored.encode().endsWith("\nfuture_profile|opaque-data"));
        assertEquals(24,restored.snapshot(EXTERNAL).get(42).x);
    }
}
