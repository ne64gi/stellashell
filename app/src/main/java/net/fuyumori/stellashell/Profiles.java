package net.fuyumori.stellashell;

import android.content.*;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Display;
import org.json.*;
import java.util.*;

final class Profiles {
    private static final Map<Integer,String> taskKeys=new HashMap<>();
    private static final Set<String> launching=new HashSet<>();
    private static int cascade;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("launch_profiles",Context.MODE_PRIVATE);}
    static String key(String component){ComponentName c=ComponentName.unflattenFromString(component);if(c==null)throw new IllegalArgumentException("Invalid component");return c.flattenToString();}
    static String requestedComponent(Context c,String component){
        String canonical=key(component);if(prefs(c).contains(canonical))return canonical;
        for(String candidate:prefs(c).getAll().keySet())if(canonical.equals(get(c,candidate).resolvedComponent))return candidate;
        return canonical;
    }
    static AppLaunchProfile get(Context c,String component){
        String k=key(component);AppLaunchProfile p=new AppLaunchProfile(k);
        p.launchMode=Launches.prefs(c).getBoolean("freeform",false)?AppLaunchProfile.Mode.WINDOWED:AppLaunchProfile.Mode.FULLSCREEN;
        try{
            JSONObject j=new JSONObject(prefs(c).getString(k,"{}"));
            p.launchMode=AppLaunchProfile.Mode.valueOf(j.optString("launchMode",p.launchMode.name()));
            p.lastState=AppLaunchProfile.Mode.valueOf(j.optString("lastState","WINDOWED"));
            p.size=AppLaunchProfile.Size.valueOf(j.optString("size","SMALL"));p.position=AppLaunchProfile.Position.valueOf(j.optString("position","AUTO"));
            p.width=j.optInt("width",800);p.height=j.optInt("height",600);p.x=j.optInt("x");p.y=j.optInt("y");p.lastWidth=j.optInt("lastWidth");p.lastHeight=j.optInt("lastHeight");
            p.hasLastBounds=j.optBoolean("hasLastBounds")&&p.lastWidth>0&&p.lastHeight>0;p.rememberBounds=j.optBoolean("rememberBounds",true);
            p.resolvedComponent=j.optString("resolvedComponent","");
        }catch(Exception ignored){}
        return p;
    }
    static void save(Context c,String component,AppLaunchProfile p){
        try {
            String value=new JSONObject().put("version",1).put("packageName",p.packageName).put("activityName",p.activityName)
                    .put("launchMode",p.launchMode.name()).put("size",p.size.name()).put("position",p.position.name())
                    .put("width",p.width).put("height",p.height).put("x",p.x).put("y",p.y).put("lastWidth",p.lastWidth).put("lastHeight",p.lastHeight)
                    .put("hasLastBounds",p.hasLastBounds).put("rememberBounds",p.rememberBounds).put("lastState",p.lastState.name())
                    .put("preferredDisplay","AUTO").put("resolvedComponent",p.resolvedComponent).toString();
            String k=key(component);if(!value.equals(prefs(c).getString(k,"")))prefs(c).edit().putString(k,value).apply();
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    static AppLaunchProfile.Plan plan(Context c,String component,int displayId){
        WorkArea area=WorkArea.get(c,displayId);AppLaunchProfile profile=get(c,component);
        if(profile.hasLastBounds){profile.x-=area.application.left;profile.y-=area.application.top;}
        AppLaunchProfile.Plan local=profile.plan(area.application.width(),area.application.height(),area.caption,0,cascade++);
        if(local.windowingMode==1)return new AppLaunchProfile.Plan(local.state,0,0,area.physical.width(),area.physical.height());
        return new AppLaunchProfile.Plan(local.state,local.left+area.application.left,local.top+area.application.top,local.right+area.application.left,local.bottom+area.application.top);
    }
    static void begin(String component){launching.add(key(component));}
    static void end(String component){launching.remove(key(component));}
    static void launched(Context c,String component,JSONObject result,int displayId)throws JSONException {
        JSONObject row=result.getJSONObject("task");String k=key(component);taskKeys.put(row.getInt("id"),k);
        AppLaunchProfile p=get(c,k);p.resolvedComponent=row.getString("component");save(c,k,p);
        observe(c,new TaskSnapshot.Task(row),displayId);
    }
    static void observe(Context c,TaskSnapshot.Task task,int displayId){
        try {
            if(WorkArea.get(c,displayId).imeVisible)return;
            String component=taskKeys.get(task.id);
            if(component==null){
                component=key(task.component);
                for(String candidate:prefs(c).getAll().keySet())if(task.component.equals(get(c,candidate).resolvedComponent)){component=candidate;break;}
            }
            if(launching.contains(component))return;
            AppLaunchProfile p=get(c,component);
            android.graphics.Rect bounds=task.bounds().toRect();
            boolean maximized=task.mode==5 && WorkArea.get(c,displayId).maximized(bounds);
            p.lastState=task.mode==1?AppLaunchProfile.Mode.FULLSCREEN:maximized?AppLaunchProfile.Mode.MAXIMIZED:AppLaunchProfile.Mode.WINDOWED;
            if(p.rememberBounds&&task.mode==5&&!maximized&&!bounds.isEmpty()){
                p.x=bounds.left;p.y=bounds.top;p.lastWidth=bounds.width();p.lastHeight=bounds.height();p.hasLastBounds=true;
            }
            save(c,component,p);
        }catch(RuntimeException ignored){}
    }
    static void rememberSnapshot(Context c,String json,int taskId,int displayId){
        try{JSONArray rows=new JSONObject(json).getJSONArray("tasks");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==taskId)observe(c,new TaskSnapshot.Task(rows.getJSONObject(i)),displayId);}catch(Exception ignored){}
    }
}
