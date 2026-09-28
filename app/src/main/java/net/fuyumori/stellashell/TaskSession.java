package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Rect;
import android.os.*;
import org.json.*;
import java.util.*;

/** Polls only the chosen display; at most one request in flight per session. */
final class TaskSession {
    static final class Task {
        final int id,mode;final String component;final boolean visible,focused;final Rect bounds;
        Task(JSONObject j)throws JSONException {
            id=j.getInt("id");mode=j.getInt("mode");component=j.getString("component");visible=j.getBoolean("visible");focused=j.getBoolean("focused");
            bounds=new Rect(j.getInt("left"),j.getInt("top"),j.getInt("right"),j.getInt("bottom"));
        }
        String packageName(){return component.substring(0,component.indexOf('/'));}
    }
    private final Context context;private final int displayId;private final Runnable changed;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<Task> tasks=new ArrayList<>();
    private final List<Task> stack=new ArrayList<>();private boolean stackReliable;
    private boolean closed,busy,canArrange;private String lastDiagnostic="";
    private Task dragTask;private Rect pendingBounds;private final Set<Integer> normalized=new HashSet<>();
    TaskSession(Context c,int id,Runnable changed){context=c;displayId=id;this.changed=changed;handler.post(poll);}
    List<Task> tasks(){return new ArrayList<>(tasks);}
    List<Task> stack(){return new ArrayList<>(stack);}
    boolean stackReliable(){return stackReliable;}
    boolean canArrange(){return canArrange && Bridge.get(context).ready();}
    private final Runnable poll=new Runnable(){public void run(){refresh();int visible=0;for(Task t:tasks)if(t.visible)visible++;if(!closed)handler.postDelayed(this,stackReliable&&visible>1?300:1100);}};
    void refresh(){
        if(closed||busy)return;
        if(!Bridge.get(context).ready()){
            if(!tasks.isEmpty()||canArrange){tasks.clear();stack.clear();stackReliable=false;canArrange=false;changed.run();}return;
        }
        busy=true;
        Bridge.get(context).call(s->s.taskSnapshot(displayId),(result,error)->{
            busy=false;if(closed)return;
            try {
                if(error!=null)throw new IllegalStateException(error);
                JSONObject data=new JSONObject(result),caps=data.getJSONObject("capabilities");
                tasks.clear();JSONArray rows=data.getJSONArray("tasks");for(int i=0;i<rows.length();i++)tasks.add(new Task(rows.getJSONObject(i)));
                stack.clear();JSONArray layers=data.optJSONArray("stack");if(layers!=null)for(int i=0;i<layers.length();i++)stack.add(new Task(layers.getJSONObject(i)));
                stackReliable=caps.optBoolean("stackOrder");
                canArrange=caps.optBoolean("bounds")&&caps.optBoolean("windowingMode")&&caps.optBoolean("reorder");
                diagnostic(data.getString("backend")+" "+caps+" boundsDispatch="+data.optString("boundsDispatch","unknown")+"\n"+data.getJSONArray("operations"));
            }catch(Exception e){tasks.clear();stack.clear();stackReliable=false;canArrange=false;diagnostic(e.toString());}
            for(Task task:tasks)if(task.focused&&task.visible){Profiles.observe(context,task,displayId);break;}
            changed.run();
            try {if(canArrange)for(Task task:tasks)if(task.focused&&task.visible&&task.mode==5&&normalized.add(task.id)){
                android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();android.graphics.Point size=new android.graphics.Point();
                android.view.Display display=Displays.require(context,displayId);display.getRealMetrics(metrics);display.getRealSize(size);
                if(task.bounds.top<Math.round(32*metrics.density)||task.bounds.bottom>size.y-Math.round(60*metrics.density))resize(task,task.bounds);
                break;
            }
            }catch(RuntimeException e){diagnostic(e.toString());}
            flushDrag();
        });
    }
    private void diagnostic(String value){if(!value.equals(lastDiagnostic)){lastDiagnostic=value;Launches.prefs(context).edit().putString("task_diagnostics",value).apply();}}
    void action(Task task,String action){
        if(closed)return;
        Bridge.get(context).call(s->{String before=s.taskSnapshot(displayId);String answer=s.taskOperation(displayId,task.id,action,0,0,0,0);return answer.startsWith("ERROR:")?answer:before;},(result,error)->{
            if(error==null)Profiles.rememberSnapshot(context,result,task.id,displayId);if(closed)return;if(error!=null)Launches.problem(context,error);refresh();
        });
    }
    void resize(Task task,Rect bounds){if(closed)return;dragTask=task;pendingBounds=new Rect(bounds);flushDrag();}
    void focusForDrag(Task task,java.util.function.Consumer<Boolean> reply){
        if(closed){reply.accept(false);return;}
        Bridge.get(context).call(s->{String answer=s.taskOperation(displayId,task.id,"focus",0,0,0,0);return answer.startsWith("ERROR:")?answer:s.taskSnapshot(displayId);},(result,error)->{
            boolean focused=false;
            if(!closed&&error==null)try{JSONArray rows=new JSONObject(result).getJSONArray("tasks");for(int i=0;i<rows.length();i++){JSONObject t=rows.getJSONObject(i);if(t.getInt("id")==task.id&&t.getBoolean("focused"))focused=true;}}catch(JSONException ignored){}
            if(error!=null&&!closed)Launches.problem(context,error);reply.accept(focused);refresh();
        });
    }
    private void flushDrag(){
        if(closed||busy||pendingBounds==null)return;
        Rect b=pendingBounds;Task t=dragTask;pendingBounds=null;busy=true;
        Bridge.get(context).call(s->s.taskOperation(displayId,t.id,"bounds",b.left,b.top,b.right,b.bottom),(result,error)->{
            busy=false;if(closed)return;
            if(error!=null){pendingBounds=null;Launches.problem(context,error);}else if(pendingBounds!=null)flushDrag();else refresh();
        });
    }
    void close(){closed=true;handler.removeCallbacksAndMessages(null);pendingBounds=null;tasks.clear();}
}
