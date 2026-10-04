package net.fuyumori.stellashell;

import android.content.*;
import android.app.Service;
import android.view.Display;
import java.util.*;

/** Profile identity follows the display, never its dimensions or rotation. */
final class WorkspaceProfile {
    static boolean phone(Context c){
        try{Display d=c instanceof Service?null:c.getDisplay();if(d!=null)return d.getDisplayId()==0;}catch(UnsupportedOperationException ignored){}
        int selected=ShellRuntime.selectedDisplay();
        return (selected>=0?selected:TaskState.of(c).target(c))==0;
    }
    static boolean standard(Context c,int display){return display==0;}
    static String key(Context c,String key){initialize(c);return phone(c)?"phone_"+key:key;}
    static boolean changed(String key,String base){return base.equals(key)||("phone_"+base).equals(key);}
    static synchronized void initialize(Context c){
        SharedPreferences p=Launches.prefs(c);if(p.getBoolean("phone_profile_initialized",false))return;
        SharedPreferences.Editor e=p.edit();
        for(String key:new String[]{"pinned","start_pinned","desktop_shortcuts","shortcut_snap","wallpaper","wallpaper_fit","wallpaper_image"}){
            Object value=p.getAll().get(key);String target="phone_"+key;
            if(p.contains(target))continue;
            if(value instanceof String)e.putString(target,(String)value);
            else if(value instanceof Boolean)e.putBoolean(target,(Boolean)value);
            else if(value instanceof Integer)e.putInt(target,(Integer)value);
        }
        // Widget IDs cannot be cloned: keep the existing independent home host/storage.
        SharedPreferences old=c.getSharedPreferences("shortcut_positions",0),positions=c.getSharedPreferences("phone_shortcut_positions",0);
        SharedPreferences.Editor pos=positions.edit();
        for(Map.Entry<String,?> entry:old.getAll().entrySet())if(!positions.contains(entry.getKey())&&entry.getValue() instanceof String)pos.putString(entry.getKey(),(String)entry.getValue());
        pos.commit();e.putBoolean("phone_profile_initialized",true).commit();
    }
}
