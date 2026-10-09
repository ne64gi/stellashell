package net.fuyumori.stellashell;

import android.content.Intent;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;

/** Standard launcher requests and shell shortcuts share routing, not HOME/task operations. */
final class StartMenuRequests {
    static final String LAUNCHER_TOGGLE="launcher.intent_action_all_apps_toggle";
    static final String SHELL_TOGGLE="net.fuyumori.stellashell.TOGGLE_START";
    static final String DISPLAY="start_display";
    static final String SYSTEM_REQUEST="system_start_request";
    static ComponentName home(Context context){
        ResolveInfo home=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);
        return home==null||home.activityInfo==null?null:new ComponentName(home.activityInfo.packageName,home.activityInfo.name);
    }
    static boolean defaultHome(Context context){ComponentName home=home(context);return home!=null&&context.getPackageName().equals(home.getPackageName());}
    enum Operation { NONE, SHOW, TOGGLE }
    static Operation operation(String action){
        if(Intent.ACTION_ALL_APPS.equals(action))return Operation.SHOW;
        if(LAUNCHER_TOGGLE.equals(action)||SHELL_TOGGLE.equals(action))return Operation.TOGGLE;
        return Operation.NONE;
    }
    static int target(int requested,int selected){return requested>=0?requested:selected>=0?selected:0;}
    static boolean route(Intent intent){
        if(intent==null)return false;
        Operation operation=operation(intent.getAction());
        if(operation==Operation.NONE)return false;
        int display=target(intent.getIntExtra(DISPLAY,-1),ShellRuntime.selectedDisplay());
        // A HOME fallback menu can still own this display when navigation attaches later.
        // Close/preserve that actual Start, never an unrelated Hub or Quick Settings panel.
        if(operation==Operation.TOGGLE&&ShellPanels.closeStart(display))return true;
        if(operation==Operation.SHOW&&ShellPanels.isStartOpen(display))return true;
        return operation==Operation.SHOW?ShellRuntime.showStart(display):ShellRuntime.toggleStart(display);
    }
    private StartMenuRequests(){}
}
