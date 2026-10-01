package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Rect;
import android.view.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable fixture windows only; restores every preference changed by this check. */
final class DesktopPolishChecks {
    private final Instrumentation test;private final Context context;private final int display;private final boolean pauseShell;
    private final List<Integer> fixtures=new ArrayList<>();private TaskSession session;private WindowChrome chrome;private AppMenu menu;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    DesktopPolishChecks(Instrumentation test,int display,boolean pauseShell){this.test=test;this.context=test.getTargetContext();this.display=display;this.pauseShell=pauseShell;}
    private void main(Runnable action){Throwable[] error={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void await(java.util.function.BooleanSupplier condition,String message)throws Exception{long until=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<until){boolean[] ok={false};main(()->ok[0]=condition.getAsBoolean());if(ok[0])return;Thread.sleep(100);}throw new AssertionError(message);}
    private String call(Bridge.Work work)throws Exception{CountDownLatch done=new CountDownLatch(1);String[] result=new String[2];Bridge.get(context).call(work,(value,error)->{result[0]=value;result[1]=error;done.countDown();});if(!done.await(15,TimeUnit.SECONDS))throw new AssertionError("bridge timeout");if(result[1]!=null)throw new AssertionError(result[1]);return result[0];}
    private int launch(String activity,int l,int t,int r,int b)throws Exception{
        String component="net.fuyumori.stellashell.test/net.fuyumori.stellashell."+activity;
        JSONObject task=new JSONObject(call(s->{WorkArea.get(context,display).sync(s,display);return s.launchProfile(component,component,display,5,l,t,r,b,false);})).getJSONObject("task");int id=task.getInt("id");fixtures.add(id);return id;
    }
    private static Object field(Object object,String name)throws Exception{java.lang.reflect.Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private int captions(){
        try{int count=0;Map<?,?> frames=(Map<?,?>)field(chrome,"frames");Rect panel=ShellPanels.bounds(display);
            for(Object frame:frames.values()){
                List<?> parts=(List<?>)field(frame,"parts");
                for(Object fragment:(List<?>)parts.get(0)){View root=(View)field(fragment,"root");if(!root.isAttachedToWindow()||root.getVisibility()!=View.VISIBLE)continue;
                    int[] xy=new int[2];root.getLocationOnScreen(xy);Rect bounds=new Rect(xy[0],xy[1],xy[0]+root.getWidth(),xy[1]+root.getHeight());
                    if(bounds.isEmpty())continue;if(panel!=null&&Rect.intersects(panel,bounds))return 0;count++;
                }
            }return count;
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}catch(Exception e){throw new RuntimeException(e);}
    }
    void run()throws Exception{
        SharedPreferences prefs=Launches.prefs(context);Map<String,?> previous=prefs.getAll();
        boolean running=Boolean.TRUE.equals(previous.get("enabled"));
        if(running&&!pauseShell)throw new AssertionError("Stop desktop before fixture check or explicitly pass polish_pause_shell=true");
        ComponentName desktopComponent=new ComponentName(context,DesktopActivity.class);
        int desktopState=context.getPackageManager().getComponentEnabledSetting(desktopComponent);
        SharedPreferences profiles=context.getSharedPreferences("launch_profiles",Context.MODE_PRIVATE);
        Map<String,?> previousProfiles=profiles.getAll();
        int[] originalFocus={-1};
        Runnable panels=()->{if(chrome!=null&&session!=null)chrome.update(session.tasks());};
        try{
            if(running)main(()->DockService.stop(context,false));
            main(()->{prefs.edit().putBoolean("enabled",true).putBoolean("primary_mode",display==0).putString("shell_layout",display==0?"compact":"desktop").apply();Bridge.get(context).connect();});
            await(()->Bridge.get(context).ready(),"Shizuku not connected");
            JSONArray originalRows=new JSONObject(call(bridge->bridge.taskSnapshot(display))).getJSONArray("tasks");
            for(int i=0;i<originalRows.length();i++)if(originalRows.getJSONObject(i).optBoolean("focused"))originalFocus[0]=originalRows.getJSONObject(i).getInt("id");
            int primary=launch("PolishPrimaryActivity",60,130,620,600),secondary=launch("PolishSecondaryActivity",650,200,1100,660);
            if(display==0){
                String first="net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishPrimaryActivity";
                String second="net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishSecondaryActivity";
                main(()->{Workspace.reset(context);Launches.app(context,first,0);Launches.app(context,second,0);});
                await(()->!Launches.pending()&&!Workspace.isBusy()&&Workspace.primary()==primary,"Sequential default launches did not keep the first app primary");
                call(s->s.taskOperation(0,secondary,"focus",0,0,0,0));
                if(Workspace.primary()!=primary)throw new AssertionError("Focusing secondary promoted it");
                JSONArray tasks=new JSONObject(call(s->s.taskSnapshot(0))).getJSONArray("tasks");JSONObject a=null,b=null;
                for(int i=0;i<tasks.length();i++){JSONObject row=tasks.getJSONObject(i);if(row.getInt("id")==primary)a=row;if(row.getInt("id")==secondary)b=row;}
                if(a==null||b==null||a.getInt("mode")!=1||b.getInt("mode")!=5||!a.getBoolean("visible")||!b.getBoolean("visible")||b.getInt("right")-b.getInt("left")>=a.getInt("right")-a.getInt("left"))throw new AssertionError("Primary and secondary not simultaneously visible at distinct sizes");
                call(s->s.taskOperation(0,secondary,"fullscreen",0,0,0,0));
                JSONArray drifted=new JSONObject(call(s->s.taskSnapshot(0))).getJSONArray("tasks");List<TaskSession.Task> changed=new ArrayList<>();
                for(int i=0;i<drifted.length();i++)changed.add(new TaskSession.Task(drifted.getJSONObject(i)));
                main(()->Workspace.observe(context,0,changed));await(()->!Workspace.isBusy(),"Secondary mode repair did not finish");
                JSONArray repaired=new JSONObject(call(s->s.taskSnapshot(0))).getJSONArray("tasks");
                for(int i=0;i<repaired.length();i++)if(repaired.getJSONObject(i).getInt("id")==secondary&&repaired.getJSONObject(i).getInt("mode")!=5)throw new AssertionError("Secondary fullscreen drift was not repaired");
                main(()->Workspace.role(context,secondary,0,true));await(()->!Workspace.isBusy()&&Workspace.primary()==secondary,"Role swap failed");
                call(s->s.taskOperation(0,primary,"focus",0,0,0,0));if(Workspace.primary()!=secondary)throw new AssertionError("Demoted task focus changed primary");
                main(()->{DockService.enableHome(context,true);Launches.home(context,0);});
                await(()->!Launches.pending()&&Workspace.needsPrimary(),"Show desktop did not prepare next primary");
                JSONArray hidden=new JSONObject(call(s->s.taskSnapshot(0))).getJSONArray("tasks");int remaining=0;
                for(int i=0;i<hidden.length();i++){JSONObject row=hidden.getJSONObject(i);if(row.getInt("id")==primary||row.getInt("id")==secondary){remaining++;if(row.optBoolean("visible"))throw new AssertionError("Show desktop left a fixture visible");}}
                if(remaining!=2)throw new AssertionError("Show desktop closed a fixture");
                main(()->Launches.app(context,first,0));
                await(()->!Launches.pending()&&Workspace.primary()==primary,"First launch after desktop did not become primary");
                main(()->Launches.app(context,second,0,false,false));
                await(()->!Launches.pending()&&Workspace.primary()==secondary,"Explicit primary launch ignored");
                main(()->Launches.app(context,second,0,false,true));
                await(()->!Launches.pending()&&Workspace.primary()==-1,"Explicit secondary launch ignored");
                main(()->Launches.app(context,first,0));
                await(()->!Launches.pending()&&Workspace.primary()==primary,"Missing primary was not replaced");
                // A failed launch must not consume the next-primary decision or block the queue.
                main(()->{Launches.home(context,0);Launches.app(context,"net.fuyumori.stellashell.test/net.fuyumori.stellashell.MissingActivity",0);Launches.app(context,second,0);});
                await(()->!Launches.pending()&&Workspace.primary()==secondary,"Failed launch consumed primary selection or stalled queue");
            }else{
                main(()->{
                    Context dc=context.createDisplayContext(Displays.require(context,display)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);WindowManager wm=dc.getSystemService(WindowManager.class);
                    session=new TaskSession(context,display,()->{if(chrome!=null)chrome.update(session.tasks());},()->false);chrome=new WindowChrome(dc,wm,session);menu=new AppMenu(dc,wm,display);ShellPanels.observe(panels);
                });
                await(()->captions()>0,"Fixture captions did not render");
                main(()->HubActivity.open(context,display));await(()->ShellPanels.bounds(display)!=null&&captions()>0,"Widget panel hid all captions");
                Object[] widget={null};main(()->{try{java.lang.reflect.Field f=HubActivity.class.getDeclaredField("visible");f.setAccessible(true);widget[0]=((java.lang.ref.WeakReference<?>)f.get(null)).get();}catch(Exception e){throw new RuntimeException(e);}});
                main(()->QuickSettingsActivity.open(context,display));await(()->((android.app.Activity)widget[0]).isFinishing()&&ShellPanels.bounds(display)!=null&&captions()>0,"Quick settings did not replace widget panel");
                main(()->menu.open(loader));await(()->menu.isOpen()&&ShellPanels.bounds(display)!=null,"Start did not replace quick settings");
                main(()->menu.back());await(()->!ShellPanels.isOpen(display)&&captions()>0,"Back failed to close Start or restore captions");
            }
        }finally{
            main(()->{ShellPanels.unobserve(panels);ShellPanels.dismiss(display);if(session!=null)session.close();if(chrome!=null)chrome.clear();});loader.shutdownNow();
            try{await(()->!Launches.pending()&&!Workspace.isBusy(),"Fixture queue did not drain");}catch(Throwable ignored){}
            for(int id:fixtures)try{call(s->s.taskOperation(display,id,"close",0,0,0,0));}catch(Throwable ignored){}
            if(originalFocus[0]>=0)try{call(s->s.taskOperation(display,originalFocus[0],"focus",0,0,0,0));}catch(Throwable ignored){}
            main(()->{Workspace.reset(context);SharedPreferences.Editor edit=prefs.edit();for(String key:new String[]{"enabled","primary_mode","shell_layout","workspace_display","recent","last_error"}){Object value=previous.get(key);if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof String)edit.putString(key,(String)value);else edit.remove(key);}edit.commit();
                SharedPreferences.Editor clean=profiles.edit();
                for(String key:profiles.getAll().keySet())if(key.startsWith("net.fuyumori.stellashell.test/")){Object old=previousProfiles.get(key);if(old instanceof String)clean.putString(key,(String)old);else clean.remove(key);}clean.commit();
                context.getPackageManager().setComponentEnabledSetting(desktopComponent,desktopState,android.content.pm.PackageManager.DONT_KILL_APP);
            });
        }
    }
}
