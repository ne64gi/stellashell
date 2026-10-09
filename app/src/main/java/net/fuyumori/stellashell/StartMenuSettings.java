package net.fuyumori.stellashell;

import android.content.SharedPreferences;
import java.util.*;

/** Start presentation belongs to the menu's display, independent of the selected output. */
final class StartMenuSettings {
    private StartMenuSettings() {}
    static String hiddenKey(int displayId){return displayId==0?"phone_hidden":"desktop_hidden";}
    static String pinsKey(int displayId){return displayId==0?"phone_start_pinned":"start_pinned";}

    /** Snapshot the old shared visibility into both profiles before either can be edited. */
    static void initializeVisibility(SharedPreferences store){
        synchronized(store){
            if(store.contains("phone_hidden")&&store.contains("desktop_hidden"))return;
            Set<String> legacy=new HashSet<>(store.getStringSet("hidden",Collections.emptySet()));
            SharedPreferences.Editor editor=store.edit();
            if(!store.contains("phone_hidden"))editor.putStringSet("phone_hidden",new HashSet<>(legacy));
            if(!store.contains("desktop_hidden"))editor.putStringSet("desktop_hidden",new HashSet<>(legacy));
            editor.apply();
        }
    }
    static boolean hidden(SharedPreferences store,int displayId,String component){
        synchronized(store){initializeVisibility(store);return store.getStringSet(hiddenKey(displayId),Collections.emptySet()).contains(component);}
    }
    static void hide(SharedPreferences store,int displayId,String component,boolean hidden){
        applyVisibility(store,displayId,Collections.singletonMap(component,!hidden));
    }
    /** Merge edited rows with the latest values, preserving concurrent edits and missing apps. */
    static void applyVisibility(SharedPreferences store,int displayId,Map<String,Boolean> changes){
        synchronized(store){
            initializeVisibility(store);String key=hiddenKey(displayId);
            Set<String> hidden=new HashSet<>(store.getStringSet(key,Collections.emptySet()));
            for(Map.Entry<String,Boolean> item:changes.entrySet()){
                if(item.getValue())hidden.remove(item.getKey());else hidden.add(item.getKey());
            }
            store.edit().putStringSet(key,hidden).apply();
        }
    }
    static void initializePins(SharedPreferences store,int displayId){
        synchronized(store){
            String key=pinsKey(displayId);if(store.contains(key))return;
            store.edit().putString(key,store.getString(displayId==0?"phone_pinned":"pinned","")).apply();
        }
    }
    static List<String> pins(SharedPreferences store,int displayId){
        synchronized(store){
            String value=store.getString(pinsKey(displayId),"");
            return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
        }
    }
    static void togglePin(SharedPreferences store,int displayId,String component){
        synchronized(store){
            initializePins(store,displayId);List<String> items=pins(store,displayId);
            if(!items.remove(component))items.add(component);
            store.edit().putString(pinsKey(displayId),String.join("\n",items)).apply();
        }
    }
}
