package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Rect;
import org.json.*;
import java.util.*;

/** Session-only task identities: never adopt unrelated tasks or relaunch a lost task. */
final class Workspace {
    private static final Map<Integer,String> owned=new LinkedHashMap<>();
    private static final Map<Integer,Rect> phoneBounds=new HashMap<>();
    private static int primary=-1;
    private static boolean busy;
    static boolean isBusy(){return busy;}
    static boolean enabled(Context c){return Displays.primary(c)&&Launches.prefs(c).getBoolean("compact_workspace",false);}
    static int target(Context c){return enabled(c)?Launches.prefs(c).getInt("workspace_display",0):0;}
    static boolean compact(Context c,int display){return enabled(c)&&display==0&&WorkArea.get(c,display).compact;}
    static void reset(Context c){owned.clear();phoneBounds.clear();primary=-1;Launches.prefs(c).edit().remove("workspace_display").apply();}
    static void launched(Context c,JSONObject data,int display,boolean floating)throws JSONException{
        JSONObject task=data.getJSONObject("task");int id=task.getInt("id");if(enabled(c))owned.put(id,task.getString("component"));
        if(compact(c,display)&&!floating)role(c,id,display,true);
    }
    static void role(Context c,int id,int display,boolean promote){
        if(busy)return;busy=true;int previous=primary;
        Bridge.get(c).call(s->{
            WorkArea.get(c,display).sync(s,display);
            if(promote){
                check(s.taskOperation(display,id,"maximize",0,0,0,0));
                check(s.taskOperation(display,id,"focus",0,0,0,0));
                if(previous>=0&&previous!=id){
                    JSONArray live=new JSONObject(s.taskSnapshot(display)).getJSONArray("tasks");
                    for(int i=0;i<live.length();i++)if(live.getJSONObject(i).getInt("id")==previous)check(s.taskOperation(display,previous,"minimize",0,0,0,0));
                }
            }else {
                Rect a=WorkArea.get(c,display).content;
                check(s.taskOperation(display,id,"bounds",a.left+a.width()/6,a.top+a.height()/6,a.right-a.width()/6,a.bottom-a.height()/6));
            }
            JSONArray rows=new JSONObject(s.taskSnapshot(display)).getJSONArray("tasks");
            for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id)return rows.getJSONObject(i).toString();
            throw new IllegalStateException("Task closed during role change");
        },(result,error)->{
            busy=false;
            if(error!=null){Launches.problem(c,error);return;}
            try{owned.put(id,new JSONObject(result).getString("component"));primary=promote?id:(primary==id?-1:primary);}
            catch(JSONException e){Launches.problem(c,e.getMessage());}
        });
    }
    private static void check(String result){if(result==null||result.startsWith("ERROR:"))throw new IllegalStateException(result);}
    static void transfer(Context c,int destination,Runnable done){
        if(busy||!enabled(c))return;
        int source=target(c);if(source==destination){done.run();return;}
        if(!Displays.allIds(c).contains(destination))return;
        busy=true;Map<Integer,String> identities=new LinkedHashMap<>(owned);
        Bridge.get(c).call(s->{
            List<Integer> moved=new ArrayList<>();Map<Integer,JSONObject> original=new HashMap<>();
            try{
                if(Displays.allIds(c).contains(source)){
                    JSONArray rows=new JSONObject(s.taskSnapshot(source)).getJSONArray("tasks");
                    for(int i=0;i<rows.length();i++){
                        JSONObject row=rows.getJSONObject(i);int id=row.getInt("id");String component=identities.get(id);
                        if(component==null||!component.equals(row.getString("component")))continue;
                        original.put(id,row);if(source==0)phoneBounds.put(id,new Rect(row.getInt("left"),row.getInt("top"),row.getInt("right"),row.getInt("bottom")));check(s.moveWorkspaceTask(source,destination,id,component));moved.add(id);
                    }
                }
                // On unplug Android may already have moved tasks to display 0. Never recreate missing ones.
                WorkArea area=WorkArea.get(c,destination);area.sync(s,destination);
                JSONArray rows=new JSONObject(s.taskSnapshot(destination)).getJSONArray("tasks");
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.getJSONObject(i);int id=row.getInt("id");
                    if(!row.getString("component").equals(identities.get(id)))continue;
                    Rect b=area.clamp(destination==0&&phoneBounds.containsKey(id)?phoneBounds.get(id):new Rect(row.getInt("left"),row.getInt("top"),row.getInt("right"),row.getInt("bottom")));
                    check(s.taskOperation(destination,id,"bounds",b.left,b.top,b.right,b.bottom));
                }
                JSONArray show=new JSONArray();
                if(primary>=0&&identities.containsKey(primary))show.put(primary);
                for(int i=rows.length()-1;i>=0;i--){
                    JSONObject row=rows.getJSONObject(i);int id=row.getInt("id");
                    JSONObject previous=original.get(id);
                    if(id!=primary&&identities.containsKey(id)&&(previous==null?row.optBoolean("visible"):previous.optBoolean("visible")))show.put(id);
                }
                return show.toString();
            }catch(Exception failure){
                for(int id:moved)try{
                    check(s.moveWorkspaceTask(destination,source,id,identities.get(id)));
                    WorkArea.get(c,source).sync(s,source);JSONObject r=original.get(id);
                    check(s.taskOperation(source,id,"bounds",r.getInt("left"),r.getInt("top"),r.getInt("right"),r.getInt("bottom")));
                }catch(Exception rollback){throw new IllegalStateException("Transfer failed; rollback incomplete for task "+id+": "+rollback.getMessage(),failure);}
                throw failure;
            }
        },(result,error)->{
            busy=false;
            if(error!=null){Launches.problem(c,error);return;}
            Launches.prefs(c).edit().putInt("workspace_display",destination).apply();done.run();
            Bridge.get(c).call(s->{
                JSONArray show=new JSONArray(result);
                for(int i=0;i<show.length();i++)check(s.taskOperation(destination,show.getInt(i),"focus",0,0,0,0));
                return "OK";
            },(focusResult,focusError)->{if(focusError!=null)Launches.problem(c,focusError);else if(destination==0&&primary>=0)role(c,primary,0,true);});
        });
    }
}
