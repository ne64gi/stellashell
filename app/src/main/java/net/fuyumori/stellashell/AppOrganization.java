package net.fuyumori.stellashell;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** Launcher presentation only: never disables packages or changes running tasks. */
final class AppOrganization {
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("app_organization",0);}
    static List<String> groups(Context c){List<String> result=new ArrayList<>(prefs(c).getStringSet("groups",Collections.emptySet()));result.sort(String.CASE_INSENSITIVE_ORDER);return result;}
    static String group(Context c,String component){return prefs(c).getString("group."+component,"");}
    static void assign(Context c,String component,String group){prefs(c).edit().putString("group."+component,group).apply();}
    static void initialize(Context c){StartMenuSettings.initializeVisibility(prefs(c));}
    static boolean hidden(Context c,int displayId,String component){return StartMenuSettings.hidden(prefs(c),displayId,component);}
    static void hide(Context c,int displayId,String component,boolean hidden){StartMenuSettings.hide(prefs(c),displayId,component,hidden);}
    static boolean hidden(Context c,String component){return hidden(c,WorkspaceProfile.phone(c)?0:1,component);}
    static void hide(Context c,String component,boolean hidden){hide(c,WorkspaceProfile.phone(c)?0:1,component,hidden);}
    /** Applies only edited rows in one preference transaction; unknown/uninstalled rows survive. */
    static boolean applySelection(Context c,String targetGroup,Map<String,Boolean> changes){
        return applySelection(c,WorkspaceProfile.phone(c)?0:1,targetGroup,changes);
    }
    static boolean applySelection(Context c,int displayId,String targetGroup,Map<String,Boolean> changes){
        if(targetGroup!=null&&!groups(c).contains(targetGroup))return false;
        if(targetGroup==null){
            StartMenuSettings.applyVisibility(prefs(c),displayId,changes);return true;
        }
        SharedPreferences store=prefs(c);SharedPreferences.Editor editor=store.edit();
        for(Map.Entry<String,Boolean> item:changes.entrySet()){
            if(item.getValue())editor.putString("group."+item.getKey(),targetGroup);
            else if(targetGroup.equals(group(c,item.getKey())))editor.remove("group."+item.getKey());
        }
        editor.apply();return true;
    }
    static void addGroup(Context c,String name){Set<String> groups=new HashSet<>(groups(c));groups.add(name);prefs(c).edit().putStringSet("groups",groups).apply();}
    static void renameGroup(Context c,String old,String name){
        Set<String> groups=new HashSet<>(groups(c));groups.remove(old);if(!name.isEmpty())groups.add(name);
        String id=prefs(c).getString("id."+old,"");
        SharedPreferences.Editor editor=prefs(c).edit().putStringSet("groups",groups).remove("id."+old);
        if(!name.isEmpty()&&!id.isEmpty())editor.putString("id."+name,id);
        for(Map.Entry<String,?> entry:prefs(c).getAll().entrySet())if(entry.getKey().startsWith("group.")&&old.equals(entry.getValue()))editor.putString(entry.getKey(),name);
        editor.apply();
        if(name.isEmpty())GroupEntries.removeReferences(c,id);
    }
}
