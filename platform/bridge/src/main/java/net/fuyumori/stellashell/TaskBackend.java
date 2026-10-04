package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.launch.Policy;
import net.fuyumori.stellashell.core.tasks.TaskModes;
import net.fuyumori.stellashell.core.tasks.TaskPinSupport;

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

/** Shell-UID backend. Every mutation re-reads the task and its permitted display. */
final class TaskBackend {
    boolean primaryMode;
    private final Context context;
    private final Class<?> api, transaction, token;
    private final Object manager, organizer;
    private final Class<?> organizerApi;
    private final Method boundsTransition,modeTransition;
    private final FrameworkTaskAccess taskAccess;
    private String lastBoundsDispatch="none";
    private final TaskPins pins;
    private final boolean pinSupported;
    private final TaskPinSupport.Route pinRoute;
    private final Method rootPin;
    private final Map<Integer,Rect> workAreas=new HashMap<>();
    void setWorkArea(int id,Rect area)throws Exception {
        Point size=new Point();display(id).getRealSize(size);
        if(area.isEmpty()||area.left<0||area.top<0||area.right>size.x||area.bottom>size.y)throw new IllegalArgumentException("Invalid work area");
        workAreas.put(id,new Rect(area));
    }
    private final Map<Integer,Rect> restoreBounds=new HashMap<>();
    private final ArrayDeque<String> log=new ArrayDeque<>();
    TaskBackend(Context context) throws Exception {
        this.context=context;
        api=Class.forName("android.app.IActivityTaskManager");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"activity_task");
        manager=Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        taskAccess=new FrameworkTaskAccess(api,manager);
        transaction=Class.forName("android.window.WindowContainerTransaction");
        token=Class.forName("android.window.WindowContainerToken");
        organizerApi=Class.forName("android.window.IWindowOrganizerController");
        organizer=api.getMethod("getWindowOrganizerController").invoke(manager);
        // Android 16 Shell owns task surfaces. A legacy WCT can update configuration
        // without moving the surface (confirmed on NX809J). Keep the proven older
        // framework path; probe the transition API rather than assuming it exists.
        Method transitionMethod=null;
        if(android.os.Build.VERSION.SDK_INT>=34)try {
            transitionMethod=organizerApi.getMethod("startNewTransition",int.class,transaction);
        }catch(NoSuchMethodException ignored){}
        modeTransition=transitionMethod;
        boundsTransition=android.os.Build.VERSION.SDK_INT>=36?transitionMethod:null;
        // Android 14's WCT path only accepts DisplayAreas. SOG06's native popup
        // instead uses the root-task API. Enable that path only on the tested OEM/OS;
        // do not conflate Sony's separate, focus-changing freeform-pinning mode.
        Method rootMethod=null;
        try{rootMethod=api.getMethod("setRootTaskAlwaysOnTop",int.class,boolean.class);}catch(NoSuchMethodException ignored){}
        rootPin=rootMethod;
        pinRoute=TaskPinSupport.select(android.os.Build.VERSION.SDK_INT,android.os.Build.MANUFACTURER,android.os.Build.MODEL,
                method(transaction,"setAlwaysOnTop",token,boolean.class),rootPin!=null);
        pinSupported=pinRoute!=TaskPinSupport.Route.UNSUPPORTED;
        pins=new TaskPins(new TaskPins.Access(){
            public List<FrameworkTaskAccess.Entry> all()throws Exception{return taskAccess.query(-1);}
            public void set(FrameworkTaskAccess.Entry task,boolean enabled)throws Exception{setPinned(task,enabled);}
            public boolean supports(FrameworkTaskAccess.Entry task){return TaskPinSupport.supportsDisplay(pinRoute,task.displayId);}
        });
    }
    private Display display(int id) {
        if(id<0 || (id==0 && !primaryMode))throw new IllegalArgumentException("The main display is not supported");
        Display d=context.getSystemService(DisplayManager.class).getDisplay(id);
        if(d==null||!d.isValid()||(d.getFlags()&Display.FLAG_PRIVATE)!=0)throw new IllegalArgumentException("No external display");
        return d;
    }
    private List<FrameworkTaskAccess.Entry> tasks(int id) throws Exception {
        display(id);
        return taskAccess.query(id);
    }
    private boolean eligible(FrameworkTaskAccess.Entry task,int id) throws Exception {
        ComponentName c=task.component;
        return task.displayId==id && task.userId==android.os.Process.myUid()/100000
                && task.activityType==1 && c!=null && !c.getPackageName().equals("net.fuyumori.stellashell");
    }
    private FrameworkTaskAccess.Entry requireTask(int id,int taskId) throws Exception {
        for(FrameworkTaskAccess.Entry t:tasks(id))if(t.id==taskId && eligible(t,id))return t;
        throw new IllegalArgumentException("The window closed or moved to another display");
    }
    private boolean method(Class<?> cls,String name,Class<?>... types){try{cls.getMethod(name,types);return true;}catch(NoSuchMethodException e){return false;}}
    private Display requirePhoneDisplay(){
        Display phone=context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if(phone==null||!phone.isValid())throw new IllegalStateException("The phone display is unavailable");
        return phone;
    }
    /** Navigation only: listing Phone tasks does not enable main-display window management. */
    String phoneSnapshot()throws Exception {
        requirePhoneDisplay();JSONArray rows=new JSONArray();
        for(FrameworkTaskAccess.Entry task:taskAccess.query(Display.DEFAULT_DISPLAY))
            if(eligible(task,Display.DEFAULT_DISPLAY))rows.put(row(task));
        return new JSONObject().put("tasks",rows).toString();
    }
    String focusPhoneTask(int taskId,String component)throws Exception {
        FrameworkTaskAccess.Entry task=requirePhoneTask(taskId,component);
        // Restore exactly the selected task, without launching another Activity,
        // moving it off the phone display. PiP must exit its OS-owned pinned mode;
        // a reorder alone cannot expand that window. Ordinary freeform is unchanged.
        if(TaskModes.pictureInPicture(task.windowMode)){applyFullscreen(task,true);awaitPhoneMode(taskId,component,1);}
        else {pins.resume(task);apply(change(task,"reorder",boolean.class,true));}
        return "OK";
    }
    private FrameworkTaskAccess.Entry requirePhoneTask(int taskId,String component)throws Exception {
        Policy.component(component);requirePhoneDisplay();
        for(FrameworkTaskAccess.Entry task:taskAccess.query(Display.DEFAULT_DISPLAY)){
            if(task.id!=taskId||!eligible(task,Display.DEFAULT_DISPLAY))continue;
            if(!task.component.flattenToString().equals(component))break;
            return task;
        }
        throw new IllegalArgumentException("The window closed or moved to another display");
    }
    /** Explicit sidebar actions do not enable automatic main-display task management. */
    String operatePhoneTask(int taskId,String component,String action,int l,int top,int r,int bottom)throws Exception {
        if(!"float".equals(action)&&!"fullscreen".equals(action)&&!"close".equals(action))throw new IllegalArgumentException("Unsupported phone operation");
        try{
            FrameworkTaskAccess.Entry task=requirePhoneTask(taskId,component);
            if("close".equals(action))closeTask(task);
            else{
                boolean floating="float".equals(action);int wantedMode=floating?5:1;
                if(floating){
                    Point size=new Point();requirePhoneDisplay().getRealSize(size);
                    Rect wanted=new Rect(l,top,r,bottom);
                    if(wanted.isEmpty())throw new IllegalArgumentException("Invalid window bounds");
                    wanted=BridgeWindowGeometry.clamp(wanted,new Rect(0,0,size.x,size.y));
                    pins.resume(task);applyFreeform(task,wanted,true);
                }else applyFullscreen(task,true);
                FrameworkTaskAccess.Entry actual=awaitPhoneMode(taskId,component,wantedMode);
                record("phone "+action+" task="+taskId+" via="+lastBoundsDispatch);
                return new JSONObject().put("task",row(actual)).toString();
            }
            record("phone "+action+" task="+taskId);return "OK";
        }catch(Exception e){record("FAILED phone "+action+" task="+taskId+" "+reason(e));throw e;}
    }
    private FrameworkTaskAccess.Entry awaitPhoneMode(int taskId,String component,int mode)throws Exception {
        long deadline=android.os.SystemClock.uptimeMillis()+3000;
        FrameworkTaskAccess.Entry actual;
        do {
            actual=requirePhoneTask(taskId,component);
            if(actual.windowMode==mode)return actual;
            android.os.SystemClock.sleep(50);
        }while(android.os.SystemClock.uptimeMillis()<deadline);
        throw new IllegalStateException("The app did not accept the requested window mode");
    }
    String snapshot(int id) throws Exception {
        try{pins.reconcile();}catch(Exception failure){record("pin reconciliation: "+reason(failure));}
        List<FrameworkTaskAccess.Entry> currentTasks=tasks(id);
        JSONArray list=new JSONArray();
        for(FrameworkTaskAccess.Entry t:currentTasks) {
            if(!eligible(t,id))continue;
            Rect b=new Rect(t.bounds);ComponentName c=t.component;
            JSONObject row=new JSONObject().put("id",t.id).put("component",c.flattenToString())
                    .put("mode",t.windowMode).put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom)
                    .put("visible",t.visible).put("focused",t.focused).put("alwaysOnTop",pins.pinned(t)).put("pinActive",t.alwaysOnTop);
            list.put(row);
        }
        JSONObject caps=new JSONObject().put("tasks",true)
                .put("resize",method(api,"resizeTask",int.class,Rect.class,int.class))
                .put("reorder",method(transaction,"reorder",token,boolean.class))
                .put("bounds",method(transaction,"setBounds",token,Rect.class))
                .put("windowingMode",method(transaction,"setWindowingMode",token,int.class))
                .put("alwaysOnTop",TaskPinSupport.supportsDisplay(pinRoute,id))
                .put("pinRoute",TaskPinSupport.supportsDisplay(pinRoute,id)?pinRoute.name():TaskPinSupport.Route.UNSUPPORTED.name())
                .put("boundsTransition",boundsTransition!=null)
                .put("close",method(api,"removeTask",int.class));
        JSONArray stack=new JSONArray();boolean stackReliable=false;
        try {
            // RootTaskInfo is top-to-bottom; its leaf childTaskIds are bottom-to-top.
            // getTasks alone is recency ordered and must NOT be used as visual z-order.
            List<FrameworkTaskAccess.Root> roots=taskAccess.roots(id);
            Map<Integer,FrameworkTaskAccess.Entry> byId=new HashMap<>();for(FrameworkTaskAccess.Entry t:currentTasks)byId.put(t.id,t);
            Set<Integer> seen=new HashSet<>();
            for(FrameworkTaskAccess.Root root:roots){
                int[] children=root.children;boolean found=false;
                for(int i=children.length-1;i>=0;i--){FrameworkTaskAccess.Entry t=byId.get(children[i]);if(t!=null){found=true;if(seen.add(children[i]))stack.put(row(t));}}
                if(!found){int rootId=root.task.id;FrameworkTaskAccess.Entry t=byId.get(rootId);if(seen.add(rootId))stack.put(row(t!=null?t:root.task));}
            }
            stackReliable=true;
            for(FrameworkTaskAccess.Entry t:currentTasks)if(t.visible&&!seen.contains(t.id))stackReliable=false;
        }catch(Exception e){record("stack probe unavailable: "+reason(e));}
        caps.put("stackOrder",stackReliable);
        return new JSONObject().put("backend","shizuku/activity_task+wct").put("capabilities",caps)
                .put("boundsDispatch",lastBoundsDispatch)
                .put("stack",stack)
                .put("tasks",list).put("operations",new JSONArray(log)).toString();
    }
    private JSONObject row(FrameworkTaskAccess.Entry t)throws Exception {
        Rect b=new Rect(t.bounds);ComponentName c=t.component;return new JSONObject().put("id",t.id).put("component",c==null?"unknown/unknown":c.flattenToString()).put("mode",t.windowMode)
                .put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom).put("visible",t.visible).put("focused",t.focused).put("alwaysOnTop",pins.pinned(t)).put("pinActive",t.alwaysOnTop);
    }
    String launchProfile(String requested,String resolved,int id,int windowMode,int l,int top,int r,int bottom,boolean newWindow)throws Exception {
        Policy.component(requested);ComponentName target=ComponentName.unflattenFromString(requested);display(id);
        if(windowMode!=1&&windowMode!=5)throw new IllegalArgumentException("Unsupported mode");
        if(!resolved.isEmpty()){Policy.component(resolved);if(!target.getPackageName().equals(ComponentName.unflattenFromString(resolved).getPackageName()))throw new IllegalArgumentException("Profile package mismatch");}
        Set<Integer> before=new HashSet<>();FrameworkTaskAccess.Entry existing=null;
        for(FrameworkTaskAccess.Entry t:tasks(id)){if(!eligible(t,id))continue;before.add(t.id);String c=t.component.flattenToString();if(c.equals(requested)||c.equals(resolved))existing=t;}
        android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic().setLaunchDisplayId(id);
        Point size=new Point();display(id).getRealSize(size);
        Rect wanted=new Rect(Math.max(0,l),Math.max(0,top),Math.min(size.x,r),Math.min(size.y,bottom));
        if(wanted.isEmpty())throw new IllegalArgumentException("Invalid launch bounds");
        if(windowMode==5){Rect area=workAreas.get(id);if(area==null)throw new IllegalStateException("Work area is not available");wanted=BridgeWindowGeometry.clamp(wanted,area);}
        if(!newWindow&&existing!=null){
            if(windowMode==1)pins.fullscreen(existing);else pins.resume(existing);
            if(existing.windowMode!=windowMode||windowMode==1){
                Object tx=transaction.getConstructor().newInstance();Object taskToken=existing.token;
                transaction.getMethod("setWindowingMode",token,int.class).invoke(tx,taskToken,windowMode);
                transaction.getMethod("setBounds",token,Rect.class).invoke(tx,taskToken,windowMode==5?wanted:null);
                if(windowMode==1)transaction.getMethod("setAppBounds",token,Rect.class).invoke(tx,taskToken,(Object)null);
                applyMode(tx);
            }
            apply(change(existing,"reorder",boolean.class,true));existing=requireTask(id,existing.id);
            record("profile reuse "+requested+" requestedMode="+windowMode+" actualMode="+existing.windowMode+" bounds="+new Rect(existing.bounds));
            return new JSONObject().put("task",row(existing)).put("created",false).toString();
        }

        options.setLaunchBounds(wanted);android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode",int.class).invoke(options,windowMode);
        android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setComponent(target)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|(newWindow?android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK:android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        Method start=null;for(Method m:api.getMethods())if(m.getName().equals("startActivityAsUser")&&m.getParameterCount()==12){start=m;break;}
        if(start==null)throw new UnsupportedOperationException("Framework launch API unavailable");
        int result=(Integer)start.invoke(manager,null,"com.android.shell",null,intent,null,null,null,0,0,null,options.toBundle(),android.os.Process.myUid()/100000);
        if(result<0)throw new IllegalStateException("Activity start failed: "+result);
        FrameworkTaskAccess.Entry found=null;boolean created=false;
        long until=android.os.SystemClock.uptimeMillis()+2500;
        do{
            for(FrameworkTaskAccess.Entry t:tasks(id))if(eligible(t,id)&&t.component.getPackageName().equals(target.getPackageName())){
                if(!before.contains(t.id)){found=t;created=true;break;}
                if(t.component.equals(target)||t.component.flattenToString().equals(resolved))found=t;
            }
            if(created)break;android.os.SystemClock.sleep(100);
        }while(android.os.SystemClock.uptimeMillis()<until);
        if(found==null)throw new IllegalStateException("Could not find the launched window");
        if(created||found.windowMode!=windowMode){
            Object tx=transaction.getConstructor().newInstance();Object taskToken=found.token;
            transaction.getMethod("setWindowingMode",token,int.class).invoke(tx,taskToken,windowMode);
            transaction.getMethod("setBounds",token,Rect.class).invoke(tx,taskToken,windowMode==5?wanted:null);
            if(windowMode==1)transaction.getMethod("setAppBounds",token,Rect.class).invoke(tx,taskToken,(Object)null);
            if(found.windowMode!=windowMode||windowMode==1)applyMode(tx);else applyBounds(tx);
            found=requireTask(id,found.id);
        }
        record("profile launch "+requested+" display="+id+" newWindow="+newWindow+" created="+created+" requested="+wanted+" actual="+new Rect(found.bounds));
        return new JSONObject().put("task",row(found)).put("created",created).toString();
    }
    String moveWorkspaceTask(int source,int destination,int taskId,String component)throws Exception {
        display(source);display(destination);
        FrameworkTaskAccess.Entry task=requireTask(source,taskId);
        if(!task.component.flattenToString().equals(component))throw new IllegalArgumentException("Task identity changed");
        // Only move a standalone root. Moving a grouped root could take unrelated apps with it.
        boolean standalone=false;
        for(FrameworkTaskAccess.Root root:taskAccess.roots(source))if(root.task.id==taskId){
            standalone=true;for(int child:root.children)if(child!=taskId)standalone=false;
        }
        if(!standalone)throw new IllegalStateException("Grouped tasks cannot be transferred");
        // A main-display-only pin must not leak into an unsupported workspace.
        if(pinSupported&&!TaskPinSupport.supportsDisplay(pinRoute,destination))pins.fullscreen(task);
        api.getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(manager,taskId,destination);
        FrameworkTaskAccess.Entry moved=requireTask(destination,taskId);
        record("handoff task="+taskId+" from="+source+" to="+destination);
        return row(moved).toString();
    }
    void releasePins()throws Exception {pins.release();}
    private void setPinned(FrameworkTaskAccess.Entry task,boolean enabled)throws Exception {
        if(!pinSupported)throw new UnsupportedOperationException("Always-on-top unsupported on this system");
        if(enabled&&!TaskPinSupport.supportsDisplay(pinRoute,task.displayId))throw new UnsupportedOperationException("Always-on-top unsupported on this display");
        if(enabled&&task.windowMode!=5)throw new IllegalArgumentException("Always-on-top requires a window");
        if(pinRoute==TaskPinSupport.Route.SONY_ROOT){
            // This API accepts an integer root id, not a task token. Revalidate
            // identity and exclusive root ownership for cleanup as well as pin.
            requireStandalone(task);
            rootPin.invoke(manager,task.id,enabled);
        }else apply(change(task,"setAlwaysOnTop",boolean.class,enabled));
        // A declared hidden API may be ignored by an OEM. Never report a successful pin on faith.
        FrameworkTaskAccess.Entry actual=null;
        for(FrameworkTaskAccess.Entry entry:taskAccess.query(task.displayId))if(entry.id==task.id&&entry.token.equals(task.token))actual=entry;
        if(actual==null||actual.alwaysOnTop!=enabled)throw new IllegalStateException("Always-on-top was not applied by the system");
    }
    private void requireStandalone(FrameworkTaskAccess.Entry task)throws Exception {
        for(FrameworkTaskAccess.Root root:taskAccess.roots(task.displayId))if(root.task.id==task.id){
            if(!root.task.token.equals(task.token)||root.task.userId!=task.userId||!Objects.equals(root.task.component,task.component))throw new IllegalArgumentException("The window identity changed");
            for(int child:root.children)if(child!=task.id)throw new IllegalArgumentException("Grouped windows cannot be pinned");
            return;
        }
        throw new IllegalArgumentException("Grouped windows cannot be pinned");
    }
    private void record(String message){if(log.size()>=30)log.removeFirst();log.addLast(System.currentTimeMillis()+" "+message);}
    private Object change(FrameworkTaskAccess.Entry t,String name,Class<?> extra,Object arg) throws Exception {
        Object tx=transaction.getConstructor().newInstance();
        transaction.getMethod(name,token,extra).invoke(tx,t.token,arg);return tx;
    }
    private void apply(Object tx) throws Exception {organizerApi.getMethod("applyTransaction",transaction).invoke(organizer,tx);}
    private void applyMode(Object tx)throws Exception {
        if(modeTransition==null){apply(tx);lastBoundsDispatch="legacy-mode-wct";return;}
        // Mode changes also change surface crop/position, even on Android 14.
        // Let Shell synchronize the app redraw with the visual transition.
        modeTransition.invoke(organizer,6,tx);lastBoundsDispatch="shell-mode-transition";
    }
    private void applyBounds(Object tx) throws Exception {
        if(boundsTransition==null){apply(tx);lastBoundsDispatch="legacy-wct";return;}
        // TRANSIT_CHANGE: let the existing SystemUI transition player synchronize
        // task configuration, surface position/crop, and input. Never register a
        // replacement organizer/player, and never replay a partially applied WCT.
        Object transitionToken=boundsTransition.invoke(organizer,6,tx);
        lastBoundsDispatch=transitionToken==null?"transition-api/legacy-no-player":"shell-transition";
    }
    private void closeTask(FrameworkTaskAccess.Entry task)throws Exception {
        if(!(Boolean)api.getMethod("removeTask",int.class).invoke(manager,task.id))throw new IllegalStateException("Could not close the window");
        restoreBounds.remove(task.id);pins.closed(task.id);
    }
    private void applyFreeform(FrameworkTaskAccess.Entry task,Rect bounds,boolean focus)throws Exception {
        Object tx=transaction.getConstructor().newInstance();
        transaction.getMethod("setWindowingMode",token,int.class).invoke(tx,task.token,5);
        transaction.getMethod("setBounds",token,Rect.class).invoke(tx,task.token,bounds);
        if(focus)transaction.getMethod("reorder",token,boolean.class).invoke(tx,task.token,true);
        if(task.windowMode!=5)applyMode(tx);else applyBounds(tx);
    }
    private void applyFullscreen(FrameworkTaskAccess.Entry task,boolean focus)throws Exception {
        pins.fullscreen(task);restoreBounds.putIfAbsent(task.id,new Rect(task.bounds));
        Object tx=transaction.getConstructor().newInstance();
        transaction.getMethod("setWindowingMode",token,int.class).invoke(tx,task.token,1);
        transaction.getMethod("setBounds",token,Rect.class).invoke(tx,task.token,(Object)null);
        transaction.getMethod("setAppBounds",token,Rect.class).invoke(tx,task.token,(Object)null);
        if(TaskModes.pictureInPicture(task.windowMode)){
            // PiP may retain an ActivityRecord override after the task mode changes.
            // Inherit the fullscreen task mode instead of leaving the activity pinned.
            transaction.getMethod("setActivityWindowingMode",token,int.class).invoke(tx,task.token,0);
        }
        if(focus)transaction.getMethod("reorder",token,boolean.class).invoke(tx,task.token,true);
        applyMode(tx);
    }
    String operateChecked(int id,int taskId,String component,String action,int l,int top,int r,int bottom)throws Exception {
        Policy.component(component);
        return operate(id,taskId,component,action,l,top,r,bottom);
    }
    String operate(int id,int taskId,String action,int l,int top,int r,int bottom)throws Exception {
        return operate(id,taskId,null,action,l,top,r,bottom);
    }
    private String operate(int id,int taskId,String component,String action,int l,int top,int r,int bottom)throws Exception {
        try {
            FrameworkTaskAccess.Entry t=requireTask(id,taskId);
            if(component!=null&&!t.component.flattenToString().equals(component))throw new IllegalArgumentException("The window closed or moved to another display");
            switch(action){
                case "pin":
                    if(!TaskPinSupport.supportsDisplay(pinRoute,id))throw new UnsupportedOperationException("Always-on-top unsupported on this display");
                    requireStandalone(t);pins.set(t,true);break;
                case "unpin": pins.set(t,false);break;
                case "fullscreen":applyFullscreen(t,TaskModes.pictureInPicture(t.windowMode));break;
                case "focus": if(TaskModes.pictureInPicture(t.windowMode))applyFullscreen(t,true);else {pins.resume(t);apply(change(t,"reorder",boolean.class,true));}break;
                case "minimize": pins.suspend(t);apply(change(t,"reorder",boolean.class,false));break;
                case "close":closeTask(t);break;
                case "resize":
                    // A queued drag/IME reflow must never turn a newly promoted main into freeform.
                    if(t.windowMode!=5)return "OK: stale window resize ignored";
                case "bounds":
                case "left":
                case "right":
                case "maximize":
                case "restore": {
                    Display d=display(id);Point size=new Point();d.getRealSize(size);
                    android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();d.getRealMetrics(metrics);
                    Rect area=workAreas.get(id);
                    if(area==null)throw new IllegalStateException("Work area is not available");
                    Rect current=new Rect(t.bounds), wanted;
                    if(action.equals("maximize")){
                        restoreBounds.putIfAbsent(taskId,current);wanted=new Rect(area);
                    } else if(action.equals("restore")) {
                        wanted=restoreBounds.remove(taskId);
                        if(wanted==null)wanted=new Rect(area.left+area.width()/6,area.top+area.height()/6,area.right-area.width()/6,area.bottom-area.height()/6);
                    } else if(action.equals("left")||action.equals("right")) {
                        restoreBounds.putIfAbsent(taskId,current);
                        int middle=area.centerX();wanted=new Rect(action.equals("left")?area.left:middle,area.top,action.equals("left")?middle:area.right,area.bottom);
                    } else wanted=new Rect(l,top,r,bottom);
                    wanted=BridgeWindowGeometry.clamp(wanted,area);
                    applyFreeform(t,wanted,false);
                    // The result may be constrained further by the application's minimum size.
                    record(action+" task="+taskId+" via="+lastBoundsDispatch+" requested="+wanted+" actual="+new Rect(requireTask(id,taskId).bounds));
                    return "OK";
                }
                default:throw new IllegalArgumentException("Unsupported operation");
            }
            record(action+" task="+taskId+" display="+id);return "OK";
        }catch(Exception e){record("FAILED "+action+" task="+taskId+" "+reason(e));throw e;}
    }
    static String reason(Throwable error){while(error instanceof InvocationTargetException && error.getCause()!=null)error=error.getCause();return error.toString();}
}
