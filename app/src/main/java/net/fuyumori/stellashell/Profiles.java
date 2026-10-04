package net.fuyumori.stellashell;

import android.content.*;
import org.json.*;
import java.util.*;
import net.fuyumori.stellashell.core.launch.AppLaunchProfile;
import net.fuyumori.stellashell.core.launch.LaunchProfileOwner;
import net.fuyumori.stellashell.core.launch.LaunchProfileSnapshot;

final class Profiles {
    private static final Map<Integer,String> taskKeys=new HashMap<>();
    private static final Set<String> launching=new HashSet<>();
    private static int cascade;
    static String key(String component){return LaunchProfilePreferencesStore.key(component);}
    static String requestedComponent(Context c,String component){
        return owner(c).requestedComponent(key(component));
    }
    private static LaunchProfileOwner owner(Context c){
        return new LaunchProfileOwner(LaunchProfilePreferencesStore.of(c));
    }
    static LaunchProfileSnapshot snapshot(Context c,String component){return owner(c).snapshot(key(component));}
    /** Legacy planner/readers receive a detached value, never shared persistence state. */
    static AppLaunchProfile get(Context c,String component){return snapshot(c,component).toMutable();}
    static void setMode(Context c,String component,AppLaunchProfile.Mode value){owner(c).setMode(key(component),value);}
    static void setSize(Context c,String component,AppLaunchProfile.Size value){owner(c).setSize(key(component),value);}
    static void setPosition(Context c,String component,AppLaunchProfile.Position value){owner(c).setPosition(key(component),value);}
    static void setRememberBounds(Context c,String component,boolean value){owner(c).setRememberBounds(key(component),value);}
    static void setCustomSize(Context c,String component,int width,int height){owner(c).setCustomSize(key(component),width,height);}
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
        owner(c).setResolvedComponent(k,row.getString("component"));
        observe(c,new TaskSnapshot.Task(row),displayId);
    }
    static void observe(Context c,TaskSnapshot.Task task,int displayId){
        try {
            if(WorkArea.get(c,displayId).imeVisible)return;
            String component=taskKeys.get(task.id);
            if(component==null){
                component=owner(c).observedComponent(key(task.component),task.component);
            }
            if(launching.contains(component))return;
            android.graphics.Rect bounds=task.bounds().toRect();
            boolean maximized=task.mode==5 && WorkArea.get(c,displayId).maximized(bounds);
            AppLaunchProfile.Mode observed=task.mode==1?AppLaunchProfile.Mode.FULLSCREEN:maximized?AppLaunchProfile.Mode.MAXIMIZED:AppLaunchProfile.Mode.WINDOWED;
            // Only real freeform bounds are observations; mode/state updates retain previous geometry.
            owner(c).observe(component,observed,bounds.left,bounds.top,task.mode==5?bounds.width():0,task.mode==5?bounds.height():0);
        }catch(RuntimeException ignored){}
    }
    static void rememberSnapshot(Context c,String json,int taskId,int displayId){
        try{JSONArray rows=new JSONObject(json).getJSONArray("tasks");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==taskId)observe(c,new TaskSnapshot.Task(rows.getJSONObject(i)),displayId);}catch(Exception ignored){}
    }
}
