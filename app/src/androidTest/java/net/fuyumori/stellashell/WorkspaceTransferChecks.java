package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.hardware.display.*;
import android.os.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Synthetic task backend: exercises asynchronous production handoffs without moving real apps. */
final class WorkspaceTransferChecks {
    private final Instrumentation test;
    WorkspaceTransferChecks(Instrumentation test){this.test=test;}
    private void main(Runnable action){test.runOnMainSync(action);}
    private static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
    private static JSONObject task(int id)throws Exception{return new JSONObject().put("id",id).put("component","fixture/Task"+id).put("mode",5).put("visible",true).put("focused",false).put("left",20).put("top",100).put("right",400).put("bottom",500);}
    void run()throws Exception{
        Context actual=test.getTargetContext();
        String storeName="workspace_transfer_fixture_"+System.nanoTime();
        class Sandbox extends ContextWrapper implements ShellSettings.Provider,TaskState.Provider,PowerMode.Provider,Bridge.Provider {
            private ShellSettings settings;private TaskState tasks;private PowerMode power;private Bridge fixtureBridge;
            Sandbox(){super(actual);}
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return actual.getSharedPreferences(storeName,mode);}
            public ShellSettings shellSettings(){if(settings==null)settings=ShellSettings.isolated(getSharedPreferences("desktop",0));return settings;}
            public TaskState taskState(){if(tasks==null)tasks=TaskState.isolated(this);return tasks;}
            public PowerMode powerMode(){if(power==null)power=PowerMode.forPreferences(Launches.prefs(this),new PowerMode.Backend(){
                public void connect(){}public void resumeHome(){}
                public void powerChanged(boolean requested,Bridge.Reply reply){reply.done("OK",null);}
                public void retry(Bridge.Reply reply){reply.done("OK",null);}
            });return power;}
            public Bridge bridge(){if(fixtureBridge==null)throw new AssertionError("Fixture backend not installed");return fixtureBridge;}
            void install(IDesktopBridge backend){fixtureBridge=Bridge.forService(this,backend,powerMode());}
            void close(){if(tasks!=null)tasks.closeOwner();if(settings!=null)settings.close();if(fixtureBridge!=null)fixtureBridge.closeIsolated();}
        }
        Sandbox context=new Sandbox();
        SharedPreferences prefs=Launches.prefs(context);VirtualDisplay display=null;
        try{
            main(()->prefs.edit().clear().putBoolean("enabled",true).putBoolean("primary_mode",true).putBoolean("power_mode",true).putInt("workspace_display",0).commit());test.waitForIdleSync();
            display=actual.getSystemService(DisplayManager.class).createVirtualDisplay("Stella rollback fixture",800,600,160,null,DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            require(display!=null,"Could not create disposable fixture display");int external=display.getDisplay().getDisplayId();
            require(Displays.allIds(context).contains(external),"Fixture display unavailable");
            Map<Integer,Integer> locations=new ConcurrentHashMap<>();locations.put(1,0);locations.put(2,0);
            List<Integer> rollback=new CopyOnWriteArrayList<>();java.util.concurrent.atomic.AtomicBoolean fail=new java.util.concurrent.atomic.AtomicBoolean(true);Binder binder=new Binder();
            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            CountDownLatch lateEntered=new CountDownLatch(1),lateResume=new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean holdLate=new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicInteger focused=new java.util.concurrent.atomic.AtomicInteger();
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
                    case "checkedTaskOperation":{
                        if("focus".equals(args[3]))focused.incrementAndGet();
                        if(holdLate.get()&&(Integer)args[0]==external&&(Integer)args[1]==2&&"bounds".equals(args[3])){
                            lateEntered.countDown();require(lateResume.await(5,TimeUnit.SECONDS),"Late transfer fixture timeout");holdLate.set(false);
                        }
                        return "OK";
                    }
                    default:return "OK";
                }
            });
            main(()->{try{context.install(fake);TaskState.of(context).reset(context);prefs.edit().putInt("workspace_display",0).commit();for(int id:new int[]{1,2})TaskState.of(context).launched(context,new JSONObject().put("task",task(id)),0,true);}catch(Exception e){throw new RuntimeException(e);}});
            CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);
            List<Throwable> errors=new CopyOnWriteArrayList<>();
            main(()->TaskState.of(context).transfer(context,external,()->{
                try{TaskState.of(context).observe(context,0,Collections.singletonList(new TaskSnapshot.Task(task(2))));require(TaskState.of(context).owns(new TaskSnapshot.Task(task(1))),"Source poll forgot stranded identity");}
                catch(Throwable error){errors.add(error);}finally{first.countDown();}
            }));
            require(entered.await(5,TimeUnit.SECONDS),"Transfer never entered backend");
            main(()->TaskState.of(context).transfer(context,0,second::countDown));resume.countDown();
            require(first.await(10,TimeUnit.SECONDS),"Failed handoff lost completion");
            require(errors.isEmpty(),"Failure callback: "+errors);
            require(second.await(10,TimeUnit.SECONDS),"Deferred return was dropped");
            require(rollback.containsAll(Arrays.asList(1,2)),"First rollback failure prevented later attempts");
            // Repeating the settled target is harmless and still completes.
            CountDownLatch retry=new CountDownLatch(1);main(()->TaskState.of(context).transfer(context,0,retry::countDown));
            require(retry.await(10,TimeUnit.SECONDS),"Recovery retry timed out");
            require(locations.get(1)==0&&locations.get(2)==0,"Recovery stranded a synthetic task");
            main(()->require(!TaskState.of(context).isBusy()&&TaskState.of(context).target(context)==0,"Transfer state stuck"));
            // Both OS moves have completed, but the async success belongs to a
            // revoked generation. Neither target commit, focus nor rollback may resume.
            holdLate.set(true);int focusBefore=focused.get(),rollbackBefore=rollback.size();
            CountDownLatch stale=new CountDownLatch(1),queued=new CountDownLatch(1);
            main(()->TaskState.of(context).transfer(context,external,stale::countDown));
            require(lateEntered.await(5,TimeUnit.SECONDS),"Late transfer never reached backend");
            main(()->{
                TaskState.of(context).transfer(context,0,queued::countDown);
                context.powerMode().setRequested(false,(result,error)->require(error==null,"Fixture OFF failed"));
                context.powerMode().setRequested(true,(result,error)->require(error==null,"Fixture ON failed"));context.powerMode().connected(true);
            });
            lateResume.countDown();
            require(stale.await(10,TimeUnit.SECONDS)&&queued.await(10,TimeUnit.SECONDS),"Revoked transfer lost completion");
            require(prefs.getInt("workspace_display",-1)==0,"Late transfer success overwrote saved target");
            require(locations.get(1)==external&&locations.get(2)==external&&rollback.size()==rollbackBefore,"Revoked transfer dispatched rollback");
            require(focused.get()==focusBefore,"Revoked transfer dispatched focus");
            main(()->{try{
                TaskState.of(context).observe(context,0,Collections.emptyList());
                require(TaskState.of(context).owns(new TaskSnapshot.Task(task(1)))&&TaskState.of(context).owns(new TaskSnapshot.Task(task(2))),"Revocation forgot partially moved identities");
            }catch(Exception error){throw new RuntimeException(error);}});
            CountDownLatch recovered=new CountDownLatch(1);main(()->TaskState.of(context).transfer(context,0,recovered::countDown));
            require(recovered.await(10,TimeUnit.SECONDS)&&locations.get(1)==0&&locations.get(2)==0,"New active recovery could not locate retained identities");
        }finally{
            main(context::close);
            if(display!=null)display.release();require(actual.deleteSharedPreferences(storeName),"Fixture store cleanup failed");
        }
    }
}
