package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.layout.DockPlacement;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/** Real overlay layout in an owned display; no service lifecycle or task/app actions. */
final class DockLayoutChecks {
    private final Instrumentation test;
    private final Context actual;
    private final String prefix="dock_layout_fixture_"+java.util.UUID.randomUUID()+"_";
    private final Set<String> preferenceFiles=new HashSet<>();
    private SelectedOutputSurface fixture;
    private ShellSettings fixtureSettings;
    private TaskState fixtureTasks;
    private PhoneNavigationOwner fixturePhone;
    private FixtureContext sandbox;
    private VirtualDisplay display;
    private ImageReader reader;
    private int displayId=-1;
    private String taskbarPin,dockPin,taskbarLabel,dockLabel;

    DockLayoutChecks(Instrumentation test){this.test=test;actual=test.getTargetContext();}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static Field field(Class<?> owner,String name)throws ReflectiveOperationException{
        Field value=owner.getDeclaredField(name);value.setAccessible(true);return value;
    }
    private Object get(String name){try{return field(SelectedOutputSurface.class,name).get(fixture);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private Object dockField(String name){try{return field(DesktopDock.class,name).get(get("desktopDock"));}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private void invoke(String name){try{Method m=SelectedOutputSurface.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(fixture);}catch(ReflectiveOperationException e){throw new AssertionError(name,e);}}
    private void main(Runnable action){
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private void await(BooleanSupplier condition,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<until){boolean[] done={false};main(()->done[0]=condition.getAsBoolean());if(done[0])return;Thread.sleep(50);}
        throw new AssertionError(message);
    }

    enum Scope { NAVIGATION, DISPLAY_SCALE, PIP, TASK_CLOSE }
    /** Resume only the already-selected session after instrumentation replaces its process. */
    static void prepare(Instrumentation test)throws Exception{
        Context context=test.getTargetContext();SharedPreferences prefs=Launches.prefs(context);
        int selected=TaskState.of(context).enabled(context)?TaskState.of(context).target(context):prefs.getInt("preferred_display",-1);
        check(selected>=0&&(!prefs.contains("active_display")||prefs.getInt("active_display",-1)==selected),"Existing selected workspace must be stable; fixture never changes it");
        Display target=context.getSystemService(DisplayManager.class).getDisplay(selected);
        check(target!=null&&target.isValid()&&(target.getFlags()&Display.FLAG_PRIVATE)==0,"Selected workspace disconnected; fixture does not fall back");
        check(prefs.getBoolean("enabled",false),"Existing session must already be enabled");
        test.runOnMainSync(()->{
            check(Bridge.get(context).authorized(),"Bridge must already be authorized");
            // active_display is a process-owned cache, cleared during recreation.
            // preferred_display may still be 0 while automatic workspace handoff
            // targets an external screen. Keep both saved choices untouched.
            context.startForegroundService(new Intent(context,DockService.class).putExtra("show_home",false));
            Bridge.get(context).connect();
        });
        long until=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<until){
            if(Bridge.get(context).ready()&&ShellRuntime.running()&&ShellRuntime.selectedDisplay()==selected)return;
            Thread.sleep(100);
        }
        throw new AssertionError("Existing selected session did not resume");
    }
    void run(Scope scope)throws Exception{
        SharedPreferences production=Launches.prefs(actual);
        check(production.getBoolean("enabled",false),"Requires an existing enabled session; fixture never changes its selection");
        int selected=production.getInt("active_display",-1);
        check(selected>=0,"Requires stable existing workspace; fixture never changes it");
        check(Settings.canDrawOverlays(actual),"Existing overlay permission is required");
        long live=ShellRuntime.snapshot().generation;
        boolean alive=ShellRuntime.running();
        check(alive&&ShellRuntime.selectedDisplay()==selected,"Existing service must retain selected display");
        boolean autoPresent=production.contains("workspace_auto"),auto=production.getBoolean("workspace_auto",false);
        // A public fixture display must not trigger the real service's auto-handoff.
        try{
            main(()->check(production.edit().putBoolean("workspace_auto",false).commit(),"Could not suspend automatic handoff"));
            main(()->{
                reader=imageReader(1200,900);
                display=actual.getSystemService(DisplayManager.class).createVirtualDisplay("StellaShell owned dock layout fixture",1200,900,160,reader.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                check(display!=null,"Public fixture display unavailable");displayId=display.getDisplay().getDisplayId();
                check(displayId>0&&(display.getDisplay().getFlags()&Display.FLAG_PRIVATE)==0,"Fixture must be an owned non-primary public display");
                sandbox=new FixtureContext(actual);SharedPreferences p=Launches.prefs(sandbox);
                taskbarPin=new ComponentName(test.getContext().getPackageName(),"net.fuyumori.stellashell.LauncherEntryFirst").flattenToString();
                dockPin=new ComponentName(actual,SetupActivity.class).flattenToString();
                try{taskbarLabel=actual.getPackageManager().getApplicationInfo(test.getContext().getPackageName(),0).loadLabel(actual.getPackageManager()).toString();dockLabel=actual.getApplicationInfo().loadLabel(actual.getPackageManager()).toString();}catch(android.content.pm.PackageManager.NameNotFoundException e){throw new AssertionError(e);}
                check(!taskbarLabel.equals(dockLabel),"Fixture packages need distinguishable labels");
                check(p.edit().putBoolean("phone_profile_initialized",true).putBoolean("primary_mode",false).putBoolean("enabled",false).putInt("active_display",displayId).putInt("workspace_display",displayId).putString("shell_layout","desktop").putString("pinned",pins(taskbarPin,6)).putString("dock_pinned",pins(dockPin,8)).commit(),"Fixture preferences failed");
                fixturePhone=new PhoneNavigationOwner(sandbox,()->{},()->{});
                fixture=new SelectedOutputSurface(sandbox,displayId,fixturePhone,false);
                check(fixtureTasks.snapshot().tasks.isEmpty(),"Layout fixture must never poll OS tasks");
            });
            test.waitForIdleSync();
            if(scope==Scope.NAVIGATION){
            for(String edge:new String[]{"left","right","top","bottom"}){
                int x="left".equals(edge)?0:"right".equals(edge)?100:50;
                int y="top".equals(edge)?0:"bottom".equals(edge)?100:50;
                mount(edge,x,y,true,true);validate(edge,x,y,true,true,false);
                mount(edge,31,43,true,true);validate(edge,31,43,true,true,false);
                mount(edge,x,y,false,true);validate(edge,x,y,false,true,false);
            }
            independentSwitches();independentPins();
            resize(320,240);
            for(String edge:new String[]{"left","right","top","bottom"}){
                int x="left".equals(edge)?0:"right".equals(edge)?100:50;
                int y="top".equals(edge)?0:"bottom".equals(edge)?100:50;
                mount(edge,x,y,true,true);validate(edge,x,y,true,true,true);
            }
            resize(280,900);
            mount("bottom",50,100,true,true);validate("bottom",50,100,true,true,true);
            mount("right",100,50,true,true);validate("right",100,50,true,true,false);
            scales();
            new PhoneTaskbarChecks(test).run(displayId);
            homeByPresentation();
            }
            resize(1280,720);
            if(scope==Scope.DISPLAY_SCALE)new DisplayScalingChecks(test).run(displayId);
            if(scope==Scope.PIP||scope==Scope.TASK_CLOSE)new PipRestoreChecks(test).run(displayId,scope==Scope.PIP);
            check(ShellRuntime.snapshot().generation==live&&ShellRuntime.running()==alive,"Fixture altered live service identity/lifecycle");
        }finally{
            try{
                try{main(this::cleanup);}finally{test.waitForIdleSync();}
            }finally{
                main(()->{
                    SharedPreferences.Editor restore=production.edit();if(autoPresent)restore.putBoolean("workspace_auto",auto);else restore.remove("workspace_auto");
                    check(restore.commit(),"Failed to restore automatic-handoff preference");
                    for(String name:preferenceFiles)actual.deleteSharedPreferences(name);
                });
            }
        }
        check(production.contains("workspace_auto")==autoPresent&&production.getBoolean("workspace_auto",false)==auto,"Automatic-handoff preference not restored exactly");
        check(ShellRuntime.snapshot().generation==live&&ShellRuntime.running()==alive,"Live service identity/lifecycle changed");
    }

    private ImageReader imageReader(int width,int height){
        ImageReader value=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        value.setOnImageAvailableListener(source->{try(Image discarded=source.acquireLatestImage()){/* Drain only; no pixels read or saved. */}catch(IllegalStateException closed){/* A queued callback may outlive a resized surface. */}},new Handler(Looper.getMainLooper()));return value;
    }
    private void cleanup(){
        Throwable failure=null;
        Runnable[] actions={
            ()->{if(fixture!=null)fixture.close();},
            ()->{if(fixturePhone!=null)fixturePhone.close();},
            ()->{if(fixtureTasks!=null)fixtureTasks.closeOwner();},
            ()->{if(fixtureSettings!=null)fixtureSettings.close();},
            ()->{if(display!=null){display.release();display=null;}},
            ()->{if(reader!=null){reader.close();reader=null;}},
            ()->{if(displayId>0)WorkArea.remove(displayId);}
        };
        for(Runnable action:actions)try{action.run();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw new AssertionError("Owned dock fixture cleanup failed",failure);
    }
    private void retire(){invoke("closeAreaObserver");invoke("removeDock");}
    private void resize(int width,int height)throws Exception{
        main(()->{retire();ImageReader previous=reader;reader=imageReader(width,height);display.setSurface(reader.getSurface());display.resize(width,height,160);previous.close();});
        await(()->{android.graphics.Point size=new android.graphics.Point();display.getDisplay().getRealSize(size);return size.x==width&&size.y==height;},"Fixture resize did not settle");
    }
    private static String pins(String component,int count){return String.join("\n",java.util.Collections.nCopies(count,component));}
    private void independentSwitches()throws Exception{
        mount("bottom",50,100,true,true);
        Object launcher=get("desktopDock");
        preferenceEvent("desktop_taskbar",false);awaitViews(true,true);validate("bottom",50,100,true,false,false);
        check(get("desktopDock")==launcher,"Stale Taskbar OFF changed the independent Dock");
        preferenceEvent("desktop_taskbar",true);awaitViews(true,true);validate("bottom",50,100,true,true,false);
        check(get("desktopDock")==launcher,"Enabling Taskbar replaced the independent Dock");
        View taskbar=(View)get("dock");
        preferenceEvent("desktop_dock",false);awaitViews(false,true);validate("bottom",50,100,false,true,false);
        check(get("dock")==taskbar,"Disabling Dock replaced the legacy Taskbar");
        preferenceEvent("desktop_dock",true);awaitViews(true,true);validate("bottom",50,100,true,true,false);
        check(get("dock")==taskbar,"Enabling Dock replaced the legacy Taskbar");
        mount("bottom",50,100,false,false);validate("bottom",50,100,false,false,false);
    }
    private void scales()throws Exception{
        main(()->check(Launches.prefs(sandbox).edit().putString("dock_pinned",pins(dockPin,24)).commit(),"Scale fixture pins failed"));
        for(String layout:new String[]{"desktop","compact"}){
        boolean compact="compact".equals(layout);
        main(()->check(Launches.prefs(sandbox).edit().putString("shell_layout",layout).commit(),"Presentation scale fixture write failed"));
        for(int[] scales:new int[][]{{50,100},{200,100},{100,100},{100,50},{100,200}}){
            main(()->check(Launches.prefs(sandbox).edit().putInt("desktop_dock_scale",scales[0]).putInt("desktop_taskbar_scale",scales[1]).commit(),"External scale fixture write failed"));
            resize(1200,900);mount("bottom",50,100,true,false);validate("bottom",50,100,true,false,false,compact);
            resize(320,240);
            for(String edge:new String[]{"left","right","top","bottom"}){
                int x="left".equals(edge)?0:"right".equals(edge)?100:50,y="top".equals(edge)?0:"bottom".equals(edge)?100:50;
                mount(edge,x,y,true,false);validate(edge,x,y,true,false,true,compact);
            }
        }
        }
        main(()->check(Launches.prefs(sandbox).edit().putString("shell_layout","desktop").commit(),"Presentation scale fixture restore failed"));
    }
    private void homeByPresentation()throws Exception{
        resize(1200,900);
        main(()->check(Launches.prefs(sandbox).edit().putString("shell_layout","desktop").commit(),"Presentation fixture setup failed"));
        mount("bottom",50,100,true,false);
        for(String layout:new String[]{"compact","desktop","compact","auto"}){
            boolean compact="compact".equals(layout);
            main(()->{SharedPreferences prefs=Launches.prefs(sandbox);check(prefs.edit().putString("shell_layout",layout).commit(),"Presentation fixture write failed");fixture.settingsChanged(java.util.EnumSet.of(ShellSettings.Change.SHELL_LAYOUT));});
            awaitViews(true,false);validate("bottom",50,100,true,false,false,compact);
            View bar=(View)get("dock");
            main(()->{
                check((Boolean)get("renderedCompact")==compact,"Rendered presentation baseline is stale");
                for(int callback=0;callback<4;callback++){invoke("areaChanged");check(get("dock")==bar,"Unchanged presentation callback rebuilt the required Taskbar");}
            });
            Thread.sleep(200);test.waitForIdleSync();check(get("dock")==bar,"Observer entered a presentation rebuild loop");
        }
        // Auto can also resolve Compact on a narrow screen. Its Taskbar must
        // stay visible/scrollable, not become the former hidden 1px edge panel.
        resize(320,240);mount("bottom",50,100,true,false);validate("bottom",50,100,true,false,true,true);
        main(()->check(Launches.prefs(sandbox).edit().putString("shell_layout","desktop").commit(),"Presentation fixture restore failed"));
    }
    private static int scaled(Context c,int dp,String key){return Math.round(c.getResources().getDisplayMetrics().density*dp*Launches.prefs(c).getInt(key,100)/100f);}
    private void preferenceEvent(String key,boolean value){
        main(()->{SharedPreferences p=Launches.prefs(sandbox);check(p.edit().putBoolean(key,value).commit(),"Fixture switch write failed");if("desktop_dock".equals(key))fixture.settingsChanged(java.util.EnumSet.of(ShellSettings.Change.EXTERNAL_DOCK_ENABLED));});
    }
    private void independentPins()throws Exception{
        main(()->Launches.toggleDockPin(sandbox.createDisplayContext(display.getDisplay()),new ComponentName(actual,HomeActivity.class).flattenToString()));
        String independent=Launches.prefs(sandbox).getString("dock_pinned","");
        check(independent.split("\n").length==9,"Independent Dock cannot hold more than legacy six pins");
        mount("bottom",50,100,true,true);validate("bottom",50,100,true,true,false);
        check(Launches.prefs(sandbox).getString("pinned","").equals(pins(taskbarPin,6)),"Dock pins modified legacy six slots");
        main(()->Launches.toggleTaskbarPin(sandbox.createDisplayContext(display.getDisplay()),taskbarPin));
        mount("bottom",50,100,true,true);validate("bottom",50,100,true,true,false);
        check(Launches.prefs(sandbox).getString("pinned","").equals(pins(taskbarPin,5)),"Legacy pin helper did not remove its own pin");
        check(Launches.prefs(sandbox).getString("dock_pinned","").equals(independent),"Taskbar pins modified independent Dock pins");
        main(()->check(Launches.prefs(sandbox).edit().putString("pinned",pins(taskbarPin,6)).putString("dock_pinned",pins(dockPin,8)).commit(),"Pin fixture restore failed"));
    }
    private void mount(String edge,int x,int y,boolean dockEnabled,boolean taskbarEnabled)throws Exception{
        main(()->{
            retire();check(Launches.prefs(sandbox).edit().putBoolean("desktop_dock",dockEnabled).putBoolean("desktop_taskbar",taskbarEnabled).putString("dock_edge",edge).putInt("dock_x",x).putInt("dock_y",y).commit(),"Fixture dock preference write failed");
            invoke("attachDock");check(get("dock")!=null,"Overlay attachment failed: "+Launches.prefs(sandbox).getString("last_error","unknown"));
        });
        awaitViews(dockEnabled,taskbarEnabled);
    }
    private void awaitViews(boolean dockEnabled,boolean taskbarEnabled)throws Exception{
        await(()->{
            View taskbar=(View)get("dock");
            if(taskbar==null||!taskbar.isAttachedToWindow()||taskbar.getWidth()<=0||taskbar.getHeight()<=0)return false;
            if(!dockEnabled)return get("desktopDock")==null;
            if(get("desktopDock")==null)return false;View dock=(View)dockField("viewport");
            return dock!=null&&dock.isAttachedToWindow()&&dock.getWidth()>0&&dock.getHeight()>0;
        },"Owned Dock/Taskbar overlays did not lay out");
        // Let the observer's initial area callback settle before checking attached geometry.
        Thread.sleep(180);test.waitForIdleSync();
    }
    private static int icons(View view,String label){
        int count=label.contentEquals(view.getTooltipText()==null?"":view.getTooltipText())?1:0;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=icons(((ViewGroup)view).getChildAt(i),label);
        return count;
    }
    private static boolean hasClock(View view){
        if(view instanceof TextClock)return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasClock(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    private void validate(String edge,int x,int y,boolean dockEnabled,boolean taskbarEnabled,boolean overflow){
        validate(edge,x,y,dockEnabled,taskbarEnabled,overflow,false);
    }
    private void validate(String edge,int x,int y,boolean dockEnabled,boolean taskbarEnabled,boolean overflow,boolean compact){
        main(()->{
            View taskbar=(View)get("dock");WorkArea area=WorkArea.get(sandbox,displayId);
            check(taskbar.getDisplay().getDisplayId()==displayId,"Taskbar escaped its owned display");
            check(get("tasks")==null,"Dock fixture created a task session");
            check(area.compact==compact,"Fixture presentation differs from its explicit/automatic selection");
            Rect available=new Rect(area.usable);available.bottom=Math.max(available.top+1,available.bottom-scaled(taskbar.getContext(),60,"desktop_taskbar_scale"));
            check(area.dockAvailable.equals(available),"Taskbar reservation missing or removed by Dock: "+area.dockAvailable+" expected "+available);
            Rect expected=new Rect(available);boolean vertical=DockPlacement.vertical(edge);
            int thickness=scaled(taskbar.getContext(),52,"desktop_dock_scale"),taskbarThickness=scaled(taskbar.getContext(),52,"desktop_taskbar_scale");
            check(area.application.equals(expected),"Dock left an unwanted workspace strip for "+edge+" Dock="+dockEnabled+" Taskbar="+taskbarEnabled+": "+area.application+" expected "+expected);
            Rect content=new Rect(expected);content.top=Math.min(content.bottom-1,content.top+area.caption);
            check(area.content.equals(content),"Caption content geometry no longer matches dock reservation");
            WindowManager.LayoutParams tp=(WindowManager.LayoutParams)taskbar.getLayoutParams();
            {
                check(taskbar.getVisibility()==View.VISIBLE&&taskbar instanceof HorizontalScrollView,"Required external Taskbar missing (including stale saved OFF)");
                check(tp.width==area.usable.width()&&tp.height==Math.min(taskbarThickness,area.usable.height())&&tp.x==area.usable.left&&tp.y==Math.max(area.usable.top,area.usable.bottom-scaled(taskbar.getContext(),56,"desktop_taskbar_scale")),"Scaled Taskbar geometry changed with Dock");
                check(taskbar.getWidth()==tp.width&&taskbar.getHeight()==tp.height,"Taskbar requested/attached dimensions differ");
                check(icons(taskbar,taskbarLabel)==Launches.taskbarPins(taskbar.getContext()).size()&&icons(taskbar,dockLabel)==0,"Taskbar displayed independent Dock pins or lost legacy pins");
                check(hasClock(taskbar)&&get("batteryText")!=null,"Traditional Taskbar lost status items");
                check(descriptions(taskbar,actual.getString(R.string.ui_app_menu))==1,"Required Taskbar Start missing");
                check(descriptions(taskbar,actual.getString(R.string.ui_show_desktop))==1,"Taskbar Home was removed with Dock Home");
                HorizontalScrollView scroll=(HorizontalScrollView)taskbar;ViewGroup actions=(ViewGroup)scroll.getChildAt(0);
                check(actions.getChildAt(0).getHeight()==scaled(taskbar.getContext(),44,"desktop_taskbar_scale"),"Taskbar icons did not scale independently");
                if(actions.getWidth()>scroll.getWidth()){
                    check(scroll.canScrollHorizontally(1),"Scaled external Taskbar controls cannot scroll");scroll.scrollTo(actions.getWidth(),0);
                    Rect visible=new Rect();check(actions.getChildAt(actions.getChildCount()-1).getGlobalVisibleRect(visible),"Scaled external Taskbar last action unreachable");scroll.scrollTo(0,0);
                    check(actions.getChildAt(0).getGlobalVisibleRect(visible),"Scaled external Taskbar Start unreachable");
                }
            }
            check(get("menu")!=null&&get("chrome")!=null,"OFF discarded the session's menu/chrome");
            if(!dockEnabled){check(get("desktopDock")==null,"Dock OFF retains its independent window");return;}
            DesktopDock launcher=(DesktopDock)get("desktopDock");View dock=(View)dockField("viewport");
            check(dock.getDisplay().getDisplayId()==displayId,"Dock escaped its owned display");
            WindowManager.LayoutParams p=(WindowManager.LayoutParams)dock.getLayoutParams();
            check(vertical?dock instanceof ScrollView:dock instanceof HorizontalScrollView,"Whole dock axis is not scrollable for "+edge);
            LinearLayout row=(LinearLayout)dockField("entries");
            check(row!=null&&icons(dock,dockLabel)==Launches.dockPins(dock.getContext()).size()&&icons(dock,taskbarLabel)==0,"Dock displayed legacy pins or capped/lost its independent pins");
            check(!hasClock(dock)&&icons(dock,actual.getString(R.string.menu_stella_settings))==0&&icons(dock,actual.getString(R.string.screenshot_take))==0,"Icon-centric Dock includes legacy Taskbar status/actions");
            check(row.getOrientation()==(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL),"Incorrect dock axis");
            int wantedW=vertical?thickness:row.getWidth(),wantedH=vertical?row.getHeight():thickness;
            int w=Math.min(wantedW,available.width()),h=Math.min(wantedH,available.height());
            int px=available.left+Math.round((available.width()-w)*x/100f),py=available.top+Math.round((available.height()-h)*y/100f);
            check(p.x==px&&p.y==py&&p.width==w&&p.height==h,"Unexpected dock position/size for "+edge+": "+p.x+","+p.y+" "+p.width+"x"+p.height+" expected "+px+","+py+" "+w+"x"+h);
            check(dock.getWidth()==p.width&&dock.getHeight()==p.height,"Actual attached overlay dimensions differ from requested geometry");
            int[] location=new int[2];dock.getLocationOnScreen(location);
            check(location[0]==p.x&&location[1]==p.y,"Actual overlay position differs from WorkArea coordinates");
            Rect dockBounds=new Rect(p.x,p.y,p.x+p.width,p.y+p.height);
            check(available.contains(dockBounds),"Dock overflows its Taskbar-free bounds");
            int[] navigation=launcher.bounds();check(navigation!=null&&dockBounds.equals(new Rect(navigation[0],navigation[1],navigation[2],navigation[3])),"Independent Dock navigation bounds differ from actual window");
            check(!Rect.intersects(dockBounds,new Rect(tp.x,tp.y,tp.x+tp.width,tp.y+tp.height)),"Dock overlaps the required Taskbar");
            check(vertical?p.width==thickness:p.height==thickness,"Dock thickness/reservation mismatch");
            View first=row.getChildAt(0),last=row.getChildAt(row.getChildCount()-1);
            check(descriptions(dock,actual.getString(R.string.ui_app_menu))==0,"External Dock duplicates required Taskbar Start");
            check(first.getHeight()==scaled(dock.getContext(),44,"desktop_dock_scale"),"Dock icons did not scale independently");
            check(descriptions(dock,actual.getString(R.string.ui_show_desktop))==(compact?1:0),"Dock Home does not follow presentation while Taskbar stays visible");
            if(overflow){
                check(vertical?row.getHeight()>dock.getHeight():row.getWidth()>dock.getWidth(),"Short span did not overflow the content axis");
                check(vertical?dock.canScrollVertically(1):dock.canScrollHorizontally(1),"Short span cannot scroll to remaining actions");
                if(vertical)((ScrollView)dock).scrollTo(0,row.getHeight());else ((HorizontalScrollView)dock).scrollTo(row.getWidth(),0);
                check(vertical?dock.getScrollY()>0:dock.getScrollX()>0,"Dock did not actually scroll");
                Rect visible=new Rect();check(last.getGlobalVisibleRect(visible)&&!visible.isEmpty(),"Last action cannot be reached after scrolling");
                if(vertical)((ScrollView)dock).scrollTo(0,0);else ((HorizontalScrollView)dock).scrollTo(0,0);
                check(first.getGlobalVisibleRect(visible)&&!visible.isEmpty(),"First Dock pin cannot be reached after scrolling back");
            }
        });
    }
    private static int descriptions(View view,String label){int count=view.getVisibility()==View.VISIBLE&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=descriptions(((ViewGroup)view).getChildAt(i),label);return count;}

    private final class FixtureContext extends ContextWrapper implements ShellSettings.Provider,TaskState.Provider {
        public ShellSettings shellSettings(){if(fixtureSettings==null)fixtureSettings=ShellSettings.isolated(getSharedPreferences("desktop",0));return fixtureSettings;}
        public TaskState taskState(){if(fixtureTasks==null)fixtureTasks=TaskState.isolated(this);return fixtureTasks;}
        FixtureContext(Context base){super(base);}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){String isolated=prefix+name;preferenceFiles.add(isolated);return actual.getSharedPreferences(isolated,mode);}
        @Override public Context createDisplayContext(Display target){return new FixtureContext(super.createDisplayContext(target));}
        @Override public Context createWindowContext(int type,Bundle options){return new FixtureContext(super.createWindowContext(type,options));}
        @Override public void startActivity(Intent intent){throw new AssertionError("Dock fixture must not launch activities");}
        @Override public void startActivity(Intent intent,Bundle options){throw new AssertionError("Dock fixture must not launch activities");}
        @Override public ComponentName startService(Intent intent){throw new AssertionError("Dock fixture must not start services");}
        @Override public ComponentName startForegroundService(Intent intent){throw new AssertionError("Dock fixture must not start services");}
        @Override public boolean stopService(Intent intent){throw new AssertionError("Dock fixture must not stop services");}
    }
}
