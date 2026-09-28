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
        String value=prefs(c).getString("pinned","");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void togglePin(Context c,String component) {
        Policy.component(component);List<String> pins=pins(c);
        if(!pins.remove(component)) {
            if(pins.size()>=6){Ui.message(c,"ピン留めは6件までです");return;}
            pins.add(component);
        }
        prefs(c).edit().putString("pinned",String.join("\n",pins)).apply();
    }
    static List<String> desktop(Context c) {
        String value=prefs(c).getString("desktop_shortcuts","");
        return value.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(value.split("\\n")));
    }
    static void toggleDesktop(Context c,String component) {
        Policy.component(component);List<String> items=desktop(c);
        if(!items.remove(component))items.add(component);
        prefs(c).edit().putString("desktop_shortcuts",String.join("\n",items)).apply();
    }
    static List<String> shortcuts(Context c) {
        List<String> out=pins(c);
        for(String item:recents(c))if(!out.contains(item))out.add(item);
        return out;
    }
    static void settings(Context c,int displayId) {
        launch(c,new ComponentName(c,SetupActivity.class).flattenToString(),displayId,1,false);
    }
    static void problem(Context c,String message) {
        prefs(c).edit().putString("last_error",message==null?"不明なエラー":message).apply();
        Ui.message(c,message==null?"操作に失敗しました":message);
    }
    static void home(Context c,int displayId) {
        launch(c,new ComponentName(c,DesktopActivity.class).flattenToString(),displayId,1,false);
    }
    static void app(Context c,String component,int displayId) {
        app(c,component,displayId,false);
    }
    static void app(Context c,String component,int displayId,boolean newWindow){
        try{
            AppLaunchProfile profile=Profiles.get(c,component);AppLaunchProfile.Plan plan=Profiles.plan(c,component,displayId);
            if(!Bridge.get(c).ready()){
                if(newWindow||plan.windowingMode!=1)throw new IllegalStateException("起動プロファイルには Shizuku の接続が必要です");
                launch(c,component,displayId,1,true);return;
            }
            Profiles.begin(component);
            Bridge.get(c).call(s->s.launchProfile(component,profile.resolvedComponent,displayId,plan.windowingMode,plan.left,plan.top,plan.right,plan.bottom,newWindow),(result,error)->{
                Profiles.end(component);
                if(error!=null){problem(c,error);return;}
                try{org.json.JSONObject data=new org.json.JSONObject(result);Profiles.launched(c,component,data,displayId);remember(c,component);
                    if(newWindow&&!data.optBoolean("created"))Ui.message(c,"このアプリは既存のウィンドウを使用しました");
                }catch(Exception e){problem(c,e.getMessage());}
            });
        }catch(RuntimeException e){Profiles.end(component);problem(c,e.getMessage());}
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
            if(mode==5)throw new IllegalStateException("ウィンドウ起動には Shizuku の再接続が必要です");
            Intent intent=new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(component))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
            if(remember)remember(c,component);
        } catch(RuntimeException e){problem(c,e.getMessage());}
    }
}
