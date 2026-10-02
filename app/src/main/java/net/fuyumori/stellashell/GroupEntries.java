package net.fuyumori.stellashell;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** Stable folder references; group names can change without losing placement. */
final class GroupEntries {
    static boolean isGroup(String item){return item.startsWith("group:");}
    static String reference(Context c,String name){
        if(!AppOrganization.groups(c).contains(name))throw new IllegalArgumentException("Unknown group");
        SharedPreferences prefs=AppOrganization.prefs(c);String id=prefs.getString("id."+name,"");
        if(id.isEmpty()){id=UUID.randomUUID().toString();prefs.edit().putString("id."+name,id).apply();}
        return "group:"+id;
    }
    static String name(Context c,String item){
        if(!isGroup(item))return null;
        for(String name:AppOrganization.groups(c))if(item.equals("group:"+AppOrganization.prefs(c).getString("id."+name,"")))return name;
        return null;
    }
    static void validate(Context c,String item){if(isGroup(item)){if(name(c,item)==null)throw new IllegalArgumentException("Unknown group");}else Policy.component(item);}
    static void removeReferences(Context c,String id){
        if(id.isEmpty())return;String token="group:"+id;SharedPreferences prefs=Launches.prefs(c);SharedPreferences.Editor edit=prefs.edit();
        for(String key:new String[]{"start_pinned","phone_start_pinned","desktop_shortcuts","phone_desktop_shortcuts"}){
            if(!prefs.contains(key))continue;
            List<String> items=new ArrayList<>(Arrays.asList(prefs.getString(key,"").split("\\n")));
            if(items.remove(token))edit.putString(key,String.join("\n",items));
        }
        edit.apply();
        for(String file:new String[]{"shortcut_positions","phone_shortcut_positions"})c.getSharedPreferences(file,0).edit().remove(token).apply();
    }
}
