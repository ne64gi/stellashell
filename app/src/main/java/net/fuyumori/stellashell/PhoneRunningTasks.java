package net.fuyumori.stellashell;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Read-only Display-0 feed, plus explicitly selected operations on an exact live task identity. */
final class PhoneRunningTasks implements AutoCloseable {
    enum State { LOADING, READY, UNAVAILABLE, ERROR }
    interface Backend {
        boolean ready();
        default void connect(){}
        void observe(Runnable changed);
        void remove(Runnable changed);
        void snapshot(Bridge.Reply reply);
        void focus(TaskSession.Task task,Bridge.Reply reply);
        void operation(TaskSession.Task task,String action,Rect bounds,Bridge.Reply reply);
    }
    private final Backend backend;
    private final Runnable changed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<TaskSession.Task> tasks=new ArrayList<>();
    private boolean active,closed,busy,commandBusy;
    private State state=State.LOADING;
    private long generation;
    private final Runnable connectionChanged=this::refresh;
    private final Runnable poll=new Runnable(){@Override public void run(){
        if(!active||closed)return;refresh();main.postDelayed(this,1100);
    }};
    PhoneRunningTasks(Context context,Runnable changed){this(new Backend(){
        private final Bridge bridge=Bridge.get(context);
        public boolean ready(){return bridge.ready()&&bridge.authorized();}
        public void connect(){if(bridge.authorized())bridge.connect();}
        public void observe(Runnable listener){bridge.observe(listener);}
        public void remove(Runnable listener){bridge.remove(listener);}
        public void snapshot(Bridge.Reply reply){bridge.call(s->s.phoneTaskSnapshot(),reply);}
        public void focus(TaskSession.Task task,Bridge.Reply reply){bridge.call(s->s.focusPhoneTask(task.id,task.component),reply);}
        public void operation(TaskSession.Task task,String action,Rect bounds,Bridge.Reply reply){
            long session=Workspace.session();
            bridge.call(s->{
                if(!Workspace.currentSession(session))throw new IllegalStateException("Workspace session ended");
                return s.phoneTaskOperation(task.id,task.component,action,bounds.left,bounds.top,bounds.right,bounds.bottom);
            },(result,error)->{
                if(error==null&&("float".equals(action)||"fullscreen".equals(action)))try{
                    TaskSession.Task actual=verifiedTask(result,task,"float".equals(action)?5:1);
                    // A completed OS operation survives hiding the sidebar. Reconcile
                    // its verified result against the Workspace session, not UI lifetime.
                    if(Workspace.currentSession(session)&&Launches.prefs(context).getBoolean("enabled",false)&&Launches.prefs(context).getInt("active_display",-1)==0){
                        if("float".equals(action))Workspace.adoptFloating(actual,session);else Workspace.forgetFloating(actual,session);
                    }
                }catch(Exception failure){reply.done(null,failure.toString());return;}
                reply.done(result,error);
            });
        }
    },changed);}
    PhoneRunningTasks(Backend backend,Runnable changed){this.backend=backend;this.changed=changed;}
    List<TaskSession.Task> tasks(){return new ArrayList<>(tasks);}
    State state(){return state;}
    void start(){if(closed||active)return;active=true;generation++;backend.observe(connectionChanged);main.post(poll);}
    void stop(){
        if(active){active=false;generation++;backend.remove(connectionChanged);}
        main.removeCallbacks(poll);clear(State.LOADING);
    }
    private void clear(State next){boolean different=!tasks.isEmpty()||state!=next;tasks.clear();state=next;if(different)changed.run();}
    private void unavailable(){generation++;clear(State.UNAVAILABLE);backend.connect();}
    void refresh(){
        if(closed||!active)return;
        if(!backend.ready()){unavailable();return;}
        if(busy)return;
        busy=true;long request=generation;
        backend.snapshot((result,error)->{
            busy=false;
            if(closed||!active)return;
            if(request!=generation){refresh();return;}
            if(!backend.ready()){unavailable();return;}
            if(error!=null){clear(State.ERROR);return;}
            try{
                JSONArray rows=new JSONObject(result).getJSONArray("tasks");
                List<TaskSession.Task> next=new ArrayList<>();Set<Integer> ids=new HashSet<>();
                for(int i=0;i<rows.length();i++){
                    TaskSession.Task task=new TaskSession.Task(rows.getJSONObject(i));
                    if(task.id<0||ComponentName.unflattenFromString(task.component)==null)continue;
                    if(ids.add(task.id))next.add(task);
                }
                boolean different=state!=State.READY||tasks.size()!=next.size();
                for(int i=0;!different&&i<tasks.size();i++){
                    TaskSession.Task before=tasks.get(i),after=next.get(i);
                    different=before.id!=after.id||!before.component.equals(after.component)||before.focused!=after.focused||before.mode!=after.mode;
                }
                tasks.clear();tasks.addAll(next);state=State.READY;if(different)changed.run();
            }catch(Exception ignored){clear(State.ERROR);}
        });
    }
    void focus(TaskSession.Task requested,Runnable focused,Consumer<String> failed){
        command(requested,null,null,focused,failed);
    }
    void operation(TaskSession.Task requested,String action,Rect bounds,Runnable completed,Consumer<String> failed){
        if(!"float".equals(action)&&!"fullscreen".equals(action)&&!"close".equals(action))return;
        if("float".equals(action)&&(bounds==null||bounds.isEmpty()))return;
        command(requested,action,"float".equals(action)?new Rect(bounds):new Rect(),completed,failed);
    }
    private void command(TaskSession.Task requested,String action,Rect bounds,Runnable completed,Consumer<String> failed){
        if(closed||!active)return;
        if(!backend.ready()){unavailable();return;}
        if(commandBusy)return;
        TaskSession.Task live=null;
        for(TaskSession.Task task:tasks)if(task.id==requested.id&&task.component.equals(requested.component)){live=task;break;}
        if(live==null){refresh();return;}
        if(("float".equals(action)&&live.mode==5)||("fullscreen".equals(action)&&!TaskModes.canReturnToMain(live.mode))){refresh();return;}
        long request=generation;commandBusy=true;
        Bridge.Reply reply=(result,error)->{
            commandBusy=false;
            if(closed||!active||request!=generation)return;
            if(!backend.ready()){unavailable();return;}
            if(error!=null){failed.accept(error);refresh();return;}
            completed.run();
        };
        if(action==null)backend.focus(live,reply);else backend.operation(live,action,bounds,reply);
    }
    static TaskSession.Task verifiedTask(String result,TaskSession.Task requested,int mode)throws Exception{
        TaskSession.Task actual=new TaskSession.Task(new JSONObject(result).getJSONObject("task"));
        if(actual.id!=requested.id||!actual.component.equals(requested.component)||actual.mode!=mode)
            throw new IllegalStateException("The selected task did not enter the requested window mode");
        return actual;
    }
    @Override public void close(){if(closed)return;stop();closed=true;generation++;}
}
