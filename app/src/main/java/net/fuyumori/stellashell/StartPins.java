package net.fuyumori.stellashell;

import android.content.Context;
import java.util.*;

/** Start pins are independent of the taskbar's six slots. */
final class StartPins {
    static void initialize(Context context,int displayId){
        WorkspaceProfile.initialize(context);StartMenuSettings.initializePins(Launches.prefs(context),displayId);
    }
    static List<String> get(Context context,int displayId){
        WorkspaceProfile.initialize(context);return StartMenuSettings.pins(Launches.prefs(context),displayId);
    }
    static void toggle(Context context,int displayId,String component){
        GroupEntries.validate(context,component);initialize(context,displayId);StartMenuSettings.togglePin(Launches.prefs(context),displayId,component);
    }
    static void initialize(Context context){initialize(context,WorkspaceProfile.phone(context)?0:1);}
    static List<String> get(Context context){return get(context,WorkspaceProfile.phone(context)?0:1);}
    static void toggle(Context context,String component){toggle(context,WorkspaceProfile.phone(context)?0:1,component);}
}
