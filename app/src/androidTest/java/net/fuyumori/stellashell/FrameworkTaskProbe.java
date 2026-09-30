package net.fuyumori.stellashell;

import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import org.json.JSONObject;

/** Shell-UID, read-only integration probe; run with app_process, not app permissions. */
public final class FrameworkTaskProbe {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] arguments)throws Exception {
        if(Looper.myLooper()==null)Looper.prepareMainLooper();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        Class<?> api=Class.forName("android.app.IActivityTaskManager");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"activity_task");
        Object manager=Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        FrameworkTaskAccess access=new FrameworkTaskAccess(api,manager);

        ActivityManager.RunningTaskInfo fixture=new ActivityManager.RunningTaskInfo();
        fixture.taskId=71903;fixture.topActivity=new ComponentName("example.test","example.test.Top");
        TaskInfo.class.getField("displayId").setInt(fixture,42);
        TaskInfo.class.getField("userId").setInt(fixture,11);
        TaskInfo.class.getField("isVisible").setBoolean(fixture,true);
        TaskInfo.class.getField("isFocused").setBoolean(fixture,false);
        Configuration config=(Configuration)TaskInfo.class.getMethod("getConfiguration").invoke(fixture);
        Object wc=Configuration.class.getField("windowConfiguration").get(config);
        wc.getClass().getMethod("setWindowingMode",int.class).invoke(wc,5);
        wc.getClass().getMethod("setActivityType",int.class).invoke(wc,1);
        Rect wanted=new Rect(17,41,517,341);
        wc.getClass().getMethod("setBounds",Rect.class).invoke(wc,wanted);
        FrameworkTaskAccess.Entry entry=access.decode(fixture);
        require(entry.id==71903&&entry.displayId==42&&entry.userId==11,"identity decode");
        require(entry.windowMode==5&&entry.activityType==1,"window metadata");
        require(entry.visible&&!entry.focused,"visibility/focus");
        require(entry.component.equals(fixture.topActivity),"top activity fallback");
        require(entry.bounds.equals(wanted),"bounds decode");
        wc.getClass().getMethod("setBounds",Rect.class).invoke(wc,new Rect(1,2,3,4));
        require(entry.bounds.equals(wanted),"snapshot must not alias framework geometry");
        fixture.baseActivity=new ComponentName("example.test","example.test.Base");
        require(access.decode(fixture).component.equals(fixture.baseActivity),"base activity precedence");
        fixture.baseActivity=null;fixture.topActivity=null;
        require(access.decode(fixture).component==null,"missing component preserved");
        int[] children={1,2};FrameworkTaskAccess.Root root=new FrameworkTaskAccess.Root(entry,children);
        children[0]=99;require(root.children[0]==1,"root children must be detached");
        System.out.println("PASS: framework fixture, component fallback, geometry and child-array isolation");

        int displays=0;
        for(Display display:context.getSystemService(DisplayManager.class).getDisplays()){
            if(!display.isValid()||(display.getFlags()&Display.FLAG_PRIVATE)!=0)continue;
            int id=display.getDisplayId();
            for(FrameworkTaskAccess.Entry live:access.query(id))require(live.displayId==id,"query escaped display");
            TaskBackend backend=new TaskBackend(context);backend.primaryMode=id==0;
            JSONObject snapshot=new JSONObject(backend.snapshot(id));
            require(snapshot.getJSONObject("capabilities").getBoolean("tasks"),"task capability");
            require(snapshot.getJSONObject("capabilities").getBoolean("stackOrder"),"root hierarchy unavailable");
            System.out.println("PASS: display="+id+" tasks="+snapshot.getJSONArray("tasks").length()+" stack="+snapshot.getJSONArray("stack").length());
            displays++;
        }
        require(displays>0,"no displays checked");
        try {new TaskBackend(context).snapshot(0);throw new AssertionError("primary policy bypass");}
        catch(IllegalArgumentException expected){}
        System.out.println("PASS: primary-display policy; no tasks moved, resized, launched or closed");
        System.exit(0);
    }
}
