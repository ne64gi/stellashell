package net.fuyumori.stellashell;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.view.Display;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** Shell-UID backend. Every mutation re-reads the task and its external display. */
final class TaskBackend {
    private final Context context;
    private final Class<?> api, transaction, token;
    private final Object manager, organizer;
    private final Class<?> organizerApi;
    private final Method boundsTransition;
    private String lastBoundsDispatch="none";
    private final Map<Integer,Rect> restoreBounds=new HashMap<>();
    private final ArrayDeque<String> log=new ArrayDeque<>();
    TaskBackend(Context context) throws Exception {
        this.context=context;
        api=Class.forName("android.app.IActivityTaskManager");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"activity_task");
        manager=Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        transaction=Class.forName("android.window.WindowContainerTransaction");
        token=Class.forName("android.window.WindowContainerToken");
        organizerApi=Class.forName("android.window.IWindowOrganizerController");
        organizer=api.getMethod("getWindowOrganizerController").invoke(manager);
        // Android 16 Shell owns task surfaces. A legacy WCT can update configuration
        // without moving the surface (confirmed on NX809J). Keep the proven older
        // framework path; probe the transition API rather than assuming it exists.
        Method transitionMethod=null;
        if(android.os.Build.VERSION.SDK_INT>=36)try {
            transitionMethod=organizerApi.getMethod("startNewTransition",int.class,transaction);
        }catch(NoSuchMethodException ignored){}
        boundsTransition=transitionMethod;
    }
    private static Object field(Object object,String name) throws Exception {return object.getClass().getField(name).get(object);}
    private static int number(Object object,String name) throws Exception {return ((Number)field(object,name)).intValue();}
    private static Object configuration(Object task) throws Exception {
        Object configuration=field(task,"configuration");return field(configuration,"windowConfiguration");
    }
    private static int mode(Object task) throws Exception {Object c=configuration(task);return (Integer)c.getClass().getMethod("getWindowingMode").invoke(c);}
    private static Rect bounds(Object task) throws Exception {Object c=configuration(task);return new Rect((Rect)c.getClass().getMethod("getBounds").invoke(c));}
    private static int type(Object task) throws Exception {Object c=configuration(task);return (Integer)c.getClass().getMethod("getActivityType").invoke(c);}
    private static ComponentName component(Object task) throws Exception {
        ComponentName c=(ComponentName)field(task,"baseActivity");
        return c!=null?c:(ComponentName)field(task,"topActivity");
    }
    private Display display(int id) {
        if(id<=0)throw new IllegalArgumentException("本体画面は操作しません");
        Display d=context.getSystemService(DisplayManager.class).getDisplay(id);
        if(d==null||!d.isValid()||(d.getFlags()&Display.FLAG_PRIVATE)!=0)throw new IllegalArgumentException("外部画面がありません");
        return d;
    }
    private List<?> tasks(int id) throws Exception {
        display(id);
        return (List<?>)api.getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,100,false,false,id);
    }
    private boolean eligible(Object task,int id) throws Exception {
        ComponentName c=component(task);
        return number(task,"displayId")==id && number(task,"userId")==android.os.Process.myUid()/100000
                && type(task)==1 && c!=null && !c.getPackageName().equals("net.fuyumori.stellashell");
    }
    private Object requireTask(int id,int taskId) throws Exception {
        for(Object t:tasks(id))if(number(t,"taskId")==taskId && eligible(t,id))return t;
        throw new IllegalArgumentException("対象ウィンドウは終了または別画面へ移動しています");
    }
    private boolean method(Class<?> cls,String name,Class<?>... types){try{cls.getMethod(name,types);return true;}catch(NoSuchMethodException e){return false;}}
    String snapshot(int id) throws Exception {
        List<?> currentTasks=tasks(id);
        JSONArray list=new JSONArray();
        for(Object t:currentTasks) {
            if(!eligible(t,id))continue;
            Rect b=bounds(t);ComponentName c=component(t);
            JSONObject row=new JSONObject().put("id",number(t,"taskId")).put("component",c.flattenToString())
                    .put("mode",mode(t)).put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom)
                    .put("visible",field(t,"isVisible")).put("focused",field(t,"isFocused"));
            list.put(row);
        }
        JSONObject caps=new JSONObject().put("tasks",true)
                .put("resize",method(api,"resizeTask",int.class,Rect.class,int.class))
                .put("reorder",method(transaction,"reorder",token,boolean.class))
                .put("bounds",method(transaction,"setBounds",token,Rect.class))
                .put("windowingMode",method(transaction,"setWindowingMode",token,int.class))
                .put("boundsTransition",boundsTransition!=null)
                .put("close",method(api,"removeTask",int.class));
        JSONArray stack=new JSONArray();boolean stackReliable=false;
        try {
            // RootTaskInfo is top-to-bottom; its leaf childTaskIds are bottom-to-top.
            // getTasks alone is recency ordered and must NOT be used as visual z-order.
            List<?> roots=(List<?>)api.getMethod("getAllRootTaskInfosOnDisplay",int.class).invoke(manager,id);
            Map<Integer,Object> byId=new HashMap<>();for(Object t:currentTasks)byId.put(number(t,"taskId"),t);
            Set<Integer> seen=new HashSet<>();
            for(Object root:roots){
                int[] children=(int[])field(root,"childTaskIds");boolean found=false;
                for(int i=children.length-1;i>=0;i--){Object t=byId.get(children[i]);if(t!=null){found=true;if(seen.add(children[i]))stack.put(row(t));}}
                if(!found){int rootId=number(root,"taskId");Object t=byId.get(rootId);if(seen.add(rootId))stack.put(row(t!=null?t:root));}
            }
            stackReliable=true;
            for(Object t:currentTasks)if((Boolean)field(t,"isVisible")&&!seen.contains(number(t,"taskId")))stackReliable=false;
        }catch(Exception e){record("stack probe unavailable: "+reason(e));}
        caps.put("stackOrder",stackReliable);
        return new JSONObject().put("backend","shizuku/activity_task+wct").put("capabilities",caps)
                .put("boundsDispatch",lastBoundsDispatch)
                .put("stack",stack)
                .put("tasks",list).put("operations",new JSONArray(log)).toString();
    }
    private JSONObject row(Object t)throws Exception {
        Rect b=bounds(t);ComponentName c=component(t);return new JSONObject().put("id",number(t,"taskId")).put("component",c==null?"unknown/unknown":c.flattenToString()).put("mode",mode(t))
                .put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom).put("visible",field(t,"isVisible")).put("focused",field(t,"isFocused"));
    }
    String launchProfile(String requested,String resolved,int id,int windowMode,int l,int top,int r,int bottom,boolean newWindow)throws Exception {
        Policy.component(requested);ComponentName target=ComponentName.unflattenFromString(requested);display(id);
        if(windowMode!=1&&windowMode!=5)throw new IllegalArgumentException("Unsupported mode");
        if(!resolved.isEmpty()){Policy.component(resolved);if(!target.getPackageName().equals(ComponentName.unflattenFromString(resolved).getPackageName()))throw new IllegalArgumentException("Profile package mismatch");}
        Set<Integer> before=new HashSet<>();Object existing=null;
        for(Object t:tasks(id)){if(!eligible(t,id))continue;before.add(number(t,"taskId"));String c=component(t).flattenToString();if(c.equals(requested)||c.equals(resolved))existing=t;}
        if(!newWindow&&existing!=null){apply(change(existing,"reorder",boolean.class,true));return new JSONObject().put("task",row(existing)).put("created",false).toString();}
        android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic().setLaunchDisplayId(id);
        Point size=new Point();display(id).getRealSize(size);
        Rect wanted=new Rect(Math.max(0,l),Math.max(0,top),Math.min(size.x,r),Math.min(size.y,bottom));
        if(wanted.isEmpty())throw new IllegalArgumentException("Invalid launch bounds");
        options.setLaunchBounds(wanted);android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode",int.class).invoke(options,windowMode);
        android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setComponent(target)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|(newWindow?android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK:android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        Method start=null;for(Method m:api.getMethods())if(m.getName().equals("startActivityAsUser")&&m.getParameterCount()==12){start=m;break;}
        if(start==null)throw new UnsupportedOperationException("Framework launch API unavailable");
        int result=(Integer)start.invoke(manager,null,"com.android.shell",null,intent,null,null,null,0,0,null,options.toBundle(),android.os.Process.myUid()/100000);
        if(result<0)throw new IllegalStateException("Activity start failed: "+result);
        Object found=null;boolean created=false;
        long until=android.os.SystemClock.uptimeMillis()+2500;
        do{
            for(Object t:tasks(id))if(eligible(t,id)&&component(t).getPackageName().equals(target.getPackageName())){
                if(!before.contains(number(t,"taskId"))){found=t;created=true;break;}
                if(component(t).equals(target)||component(t).flattenToString().equals(resolved))found=t;
            }
            if(created)break;android.os.SystemClock.sleep(100);
        }while(android.os.SystemClock.uptimeMillis()<until);
        if(found==null)throw new IllegalStateException("起動先のウィンドウを確認できませんでした");
        if(created){
            Object tx=transaction.getConstructor().newInstance();Object taskToken=field(found,"token");
            transaction.getMethod("setWindowingMode",token,int.class).invoke(tx,taskToken,windowMode);
            if(windowMode==5)transaction.getMethod("setBounds",token,Rect.class).invoke(tx,taskToken,wanted);
            if(windowMode==5)applyBounds(tx);else apply(tx);
            found=requireTask(id,number(found,"taskId"));
        }
        record("profile launch "+requested+" display="+id+" newWindow="+newWindow+" created="+created+" requested="+wanted+" actual="+bounds(found));
        return new JSONObject().put("task",row(found)).put("created",created).toString();
    }
    private void record(String message){if(log.size()>=30)log.removeFirst();log.addLast(System.currentTimeMillis()+" "+message);}
    private Object change(Object t,String name,Class<?> extra,Object arg) throws Exception {
        Object tx=transaction.getConstructor().newInstance();
        transaction.getMethod(name,token,extra).invoke(tx,field(t,"token"),arg);return tx;
    }
    private void apply(Object tx) throws Exception {organizerApi.getMethod("applyTransaction",transaction).invoke(organizer,tx);}
    private void applyBounds(Object tx) throws Exception {
        if(boundsTransition==null){apply(tx);lastBoundsDispatch="legacy-wct";return;}
        // TRANSIT_CHANGE: let the existing SystemUI transition player synchronize
        // task configuration, surface position/crop, and input. Never register a
        // replacement organizer/player, and never replay a partially applied WCT.
        Object transitionToken=boundsTransition.invoke(organizer,6,tx);
        lastBoundsDispatch=transitionToken==null?"transition-api/legacy-no-player":"shell-transition";
    }
    String operate(int id,int taskId,String action,int l,int top,int r,int bottom) throws Exception {
        try {
            Object t=requireTask(id,taskId);Object taskToken=field(t,"token");
            switch(action){
                case "focus": apply(change(t,"reorder",boolean.class,true));break;
                case "minimize": apply(change(t,"reorder",boolean.class,false));break;
                case "close":
                    if(!(Boolean)api.getMethod("removeTask",int.class).invoke(manager,taskId))throw new IllegalStateException("終了できませんでした");
                    restoreBounds.remove(taskId);break;
                case "bounds":
                case "left":
                case "right":
                case "maximize":
                case "restore": {
                    Display d=display(id);Point size=new Point();d.getRealSize(size);
                    android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();d.getRealMetrics(metrics);
                    int dock=Math.round(60*metrics.density), caption=Math.round(32*metrics.density);
                    int[] area=WindowGeometry.area(size.x,size.y,dock);
                    Rect current=bounds(t), wanted;
                    if(action.equals("maximize")){
                        restoreBounds.putIfAbsent(taskId,current);wanted=new Rect(area[0],caption,area[2],area[3]);
                    } else if(action.equals("restore")) {
                        wanted=restoreBounds.remove(taskId);
                        if(wanted==null)wanted=new Rect(size.x/6,size.y/6,size.x*5/6,Math.min(area[3],size.y*5/6));
                    } else if(action.equals("left")||action.equals("right")) {
                        restoreBounds.putIfAbsent(taskId,current);
                        int middle=size.x/2;wanted=new Rect(action.equals("left")?0:middle,caption,action.equals("left")?middle:size.x,area[3]);
                    } else wanted=new Rect(l,top,r,bottom);
                    int[] safe=WindowGeometry.clamp(wanted.left,wanted.top-caption,wanted.right,wanted.bottom-caption,area[2],Math.max(1,area[3]-caption),Math.round(240*metrics.density),Math.round(160*metrics.density));
                    wanted=new Rect(safe[0],safe[1]+caption,safe[2],safe[3]+caption);
                    Object tx=transaction.getConstructor().newInstance();
                    transaction.getMethod("setWindowingMode",token,int.class).invoke(tx,taskToken,5);
                    transaction.getMethod("setBounds",token,Rect.class).invoke(tx,taskToken,wanted);
                    applyBounds(tx);
                    // The result may be constrained further by the application's minimum size.
                    record(action+" task="+taskId+" via="+lastBoundsDispatch+" requested="+wanted+" actual="+bounds(requireTask(id,taskId)));
                    return "OK";
                }
                default:throw new IllegalArgumentException("未対応の操作です");
            }
            record(action+" task="+taskId+" display="+id);return "OK";
        }catch(Exception e){record("FAILED "+action+" task="+taskId+" "+reason(e));throw e;}
    }
    static String reason(Throwable error){while(error instanceof InvocationTargetException && error.getCause()!=null)error=error.getCause();return error.toString();}
}
