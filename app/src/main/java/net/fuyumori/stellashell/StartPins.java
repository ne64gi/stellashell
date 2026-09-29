package net.fuyumori.stellashell;

import android.content.Context;
import java.util.*;

/** Start pins are independent of the taskbar's six slots. */
final class StartPins {
    private static final String KEY="start_pinned";
    static void initialize(Context context){
        if(!Launches.prefs(context).contains(KEY))Launches.prefs(context).edit().putString(KEY,String.join("\n",Launches.pins(context))).apply();
    }
    static List<String> get(Context context){
        String value=Launches.prefs(context).getString(KEY,"");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void toggle(Context context,String component){
        Policy.component(component);initialize(context);List<String> items=get(context);
        if(!items.remove(component))items.add(component);
        Launches.prefs(context).edit().putString(KEY,String.join("\n",items)).apply();
    }
}
