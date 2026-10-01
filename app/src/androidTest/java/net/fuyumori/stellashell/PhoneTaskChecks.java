package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Only disposable fixture tasks move; ordinary Phone tasks must never be adopted. */
final class PhoneTaskChecks {
    private final Instrumentation test;private final Context context;
    private final String floating="net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishPrimaryActivity";
    private final String normal="net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishSecondaryActivity";
    PhoneTaskChecks(Instrumentation t){test=t;context=t.getTargetContext();}
    private void main(Runnable action){test.runOnMainSync(action);}
    private static void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private void await(java.util.function.BooleanSupplier condition,String text)throws Exception{
        long until=SystemClock.uptimeMillis()+15000;while(!condition.getAsBoolean()&&SystemClock.uptimeMillis()<until)Thread.sleep(100);require(condition.getAsBoolean(),text);
    }
    private String call(Bridge.Work work)throws Exception{
        String[] result=new String[2];CountDownLatch done=new CountDownLatch(1);
        main(()->Bridge.get(context).call(work,(value,error)->{result[0]=value;result[1]=error;done.countDown();}));
        require(done.await(20,TimeUnit.SECONDS),"bridge timeout");require(result[1]==null,"bridge: "+result[1]);return result[0];
    }
    private JSONObject task(int display,String component)throws Exception{
        JSONArray rows=new JSONObject(call(s->s.taskSnapshot(display))).getJSONArray("tasks");
        for(int i=0;i<rows.length();i++)if(component.equals(rows.getJSONObject(i).getString("component")))return rows.getJSONObject(i);
        return null;
    }
    void run(int external)throws Exception{
        SharedPreferences prefs=Launches.prefs(context);Map<String,?> before=prefs.getAll();
        SharedPreferences profiles=context.getSharedPreferences("launch_profiles",0);Map<String,?> profileBefore=profiles.getAll();
        ComponentName desktop=new ComponentName(context,DesktopActivity.class);int state=context.getPackageManager().getComponentEnabledSetting(desktop);
        try {
            main(()->{DockService.stop(context,false);prefs.edit().putBoolean("primary_mode",true).putBoolean("phone_window_management",false).putBoolean("phone_sidebar",true).putInt("workspace_display",0).commit();DockService.start(context,0,false);Bridge.get(context).connect();});
            await(()->Bridge.get(context).ready(),"Shizuku unavailable for floating fixture");
            main(()->Launches.app(context,floating,0,false,true));await(()->!Launches.pending(),"floating launch stuck");
            JSONObject f=task(0,floating);require(f!=null&&f.getInt("mode")==5,"explicit floating was not freeform");int id=f.getInt("id");
            main(()->Launches.app(context,normal,0));await(()->!Launches.pending(),"normal launch stuck");Thread.sleep(600);
            JSONObject n=task(0,normal);require(n!=null&&n.getInt("mode")==1,"ordinary Phone app was not fullscreen");int ordinary=n.getInt("id");
            require(!Workspace.owns(new TaskSession.Task(n)),"ordinary Phone app was adopted");
            require(Workspace.owns(new TaskSession.Task(f)),"explicit float lost ownership");
            main(()->DockService.handoff(context,external));await(()->Workspace.target(context)==external&&!Workspace.isBusy(),"handoff did not finish");
            f=task(external,floating);require(f!=null&&f.getInt("id")==id,"float task identity lost on external handoff");
            n=task(0,normal);require(n!=null&&n.getInt("id")==ordinary&&n.getInt("mode")==1,"normal app was moved or resized");
            main(()->DockService.handoff(context,0));await(()->Workspace.target(context)==0&&!Workspace.isBusy(),"return did not finish");
            f=task(0,floating);require(f!=null&&f.getInt("id")==id&&f.getInt("mode")==5,"floating task not preserved on return");
            n=task(0,normal);require(n!=null&&n.getInt("id")==ordinary&&n.getInt("mode")==1,"normal task changed on return");
        } finally {
            for(int display:new int[]{0,external})for(String component:new String[]{floating,normal})try{
                JSONObject row=task(display,component);if(row!=null){int id=row.getInt("id");call(s->s.taskOperation(display,id,"close",0,0,0,0));}
            }catch(Exception ignored){}
            main(()->{
                DockService.stop(context,false);SharedPreferences.Editor e=prefs.edit();
                for(String key:new String[]{"enabled","primary_mode","phone_window_management","phone_sidebar","workspace_display","active_display","preferred_display","recent","last_error"}){
                    Object old=before.get(key);if(old instanceof Boolean)e.putBoolean(key,(Boolean)old);else if(old instanceof Integer)e.putInt(key,(Integer)old);else if(old instanceof String)e.putString(key,(String)old);else e.remove(key);
                }e.commit();
                SharedPreferences.Editor p=profiles.edit();for(String key:profiles.getAll().keySet())if(key.startsWith("net.fuyumori.stellashell.test/")){Object old=profileBefore.get(key);if(old instanceof String)p.putString(key,(String)old);else p.remove(key);}p.commit();
                context.getPackageManager().setComponentEnabledSetting(desktop,state,android.content.pm.PackageManager.DONT_KILL_APP);
            });
        }
    }
}
