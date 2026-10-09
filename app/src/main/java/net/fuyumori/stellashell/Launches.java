package net.fuyumori.stellashell;

import android.app.ActivityOptions;
import android.content.*;
import android.graphics.drawable.Drawable;
import java.util.*;
import net.fuyumori.stellashell.core.launch.Policy;
import net.fuyumori.stellashell.feature.launch.AppCatalog;
import net.fuyumori.stellashell.feature.launch.LaunchItems;
import net.fuyumori.stellashell.feature.launch.PublicLauncher;

/** Legacy app-facing facade; domain owners and platform executors do the work. */
final class Launches {
    private static final ShellLaunchCoordinator coordinator=new ShellLaunchCoordinator();
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("desktop",Context.MODE_PRIVATE);}
    static final class App {
        final String component,label;final Drawable icon;
        App(String component,String label,Drawable icon){this.component=component;this.label=label;this.icon=icon;}
    }
    static List<App> catalog(Context c){
        return catalogApps(new AppCatalog(c).entries());
    }
    static List<App> cachedCatalog(Context c){
        List<AppCatalog.Entry> entries=new AppCatalog(c).cachedEntries();
        return entries==null?null:catalogApps(entries);
    }
    private static List<App> catalogApps(List<AppCatalog.Entry> entries){
        List<App> result=new ArrayList<>();
        for(AppCatalog.Entry entry:entries)result.add(new App(entry.component,entry.label,entry.icon));
        return result;
    }
    private static LaunchItems items(Context c){return new LaunchItems(prefs(c));}
    private static LaunchItems.Profile profile(Context c){return WorkspaceProfile.phone(c)?LaunchItems.Profile.PHONE:LaunchItems.Profile.DESKTOP;}
    static List<String> recents(Context c){return items(c).recents();}
    static void remember(Context c,String component){items(c).remember(component);}
    static List<String> pins(Context c){
        WorkspaceProfile.initialize(c);LaunchItems.Profile profile=profile(c);
        return items(c).pins(profile,profile==LaunchItems.Profile.PHONE?LaunchItems.Surface.DOCK:LaunchItems.Surface.TASKBAR);
    }
    static void togglePin(Context c,String component){
        Policy.component(component);WorkspaceProfile.initialize(c);LaunchItems.Profile profile=profile(c);
        toggle(c,profile,profile==LaunchItems.Profile.PHONE?LaunchItems.Surface.DOCK:LaunchItems.Surface.TASKBAR,component);
    }
    static List<String> taskbarPins(Context c){
        if(!WorkspaceProfile.phone(c))return pins(c);
        return items(c).pins(LaunchItems.Profile.PHONE,LaunchItems.Surface.TASKBAR);
    }
    static void toggleTaskbarPin(Context c,String component){
        Policy.component(component);if(!WorkspaceProfile.phone(c)){togglePin(c,component);return;}
        toggle(c,LaunchItems.Profile.PHONE,LaunchItems.Surface.TASKBAR,component);
    }
    static List<String> dockPins(Context c){
        if(WorkspaceProfile.phone(c))return pins(c);
        return items(c).pins(LaunchItems.Profile.DESKTOP,LaunchItems.Surface.DOCK);
    }
    static void toggleDockPin(Context c,String component){
        Policy.component(component);if(WorkspaceProfile.phone(c)){togglePin(c,component);return;}
        toggle(c,LaunchItems.Profile.DESKTOP,LaunchItems.Surface.DOCK,component);
    }
    private static void toggle(Context c,LaunchItems.Profile profile,LaunchItems.Surface surface,String component){
        if(items(c).togglePin(profile,surface,component)==LaunchItems.ToggleResult.LIMIT_REACHED)
            Ui.message(c,c.getString(R.string.ui_you_can_pin_up_to_6_apps));
    }
    static List<String> desktop(Context c){WorkspaceProfile.initialize(c);return items(c).desktop(profile(c));}
    static void toggleDesktop(Context c,String component){
        GroupEntries.validate(c,component);WorkspaceProfile.initialize(c);items(c).toggleDesktop(profile(c),component);
    }
    static void removeDesktopReference(Context c,String reference){items(c).removeDesktopReference(reference);}
    static List<String> shortcuts(Context c){WorkspaceProfile.initialize(c);return items(c).shortcuts(profile(c));}
    static void normalApp(Context c,String component){ShellLaunchExecutor.normal(c,component);}
    static Intent normalAppIntent(Context c,String component){return new PublicLauncher(c).intent(component);}
    static boolean basicHome(Context c,int display){
        return WorkspaceProfile.standard(c,display)||display==0&&c instanceof HomeActivity&&!HomeActivity.extensions(c);
    }
    static void settings(Context c,int displayId) {
        if(c instanceof HomeActivity||WorkspaceProfile.standard(c,displayId)){c.startActivity(new Intent(c,SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());return;}
        ShellLaunchExecutor.simple(c,new ComponentName(c,SetupActivity.class).flattenToString(),displayId,1,false);
    }
    static void problem(Context c,String message) {
        message=ErrorText.localize(c,message);
        prefs(c).edit().putString("last_error",message==null?c.getString(R.string.ui_unknown_error):message).apply();
        Ui.message(c,message==null?c.getString(R.string.ui_operation_failed):message);
    }
    static void desktopAction(Context c,int displayId,int action){
        try{
            if(!(c instanceof HomeActivity&&displayId==0))Displays.require(c,displayId);
            Intent intent=new Intent(c,WorkspaceProfile.standard(c,displayId)?HomeActivity.class:DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("desktop_action",action);
            c.startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        }catch(RuntimeException e){problem(c,e.getMessage());}
    }
    static boolean pending(){return coordinator.pending();}
    static void role(Context c,TaskSnapshot.Task task,int display,boolean primary){coordinator.role(c,task,display,primary);}
    static void focus(Context c,TaskSnapshot.Task task,int display,TaskState state){coordinator.focus(c,task,display,state);}
    static void home(Context c,int display){coordinator.home(c,display);}
    static void returnedHome(Context c){coordinator.returnedHome(c);}
    static void app(Context c,String component,int display){app(c,component,display,false);}
    static void app(Context c,String component,int display,boolean newWindow){coordinator.app(c,component,display,newWindow,null);}
    static void app(Context c,String component,int display,boolean newWindow,boolean floating){coordinator.app(c,component,display,newWindow,floating);}
}
