package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.layout.EdgeDockReveal.Method;
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
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Attached production overlays, but only synthetic input/feed and fixture-owned geometry/settings. */
final class AttachedDockRevealChecks {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Object get(Object owner,String name){
        try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static void main(Instrumentation test,Runnable action){
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static void await(Instrumentation test,BooleanSupplier condition,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<until){boolean[] done={false};main(test,()->done[0]=condition.getAsBoolean());if(done[0])return;Thread.sleep(50);}
        throw new AssertionError(message);
    }
    private static WindowManager.LayoutParams params(View view){return (WindowManager.LayoutParams)view.getLayoutParams();}
    private static Rect rect(View view){WindowManager.LayoutParams p=params(view);return new Rect(p.x,p.y,p.x+p.width,p.y+p.height);}
    private static boolean vertical(String edge){return "left".equals(edge)||"right".equals(edge);}
    private static void anchored(Rect bounds,Rect available,String edge,int margin,String operation){
        int gap="left".equals(edge)?bounds.left-available.left:"right".equals(edge)?available.right-bounds.right
            :"top".equals(edge)?bounds.top-available.top:available.bottom-bounds.bottom;
        int room=vertical(edge)?available.width()-bounds.width():available.height()-bounds.height();
        check(gap==Math.min(margin,Math.max(0,room)),operation+": selected edge anchor/inset was not applied");
    }
    private static void touch(View view,String edge,int action,float distance,long start){
        float x=8,y=8;
        if("left".equals(edge))x+=distance;else if("right".equals(edge))x-=distance;
        else if("top".equals(edge))y+=distance;else y-=distance;
        MotionEvent event=MotionEvent.obtain(start,start+(action==MotionEvent.ACTION_DOWN?0:16),action,x,y,0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{view.dispatchTouchEvent(event);}finally{event.recycle();}
    }
    private static long preview(DockHandleView handle,String edge,int travel,float fraction){
        long start=SystemClock.uptimeMillis();touch(handle,edge,MotionEvent.ACTION_DOWN,0,start);
        touch(handle,edge,MotionEvent.ACTION_MOVE,travel*fraction,start);
        check(handle.tracking(),edge+": attached handle did not track inward preview");return start;
    }
    private static long tap(View handle,String edge,long start){touch(handle,edge,MotionEvent.ACTION_DOWN,0,start);touch(handle,edge,MotionEvent.ACTION_UP,0,start);return start+16;}
    private static void thickness(View handle,String edge,Method method,Rect available){
        int pixels=Ui.dp(handle.getContext(),method==Method.SWIPE?32:16);
        int expected=Math.min(pixels,vertical(edge)?available.width():available.height());
        check((vertical(edge)?params(handle).width:params(handle).height)==expected,method+": handle hit thickness differs from 32dp swipe/16dp tap contract");
        int description=method==Method.SINGLE_TAP?R.string.dock_handle_single_tap:method==Method.DOUBLE_TAP?R.string.dock_handle_double_tap:R.string.dock_handle_open;
        check(handle.getContext().getString(description).contentEquals(handle.getContentDescription()),method+": attached handle description differs from selection");
    }
    private static void translated(View content,String edge,int width,int height,float fraction){
        float remaining=1-fraction;
        float x="left".equals(edge)?-width*remaining:"right".equals(edge)?width*remaining:0;
        float y="top".equals(edge)?-height*remaining:"bottom".equals(edge)?height*remaining:0;
        check(Math.abs(content.getTranslationX()-x)<1&&Math.abs(content.getTranslationY()-y)<1,
            edge+": attached preview did not follow its inward axis");
    }
    private static void endcaps(View scroll,LinearLayout entries,String operation){
        check(entries.getChildCount()>1,operation+": fixture has no end controls");
        boolean vertical=scroll instanceof ScrollView;
        Rect visible=new Rect();View first=entries.getChildAt(0),last=entries.getChildAt(entries.getChildCount()-1);
        if(vertical)((ScrollView)scroll).scrollTo(0,entries.getHeight());
        else ((HorizontalScrollView)scroll).scrollTo(entries.getWidth(),0);
        check(last.getGlobalVisibleRect(visible)&&!visible.isEmpty(),operation+": final control is clipped/unreachable");
        if(vertical)((ScrollView)scroll).scrollTo(0,0);else ((HorizontalScrollView)scroll).scrollTo(0,0);
        check(first.getGlobalVisibleRect(visible)&&!visible.isEmpty(),operation+": first control cannot be reached again");
    }

    static void phone(Instrumentation test)throws Exception{
        Context actual=test.getTargetContext();Sandbox[] sandbox={null};PhoneSidebar[] owner={null};
        WorkArea[] geometry={null};Feed feed=new Feed();View[] retiredPanel={null};DockHandleView[] retiredHandle={null};
        try{
            main(test,()->{
                sandbox[0]=new Sandbox(actual);
                Context window=sandbox[0].createDisplayContext(actual.getSystemService(DisplayManager.class).getDisplay(0))
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                WorkArea measured=WorkArea.read(window,window.getSystemService(WindowManager.class).getCurrentWindowMetrics().getWindowInsets());
                // Synthetic portrait geometry stays within the actual safe main-display rectangle.
                geometry[0]=new WorkArea(new Rect(0,0,measured.physical.width(),Math.max(measured.physical.height(),measured.physical.width()+1)),measured.usable,false,0,Ui.dp(window,60));
                String pin=new ComponentName(actual,SetupActivity.class).flattenToString();
                check(Launches.prefs(sandbox[0]).edit().putBoolean("phone_profile_initialized",true).putBoolean("enabled",false)
                    .putBoolean("phone_sidebar",true).putBoolean("phone_sidebar_over_apps",true).putString("phone_sidebar_side","both")
                    .putInt("phone_dock_scale",100).putInt("sidebar_height",80).putString("phone_pinned",String.join("\n",java.util.Collections.nCopies(24,pin))).commit(),"Attached phone fixture preferences failed");
                owner[0]=new PhoneSidebar(sandbox[0],()->{},feed,()->geometry[0]);
            });
            PhoneSidebar dock=owner[0];
            await(test,()->dock.handles.size()==2&&dock.handles.stream().allMatch(v->v.isAttachedToWindow()&&v.getWidth()>1&&v.getHeight()>1),"Portrait fixture handles did not attach");
            main(test,()->{
                View panel=(View)get(dock,"panel");
                check(panel instanceof FrameLayout&&((FrameLayout)panel).getClipChildren()&&get(dock,"strip") instanceof ScrollView
                    &&dock.handles.stream().filter(v->v.getVisibility()==View.VISIBLE).count()==2,"Portrait legacy two-handle/vertical clipped Dock was not preserved");
                check(get(dock,"observer")==null,"Synthetic Phone reveal must not cache/observe real WorkArea-0");
                hiddenPhone(dock,feed,0,"Portrait initial");
                for(int i=0;i<2;i++){
                    DockHandleView handle=(DockHandleView)dock.handles.get(i);String edge=i==0?"left":"right";
                    thickness(handle,edge,Method.SWIPE,geometry[0].dockAvailable);
                    check(handle.getHeight()>handle.getWidth(),"Portrait handle stroke/window orientation changed");
                    long start=SystemClock.uptimeMillis();touch(handle,edge,MotionEvent.ACTION_DOWN,0,start);touch(handle,edge,MotionEvent.ACTION_UP,0,start);
                    hiddenPhone(dock,feed,0,"Portrait light tap");
                    int travel=Math.min(Ui.dp(panel.getContext(),76),geometry[0].dockAvailable.width());
                    start=preview(handle,edge,travel,.75f);previewPhone(dock,feed,0,edge,.75f);
                    touch(handle,edge,MotionEvent.ACTION_CANCEL,travel*.75f,start);hiddenPhone(dock,feed,0,"Portrait cancel");
                }
            });
            // The synthetic landscape rectangle is bounded within real safe main-display geometry.
            WorkArea portrait=geometry[0];Rect landscape=new Rect(portrait.usable);
            landscape.bottom=Math.min(landscape.bottom,landscape.top+Math.max(1,landscape.width()/2));
            for(String edge:new String[]{"left","right","top","bottom"})for(int position:new int[]{0,37,100}){
                main(test,()->{
                    geometry[0]=new WorkArea(new Rect(0,0,portrait.physical.width(),Math.max(1,portrait.physical.width()/2)),landscape,false,0,Ui.dp(actual,60));
                    check(Launches.prefs(sandbox[0]).edit().putString("phone_dock_landscape_edge",edge).putInt("phone_dock_landscape_position",position).commit(),"Landscape fixture preference write failed");dock.rebuild();
                });
                await(test,()->dock.handles.size()==2&&dock.handles.get(0).isAttachedToWindow()&&dock.handles.get(0).getWidth()==params(dock.handles.get(0)).width&&dock.handles.get(0).getHeight()==params(dock.handles.get(0)).height,"Landscape handle did not lay out");
                int before=feed.requests;long[] start={0};int[] travel={0};
                main(test,()->{
                    View panel=(View)get(dock,"panel");DockHandleView handle=(DockHandleView)dock.handles.get(0);View suppressed=dock.handles.get(1);
                    check(dock.handles.stream().filter(v->v.getVisibility()==View.VISIBLE).count()==1,"Landscape must have one visible handle");
                    check(suppressed.getVisibility()==View.GONE&&params(suppressed).width==1&&params(suppressed).height==1&&(params(suppressed).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,"Landscape second handle retained an input window");
                    View content=(View)get(dock,"strip");
                    check(panel instanceof FrameLayout&&((FrameLayout)panel).getClipChildren(),"Phone preview must have a clipping viewport");
                    check(vertical(edge)?content instanceof ScrollView:content instanceof HorizontalScrollView,"Landscape Dock used wrong content scroll axis");
                    check(vertical(edge)?handle.getHeight()>handle.getWidth():handle.getWidth()>handle.getHeight(),"Landscape handle used wrong orientation");
                    Rect available=geometry[0].dockAvailable,h=rect(handle);check(available.contains(h),"Landscape handle escaped synthetic Taskbar-free area");
                    thickness(handle,edge,Method.SWIPE,available);
                    anchored(h,available,edge,Ui.dp(handle.getContext(),8),"Phone handle");
                    int expected=vertical(edge)?available.top+Math.round((available.height()-h.height())*position/100f):available.left+Math.round((available.width()-h.width())*position/100f);
                    check((vertical(edge)?h.top:h.left)==expected,"Landscape position/end point was not applied");
                    hiddenPhone(dock,feed,before,"Landscape initial");
                    travel[0]=Math.min(Ui.dp(panel.getContext(),76),vertical(edge)?available.width():available.height());
                    long shortPull=preview(handle,edge,travel[0],.4f);previewPhone(dock,feed,before,edge,.4f);
                    touch(handle,edge,MotionEvent.ACTION_UP,travel[0]*.4f,shortPull);hiddenPhone(dock,feed,before,"Phone below-threshold release");
                    start[0]=preview(handle,edge,travel[0],.75f);previewPhone(dock,feed,before,edge,.75f);
                    dock.relayout();check(handle.tracking(),"Unchanged Phone geometry canceled active preview");
                    touch(handle,edge,MotionEvent.ACTION_UP,travel[0]*.75f,start[0]);
                    check((Boolean)get(dock,"shown")&&feed.requests==before,"Phone commit did not expose panel or started feed synchronously before its posted refresh");
                });
                await(test,()->{View p=(View)get(dock,"panel");return p.getVisibility()==View.VISIBLE&&p.getWidth()==params(p).width&&p.getHeight()==params(p).height&&feed.requests==before+1;},"Committed landscape panel/feed did not lay out/start once");
                main(test,()->{
                    View panel=(View)get(dock,"panel"),content=(View)get(dock,"strip");
                    check((params(panel).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0&&geometry[0].dockAvailable.contains(rect(panel)),"Committed Phone panel remained untouchable or overlapped reserved area");
                    anchored(rect(panel),geometry[0].dockAvailable,edge,0,"Phone panel");
                    LinearLayout entries=(LinearLayout)get(dock,"entries");check(entries.getOrientation()==(vertical(edge)?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL),"Phone content orientation did not follow edge");
                    endcaps(content,entries,"Phone "+edge+" position "+position);
                    dock.hide();int baseline=feed.requests;DockHandleView handle=(DockHandleView)dock.handles.get(0);
                    long pull=preview(handle,edge,travel[0],.75f);handle.reset();hiddenPhone(dock,feed,baseline,"Phone reset");
                    touch(handle,edge,MotionEvent.ACTION_UP,travel[0],pull);hiddenPhone(dock,feed,baseline,"Phone reset stale UP");
                    pull=preview(handle,edge,travel[0],.75f);
                    WorkArea previous=geometry[0];Rect changed=new Rect(previous.usable);changed.right--;
                    geometry[0]=new WorkArea(previous.physical,changed,false,0,Ui.dp(actual,60));dock.relayout();
                    check(!handle.tracking(),"Changed Phone geometry retained handle gesture");hiddenPhone(dock,feed,baseline,"Phone geometry cancel");
                    touch(handle,edge,MotionEvent.ACTION_UP,travel[0],pull);hiddenPhone(dock,feed,baseline,"Phone geometry stale UP");
                });
            }
            phoneTapMethods(test,dock,sandbox[0],geometry,feed);
            main(test,()->{
                retiredPanel[0]=(View)get(dock,"panel");retiredHandle[0]=(DockHandleView)dock.handles.get(0);
                int travel=(Integer)get(retiredHandle[0],"travel");preview(retiredHandle[0],"bottom",travel,.75f);
                dock.close();check(dock.handles.isEmpty()&&get(dock,"panel")==null&&!retiredHandle[0].tracking(),"Phone close retained reveal ownership");
            });
            await(test,()->!retiredPanel[0].isAttachedToWindow()&&!retiredHandle[0].isAttachedToWindow(),"Phone close did not detach actual reveal windows");
            check(feed.observed==feed.removed&&feed.operations==0,"Phone reveal leaked observer or operated a task");
        }finally{
            main(test,()->{if(owner[0]!=null)owner[0].close();if(sandbox[0]!=null)sandbox[0].cleanup();});test.waitForIdleSync();
        }
    }
    static void anywhere(Instrumentation test){
        main(test,()->{
            Sandbox sandbox=new Sandbox(test.getTargetContext());PhoneSidebar dock=null;
            try{
                Context window=sandbox.createDisplayContext(test.getTargetContext().getSystemService(DisplayManager.class).getDisplay(0))
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                WorkArea measured=WorkArea.read(window,window.getSystemService(WindowManager.class).getCurrentWindowMetrics().getWindowInsets());
                WorkArea[] geometry={measured};
                sandbox.shellSettings().setPhoneDockSide(ShellSettings.PhoneSide.GESTURE);
                sandbox.shellSettings().setPhoneDockOverApps(true);
                dock=new PhoneSidebar(sandbox,()->{},new Feed(),()->geometry[0]);
                View panel=(View)get(dock,"panel");LinearLayout entries=(LinearLayout)get(dock,"entries");
                check(entries.getOrientation()==LinearLayout.VERTICAL,"Gesture Dock must be vertical");
                for(boolean right:new boolean[]{true,false}){
                    dock.showGesture(right?.9f:.1f,.55f,right);
                    check(panel.getVisibility()==View.VISIBLE&&measured.dockAvailable.contains(rect(panel)),"Gesture Dock escaped work area");
                    check(entries.getLayoutDirection()==View.LAYOUT_DIRECTION_LTR,"Vertical item order changed with gesture direction");
                    Rect initial=rect(panel);dock.showGesture(.5f,.2f,!right);check(initial.equals(rect(panel)),"Open Dock moved under a second gesture");
                    View scroll=(View)get(dock,"strip");long start=SystemClock.uptimeMillis();
                    sandbox.captureWidgetPull=true;sandbox.capturedPull=null;
                    touch(scroll,"top",MotionEvent.ACTION_DOWN,0,start);
                    touch(scroll,"top",MotionEvent.ACTION_MOVE,Ui.dp(scroll.getContext(),60),start);
                    touch(scroll,"top",MotionEvent.ACTION_CANCEL,Ui.dp(scroll.getContext(),60),start);
                    check(sandbox.capturedPull==null,"Vertical scroll opened Widgets");
                    String inward=right?"left":"right";
                    touch(scroll,inward,MotionEvent.ACTION_DOWN,0,start+100);
                    touch(scroll,inward,MotionEvent.ACTION_MOVE,Ui.dp(scroll.getContext(),60),start+100);
                    HubActivity.SidebarDrag pull=(HubActivity.SidebarDrag)get(dock,"drag");
                    check(sandbox.capturedPull!=null&&pull!=null,"Floating Dock did not pull Widgets");
                    check(pull.origin!=null&&Math.abs(pull.origin-(right?initial.left:initial.right))<1,"Widget pull jumped from floating Dock to screen edge");
                    touch(scroll,inward,MotionEvent.ACTION_UP,Ui.dp(scroll.getContext(),60),start+100);
                    check(pull.commit,"Floating Widget pull did not commit");pull.complete();sandbox.captureWidgetPull=false;
                    dock.hide();check(panel.getVisibility()==View.GONE,"Gesture Dock did not close");
                }
                check(dock.handles.get(0).getVisibility()==View.VISIBLE,"Unavailable backend must retain a handle");
                check(get(dock,"strip") instanceof android.widget.ScrollView,"Gesture Dock must scroll vertically");
                dock.showGesture(.5f,.55f,true);Rect changed=new Rect(measured.usable);changed.right--;
                geometry[0]=new WorkArea(measured.physical,changed,measured.compact,measured.caption,measured.usable.bottom-measured.dockAvailable.bottom);
                dock.relayout();check(panel.getVisibility()==View.GONE,"Geometry change kept an old gesture anchor");
                check(get(dock,"gestures")==null,"Isolated geometry fixture opened a global input reader");
                Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                check(HomeRecoveryEntry.isPress(home),"OS HOME was rejected");
                check(!HomeRecoveryEntry.isPress(new Intent(home).putExtra(HomeRecoveryEntry.INTERNAL_HOME,true)),"Internal HOME counted");
                check(!HomeRecoveryEntry.isPress(new Intent(home).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)),"History counted as HOME");
                check(!HomeRecoveryEntry.isPress(new Intent(home).setAction(Intent.ACTION_ALL_APPS)),"Start menu request counted as HOME");
                check(!HomeRecoveryEntry.isPress(new Intent(Intent.ACTION_MAIN)),"Explicit Activity launch counted as HOME");
            }finally{if(dock!=null)dock.close();sandbox.cleanup();}
        });
    }
    private static void hiddenPhone(PhoneSidebar dock,Feed feed,int requests,String operation){
        View panel=(View)get(dock,"panel");
        check(!(Boolean)get(dock,"shown")&&panel.getVisibility()==View.GONE&&params(panel).width==1&&params(panel).height==1,operation+": hidden Phone Dock retained content window");
        check(feed.requests==requests&&((PhoneRunningTasks)get(dock,"running")).tasks().isEmpty(),operation+": hidden/preview Phone Dock read or retained tasks");
    }
    private static void previewPhone(PhoneSidebar dock,Feed feed,int requests,String edge,float fraction){
        View panel=(View)get(dock,"panel");WindowManager.LayoutParams p=params(panel);
        check(!(Boolean)get(dock,"shown")&&panel.getVisibility()==View.VISIBLE&&(p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,edge+": preview became an interactive committed Phone panel");
        check(feed.requests==requests&&((PhoneRunningTasks)get(dock,"running")).tasks().isEmpty(),edge+": preview started task feed before commit");
        translated((View)get(dock,"strip"),edge,p.width,p.height,fraction);
    }
    private static void phoneTapMethods(Instrumentation test,PhoneSidebar dock,Sandbox sandbox,WorkArea[] geometry,Feed feed)throws Exception{
        for(Method method:new Method[]{Method.SINGLE_TAP,Method.DOUBLE_TAP})for(String edge:new String[]{"left","right","top","bottom"}){
            main(test,()->{
                sandbox.shellSettings().setPhoneDockOpenMethod(method);
                check(sandbox.shellSettings().snapshot().phoneDock.openMethod==method
                    &&sandbox.shellSettings().snapshot().externalDock.openMethod==Method.SWIPE,"Phone method selection crossed external Dock domain");
                check(Launches.prefs(sandbox).edit().putString("phone_dock_landscape_edge",edge).putInt("phone_dock_landscape_position",37).commit(),"Phone tap method edge write failed");dock.rebuild();
            });
            await(test,()->{View h=dock.handles.get(0);return h.isAttachedToWindow()&&h.getWidth()==params(h).width&&h.getHeight()==params(h).height;},"Phone tap-method handle did not lay out");
            int before=feed.requests;
            main(test,()->{
                DockHandleView handle=(DockHandleView)dock.handles.get(0);View panel=(View)get(dock,"panel"),strip=(View)get(dock,"strip");
                check(dock.handles.stream().filter(v->v.getVisibility()==View.VISIBLE).count()==1,"Tap method exposed both landscape handles");
                thickness(handle,edge,method,geometry[0].dockAvailable);
                long start=SystemClock.uptimeMillis();touch(handle,edge,MotionEvent.ACTION_DOWN,0,start);
                hiddenPhone(dock,feed,before,"Tap method DOWN has no preview");
                check(strip.getTranslationX()==0&&strip.getTranslationY()==0,"Tap mode translated preview content");
                touch(handle,edge,MotionEvent.ACTION_UP,0,start);
                if(method==Method.DOUBLE_TAP){
                    hiddenPhone(dock,feed,before,"First attached double tap");dock.relayout();
                    tap(handle,edge,start+32);
                }
                check((Boolean)get(dock,"shown")&&panel.getVisibility()==View.VISIBLE&&feed.requests==before,
                    method+": attached Phone tap did not commit before posted feed");
                check((params(panel).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"Tap commit left Phone panel untouchable");
            });
            await(test,()->{View p=(View)get(dock,"panel");return p.getWidth()==params(p).width&&p.getHeight()==params(p).height&&feed.requests==before+1;},"Phone tap commit did not lay out/start one fake read");
            main(test,()->{
                dock.hide();hiddenPhone(dock,feed,before+1,"Phone tap hide");
                DockHandleView handle=(DockHandleView)dock.handles.get(0);long start=SystemClock.uptimeMillis()+1000;
                if(method==Method.DOUBLE_TAP){
                    tap(handle,edge,start);handle.reset();tap(handle,edge,start+32);
                    hiddenPhone(dock,feed,before+1,"Attached double candidate reset");
                }
                handle.reset();
                long cancelled=start+64;touch(handle,edge,MotionEvent.ACTION_DOWN,0,cancelled);touch(handle,edge,MotionEvent.ACTION_CANCEL,0,cancelled);
                hiddenPhone(dock,feed,before+1,"Attached tap cancel");
            });
        }
        main(test,()->{sandbox.shellSettings().setPhoneDockOpenMethod(Method.SWIPE);
            check(Launches.prefs(sandbox).edit().putString("phone_dock_landscape_edge","bottom").commit(),"Phone swipe fixture restore failed");dock.rebuild();});
        await(test,()->dock.handles.get(0).isAttachedToWindow(),"Restored swipe handle did not attach");
    }

    static void desktop(Instrumentation test,DesktopDock dock,Rect available,View taskbar,String edge)throws Exception{
        DockHandleView handle=(DockHandleView)get(dock,"handle");View viewport=(View)get(dock,"viewport"),strip=(View)get(dock,"strip");
        await(test,()->handle!=null&&handle.isAttachedToWindow()&&handle.getWidth()==params(handle).width&&handle.getHeight()==params(handle).height,"Desktop reveal handle did not attach");
        long[] start={0};int[] travel={0};
        main(test,()->{
            check(viewport instanceof FrameLayout&&((FrameLayout)viewport).getClipChildren(),"Desktop reveal content has no clipping viewport");
            check(vertical(edge)?strip instanceof ScrollView:strip instanceof HorizontalScrollView,"Desktop reveal scroll axis changed");
            hiddenDesktop(dock,viewport,handle,"Desktop initial");
            check(available.contains(rect(handle))&&!Rect.intersects(rect(handle),rect(taskbar)),"Desktop handle intrudes into mandatory Taskbar reserve");
            anchored(rect(handle),available,edge,Ui.dp(handle.getContext(),8),"Desktop handle");
            thickness(handle,edge,Method.SWIPE,available);
            travel[0]=(Integer)get(handle,"travel");long tap=SystemClock.uptimeMillis();touch(handle,edge,MotionEvent.ACTION_DOWN,0,tap);touch(handle,edge,MotionEvent.ACTION_UP,0,tap);
            hiddenDesktop(dock,viewport,handle,"Desktop light tap");
            long shortPull=preview(handle,edge,travel[0],.4f);previewDesktop(dock,viewport,strip,available,taskbar,edge,.4f);
            touch(handle,edge,MotionEvent.ACTION_UP,travel[0]*.4f,shortPull);hiddenDesktop(dock,viewport,handle,"Desktop below-threshold release");
            start[0]=preview(handle,edge,travel[0],.75f);previewDesktop(dock,viewport,strip,available,taskbar,edge,.75f);
            dock.position();check(handle.tracking(),"Unchanged Desktop position canceled preview");
            touch(handle,edge,MotionEvent.ACTION_CANCEL,travel[0]*.75f,start[0]);hiddenDesktop(dock,viewport,handle,"Desktop cancel");
            start[0]=preview(handle,edge,travel[0],.75f);touch(handle,edge,MotionEvent.ACTION_UP,travel[0]*.75f,start[0]);
        });
        await(test,()->viewport.getVisibility()==View.VISIBLE&&viewport.getWidth()==params(viewport).width&&viewport.getHeight()==params(viewport).height,"Desktop committed panel did not lay out");
        main(test,()->{
            check(dock.bounds()!=null&&handle.getVisibility()==View.GONE&&(params(viewport).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"Desktop commit did not expose interactive content/hide handle");
            check(available.contains(rect(viewport))&&!Rect.intersects(rect(viewport),rect(taskbar)),"Committed Desktop Dock overlaps mandatory Taskbar");
            anchored(rect(viewport),available,edge,0,"Desktop panel");
            translated(strip,edge,params(viewport).width,params(viewport).height,1);
            endcaps(strip,(LinearLayout)get(dock,"entries"),"Desktop "+edge);
            SharedPreferences prefs=Launches.prefs(viewport.getContext());String pins=prefs.getString("dock_pinned","");
            LinearLayout previous=(LinearLayout)get(dock,"entries");
            check(prefs.edit().putString("dock_pinned",pins+"\n"+pins.split("\n")[0]).commit(),"Open Desktop refresh fixture write failed");dock.refresh();
            check(get(dock,"viewport")==viewport&&get(dock,"handle")==handle&&get(dock,"entries")!=previous
                &&((LinearLayout)get(dock,"entries")).getChildCount()==previous.getChildCount()+1&&dock.bounds()!=null&&handle.getVisibility()==View.GONE,
                "Open Desktop content refresh replaced windows or closed/lost panel");
            check(prefs.edit().putString("dock_pinned",pins).commit(),"Open Desktop refresh fixture restore failed");dock.refresh();
            long outside=SystemClock.uptimeMillis();touch(viewport,edge,MotionEvent.ACTION_OUTSIDE,0,outside);hiddenDesktop(dock,viewport,handle,"Desktop outside close");
            deferredDesktopRefresh(dock,viewport,handle,edge,travel[0]);
            start[0]=preview(handle,edge,travel[0],.75f);handle.reset();hiddenDesktop(dock,viewport,handle,"Desktop reset");
            touch(handle,edge,MotionEvent.ACTION_UP,travel[0],start[0]);hiddenDesktop(dock,viewport,handle,"Desktop reset stale UP");
            // Synthetic cache applies to the owned non-primary display only, and is restored exactly.
            int id=viewport.getDisplay().getDisplayId();check(id>0,"Desktop fixture must never mutate main WorkArea cache");
            WorkArea original=WorkArea.get(viewport.getContext(),id);Rect altered=new Rect(original.usable);altered.right--;
            start[0]=preview(handle,edge,travel[0],.75f);
            try{WorkArea.put(id,new WorkArea(original.physical,altered,original.compact,original.caption,original.usable.bottom-original.dockAvailable.bottom));dock.position();
                check(!handle.tracking(),"Changed Desktop geometry retained captured gesture");hiddenDesktop(dock,viewport,handle,"Desktop geometry cancel");
                touch(handle,edge,MotionEvent.ACTION_UP,travel[0],start[0]);hiddenDesktop(dock,viewport,handle,"Desktop geometry stale UP");
            }finally{WorkArea.put(id,original);dock.position();}
            long closing=preview(handle,edge,travel[0],.75f);dock.refresh();dock.refresh();
            check((Boolean)get(dock,"refreshPending"),"Desktop close fixture failed to queue content refresh");dock.close();
            touch(handle,edge,MotionEvent.ACTION_UP,travel[0],closing);dock.refresh();
            check(get(dock,"handle")==null&&get(dock,"viewport")==null&&get(dock,"entries")==null&&dock.bounds()==null
                &&!handle.tracking()&&!(Boolean)get(dock,"refreshPending"),"Desktop close retained reveal windows/gesture/pending refresh");
        });
        await(test,()->!handle.isAttachedToWindow()&&!viewport.isAttachedToWindow(),"Desktop close did not detach actual reveal windows");
    }
    static void desktopTap(Instrumentation test,DesktopDock dock,Rect available,View taskbar,String edge,Method method)throws Exception{
        DockHandleView handle=(DockHandleView)get(dock,"handle");View viewport=(View)get(dock,"viewport"),strip=(View)get(dock,"strip");
        await(test,()->handle.isAttachedToWindow()&&handle.getWidth()==params(handle).width&&handle.getHeight()==params(handle).height,"Desktop tap-method handle did not lay out");
        main(test,()->{
            check(ShellSettings.of(viewport.getContext()).snapshot().externalDock.openMethod==method,"Desktop method selection was not wired to attached handle");
            thickness(handle,edge,method,available);hiddenDesktop(dock,viewport,handle,"Desktop tap initial");
            check(available.contains(rect(handle))&&!Rect.intersects(rect(handle),rect(taskbar)),"Tap handle overlapped mandatory Taskbar reserve");
            long start=SystemClock.uptimeMillis();touch(handle,edge,MotionEvent.ACTION_DOWN,0,start);
            hiddenDesktop(dock,viewport,handle,"Desktop tap DOWN without preview");touch(handle,edge,MotionEvent.ACTION_UP,0,start);
            if(method==Method.DOUBLE_TAP){hiddenDesktop(dock,viewport,handle,"Desktop first double tap");dock.refresh();tap(handle,edge,start+32);}
            check(dock.bounds()!=null&&viewport.getVisibility()==View.VISIBLE&&handle.getVisibility()==View.GONE,
                method+": Desktop tap method did not commit on the correct UP");
            check((params(viewport).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"Desktop tap commit remained untouchable");
            translated(strip,edge,params(viewport).width,params(viewport).height,1);
            check(available.contains(rect(viewport))&&!Rect.intersects(rect(viewport),rect(taskbar)),"Desktop tap commit escaped reserved area");
            touch(viewport,edge,MotionEvent.ACTION_OUTSIDE,0,start+64);hiddenDesktop(dock,viewport,handle,"Desktop tap outside close");
            if(method==Method.DOUBLE_TAP){tap(handle,edge,start+96);handle.reset();tap(handle,edge,start+128);hiddenDesktop(dock,viewport,handle,"Desktop double candidate reset");}
            handle.reset();touch(handle,edge,MotionEvent.ACTION_DOWN,0,start+160);touch(handle,edge,MotionEvent.ACTION_CANCEL,0,start+160);
            hiddenDesktop(dock,viewport,handle,"Desktop tap cancel");
            if(method==Method.DOUBLE_TAP)tap(handle,edge,start+192);
            dock.close();tap(handle,edge,start+224);
            check(get(dock,"handle")==null&&get(dock,"viewport")==null&&dock.bounds()==null&&!handle.tracking(),"Closed Desktop tap candidate revived ownership");
        });
        await(test,()->!handle.isAttachedToWindow()&&!viewport.isAttachedToWindow(),"Desktop tap close did not detach windows");
    }
    private static void hiddenDesktop(DesktopDock dock,View viewport,DockHandleView handle,String operation){
        check(dock.bounds()==null&&viewport.getVisibility()==View.GONE&&params(viewport).width==1&&params(viewport).height==1
            &&(params(viewport).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0&&handle.getVisibility()==View.VISIBLE,operation+": hidden Desktop Dock retained navigation/input bounds");
    }
    private static void deferredDesktopRefresh(DesktopDock dock,View viewport,DockHandleView handle,String edge,int travel){
        SharedPreferences prefs=Launches.prefs(viewport.getContext());String pins=prefs.getString("dock_pinned","");
        try{
            for(int index=0;index<3;index++){
                // Third iteration captures a real pull; the first two are pending lightweight taps.
                boolean commit=index==2;int terminal=index==1?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP;
                LinearLayout before=(LinearLayout)get(dock,"entries");long start=SystemClock.uptimeMillis();
                touch(handle,edge,MotionEvent.ACTION_DOWN,0,start);
                check(prefs.edit().putString("dock_pinned",pins+"\n"+pins.split("\n")[0]).commit(),"Deferred Desktop refresh fixture write failed");
                dock.refresh();dock.refresh();
                check(get(dock,"entries")==before&&(Boolean)get(dock,"refreshPending")&&get(dock,"viewport")==viewport&&get(dock,"handle")==handle,
                    "DOWN-time Desktop updates were not coalesced without replacing handle");
                if(commit)touch(handle,edge,MotionEvent.ACTION_MOVE,travel*.75f,start);
                touch(handle,edge,terminal,commit?travel*.75f:0,start);
                LinearLayout after=(LinearLayout)get(dock,"entries");
                check(after!=before&&after.getChildCount()==before.getChildCount()+1&&!(Boolean)get(dock,"refreshPending")&&!handle.tracking(),
                    "Terminal Desktop gesture did not flush one latest content update");
                check(commit?dock.bounds()!=null:dock.bounds()==null,"Deferred Desktop refresh changed tap/cancel/commit result");
                touch(handle,edge,terminal,0,start);dock.position();
                check(get(dock,"entries")==after,"Desktop gesture remainder replayed the pending refresh");
                if(commit)touch(viewport,edge,MotionEvent.ACTION_OUTSIDE,0,start);
                check(prefs.edit().putString("dock_pinned",pins).commit(),"Deferred Desktop refresh fixture restore failed");dock.refresh();
                hiddenDesktop(dock,viewport,handle,"Deferred Desktop refresh terminal "+index);
            }
        }finally{check(prefs.edit().putString("dock_pinned",pins).commit(),"Deferred Desktop pin cleanup failed");dock.refresh();}
    }
    private static void previewDesktop(DesktopDock dock,View viewport,View strip,Rect available,View taskbar,String edge,float fraction){
        check(dock.bounds()==null&&viewport.getVisibility()==View.VISIBLE&&(params(viewport).flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,edge+": Desktop preview became interactive/navigation bounds");
        check(available.contains(rect(viewport))&&!Rect.intersects(rect(viewport),rect(taskbar)),edge+": Desktop preview escaped its clipping/reserved area");
        translated(strip,edge,params(viewport).width,params(viewport).height,fraction);
    }
    private static final class Feed implements PhoneRunningTasks.Backend {
        int observed,removed,requests,operations;
        public boolean ready(){return true;}public void connect(){throw new AssertionError("Synthetic feed must not connect");}
        public void observe(Runnable listener){observed++;}public void remove(Runnable listener){removed++;}
        public void snapshot(Bridge.Reply reply){requests++;reply.done("{\"tasks\":[]}",null);}
        public void focus(TaskSnapshot.Task task,Bridge.Reply reply){operations++;throw new AssertionError("Reveal fixture must not focus tasks");}
        public void operation(TaskSnapshot.Task task,String action,Rect bounds,Bridge.Reply reply){operations++;throw new AssertionError("Reveal fixture must not operate tasks");}
    }
    private static final class Sandbox extends ContextWrapper implements ShellSettings.Provider,TaskState.Provider {
        private final Context actual;private final String prefix;private final Set<String> files;private final Sandbox owner;
        private ShellSettings settings;private TaskState tasks;
        private boolean captureWidgetPull;private Intent capturedPull;
        Sandbox(Context actual){super(actual);this.actual=actual;prefix="attached_dock_reveal_"+UUID.randomUUID()+"_";files=new HashSet<>();owner=this;}
        Sandbox(Context base,Sandbox owner){super(base);this.owner=owner;actual=owner.actual;prefix=owner.prefix;files=owner.files;}
        public ShellSettings shellSettings(){if(owner.settings==null)owner.settings=ShellSettings.isolated(getSharedPreferences("desktop",0));return owner.settings;}
        public TaskState taskState(){if(owner.tasks==null)owner.tasks=TaskState.isolated(this);return owner.tasks;}
        @Override public Context getApplicationContext(){return owner;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){String isolated=prefix+name;files.add(isolated);return actual.getSharedPreferences(isolated,mode);}
        @Override public Context createDisplayContext(Display target){return new Sandbox(super.createDisplayContext(target),owner);}
        @Override public Context createWindowContext(int type,Bundle options){return new Sandbox(super.createWindowContext(type,options),owner);}
        @Override public void startActivity(Intent intent){throw new AssertionError("Reveal fixture cannot launch activities");}
        @Override public void startActivity(Intent intent,Bundle options){
            if(owner.captureWidgetPull&&intent.getComponent()!=null&&intent.getComponent().getClassName().equals(HubActivity.class.getName())){owner.capturedPull=new Intent(intent);return;}
            throw new AssertionError("Reveal fixture cannot launch activities");
        }
        @Override public ComponentName startService(Intent intent){throw new AssertionError("Reveal fixture cannot start services");}
        @Override public ComponentName startForegroundService(Intent intent){throw new AssertionError("Reveal fixture cannot start services");}
        @Override public boolean stopService(Intent intent){throw new AssertionError("Reveal fixture cannot stop services");}
        void cleanup(){if(owner.settings!=null)owner.settings.close();if(owner.tasks!=null)owner.tasks.closeOwner();for(String name:files)actual.deleteSharedPreferences(name);}
    }
}
