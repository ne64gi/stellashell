package net.fuyumori.stellashell;

import android.app.ActivityOptions;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import java.text.Collator;
import java.util.*;

final class Launches {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("desktop",Context.MODE_PRIVATE); }
    static class App {
        final String component,label; final Drawable icon;
        App(String c,String l,Drawable d){component=c;label=l;icon=d;}
    }
    static List<App> catalog(Context c) {
        PackageManager pm=c.getPackageManager();List<App> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for(ResolveInfo info:pm.queryIntentActivities(query,0)) {
            if(info.activityInfo==null || c.getPackageName().equals(info.activityInfo.packageName))continue;
            ComponentName component=new ComponentName(info.activityInfo.packageName,info.activityInfo.name);
            if(seen.add(component.flattenToString()))out.add(new App(component.flattenToString(),info.loadLabel(pm).toString(),info.loadIcon(pm)));
        }
        Collator sorter=Collator.getInstance();out.sort((a,b)->sorter.compare(a.label,b.label));return out;
    }
    static List<String> recents(Context c) {
        String value=prefs(c).getString("recent","");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void remember(Context c,String component) {
        prefs(c).edit().putString("recent",String.join("\n",Policy.recent(recents(c),component))).apply();
    }
    static List<String> pins(Context c) {
        String value=prefs(c).getString(WorkspaceProfile.key(c,"pinned"),"");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void togglePin(Context c,String component) {
        Policy.component(component);List<String> pins=pins(c);
        if(!pins.remove(component)) {
            if(!WorkspaceProfile.phone(c)&&pins.size()>=6){Ui.message(c,c.getString(R.string.ui_you_can_pin_up_to_6_apps));return;}
            pins.add(component);
        }
        prefs(c).edit().putString(WorkspaceProfile.key(c,"pinned"),String.join("\n",pins)).apply();
    }
    static List<String> taskbarPins(Context c){
        if(!WorkspaceProfile.phone(c))return pins(c);
        String value=prefs(c).getString("phone_taskbar_pinned","");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void toggleTaskbarPin(Context c,String component){
        Policy.component(component);if(!WorkspaceProfile.phone(c)){togglePin(c,component);return;}
        List<String> items=taskbarPins(c);
        if(!items.remove(component)){if(items.size()>=6){Ui.message(c,c.getString(R.string.ui_you_can_pin_up_to_6_apps));return;}items.add(component);}
        prefs(c).edit().putString("phone_taskbar_pinned",String.join("\n",items)).apply();
    }
    /** The added Dock has its own Desktop pins; legacy taskbar's six slots stay separate. */
    static List<String> dockPins(Context c){
        if(WorkspaceProfile.phone(c))return pins(c);
        String value=prefs(c).getString("dock_pinned","");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void toggleDockPin(Context c,String component){
        Policy.component(component);if(WorkspaceProfile.phone(c)){togglePin(c,component);return;}
        List<String> items=dockPins(c);if(!items.remove(component))items.add(component);
        prefs(c).edit().putString("dock_pinned",String.join("\n",items)).apply();
    }
    static List<String> desktop(Context c) {
        String value=prefs(c).getString(WorkspaceProfile.key(c,"desktop_shortcuts"),"");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void toggleDesktop(Context c,String component) {
        GroupEntries.validate(c,component);List<String> items=desktop(c);
        if(!items.remove(component))items.add(component);
        prefs(c).edit().putString(WorkspaceProfile.key(c,"desktop_shortcuts"),String.join("\n",items)).apply();
    }
    static List<String> shortcuts(Context c) {
        List<String> out=pins(c);
        for(String item:recents(c))if(!out.contains(item))out.add(item);
        return out;
    }
    static boolean basicHome(Context c,int display){
        return WorkspaceProfile.standard(c,display)||display==0&&c instanceof HomeActivity&&!HomeActivity.extensions(c);
    }
    static void normalApp(Context c,String component){
        Intent intent=normalAppIntent(c,component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        c.startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        remember(c,component);
    }
    /** Stored shortcuts can name an internal task Activity, not a public launcher entry. */
    static Intent normalAppIntent(Context c,String component){
        Policy.component(component);
        ComponentName requested=ComponentName.unflattenFromString(component);
        PackageManager pm=c.getPackageManager();
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(requested.getPackageName());
        ComponentName firstAccessible=null;
        for(ResolveInfo entry:pm.queryIntentActivities(query,0)){
            ActivityInfo activity=entry.activityInfo;
            if(activity==null||!accessible(c,activity))continue;
            ComponentName launcher=new ComponentName(activity.packageName,activity.name);
            if(requested.equals(launcher))return new Intent(query).setComponent(requested);
            if(firstAccessible==null)firstAccessible=launcher;
        }
        // Keep an explicitly selected launcher alias; only obsolete/internal entries fall back.
        Intent fallback=pm.getLaunchIntentForPackage(requested.getPackageName());
        if(fallback!=null&&fallback.getComponent()!=null)try{
            if(accessible(c,pm.getActivityInfo(fallback.getComponent(),0)))return new Intent(fallback);
        }catch(PackageManager.NameNotFoundException ignored){}
        if(firstAccessible!=null)return new Intent(query).setComponent(firstAccessible);
        throw new ActivityNotFoundException("No accessible launcher for "+requested.getPackageName());
    }
    private static boolean accessible(Context c,ActivityInfo activity){
        return activity.enabled&&activity.applicationInfo!=null&&activity.applicationInfo.enabled
                &&(activity.exported||activity.applicationInfo.uid==android.os.Process.myUid())
                &&(activity.permission==null||c.checkSelfPermission(activity.permission)==PackageManager.PERMISSION_GRANTED);
    }
    static void settings(Context c,int displayId) {
        if(c instanceof HomeActivity||WorkspaceProfile.standard(c,displayId)){c.startActivity(new Intent(c,SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());return;}
        launch(c,new ComponentName(c,SetupActivity.class).flattenToString(),displayId,1,false);
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
    // Serialize launches through role assignment, so rapid taps cannot both become primary.
    private static final ArrayDeque<Runnable> appQueue=new ArrayDeque<>();
    private static final android.os.Handler appHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean appLaunching;
    private static void enqueue(Runnable action){appQueue.add(action);drainApps();}
    private static void drainApps(){
        appHandler.removeCallbacks(retryApps);
        if(appLaunching||appQueue.isEmpty())return;
        if(Workspace.isBusy()){appHandler.postDelayed(retryApps,100);return;}
        appLaunching=true;appQueue.remove().run();
    }
    private static final Runnable retryApps=Launches::drainApps;
    private static void appDone(){appLaunching=false;drainApps();}
    static boolean pending(){return appLaunching||!appQueue.isEmpty();}
    static void role(Context c,int task,int display,boolean primary){
        enqueue(()->Workspace.role(c,task,display,primary,Launches::appDone));
    }
    static void focus(Context c,TaskSession.Task task,int display,TaskSession session){
        enqueue(()->{
            if(TaskModes.pictureInPicture(task.mode)){session.action(task,"fullscreen");appDone();return;}
            if(Workspace.compact(c,display)&&Workspace.needsPrimary()&&!task.alwaysOnTop)
                Workspace.role(c,task.id,display,true,Launches::appDone);
            else{session.action(task,"focus");appDone();}
        });
    }
    static void home(Context c,int displayId) {
        if(WorkspaceProfile.standard(c,displayId)){
            ShellPanels.dismiss(0);c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());return;
        }
        enqueue(()->{
            ShellPanels.dismiss(displayId);
            String component=new ComponentName(c,displayId==0&&HomeRegistration.selected(c)?HomeActivity.class:DesktopActivity.class).flattenToString();
            if(!Bridge.get(c).ready()){
                try{launch(c,component,displayId,1,false);}finally{appDone();}return;
            }
            Bridge.get(c).call(s->{
                // Keep the tasks alive, but put them behind the desktop on this display only.
                org.json.JSONArray rows=new org.json.JSONObject(s.taskSnapshot(displayId)).getJSONArray("tasks");
                for(int i=0;i<rows.length();i++){
                    org.json.JSONObject row=rows.getJSONObject(i);
                    if(!row.optBoolean("visible"))continue;
                    String answer=s.taskOperation(displayId,row.getInt("id"),"minimize",0,0,0,0);
                    if(answer==null||answer.startsWith("ERROR:"))throw new IllegalStateException(answer);
                }
                return s.launch(component,displayId,1);
            },(result,error)->{
                try{if(error!=null)problem(c,error);else Workspace.desktopShown(c,displayId);}
                finally{appDone();}
            });
        });
    }
    static void returnedHome(Context c){
        if(!HomeActivity.extensions(c))return;
        enqueue(()->Bridge.get(c).call(s->{
            org.json.JSONArray rows=new org.json.JSONObject(s.taskSnapshot(0)).getJSONArray("tasks");
            for(int i=0;i<rows.length();i++){
                org.json.JSONObject row=rows.getJSONObject(i);
                if(!row.optBoolean("visible"))continue;
                String result=s.taskOperation(0,row.getInt("id"),"minimize",0,0,0,0);
                if(result==null||result.startsWith("ERROR:"))throw new IllegalStateException(result);
            }
            return "OK";
        },(result,error)->{
            try{if(error==null)Workspace.desktopShown(c,0);else problem(c,error);}
            finally{appDone();}
        }));
    }
    static void app(Context c,String component,int displayId) {
        app(c,component,displayId,false);
    }
    static void app(Context c,String component,int displayId,boolean newWindow){
        enqueue(()->appNow(c,component,displayId,newWindow,null));
    }
    /** Explicit long-press choice; the ordinary overload chooses automatically. */
    static void app(Context c,String component,int displayId,boolean newWindow,boolean floating){
        enqueue(()->appNow(c,component,displayId,newWindow,floating));
    }
    private static void appNow(Context c,String component,int displayId,boolean newWindow,Boolean explicitFloating){
        ShellPanels.dismiss(displayId);
        try{
            if(basicHome(c,displayId)&&!Boolean.TRUE.equals(explicitFloating)){normalApp(c,component);appDone();return;}
            Displays.require(c,displayId);
            AppLaunchProfile profile=Profiles.get(c,component);AppLaunchProfile.Plan planned=Profiles.plan(c,component,displayId);
            boolean compact=Workspace.compact(c,displayId);
            boolean floating=explicitFloating!=null?explicitFloating:Workspace.secondaryLaunch(component,profile.resolvedComponent,newWindow);
            android.graphics.Rect area=WorkArea.get(c,displayId).content;
            final AppLaunchProfile.Plan plan=WorkspaceProfile.standard(c,displayId)&&Boolean.TRUE.equals(explicitFloating)?new AppLaunchProfile.Plan(AppLaunchProfile.Mode.WINDOWED,area.left+area.width()/6,area.top+area.height()/6,area.right-area.width()/6,area.bottom-area.height()/6):compact&&!floating?new AppLaunchProfile.Plan(AppLaunchProfile.Mode.FULLSCREEN,0,0,WorkArea.get(c,displayId).physical.width(),WorkArea.get(c,displayId).physical.height()):compact?new AppLaunchProfile.Plan(AppLaunchProfile.Mode.WINDOWED,area.left+area.width()/6,area.top+area.height()/6,area.right-area.width()/6,area.bottom-area.height()/6):planned;
            if(!Bridge.get(c).ready()){
                if(newWindow||plan.windowingMode!=1)throw new IllegalStateException(c.getString(R.string.ui_launch_profiles_require_a_shizuku_connection));
                launch(c,component,displayId,1,true);appDone();return;
            }
            Profiles.begin(component);
            long session=Workspace.session();
            Bridge.get(c).call(s->{if(!Workspace.currentSession(session))throw new IllegalStateException("Workspace session ended");WorkArea.get(c,displayId).sync(s,displayId);return s.launchProfile(component,profile.resolvedComponent,displayId,plan.windowingMode,plan.left,plan.top,plan.right,plan.bottom,newWindow);},(result,error)->{
                Profiles.end(component);
                if(!Workspace.currentSession(session)){appDone();return;}
                if(error!=null){problem(c,error);appDone();return;}
                try{org.json.JSONObject data=new org.json.JSONObject(result);Profiles.launched(c,component,data,displayId);remember(c,component);
                    if(newWindow&&!data.optBoolean("created"))Ui.message(c,c.getString(R.string.ui_this_app_reused_its_existing_window));
                    Workspace.launched(c,data,displayId,floating,Launches::appDone);
                }catch(Exception e){problem(c,e.getMessage());appDone();}
            });
        }catch(RuntimeException e){Profiles.end(component);problem(c,e.getMessage());appDone();}
    }
    private static void launch(Context c,String component,int displayId,int mode,boolean remember) {
        try {
            Displays.require(c,displayId); Policy.component(component);
            if(Bridge.get(c).ready()) {
                Bridge.get(c).call(s->s.launch(component,displayId,mode),(result,error)->{
                    if(error!=null)problem(c,error);
                    else if(remember)remember(c,component);
                });return;
            }
            if(mode==5)throw new IllegalStateException(c.getString(R.string.ui_reconnect_to_shizuku_to_launch_a_window));
            Intent intent=new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(component))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
            if(remember)remember(c,component);
        } catch(RuntimeException e){problem(c,e.getMessage());}
    }
}
