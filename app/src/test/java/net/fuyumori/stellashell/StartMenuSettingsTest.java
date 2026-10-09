package net.fuyumori.stellashell;

import android.content.SharedPreferences;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartMenuSettingsTest {
    @Test public void firstPhoneEditPreservesDesktopLegacyBaseline(){
        MemoryPreferences store=new MemoryPreferences();
        store.edit().putStringSet("hidden",Set.of("old/.Hidden","missing/.App")).apply();

        StartMenuSettings.hide(store,0,"old/.Hidden",false);
        StartMenuSettings.hide(store,0,"phone/.Private",true);

        assertFalse(StartMenuSettings.hidden(store,0,"old/.Hidden"));
        assertTrue(StartMenuSettings.hidden(store,41,"old/.Hidden"));
        assertFalse(StartMenuSettings.hidden(store,41,"phone/.Private"));
        assertTrue(StartMenuSettings.hidden(store,0,"missing/.App"));
        assertTrue(StartMenuSettings.hidden(store,41,"missing/.App"));
        assertEquals(Set.of("old/.Hidden","missing/.App"),store.getStringSet("hidden",Set.of()));
    }

    @Test public void externalDisplaysShareOneProfileAndPhoneRemainsIndependent(){
        MemoryPreferences store=new MemoryPreferences();
        StartMenuSettings.hide(store,17,"external/.App",true);
        assertTrue(StartMenuSettings.hidden(store,29,"external/.App"));
        assertFalse(StartMenuSettings.hidden(store,0,"external/.App"));
        StartMenuSettings.hide(store,29,"external/.App",false);
        assertFalse(StartMenuSettings.hidden(store,17,"external/.App"));
    }

    @Test public void migrationRetainsExistingProfileEvenWhenEmpty(){
        MemoryPreferences store=new MemoryPreferences();
        store.edit().putStringSet("hidden",Set.of("legacy/.Hidden"))
            .putStringSet("phone_hidden",Set.of()).apply();
        StartMenuSettings.initializeVisibility(store);
        assertFalse(StartMenuSettings.hidden(store,0,"legacy/.Hidden"));
        assertTrue(StartMenuSettings.hidden(store,8,"legacy/.Hidden"));
        StartMenuSettings.hide(store,8,"legacy/.Hidden",false);
        StartMenuSettings.initializeVisibility(store);
        assertFalse(StartMenuSettings.hidden(store,8,"legacy/.Hidden"));
    }

    @Test public void stagedSaveMergesOnlyChangedRowsWithLatestProfile(){
        MemoryPreferences store=new MemoryPreferences();
        store.edit().putStringSet("hidden",Set.of("missing/.App","edited/.App")).apply();
        // The selector opened before these other actions; only its edited row is saved.
        StartMenuSettings.hide(store,0,"concurrent/.App",true);
        StartMenuSettings.hide(store,7,"desktop/.App",true);
        StartMenuSettings.applyVisibility(store,0,Map.of("edited/.App",true));
        assertFalse(StartMenuSettings.hidden(store,0,"edited/.App"));
        assertTrue(StartMenuSettings.hidden(store,0,"missing/.App"));
        assertTrue(StartMenuSettings.hidden(store,0,"concurrent/.App"));
        assertTrue(StartMenuSettings.hidden(store,7,"edited/.App"));
        assertTrue(StartMenuSettings.hidden(store,7,"desktop/.App"));
        assertFalse(StartMenuSettings.hidden(store,7,"concurrent/.App"));
    }

    @Test public void startPinsPreserveBothLegacyListsAndDoNotChangeBars(){
        MemoryPreferences store=new MemoryPreferences();
        store.edit().putString("start_pinned","desktop/.Old\ngroup:folder")
            .putString("phone_start_pinned","phone/.Old")
            .putString("pinned","taskbar/.App").putString("phone_pinned","dock/.App")
            .putString("dock_pinned","externaldock/.App").putString("phone_taskbar_pinned","phonetaskbar/.App").apply();
        Map<String,?> before=store.getAll();
        StartMenuSettings.togglePin(store,0,"phone/.New");
        assertEquals(List.of("phone/.Old","phone/.New"),StartMenuSettings.pins(store,0));
        assertEquals(List.of("desktop/.Old","group:folder"),StartMenuSettings.pins(store,23));
        StartMenuSettings.togglePin(store,23,"external/.New");
        assertEquals(List.of("desktop/.Old","group:folder","external/.New"),StartMenuSettings.pins(store,42));
        assertFalse(StartMenuSettings.pins(store,0).contains("external/.New"));
        for(String key:List.of("pinned","phone_pinned","dock_pinned","phone_taskbar_pinned"))assertEquals(before.get(key),store.getAll().get(key));
    }

    @Test public void newStartProfilesSeedFromCorrectLegacySurfaceAndKeepExplicitEmpty(){
        MemoryPreferences store=new MemoryPreferences();
        store.edit().putString("pinned","taskbar/.App").putString("phone_pinned","dock/.App").apply();
        StartMenuSettings.initializePins(store,31);
        StartMenuSettings.initializePins(store,0);
        assertEquals(List.of("taskbar/.App"),StartMenuSettings.pins(store,31));
        assertEquals(List.of("dock/.App"),StartMenuSettings.pins(store,0));
        store.edit().putString("phone_start_pinned","").apply();
        StartMenuSettings.initializePins(store,0);
        assertTrue(StartMenuSettings.pins(store,0).isEmpty());
    }

    /** Minimal injected store: exercises real migration/merge commands without Android Context. */
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String,Object> values=new HashMap<>();
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String key,String fallback){return (String)values.getOrDefault(key,fallback);}
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> fallback){return new HashSet<>((Set<String>)values.getOrDefault(key,fallback));}
        public int getInt(String key,int fallback){return (Integer)values.getOrDefault(key,fallback);}
        public long getLong(String key,long fallback){return (Long)values.getOrDefault(key,fallback);}
        public float getFloat(String key,float fallback){return (Float)values.getOrDefault(key,fallback);}
        public boolean getBoolean(String key,boolean fallback){return (Boolean)values.getOrDefault(key,fallback);}
        public boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> changes=new HashMap<>();boolean clear;
            public Editor putString(String key,String value){changes.put(key,value);return this;}
            public Editor putStringSet(String key,Set<String> value){changes.put(key,value==null?null:new HashSet<>(value));return this;}
            public Editor putInt(String key,int value){changes.put(key,value);return this;}
            public Editor putLong(String key,long value){changes.put(key,value);return this;}
            public Editor putFloat(String key,float value){changes.put(key,value);return this;}
            public Editor putBoolean(String key,boolean value){changes.put(key,value);return this;}
            public Editor remove(String key){changes.put(key,null);return this;}
            public Editor clear(){clear=true;return this;}
            public boolean commit(){apply();return true;}
            public void apply(){if(clear)values.clear();for(Map.Entry<String,Object> entry:changes.entrySet()){if(entry.getValue()==null)values.remove(entry.getKey());else values.put(entry.getKey(),entry.getValue());}}
        };}
    }
}
