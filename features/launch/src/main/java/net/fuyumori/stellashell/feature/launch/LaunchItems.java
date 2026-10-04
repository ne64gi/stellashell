package net.fuyumori.stellashell.feature.launch;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.fuyumori.stellashell.core.launch.Policy;

/** Pins, shared recents, and profile desktop placement. The app injects its existing preferences. */
public final class LaunchItems {
    public enum Profile { PHONE, DESKTOP }
    public enum Surface { DOCK, TASKBAR }
    public enum ToggleResult { ADDED, REMOVED, LIMIT_REACHED }
    public static final class Snapshot {
        public final List<String> dockPins, taskbarPins, recent, desktop;
        private Snapshot(List<String> dockPins,List<String> taskbarPins,List<String> recent,List<String> desktop){
            this.dockPins=immutable(dockPins);this.taskbarPins=immutable(taskbarPins);
            this.recent=immutable(recent);this.desktop=immutable(desktop);
        }
    }
    private final SharedPreferences preferences;
    public LaunchItems(SharedPreferences preferences){this.preferences=Objects.requireNonNull(preferences);}
    private static List<String> immutable(List<String> items){return Collections.unmodifiableList(new ArrayList<>(items));}
    private List<String> read(String key){
        String value=preferences.getString(key,"");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    private static String pinKey(Profile profile,Surface surface){
        Objects.requireNonNull(profile);Objects.requireNonNull(surface);
        if(profile==Profile.PHONE)return surface==Surface.DOCK?"phone_pinned":"phone_taskbar_pinned";
        return surface==Surface.DOCK?"dock_pinned":"pinned";
    }
    private static String desktopKey(Profile profile){
        return Objects.requireNonNull(profile)==Profile.PHONE?"phone_desktop_shortcuts":"desktop_shortcuts";
    }
    public Snapshot snapshot(Profile profile){
        synchronized(preferences){return new Snapshot(read(pinKey(profile,Surface.DOCK)),read(pinKey(profile,Surface.TASKBAR)),read("recent"),read(desktopKey(profile)));}
    }
    public List<String> pins(Profile profile,Surface surface){
        synchronized(preferences){return immutable(read(pinKey(profile,surface)));}
    }
    public List<String> recents(){synchronized(preferences){return immutable(read("recent"));}}
    public List<String> desktop(Profile profile){synchronized(preferences){return immutable(read(desktopKey(profile)));}}
    /** Original shortcut union uses phone Dock or external Taskbar pins, then shared recents. */
    public List<String> shortcuts(Profile profile){
        synchronized(preferences){
            List<String> out=read(pinKey(profile,profile==Profile.PHONE?Surface.DOCK:Surface.TASKBAR));
            for(String item:read("recent"))if(!out.contains(item))out.add(item);
            return immutable(out);
        }
    }
    public ToggleResult togglePin(Profile profile,Surface surface,String component){
        Policy.component(component);
        synchronized(preferences){
            String key=pinKey(profile,surface);List<String> items=read(key);ToggleResult result;
            if(items.remove(component))result=ToggleResult.REMOVED;
            else{
                if(surface==Surface.TASKBAR&&items.size()>=6)return ToggleResult.LIMIT_REACHED;
                items.add(component);result=ToggleResult.ADDED;
            }
            preferences.edit().putString(key,String.join("\n",items)).apply();return result;
        }
    }
    public void remember(String component){
        Policy.component(component);
        synchronized(preferences){preferences.edit().putString("recent",String.join("\n",Policy.recent(read("recent"),component))).apply();}
    }
    /** Placement may be a group reference. The app validates identity/group existence before this command. */
    public void toggleDesktop(Profile profile,String placement){
        Objects.requireNonNull(placement);
        synchronized(preferences){
            String key=desktopKey(profile);List<String> items=read(key);
            if(!items.remove(placement))items.add(placement);
            preferences.edit().putString(key,String.join("\n",items)).apply();
        }
    }
    /** Remove the first matching legacy placement in each existing profile, without creating keys. */
    public void removeDesktopReference(String reference){
        Objects.requireNonNull(reference);
        synchronized(preferences){
            SharedPreferences.Editor edit=preferences.edit();
            for(String key:new String[]{"desktop_shortcuts","phone_desktop_shortcuts"}){
                if(!preferences.contains(key))continue;
                List<String> items=new ArrayList<>(Arrays.asList(preferences.getString(key,"").split("\\n")));
                if(items.remove(reference))edit.putString(key,String.join("\n",items));
            }
            edit.apply();
        }
    }
}
