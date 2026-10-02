package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.hardware.display.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Synthetic task backend: exercises asynchronous production handoffs without moving real apps. */
final class WorkspaceTransferChecks {
    private final Instrumentation test;
    WorkspaceTransferChecks(Instrumentation test){this.test=test;}
    private void main(Runnable action){test.runOnMainSync(action);}
    private static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static JSONObject task(int id)throws Exception{return new JSONObject().put("id",id).put("component","fixture/Task"+id).put("mode",5).put("visible",true).put("focused",false).put("left",20).put("top",100).put("right",400).put("bottom",500);}
    void run()throws Exception{
        Context actual=test.getTargetContext();
        Context context=new ContextWrapper(actual){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("workspace_transfer_fixture",mode);}};
        SharedPreferences prefs=Launches.prefs(context),real=Launches.prefs(actual);Map<String,?> before=real.getAll();
        Bridge bridge=Bridge.get(actual);Field service=field(Bridge.class,"service"),binding=field(Bridge.class,"binding");
        Object previous=service.get(bridge);boolean wasBinding=binding.getBoolean(bridge);VirtualDisplay display=null;
        try{
            main(()->{DockService.stop(actual,false);real.edit().putBoolean("workspace_auto",false).commit();prefs.edit().clear().putBoolean("enabled",true).putBoolean("primary_mode",true).putInt("workspace_display",0).commit();});test.waitForIdleSync();
            display=actual.getSystemService(DisplayManager.class).createVirtualDisplay("Stella rollback fixture",800,600,160,null,DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            require(display!=null,"Could not create disposable fixture display");int external=display.getDisplay().getDisplayId();
            require(Displays.allIds(context).contains(external),"Fixture display unavailable");
            Map<Integer,Integer> locations=new ConcurrentHashMap<>();locations.put(1,0);locations.put(2,0);
            List<Integer> rollback=new CopyOnWriteArrayList<>();java.util.concurrent.atomic.AtomicBoolean fail=new java.util.concurrent.atomic.AtomicBoolean(true);Binder binder=new Binder();
            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            IDesktopBridge fake=(IDesktopBridge)Proxy.newProxyInstance(IDesktopBridge.class.getClassLoader(),new Class<?>[]{IDesktopBridge.class},(proxy,method,args)->{
                switch(method.getName()){
                    case "asBinder":return binder;
                    case "setPrimaryMode":case "setWorkArea":return null;
                    case "taskSnapshot":{
                        JSONArray rows=new JSONArray();for(int id:new TreeSet<>(locations.keySet()))if(locations.get(id).equals(args[0]))rows.put(task(id));return new JSONObject().put("tasks",rows).toString();
                    }
                    case "moveWorkspaceTask":{
                        int from=(Integer)args[0],to=(Integer)args[1],id=(Integer)args[2];
                        require(locations.get(id)==from,"Wrong move source");require(args[3].equals("fixture/Task"+id),"Wrong identity");
                        if(fail.get()&&from==0&&to==external&&id==1){entered.countDown();require(resume.await(5,TimeUnit.SECONDS),"Fixture release timeout");}
                        if(fail.get()&&from==external&&to==0){rollback.add(id);if(id==1)throw new IllegalStateException("Injected first rollback failure");fail.set(false);}
                        locations.put(id,to);
                        if(fail.get()&&from==0&&to==external&&id==2)throw new IllegalStateException("Injected error after task moved");
                        return "OK";
                    }
                    default:return "OK";
                }
            });
            main(()->{try{service.set(bridge,fake);binding.setBoolean(bridge,true);Workspace.reset(context);prefs.edit().putInt("workspace_display",0).commit();for(int id:new int[]{1,2})Workspace.launched(context,new JSONObject().put("task",task(id)),0,true);}catch(Exception e){throw new RuntimeException(e);}});
            CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);
            List<Throwable> errors=new CopyOnWriteArrayList<>();
            main(()->Workspace.transfer(context,external,()->{
                try{Workspace.observe(context,0,Collections.singletonList(new TaskSession.Task(task(2))));require(Workspace.owns(new TaskSession.Task(task(1))),"Source poll forgot stranded identity");}
                catch(Throwable error){errors.add(error);}finally{first.countDown();}
            }));
            require(entered.await(5,TimeUnit.SECONDS),"Transfer never entered backend");
            main(()->Workspace.transfer(context,0,second::countDown));resume.countDown();
            require(first.await(10,TimeUnit.SECONDS),"Failed handoff lost completion");
            require(errors.isEmpty(),"Failure callback: "+errors);
            require(second.await(10,TimeUnit.SECONDS),"Deferred return was dropped");
            require(rollback.containsAll(Arrays.asList(1,2)),"First rollback failure prevented later attempts");
            // Repeating the settled target is harmless and still completes.
            CountDownLatch retry=new CountDownLatch(1);main(()->Workspace.transfer(context,0,retry::countDown));
            require(retry.await(10,TimeUnit.SECONDS),"Recovery retry timed out");
            require(locations.get(1)==0&&locations.get(2)==0,"Recovery stranded a synthetic task");
            main(()->require(!Workspace.isBusy()&&Workspace.target(context)==0,"Transfer state stuck"));
        }finally{
            main(()->{Workspace.reset(context);try{service.set(bridge,previous);binding.setBoolean(bridge,wasBinding);}catch(Exception e){throw new RuntimeException(e);}prefs.edit().clear().commit();
                SharedPreferences.Editor edit=real.edit();for(String key:new String[]{"enabled","workspace_auto","workspace_display","active_display","preferred_display","last_error"}){Object old=before.get(key);if(old instanceof Boolean)edit.putBoolean(key,(Boolean)old);else if(old instanceof Integer)edit.putInt(key,(Integer)old);else if(old instanceof String)edit.putString(key,(String)old);else edit.remove(key);}edit.commit();
            });
            if(display!=null)display.release();
        }
    }
}
