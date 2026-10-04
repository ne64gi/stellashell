package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.PopupMenu;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated real main overlays and synthetic feeds; no cached WorkArea-0 or personal task changes. */
final class PhoneTaskbarChecks {
    private final Instrumentation test;private final Context actual;
    private final String prefix="phone_taskbar_fixture_"+java.util.UUID.randomUUID()+"_";
    private final Set<String> files=new HashSet<>();
    private Context sandbox,mainContext;
    private ShellSettings fixtureSettings;private TaskState fixtureTasks;
    private PhoneTaskbar bar;private PhoneSidebar dock;
    private final FakeBackend barBackend=new FakeBackend(),dockBackend=new FakeBackend();
    private String barPin,dockPin,barLabel;private int external;
    PhoneTaskbarChecks(Instrumentation test){this.test=test;actual=test.getTargetContext();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Object get(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private void main(Runnable action){Throwable[] error={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void await(BooleanSupplier condition,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<until){boolean[] ok={false};main(()->ok[0]=condition.getAsBoolean());if(ok[0])return;Thread.sleep(50);}
        throw new AssertionError(message);
    }
    private WorkArea area(){WindowManager wm=mainContext.getSystemService(WindowManager.class);return WorkArea.read(mainContext,wm.getCurrentWindowMetrics().getWindowInsets());}
    void run(int externalDisplay)throws Exception{
        external=externalDisplay;check(external>0,"Requires the parent test's owned external display");
        try{
            main(()->{
                sandbox=new Sandbox(actual);
                mainContext=sandbox.createDisplayContext(actual.getSystemService(DisplayManager.class).getDisplay(0)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                barPin=new ComponentName(actual,SetupActivity.class).flattenToString();
                dockPin=new ComponentName(test.getContext().getPackageName(),"net.fuyumori.stellashell.LauncherEntryFirst").flattenToString();
                barLabel=actual.getApplicationInfo().loadLabel(actual.getPackageManager()).toString();
                SharedPreferences p=Launches.prefs(sandbox);
                check(p.edit().putBoolean("phone_profile_initialized",true).putBoolean("enabled",false).putBoolean("primary_mode",false)
                    .putInt("active_display",external).putInt("workspace_display",external).putBoolean("phone_sidebar",true).putBoolean("phone_sidebar_over_apps",true)
                    .putString("phone_taskbar_pinned",String.join("\n",java.util.Collections.nCopies(6,barPin)))
                    .putString("phone_pinned",String.join("\n",java.util.Collections.nCopies(2,dockPin))).commit(),"Main fixture preferences failed");
                barBackend.payload=snapshot(dockPin,12);dockBackend.payload=snapshot(barPin,1);
                switchFlags(true,false);
                bar=new PhoneTaskbar(sandbox,()->{},barBackend,this::area);
                dock=new PhoneSidebar(sandbox,()->{},dockBackend,this::area);dock.taskbarReady(bar.ready());dock.show(true);
                check(get(bar,"observer")==null&&get(dock,"observer")==null,"Main fixture must not register/cache a real WorkArea-0 observer");
            });
            await(()->laidOut(),"Main Taskbar/Dock did not attach");test.waitForIdleSync();
            main(()->{
                validate();check(barBackend.observed==1&&dockBackend.observed==1,"Main overlays did not use separate bounded feeds");
                check(Launches.prefs(sandbox).getInt("active_display",-1)==external,"Main overlays rewrote external output selection");
                HorizontalScrollView panel=(HorizontalScrollView)get(bar,"panel");LinearLayout row=(LinearLayout)get(bar,"row");
                check(row.getWidth()>panel.getWidth()&&panel.canScrollHorizontally(1),"Whole main Taskbar axis is not scrollable");
                View start=row.getChildAt(0),last=row.getChildAt(row.getChildCount()-1);Rect visible=new Rect();
                panel.scrollTo(row.getWidth(),0);check(panel.getScrollX()>0&&last.getGlobalVisibleRect(visible)&&!visible.isEmpty(),"Main Taskbar status/end controls are unreachable");
                panel.scrollTo(0,0);check(start.getGlobalVisibleRect(visible)&&!visible.isEmpty(),"Main Taskbar Start cannot be reached again");
                check(icons(panel,barLabel)==6&&((LinearLayout)get(bar,"activeTasks")).getChildCount()==12,"Main Taskbar bound Dock pins or lost its own pins/synthetic tasks");
                check(((PhoneRunningTasks)get(bar,"running")).tasks().size()==12,"Main Taskbar fake task feed missing");
                String phoneDockPins=Launches.prefs(sandbox).getString("phone_pinned","");
                Launches.toggleTaskbarPin(mainContext,barPin);bar.rebuild();
                check(Launches.taskbarPins(mainContext).size()==5&&Launches.prefs(sandbox).getString("phone_pinned","").equals(phoneDockPins),"Main Taskbar pin helper modified PhoneDock pins");
                check(icons((View)get(bar,"panel"),barLabel)==5,"Main Taskbar did not rebind its own pin removal");
                String mainPins=Launches.prefs(sandbox).getString("phone_taskbar_pinned","");
                Launches.toggleDockPin(mainContext,dockPin);dock.rebuild();
                check(Launches.pins(mainContext).size()==1&&Launches.prefs(sandbox).getString("phone_taskbar_pinned","").equals(mainPins),"PhoneDock pin helper modified main Taskbar pins");
            });
            await(this::laidOut,"Rebuilt main overlays did not lay out");
            main(()->{switchFlags(true,true);bar.refreshArea();dock.relayout();validate();switchFlags(true,false);bar.refreshArea();dock.relayout();validate();});
            main(this::taskMenus);
            scales();
            PhoneRunningTasks feed=(PhoneRunningTasks)get(bar,"running");View removedPanel=(View)get(bar,"panel");
            Bridge.Reply[] late={null};
            main(()->{
                barBackend.hold=true;feed.refresh();late[0]=barBackend.pending;check(late[0]!=null,"No pending synthetic reply for hide test");
                switchFlags(false,true);bar.close();dock.taskbarReady(false);check(!bar.ready()&&get(bar,"panel")==null,"Hidden main Taskbar retained its logical overlay");
                check(feed.tasks().isEmpty()&&barBackend.observed==barBackend.removed,"Hidden main Taskbar retained task feed/observer");
                int requests=barBackend.requests;late[0].done(barBackend.payload,null);barBackend.pending=null;feed.refresh();
                check(feed.tasks().isEmpty()&&barBackend.requests==requests&&get(bar,"panel")==null,"Late reply resurrected hidden main Taskbar");
                dock.rebuild();WorkArea mainArea=area();check(mainArea.application.equals(mainArea.usable)&&mainArea.dockAvailable.equals(mainArea.usable),"External-only Taskbar reserved a phantom main strip");
                check(dock.ready()&&get(dock,"panel")!=null,"Disabling main Taskbar discarded PhoneDock");
                check(descriptions((View)get(dock,"panel"),actual.getString(R.string.ui_app_menu))==1,"PhoneDock failed to restore Start without main Taskbar");
            });
            // Window destruction may be queued by ViewRootImpl even for removeViewImmediate.
            // Require actual detachment after returning to the main loop; never remove it here.
            await(()->!removedPanel.isAttachedToWindow()&&removedPanel.getParent()==null,"Hidden main Taskbar overlay did not detach after close (bounded wait)");
            int requests=barBackend.requests;Thread.sleep(1200);test.waitForIdleSync();
            check(barBackend.requests==requests,"Hidden main Taskbar kept polling");
            main(()->check(!removedPanel.isAttachedToWindow()&&get(bar,"panel")==null&&feed.tasks().isEmpty(),"Late reply resurrected detached main Taskbar"));
            main(()->{barBackend.hold=false;switchFlags(true,false);bar=new PhoneTaskbar(sandbox,()->{},barBackend,this::area);dock.taskbarReady(bar.ready());dock.rebuild();});
            await(this::laidOut,"Main Taskbar did not recreate after toggle");main(this::validate);
            check(barBackend.operations==2&&dockBackend.operations==0,"Unexpected operation outside the two synthetic Taskbar close-menu checks");
        }finally{
            main(()->{
                Throwable failure=null;
                try{if(bar!=null)bar.close();}catch(Throwable t){failure=t;}
                try{if(dock!=null)dock.close();}catch(Throwable t){if(failure==null)failure=t;else failure.addSuppressed(t);}
                if(fixtureSettings!=null)fixtureSettings.close();if(fixtureTasks!=null)fixtureTasks.closeOwner();for(String name:files)actual.deleteSharedPreferences(name);
                if(failure!=null)throw new AssertionError("Main fixture cleanup failed",failure);
            });
            test.waitForIdleSync();
        }
        check(barBackend.observed==barBackend.removed&&dockBackend.observed==dockBackend.removed,"Main fixture leaked task observers");
    }
    private boolean laidOut(){
        if(bar==null||dock==null)return false;View panel=(View)get(bar,"panel"),sidebar=(View)get(dock,"panel");
        return panel!=null&&panel.isAttachedToWindow()&&panel.getWidth()>0&&panel.getHeight()>0&&sidebar!=null&&sidebar.isAttachedToWindow()&&sidebar.getWidth()>1&&sidebar.getHeight()>1;
    }
    private void switchFlags(boolean mainEnabled,boolean externalEnabled){
        SharedPreferences p=Launches.prefs(sandbox);check(p.edit().putBoolean("phone_taskbar",mainEnabled).putBoolean("desktop_taskbar",externalEnabled).commit(),"Main/external fixture switch failed");
        check(p.getBoolean("phone_taskbar",false)==mainEnabled&&p.getBoolean("desktop_taskbar",false)==externalEnabled,"Main/external switches are not independent");
    }
    private static int scaled(Context c,int dp,String key){return Math.round(c.getResources().getDisplayMetrics().density*dp*Launches.prefs(c).getInt(key,100)/100f);}
    private void taskMenus(){
        PopupMenu[] presented={null};PhoneTaskMenu captured=new PhoneTaskMenu(mainContext,popup->presented[0]=popup);
        try{
            Field menu=PhoneTaskbar.class.getDeclaredField("taskMenu");menu.setAccessible(true);((PhoneTaskMenu)menu.get(bar)).close();menu.set(bar,captured);
            JSONObject payload=new JSONObject(barBackend.payload);payload.getJSONArray("tasks").put(new JSONObject().put("id",990200).put("component",barPin).put("mode",1).put("visible",false).put("focused",false).put("left",0).put("top",0).put("right",300).put("bottom",600));barBackend.payload=payload.toString();
            ((PhoneRunningTasks)get(bar,"running")).refresh();barBackend.captureOperations=true;
            View pin=((LinearLayout)get(bar,"pinnedTasks")).getChildAt(0);
            check(pin.performLongClick()&&presented[0]!=null,"Live Taskbar pin cannot open its task menu");
            check(presented[0].getMenu().findItem(PhoneTaskMenu.CLOSE)!=null&&presented[0].getMenu().findItem(PhoneTaskMenu.UNPIN)!=null,"Live pin menu lacks task close/unpin");
            presented[0].getMenu().performIdentifierAction(PhoneTaskMenu.CLOSE,0);
            check(barBackend.operations==1&&barBackend.operated.id==990200&&barBackend.operated.component.equals(barPin)&&"close".equals(barBackend.action),"Pinned Taskbar close lost exact synthetic task identity");
            View active=((LinearLayout)get(bar,"activeTasks")).getChildAt(0);presented[0]=null;
            check(active.performLongClick()&&presented[0]!=null,"Active Taskbar icon cannot open its task menu");
            check(presented[0].getMenu().findItem(PhoneTaskMenu.CLOSE)!=null&&presented[0].getMenu().findItem(PhoneTaskMenu.UNPIN)==null,"Active task menu lost close or incorrectly became a pin menu");
            presented[0].getMenu().performIdentifierAction(PhoneTaskMenu.CLOSE,0);
            check(barBackend.operations==2&&barBackend.operated.id==990000&&barBackend.operated.component.equals(dockPin)&&"close".equals(barBackend.action),"Active Taskbar close lost exact synthetic task identity");
        }catch(ReflectiveOperationException|org.json.JSONException e){throw new AssertionError(e);}
        finally{barBackend.captureOperations=false;captured.close();}
    }
    private void scales()throws Exception{
        main(()->{check(Launches.prefs(sandbox).edit().putString("phone_pinned",String.join("\n",java.util.Collections.nCopies(24,dockPin))).commit(),"Main scale fixture pins failed");barBackend.payload=snapshot(dockPin,32);((PhoneRunningTasks)get(bar,"running")).refresh();});
        for(int[] pair:new int[][]{{50,100},{200,100},{100,100},{100,50},{100,200}}){
            main(()->{check(Launches.prefs(sandbox).edit().putInt("phone_dock_scale",pair[0]).putInt("phone_taskbar_scale",pair[1]).commit(),"Main scale fixture write failed");bar.rebuild();dock.rebuild();});
            await(this::laidOut,"Scaled main overlays did not lay out");test.waitForIdleSync();
            main(()->{
                validate();HorizontalScrollView panel=(HorizontalScrollView)get(bar,"panel");LinearLayout row=(LinearLayout)get(bar,"row");
                check(row.getWidth()>panel.getWidth()&&panel.canScrollHorizontally(1),"Scaled main Taskbar lost whole-axis scroll");
                Rect visible=new Rect();panel.scrollTo(row.getWidth(),0);check(row.getChildAt(row.getChildCount()-1).getGlobalVisibleRect(visible),"Scaled main Taskbar last control unreachable");panel.scrollTo(0,0);check(row.getChildAt(0).getGlobalVisibleRect(visible),"Scaled main Taskbar Start unreachable");
                ScrollView sidebar=(ScrollView)get(dock,"panel");ViewGroup column=(ViewGroup)sidebar.getChildAt(0);
                check(column.getHeight()>sidebar.getHeight()&&sidebar.canScrollVertically(1),"Scaled PhoneDock lost pin/Home scroll");
                check(column.getChildAt(0).getHeight()==scaled(sidebar.getContext(),52,"phone_dock_scale"),"PhoneDock pins did not scale independently");
                sidebar.scrollTo(0,column.getHeight());check(column.getChildAt(column.getChildCount()-1).getGlobalVisibleRect(visible),"Scaled PhoneDock Home unreachable");sidebar.scrollTo(0,0);check(column.getChildAt(0).getGlobalVisibleRect(visible),"Scaled PhoneDock first pin unreachable");
            });
        }
    }
    private void validate(){
        View panel=(View)get(bar,"panel"),sidebar=(View)get(dock,"panel");WorkArea a=area();
        check(panel.getDisplay().getDisplayId()==0&&sidebar.getDisplay().getDisplayId()==0,"Main Taskbar/Dock followed the external output");
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)panel.getLayoutParams(),s=(WindowManager.LayoutParams)sidebar.getLayoutParams();
        int thickness=scaled(panel.getContext(),52,"phone_taskbar_scale"),reserve=scaled(panel.getContext(),60,"phone_taskbar_scale");
        Rect expected=new Rect(a.usable);expected.bottom=Math.max(expected.top+1,expected.bottom-reserve);
        check(a.dockAvailable.equals(expected)&&a.application.equals(expected),"Main Taskbar does not independently reserve its 60dp strip");
        check(p.x==a.usable.left&&p.y==Math.max(a.usable.top,a.usable.bottom-scaled(panel.getContext(),56,"phone_taskbar_scale"))&&p.width==a.usable.width()&&p.height==Math.min(thickness,a.usable.height()),"Incorrect scaled main Taskbar window geometry");
        check(panel.getWidth()==p.width&&panel.getHeight()==p.height,"Main Taskbar requested/attached dimensions differ");
        int[] location=new int[2];panel.getLocationOnScreen(location);check(location[0]==p.x&&location[1]==p.y,"Main Taskbar actual screen position differs from geometry");
        Rect dockBounds=new Rect(s.x,s.y,s.x+s.width,s.y+s.height),barBounds=new Rect(p.x,p.y,p.x+p.width,p.y+p.height);
        check(a.dockAvailable.contains(dockBounds)&&!Rect.intersects(dockBounds,barBounds),"PhoneDock overlaps main Taskbar reserved strip");
        check(s.width==Math.min(scaled(sidebar.getContext(),76,"phone_dock_scale"),a.dockAvailable.width())&&s.height==Math.min(scaled(sidebar.getContext(),500,"phone_dock_scale"),a.dockAvailable.height()),"PhoneDock scale/available bounds mismatch");
        check(descriptions(panel,actual.getString(R.string.ui_app_menu))==1&&descriptions(sidebar,actual.getString(R.string.ui_app_menu))==0,"Start duplicated or lost when main Taskbar enabled");
        View first=((LinearLayout)get(bar,"row")).getChildAt(0);check(first.getHeight()==scaled(panel.getContext(),44,"phone_taskbar_scale"),"Main Taskbar controls did not scale independently");
        for(View handle:dock.handles){WindowManager.LayoutParams h=(WindowManager.LayoutParams)handle.getLayoutParams();check(a.dockAvailable.contains(new Rect(h.x,h.y,h.x+h.width,h.y+h.height)),"PhoneDock edge handle intrudes into main Taskbar");}
    }
    private static int icons(View view,String label){int count=label.contentEquals(view.getTooltipText()==null?"":view.getTooltipText())?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=icons(((ViewGroup)view).getChildAt(i),label);return count;}
    private static int descriptions(View view,String label){int count=view.getVisibility()==View.VISIBLE&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=descriptions(((ViewGroup)view).getChildAt(i),label);return count;}
    private static String snapshot(String component,int count){
        try{JSONArray tasks=new JSONArray();for(int i=0;i<count;i++)tasks.put(new JSONObject().put("id",990000+i).put("component",component).put("mode",1).put("visible",i==0).put("focused",i==0).put("left",0).put("top",0).put("right",300).put("bottom",600));return new JSONObject().put("tasks",tasks).toString();}catch(org.json.JSONException e){throw new AssertionError(e);}
    }
    private static final class FakeBackend implements PhoneRunningTasks.Backend {
        String payload;boolean hold,captureOperations;int observed,removed,requests,operations;Bridge.Reply pending;Runnable observer;TaskSnapshot.Task operated;String action;
        public boolean ready(){return true;}
        public void observe(Runnable listener){check(observer==null,"Duplicate main fixture feed observer");observer=listener;observed++;}
        public void remove(Runnable listener){check(observer==listener,"Wrong main fixture feed observer removed");observer=null;removed++;}
        public void snapshot(Bridge.Reply reply){requests++;if(hold){check(pending==null,"Overlapping main fixture snapshot");pending=reply;}else reply.done(payload,null);}
        public void focus(TaskSnapshot.Task task,Bridge.Reply reply){operations++;throw new AssertionError("Main fixture must not focus tasks");}
        public void operation(TaskSnapshot.Task task,String action,Rect bounds,Bridge.Reply reply){operations++;check(captureOperations,"Main fixture must not operate tasks outside captured synthetic menu checks");operated=task;this.action=action;reply.done("OK",null);}
    }
    private final class Sandbox extends ContextWrapper implements ShellSettings.Provider,TaskState.Provider {
        public ShellSettings shellSettings(){if(fixtureSettings==null)fixtureSettings=ShellSettings.isolated(getSharedPreferences("desktop",0));return fixtureSettings;}
        public TaskState taskState(){if(fixtureTasks==null)fixtureTasks=TaskState.isolated(this);return fixtureTasks;}
        Sandbox(Context base){super(base);}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){String isolated=prefix+name;files.add(isolated);return actual.getSharedPreferences(isolated,mode);}
        @Override public Context createDisplayContext(Display display){return new Sandbox(super.createDisplayContext(display));}
        @Override public Context createWindowContext(int type,Bundle options){return new Sandbox(super.createWindowContext(type,options));}
        @Override public void startActivity(Intent intent){throw new AssertionError("Main fixture must not launch activities");}
        @Override public void startActivity(Intent intent,Bundle options){throw new AssertionError("Main fixture must not launch activities");}
        @Override public ComponentName startService(Intent intent){throw new AssertionError("Main fixture must not start services");}
        @Override public ComponentName startForegroundService(Intent intent){throw new AssertionError("Main fixture must not start services");}
        @Override public boolean stopService(Intent intent){throw new AssertionError("Main fixture must not stop services");}
    }
}
