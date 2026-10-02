package net.fuyumori.stellashell;

import android.content.Context;
import java.util.*;

/** Start pins are independent of the taskbar's six slots. */
final class StartPins {
    private static final String KEY="start_pinned";
    static void initialize(Context context){
        if(!Launches.prefs(context).contains(WorkspaceProfile.key(context,KEY)))Launches.prefs(context).edit().putString(WorkspaceProfile.key(context,KEY),String.join("\n",Launches.pins(context))).apply();
    }
    static List<String> get(Context context){
        String value=Launches.prefs(context).getString(WorkspaceProfile.key(context,KEY),"");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void toggle(Context context,String component){
        GroupEntries.validate(context,component);initialize(context);List<String> items=get(context);
        if(!items.remove(component))items.add(component);
        Launches.prefs(context).edit().putString(WorkspaceProfile.key(context,KEY),String.join("\n",items)).apply();
    }
}
