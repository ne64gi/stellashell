package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Installed app -> real Shizuku service -> native flag, using the actual caption button. */
final class PinDeviceChecks {
    private final Instrumentation test;private final Context context;
    private static final String COMPONENT="net.fuyumori.stellashell.test/net.fuyumori.stellashell.PinInputActivity";
    PinDeviceChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private static void require(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private void main(Runnable action){Throwable[] error={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private String call(Bridge.Work work)throws Exception{
        CountDownLatch done=new CountDownLatch(1);String[] answer=new String[2];
        main(()->Bridge.get(context).call(work,(value,error)->{answer[0]=value;answer[1]=error;done.countDown();}));
        require(done.await(15,TimeUnit.SECONDS),"Bridge timeout");require(answer[1]==null,"Bridge failure: "+answer[1]);return answer[0];
    }
    private void settleAndCheck(java.util.function.BooleanSupplier condition,String message)throws Exception{
        // One bounded frame/transition settle after an explicit UI action, not polling.
        Thread.sleep(1200);boolean[] ok={false};main(()->ok[0]=condition.getAsBoolean());require(ok[0],message);
    }
    private void connect()throws Exception{
        CountDownLatch ready=new CountDownLatch(1);
        Runnable changed=()->{if(Bridge.get(context).ready())ready.countDown();};
        main(()->{Bridge.get(context).observe(changed);ShellRuntime.start(context,0,false);Bridge.get(context).connect();changed.run();});
        try{require(ready.await(15,TimeUnit.SECONDS),"Shizuku service not ready");}
        finally{main(()->Bridge.get(context).remove(changed));}
    }
    private static Object field(Class<?> type,Object object,String name)throws Exception{java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private View button(int id){
        try{
            Object chrome=ShellFixtureAccess.chrome();if(chrome==null)return null;
            Object frame=((Map<?,?>)field(WindowChrome.class,chrome,"frames")).get(id);if(frame==null)return null;
            for(Object fragment:(List<?>)((List<?>)field(frame.getClass(),frame,"parts")).get(0)){
                View root=(View)field(fragment.getClass(),fragment,"root");
                if(root.isAttachedToWindow()){View found=findPin(root);if(found!=null)return found;}
            }return null;
        }catch(Exception e){throw new AssertionError(e);}
    }
    private String fixtureTree(View view)throws Exception{
        String value=view.getClass().getSimpleName()+":"+view.getVisibility();
        if(view.getClass().getSimpleName().equals("CaptionButton"))value+="/"+field(view.getClass(),view,"action");
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)value+="["+fixtureTree(((ViewGroup)view).getChildAt(i))+"]";
        return value;
    }
    private View findPin(View view)throws Exception{
        if(view.getClass().getSimpleName().equals("CaptionButton")&&"pin".equals(field(view.getClass(),view,"action"))&&view.isShown())return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findPin(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private JSONObject task(int id)throws Exception{
        JSONArray rows=new JSONObject(call(s->s.taskSnapshot(0))).getJSONArray("tasks");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getInt("id")==id&&COMPONENT.equals(row.getString("component")))return row;}
        return null;
    }
    void run()throws Exception{
        require(("SOG06".equals(Build.MODEL)&&Build.VERSION.SDK_INT==34)||("NX809J".equals(Build.MODEL)&&Build.VERSION.SDK_INT==36),"Verified Sony/REDMAGIC main display only");
        require(!context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked(),"Unlock normally first");
        require(Launches.prefs(context).getBoolean("enabled",false)&&Launches.prefs(context).getBoolean("primary_mode",false),"Requires an already enabled main-display session");
        SharedPreferences profiles=context.getSharedPreferences("launch_profiles",0);Map<String,?> before=profiles.getAll();int fixture=-1;
        connect();
        try{
            JSONObject initial=new JSONObject(call(s->s.taskSnapshot(0)));
            require(initial.getJSONObject("capabilities").getBoolean("alwaysOnTop"),"Installed service did not advertise pinning");
            JSONArray rows=initial.getJSONArray("tasks");for(int i=0;i<rows.length();i++)require(!COMPONENT.equals(rows.getJSONObject(i).getString("component")),"Refuse to reuse an existing fixture");
            JSONObject launched=new JSONObject(call(s->{WorkArea.get(context,0).sync(s,0);return s.launchProfile(COMPONENT,"",0,5,100,700,950,1700,true);}));
            JSONObject taskRecord=launched.getJSONObject("task");
            TaskSnapshot.Task model=new TaskSnapshot.Task(taskRecord);fixture=model.id;int id=fixture;
            JSONObject ownershipRecord=new JSONObject().put("task",taskRecord);
            CountDownLatch owned=new CountDownLatch(1);
            main(()->{
                try{TaskState.of(context).launched(context,ownershipRecord,0,true,owned::countDown);}
                catch(org.json.JSONException error){throw new AssertionError(error);}
            });
            require(owned.await(15,TimeUnit.SECONDS),"Fixture role transition did not complete");
            main(()->TaskState.of(context).refresh());
            try{settleAndCheck(()->button(id)!=null,"Pin caption did not render");}
            catch(AssertionError missing){
                String[] state={""};main(()->{TaskSnapshot t=TaskState.of(context).snapshot();state[0]=" chrome="+(ShellFixtureAccess.chrome()!=null)+" display="+ShellRuntime.selectedDisplay()+" canArrange="+t.canArrange+" canPin="+t.canPin;
                    for(TaskSnapshot.Task row:t.tasks)if(row.id==id)state[0]+=" modelMode="+row.mode+" visible="+row.visible+" focused="+row.focused+" bounds="+row.bounds();
                    try{WindowChrome chrome=ShellFixtureAccess.chrome();if(chrome!=null){Object frame=((Map<?,?>)field(WindowChrome.class,chrome,"frames")).get(id);state[0]+=" frame="+(frame!=null);
                        if(frame!=null){List<?> parts=(List<?>)((List<?>)field(frame.getClass(),frame,"parts")).get(0);state[0]+=" captions="+parts.size();
                            for(Object part:parts){View root=(View)field(part.getClass(),part,"root");state[0]+=" attached="+root.isAttachedToWindow()+" shown="+root.isShown()+" size="+root.getWidth()+"x"+root.getHeight()+" tree="+fixtureTree(root);}}}}
                    catch(Exception error){throw new AssertionError(error);}});
                JSONObject nativeTask=task(id);
                throw new AssertionError(missing.getMessage()+state[0]+" nativeMode="+(nativeTask==null?-1:nativeTask.getInt("mode")));
            }
            main(()->require(button(id).performClick(),"Pin caption click failed"));
            settleAndCheck(()->button(id)!=null&&button(id).isSelected(),"Pin caption did not select");
            require(task(id).getBoolean("pinActive"),"Selected caption lacks native pin");
            main(()->require(button(id).performClick(),"Unpin caption click failed"));
            settleAndCheck(()->button(id)!=null&&!button(id).isSelected(),"Pin caption did not deselect");
            require(!task(id).getBoolean("pinActive"),"Unpin caption left native pin");
        }finally{
            if(fixture>=0&&task(fixture)!=null){int id=fixture;call(s->s.taskOperation(0,id,"close",0,0,0,0));}
            main(()->{SharedPreferences.Editor edit=profiles.edit();for(String key:profiles.getAll().keySet())if(key.startsWith(COMPONENT)){Object old=before.get(key);if(old instanceof String)edit.putString(key,(String)old);else edit.remove(key);}edit.commit();});
        }
    }
}
