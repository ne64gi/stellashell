package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.tasks.WorkspaceOperations;

import android.content.Context;
import android.graphics.Rect;
import org.json.*;
import java.util.*;

/** Session-only task identities: never adopt unrelated tasks or relaunch a lost task. */
final class Workspace {
    private final Map<Integer,String> owned=new LinkedHashMap<>();
    private final Map<Integer,Rect> phoneBounds=new HashMap<>();
    private final Set<Integer> roleFailures=new HashSet<>();
    private final Set<Integer> recoveryDisplays=new LinkedHashSet<>();
    private int primary=-1;
    private final WorkspaceOperations operations=new WorkspaceOperations();
    private boolean desktopShown=true;
    boolean isBusy(){return operations.busy();}
    long session(){return operations.generation();}
    boolean currentSession(long session){return operations.current(session);}
    void enqueue(Runnable operation){if(!operations.defer(operation))operation.run();}
    long beginCommand(){return operations.begin();}
    void finishCommand(long ticket){if(operations.release(ticket))operations.drain();}
    AutoCloseable whenIdle(Runnable callback){return operations.whenIdle(callback);}
    int primary(){return primary;}
    boolean owns(TaskSnapshot.Task task){return task.component.equals(owned.get(task.id));}
    private void replaceOwned(TaskSnapshot.Identity identity){
        String previous=owned.put(identity.id,identity.component);
        if(!identity.component.equals(previous)){
            phoneBounds.remove(identity.id);roleFailures.remove(identity.id);
            if(primary==identity.id)primary=-1;
        }
    }
    /** Register an explicitly converted task without launch/role/transfer side effects. */
    void adoptFloating(TaskSnapshot.Task task,long session){
        if(!currentSession(session)||task.mode!=5)return;
        replaceOwned(task.identity());
    }
    void forgetFloating(TaskSnapshot.Task task,long session){
        if(!currentSession(session)||task.mode!=1||!owns(task))return;
        owned.remove(task.id);phoneBounds.remove(task.id);roleFailures.remove(task.id);
        if(primary==task.id)primary=-1;
    }
    void forgetClosedTask(TaskSnapshot.Identity identity,long session){
        if(!currentSession(session)||!identity.component.equals(owned.get(identity.id)))return;
        owned.remove(identity.id);phoneBounds.remove(identity.id);roleFailures.remove(identity.id);
        if(primary==identity.id)primary=-1;
    }
    String label(Context c,TaskSnapshot.Task task){return !owns(task)?"":c.getString(task.id==primary?R.string.workspace_primary_label:R.string.workspace_secondary_label);}
    void observe(Context c,int display,List<TaskSnapshot.Task> tasks,boolean enabled,int target){
        if(isBusy()||display!=(enabled?target:0))return;
        Map<Integer,String> live=new HashMap<>();for(TaskSnapshot.Task task:tasks)live.put(task.id,task.component);
        // One display snapshot cannot retire identities left on another display
        // by an incomplete transfer/rollback. The next recovery checks them.
        if(recoveryDisplays.isEmpty())owned.entrySet().removeIf(e->{
            if(e.getValue().equals(live.get(e.getKey())))return false;
            phoneBounds.remove(e.getKey());roleFailures.remove(e.getKey());
            if(primary==e.getKey())primary=-1;
            return true;
        });
        phoneBounds.keySet().retainAll(owned.keySet());if(!owned.containsKey(primary))primary=-1;
        roleFailures.retainAll(owned.keySet());
        if(compact(c,display))for(TaskSnapshot.Task task:tasks){
            if(!owns(task)||!task.visible||roleFailures.contains(task.id))continue;
            // Apps may request fullscreen again after launch/resume. Keep session roles authoritative.
            if(task.mode!=(task.id==primary?1:5)){role(c,task.identity(),display,task.id==primary,()->{});break;}
        }
    }
    boolean compact(Context c,int display){return !WorkspaceProfile.standard(c,display)&&Displays.primary(c)&&display==0&&WorkArea.get(c,display).compact;}
    void reset(){operations.reset();owned.clear();phoneBounds.clear();roleFailures.clear();recoveryDisplays.clear();primary=-1;desktopShown=true;}
    void desktopShown(Context c,int display){if(compact(c,display))desktopShown=true;}
    boolean needsPrimary(){return desktopShown||primary<0;}
    boolean secondaryLaunch(String requested,String resolved,boolean newWindow){
        return defaultSecondary(desktopShown,owned.get(primary),requested,resolved,newWindow);
    }
    static boolean defaultSecondary(boolean desktop,String main,String requested,String resolved,boolean newWindow){
        return !desktop&&main!=null&&(newWindow||(!main.equals(requested)&&!main.equals(resolved)));
    }
    void focused(Context c,TaskSnapshot.Task task,int display){
        if(compact(c,display)&&owns(task)&&task.id==primary)desktopShown=false;
    }
    void launched(Context c,JSONObject data,int display,boolean floating,boolean workspaceEnabled,Runnable done)throws JSONException{
        JSONObject task=data.getJSONObject("task");int id=task.getInt("id");String component=task.getString("component");if(workspaceEnabled||compact(c,display)||WorkspaceProfile.standard(c,display))replaceOwned(new TaskSnapshot.Identity(id,component));
        // Reused tasks also need their bounds changed: launchProfile may only focus them.
        if(compact(c,display))role(c,new TaskSnapshot.Identity(id,component),display,!floating,done);else done.run();
    }
    void role(Context c,TaskSnapshot.Identity target,int display,boolean promote,Runnable done){
        int id=target.id;
        if(isBusy()){done.run();return;}long ticket=operations.begin();roleFailures.remove(id);int previous=primary;String previousIdentity=owned.get(previous);
        Bridge.get(c).call(s->{
            operations.requireCurrent(ticket);
            WorkArea.get(c,display).sync(s,display);
            if(promote){
                check(s.checkedTaskOperation(display,id,target.component,"fullscreen",0,0,0,0));
                check(s.checkedTaskOperation(display,id,target.component,"focus",0,0,0,0));
                if(previous>=0&&previous!=id){
                    JSONArray live=new JSONObject(s.taskSnapshot(display)).getJSONArray("tasks");
                    for(int i=0;i<live.length();i++)if(live.getJSONObject(i).getInt("id")==previous&&live.getJSONObject(i).getString("component").equals(previousIdentity)){
                        Rect a=WorkArea.get(c,display).content;
                        check(s.checkedTaskOperation(display,previous,previousIdentity,"bounds",a.left+a.width()/6,a.top+a.height()/6,a.right-a.width()/6,a.bottom-a.height()/6));
                        check(s.checkedTaskOperation(display,previous,previousIdentity,"minimize",0,0,0,0));
                    }
                }
            }else {
                Rect a=WorkArea.get(c,display).content;
                check(s.checkedTaskOperation(display,id,target.component,"bounds",a.left+a.width()/6,a.top+a.height()/6,a.right-a.width()/6,a.bottom-a.height()/6));
                check(s.checkedTaskOperation(display,id,target.component,"focus",0,0,0,0));
            }
            JSONArray rows=new JSONObject(s.taskSnapshot(display)).getJSONArray("tasks");
            for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id&&target.component.equals(rows.getJSONObject(i).getString("component"))){
                JSONObject actual=rows.getJSONObject(i);
                if(actual.getInt("mode")!=(promote?1:5)){
                    if(!promote)check(s.checkedTaskOperation(display,id,target.component,"minimize",0,0,0,0));
                    throw new IllegalStateException(c.getString(R.string.workspace_role_unavailable));
                }
                return actual.toString();
            }
            throw new IllegalStateException("Task closed during role change");
        },(result,error)->{
            if(!operations.release(ticket)){done.run();return;}
            try{
                if(error!=null){roleFailures.add(id);Launches.problem(c,error);return;}
                replaceOwned(new TaskSnapshot.Identity(id,new JSONObject(result).getString("component")));primary=promote?id:(primary==id?-1:primary);
                if(promote)desktopShown=false;
            }catch(JSONException e){Launches.problem(c,e.getMessage());}
            finally{try{done.run();}finally{operations.drain();}}
        });
    }
    private static void check(String result){if(result==null||result.startsWith("ERROR:"))throw new IllegalStateException(result);}
    private static int locate(Context c,IDesktopBridge service,int id,String identity,int preferred)throws Exception{
        Set<Integer> candidates=new LinkedHashSet<>();candidates.add(preferred);candidates.addAll(Displays.allIds(c));
        for(int display:candidates){
            if(!Displays.allIds(c).contains(display))continue;
            JSONArray rows;
            try{rows=new JSONObject(service.taskSnapshot(display)).getJSONArray("tasks");}catch(Exception unavailable){continue;}
            for(int n=0;n<rows.length();n++){JSONObject task=rows.getJSONObject(n);if(task.getInt("id")==id&&identity.equals(task.getString("component")))return display;}
        }
        throw new IllegalStateException("Transferred task is no longer available: "+id);
    }
    void transfer(Context c,java.util.function.IntSupplier currentSource,java.util.function.BooleanSupplier permitted,
            int destination,java.util.function.IntConsumer commitTarget,Runnable done){
        operations.enqueueTransfer(currentSource,permitted,source->transferNow(c,source,destination,commitTarget,done),done);
    }
    private void transferNow(Context c,int source,int destination,java.util.function.IntConsumer commitTarget,Runnable done){
        if(source<0){done.run();return;}
        if(source==destination&&recoveryDisplays.isEmpty()){done.run();return;}
        if(!Displays.allIds(c).contains(destination)){done.run();return;}
        if(owned.isEmpty()){commitTarget.accept(destination);done.run();return;}
        long ticket=operations.begin();Map<Integer,String> identities=new LinkedHashMap<>(owned);
        Map<Integer,Rect> savedBounds=new HashMap<>(phoneBounds);
        Set<Integer> sources=new LinkedHashSet<>();sources.add(source);sources.addAll(recoveryDisplays);sources.remove(destination);
        int mainTask=primary;
        Bridge.get(c).call(s->{
            operations.requireCurrent(ticket);
            List<Integer> attempted=new ArrayList<>();Map<Integer,JSONObject> original=new HashMap<>();Map<Integer,Integer> origins=new HashMap<>();
            try{
                for(int from:sources)if(Displays.allIds(c).contains(from)){
                    JSONArray rows=new JSONObject(s.taskSnapshot(from)).getJSONArray("tasks");
                    for(int i=0;i<rows.length();i++){
                        operations.requireCurrent(ticket);
                        JSONObject row=rows.getJSONObject(i);int id=row.getInt("id");String component=identities.get(id);
                        if(component==null||!component.equals(row.getString("component")))continue;
                        original.put(id,row);origins.put(id,from);if(from==0)savedBounds.put(id,new Rect(row.getInt("left"),row.getInt("top"),row.getInt("right"),row.getInt("bottom")));attempted.add(id);check(s.moveWorkspaceTask(from,destination,id,component));
                    }
                }
                // On unplug Android may already have moved tasks to display 0. Never recreate missing ones.
                WorkArea area=WorkArea.get(c,destination);area.sync(s,destination);
                JSONArray rows=new JSONObject(s.taskSnapshot(destination)).getJSONArray("tasks");
                for(int i=0;i<rows.length();i++){
                    operations.requireCurrent(ticket);
                    JSONObject row=rows.getJSONObject(i);int id=row.getInt("id");
                    if(!row.getString("component").equals(identities.get(id)))continue;
                    Rect b=area.clamp(destination==0&&savedBounds.containsKey(id)?savedBounds.get(id):new Rect(row.getInt("left"),row.getInt("top"),row.getInt("right"),row.getInt("bottom")));
                    check(s.checkedTaskOperation(destination,id,identities.get(id),"bounds",b.left,b.top,b.right,b.bottom));
                }
                JSONArray show=new JSONArray();
                if(mainTask>=0&&identities.containsKey(mainTask))show.put(new JSONObject().put("id",mainTask).put("component",identities.get(mainTask)));
                for(int i=rows.length()-1;i>=0;i--){
                    JSONObject row=rows.getJSONObject(i);int id=row.getInt("id");
                    JSONObject previous=original.get(id);
                    if(id!=mainTask&&row.getString("component").equals(identities.get(id))&&(previous==null?row.optBoolean("visible"):previous.optBoolean("visible")))show.put(new JSONObject().put("id",id).put("component",identities.get(id)));
                }
                return show.toString();
            }catch(Exception failure){
                throw WorkspaceOperations.rollback(attempted,id->{
                    int originalDisplay=origins.get(id),origin=Displays.allIds(c).contains(originalDisplay)?originalDisplay:0;
                    int actual=locate(c,s,id,identities.get(id),destination);
                    if(actual!=origin)check(s.moveWorkspaceTask(actual,origin,id,identities.get(id)));
                    WorkArea area=WorkArea.get(c,origin);area.sync(s,origin);JSONObject r=original.get(id);
                    Rect restore=origin!=originalDisplay&&savedBounds.containsKey(id)?savedBounds.get(id):new Rect(r.getInt("left"),r.getInt("top"),r.getInt("right"),r.getInt("bottom"));
                    restore=area.clamp(restore);check(s.checkedTaskOperation(origin,id,identities.get(id),"bounds",restore.left,restore.top,restore.right,restore.bottom));
                },failure);
            }
        },(result,error)->{
            if(!operations.current(ticket))return;
            if(error!=null){
                // A vanished source can make rollback impossible. The next
                // handoff must also search this destination for our identities.
                recoveryDisplays.add(destination);recoveryDisplays.addAll(sources);recoveryDisplays.add(0);
                phoneBounds.clear();phoneBounds.putAll(savedBounds);
                if(!Displays.allIds(c).contains(source))commitTarget.accept(0);
                operations.release(ticket);try{Launches.problem(c,error);done.run();}finally{operations.drain();}return;
            }
            recoveryDisplays.clear();
            phoneBounds.clear();phoneBounds.putAll(savedBounds);
            commitTarget.accept(destination);
            Bridge.get(c).call(s->{
                operations.requireCurrent(ticket);
                JSONArray show=new JSONArray(result);
                for(int i=0;i<show.length();i++){
                    JSONObject task=show.getJSONObject(i);
                    check(s.checkedTaskOperation(destination,task.getInt("id"),task.getString("component"),"focus",0,0,0,0));
                }
                return "OK";
            },(focusResult,focusError)->{
                if(!operations.release(ticket))return;
                try{
                    if(focusError!=null)Launches.problem(c,focusError);
                    else if(destination==0&&primary>=0&&!WorkspaceProfile.standard(c,0)){
                        String identity=owned.get(primary);
                        if(identity!=null)role(c,new TaskSnapshot.Identity(primary,identity),0,true,()->{});
                    }
                    done.run();
                }finally{operations.drain();}
            });
        });
    }
}
