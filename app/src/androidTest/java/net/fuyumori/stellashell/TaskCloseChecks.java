package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real disposable fixture tasks only. Never dumps snapshots or force-stops a package. Run off main. */
final class TaskCloseChecks {
    private static final String PRIMARY="net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishPrimaryActivity";
    private static final String SECONDARY="net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishSecondaryActivity";
    private final Instrumentation test;
    private final Context context;
    private final Map<Integer,String> created=new LinkedHashMap<>();
    private final List<String> attempted=new ArrayList<>();
    TaskCloseChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private interface Condition {boolean ready()throws Exception;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void await(Condition condition,String message)throws Exception {
        long deadline=SystemClock.uptimeMillis()+15_000;
        while(SystemClock.uptimeMillis()<deadline){if(condition.ready())return;Thread.sleep(100);}
        check(condition.ready(),message);
    }
    private String call(Bridge.Work work)throws Exception {
        String[] reply=new String[2];CountDownLatch done=new CountDownLatch(1);
        test.runOnMainSync(()->Bridge.get(context).call(s->{
            try{return work.run(s);}finally{s.setPrimaryMode(Displays.primaryActive(context));}
        },(result,error)->{reply[0]=result;reply[1]=error;done.countDown();}));
        check(done.await(20,TimeUnit.SECONDS),"task close fixture bridge timeout");
        check(reply[1]==null,"task close fixture bridge error: "+reply[1]);return reply[0];
    }
    private JSONArray phoneTasks()throws Exception {
        return new JSONObject(call(s->{s.setPrimaryMode(false);return s.phoneTaskSnapshot();})).getJSONArray("tasks");
    }
    private static JSONObject find(JSONArray tasks,int id,String component)throws Exception {
        for(int i=0;i<tasks.length();i++){
            JSONObject row=tasks.getJSONObject(i);
            if((id<0||row.getInt("id")==id)&&component.equals(row.getString("component")))return row;
        }
        return null;
    }
    private JSONObject fixture(int id,String component)throws Exception {return find(phoneTasks(),id,component);}
    private int launch(String component)throws Exception {
        attempted.add(component);
        call(s->{s.setPrimaryMode(true);return s.launch(component,0,1);});
        await(()->fixture(-1,component)!=null,"fixture launch missing");
        JSONObject row=fixture(-1,component);int id=row.getInt("id");created.put(id,component);
        check(row.getInt("mode")==1,"fixture launch not fullscreen");return id;
    }
    private void focus(int id,String component)throws Exception {
        call(s->{s.setPrimaryMode(false);return s.focusPhoneTask(id,component);});
        await(()->{JSONObject row=fixture(id,component);return row!=null&&row.getBoolean("focused");},"phone focus did not select exact fixture");
    }
    private void rejectFocus(int id,String component,String message)throws Exception {
        JSONObject result=new JSONObject(call(s->{s.setPrimaryMode(false);String answer=s.focusPhoneTask(id,component);return new JSONObject().put("rejected",answer!=null&&answer.startsWith("ERROR:")).toString();}));
        check(result.getBoolean("rejected"),message);
    }
    private void modeViaFeed(int id,String component,String action,Rect bounds)throws Exception {
        PhoneRunningTasks[] feed={null};
        try{
            test.runOnMainSync(()->{feed[0]=new PhoneRunningTasks(context,()->{});feed[0].start();feed[0].refresh();});
            TaskSession.Task[] selected={null};
            await(()->{
                test.runOnMainSync(()->{
                    selected[0]=null;
                    for(TaskSession.Task task:feed[0].tasks())if(task.id==id&&component.equals(task.component)){selected[0]=task;break;}
                });
                return selected[0]!=null;
            },"production phone feed did not find exact fixture");
            CountDownLatch done=new CountDownLatch(1);String[] error={null};
            test.runOnMainSync(()->feed[0].operation(selected[0],action,bounds,done::countDown,problem->{error[0]=problem;done.countDown();}));
            check(done.await(20,TimeUnit.SECONDS),"production phone "+action+" callback timeout");
            check(error[0]==null,"production phone "+action+" failed: "+error[0]);
            JSONObject row=fixture(id,component);check(row!=null,"production phone "+action+" lost fixture");
            TaskSession.Task actual=new TaskSession.Task(row);boolean[] adopted={false},phoneOutput={false};
            check(actual.mode==("float".equals(action)?5:1),"production phone "+action+" returned wrong actual mode");
            test.runOnMainSync(()->{phoneOutput[0]=Launches.prefs(context).getInt("active_display",-1)==0;adopted[0]=Workspace.owns(actual);});
            if("fullscreen".equals(action))check(!adopted[0],"fullscreen task retained floating workspace ownership");
            else if(phoneOutput[0])check(adopted[0],"phone float not adopted for local window chrome");
            else check(!adopted[0],"phone float mixed into external workspace ownership");
        }finally{
            if(feed[0]!=null){
                boolean[] cleared={false};
                test.runOnMainSync(()->{feed[0].close();cleared[0]=feed[0].tasks().isEmpty();});
                check(cleared[0],"production phone feed retained tasks after disposal");
            }
        }
    }
    @SuppressWarnings("unchecked")
    private void clearFixtureOwnership()throws Exception {
        Field field=Workspace.class.getDeclaredField("owned");field.setAccessible(true);
        Map<Integer,String> owned=(Map<Integer,String>)field.get(null);
        test.runOnMainSync(()->{
            for(Map.Entry<Integer,String> entry:created.entrySet())
                if(entry.getValue().equals(owned.get(entry.getKey())))owned.remove(entry.getKey());
        });
    }
    private void rejectOperation(int id,String component,String action,Rect bounds,String message)throws Exception {
        JSONObject result=new JSONObject(call(s->{
            s.setPrimaryMode(false);
            String answer=s.phoneTaskOperation(id,component,action,bounds.left,bounds.top,bounds.right,bounds.bottom);
            return new JSONObject().put("rejected",answer!=null&&answer.startsWith("ERROR:")).toString();
        }));
        check(result.getBoolean("rejected"),message);
    }
    private static Rect bounds(JSONObject row)throws Exception {
        return new Rect(row.getInt("left"),row.getInt("top"),row.getInt("right"),row.getInt("bottom"));
    }
    private static int count(JSONArray tasks,String component)throws Exception {
        int count=0;for(int i=0;i<tasks.length();i++)if(component.equals(tasks.getJSONObject(i).getString("component")))count++;return count;
    }
    private void closeFixture(int id,String component)throws Exception {
        call(s->{
            s.setPrimaryMode(false);JSONArray current=new JSONObject(s.phoneTaskSnapshot()).getJSONArray("tasks");
            if(find(current,id,component)==null)return "OK: fixture already absent";
            // Only the just-revalidated, fixture-owned id+component can reach task removal.
            return s.phoneTaskOperation(id,component,"close",0,0,0,0);
        });
    }
    void run()throws Exception {
        test.runOnMainSync(()->Bridge.get(context).connect());
        await(()->Bridge.get(context).ready(),"Shizuku unavailable for task close fixture");
        JSONArray before=phoneTasks();
        check(find(before,-1,PRIMARY)==null&&find(before,-1,SECONDARY)==null,"preexisting fixture tasks: refusing to reuse or close them");
        try{
            int primary=launch(PRIMARY),secondary=launch(SECONDARY);
            check(primary!=secondary,"fixtures did not create independent tasks");
            // Task creation/mode can be reported before Android has completed its
            // foreground transition. Establish focus before asserting rejected
            // operations leave the current fixture unchanged.
            focus(secondary,SECONDARY);
            JSONObject first=fixture(primary,PRIMARY),second=fixture(secondary,SECONDARY);
            check(first!=null&&second!=null,"phone snapshot unavailable with primaryMode=false");
            int mode=first.getInt("mode");Rect original=bounds(first),otherBounds=bounds(second);
            Rect area=WorkArea.get(context,0).content;
            Rect floating=new Rect(area.left+area.width()/8,area.top+area.height()/8,area.left+area.width()*7/8,area.top+area.height()*5/8);
            rejectFocus(primary,SECONDARY,"wrong-component task focus accepted");
            check(fixture(secondary,SECONDARY).getBoolean("focused"),"rejected focus disturbed current fixture");
            rejectOperation(primary,SECONDARY,"float",floating,"wrong-component float accepted");
            rejectOperation(primary,SECONDARY,"fullscreen",new Rect(),"wrong-component fullscreen accepted");
            rejectOperation(primary,SECONDARY,"close",new Rect(),"wrong-component close accepted");
            rejectOperation(primary,PRIMARY,"minimize",new Rect(),"unsupported phone task action accepted");
            first=fixture(primary,PRIMARY);second=fixture(secondary,SECONDARY);
            check(first!=null&&first.getInt("mode")==mode&&bounds(first).equals(original),"rejected operation changed target fixture");
            check(second!=null&&second.getBoolean("focused")&&bounds(second).equals(otherBounds),"rejected operation disturbed survivor");
            focus(primary,PRIMARY);
            check(fixture(primary,PRIMARY).getInt("mode")==mode,"phone focus changed windowing mode");
            check(fixture(secondary,SECONDARY)!=null,"focusing selected task removed unrelated fixture");
            modeViaFeed(primary,PRIMARY,"float",floating);
            await(()->{JSONObject row=fixture(primary,PRIMARY);return row!=null&&row.getInt("mode")==5&&bounds(row).equals(floating);},"phone float did not convert exact task/mode/bounds");
            JSONArray converted=phoneTasks();
            check(count(converted,PRIMARY)==1&&count(converted,SECONDARY)==1,"phone float launched another task or lost fixture");
            second=find(converted,secondary,SECONDARY);
            check(second!=null&&second.getInt("mode")==1&&bounds(second).equals(otherBounds),"phone float changed unrelated fixture");
            focus(primary,PRIMARY);
            first=fixture(primary,PRIMARY);
            check(first.getInt("mode")==5&&bounds(first).equals(floating),"phone focus resized or fullscreened floating task");
            modeViaFeed(primary,PRIMARY,"fullscreen",new Rect());
            await(()->{JSONObject row=fixture(primary,PRIMARY);return row!=null&&row.getInt("mode")==1&&bounds(row).equals(original);},"phone fullscreen did not restore same task/fullscreen bounds");
            JSONArray restored=phoneTasks();
            check(count(restored,PRIMARY)==1&&count(restored,SECONDARY)==1,"phone fullscreen launched another task or lost fixture");
            second=find(restored,secondary,SECONDARY);
            check(second!=null&&second.getInt("mode")==1&&bounds(second).equals(otherBounds),"phone fullscreen changed unrelated fixture");
            modeViaFeed(primary,PRIMARY,"float",floating);
            await(()->{JSONObject row=fixture(primary,PRIMARY);return row!=null&&row.getInt("mode")==5&&bounds(row).equals(floating);},"phone re-float did not preserve same task/mode/bounds");
            focus(secondary,SECONDARY);
            check(!fixture(primary,PRIMARY).getBoolean("focused"),"close target was not a background fixture");
            closeFixture(primary,PRIMARY);
            await(()->fixture(primary,PRIMARY)==null,"close minimized fixture instead of removing task");
            second=fixture(secondary,SECONDARY);
            check(second!=null&&second.getInt("mode")==1,"closing target removed/resized other fixture");
            rejectFocus(primary,PRIMARY,"removed task could still focus");
            rejectOperation(primary,PRIMARY,"float",floating,"removed task could still float");
            rejectOperation(primary,PRIMARY,"fullscreen",new Rect(),"removed task could still fullscreen");
            rejectOperation(primary,PRIMARY,"close",new Rect(),"removed task close accepted");
            second=fixture(secondary,SECONDARY);
            check(second!=null&&second.getInt("mode")==1&&bounds(second).equals(otherBounds),"removed-task operation rejection disturbed survivor");
        }finally{
            Throwable cleanup=null;
            // A launch may create its task before a later wait/parse fails; reclaim only
            // these prechecked-absent fixture components, never an unrelated Activity.
            try{
                JSONArray remaining=phoneTasks();
                for(int i=0;i<remaining.length();i++){
                    JSONObject row=remaining.getJSONObject(i);
                    String component=row.getString("component");
                    if(attempted.contains(component))created.put(row.getInt("id"),component);
                }
            }catch(Throwable error){if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            for(Map.Entry<Integer,String> fixture:created.entrySet())try{
                closeFixture(fixture.getKey(),fixture.getValue());
                await(()->fixture(fixture.getKey(),fixture.getValue())==null,"fixture cleanup did not remove task");
            }catch(Throwable error){if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            try{clearFixtureOwnership();}catch(Throwable error){if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            if(cleanup instanceof Exception)throw (Exception)cleanup;
            if(cleanup instanceof Error)throw (Error)cleanup;
        }
    }
}
