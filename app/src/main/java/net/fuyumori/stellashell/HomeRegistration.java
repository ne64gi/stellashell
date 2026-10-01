package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.*;
import android.provider.Settings;

/** Role selection stays in Android's UI; enabling the desktop never selects HOME. */
final class HomeRegistration {
    static boolean selected(Context context){
        RoleManager roles=context.getSystemService(RoleManager.class);
        return roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_HOME)&&roles.isRoleHeld(RoleManager.ROLE_HOME);
    }
    static void select(Activity activity){
        try{
            RoleManager roles=activity.getSystemService(RoleManager.class);
            if(roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_HOME)&&!roles.isRoleHeld(RoleManager.ROLE_HOME))
                activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME),7201);
            else settings(activity);
        }catch(RuntimeException error){settings(activity);}
    }
    static void settings(Activity activity){
        try{activity.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));}
        catch(ActivityNotFoundException error){
            try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));}
            catch(RuntimeException failure){Launches.problem(activity,failure.getMessage());}
        }
    }
}
