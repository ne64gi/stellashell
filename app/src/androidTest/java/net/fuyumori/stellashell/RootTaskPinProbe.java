package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.*;
import org.json.*;

/** Shell-only fixture diagnostic. Only fixture tasks may be manipulated or logged. */
public final class RootTaskPinProbe {
    private static final List<Integer> fixtures=new ArrayList<>();
    private static final Map<Integer,FrameworkTaskAccess.Entry> fixtureIdentity=new HashMap<>();
    private static DesktopBridgeService bridge;
    private static Object manager;private static Method nativePin;
    private static FrameworkTaskAccess access;
    private static int display;private static boolean integrated,mainDisplay;
    private static final Binder owner=new Binder();
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static String check(String value){if(value==null||value.startsWith("ERROR:"))throw new AssertionError(value);return value;}
    private static JSONObject snapshot()throws Exception{return new JSONObject(check(bridge.taskSnapshot(display)));}
    private static JSONObject task(int id)throws Exception{
        JSONArray rows=snapshot().getJSONArray("tasks");
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id)return rows.getJSONObject(i);
        throw new AssertionError("Missing fixture "+id);
    }
    private static void action(int id,String action)throws Exception{
        check(bridge.taskOperation(display,id,action,0,0,0,0));
        int wanted="fullscreen".equals(action)?1:"restore".equals(action)||"maximize".equals(action)?5:0;
        if(wanted!=0){
            long until=SystemClock.uptimeMillis()+3000;
            while(task(id).getInt("mode")!=wanted&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);
            require(task(id).getInt("mode")==wanted,"Window mode transition did not settle: "+action);
        }
    }
    private static void focus(int id)throws Exception{
        action(id,"focus");long until=SystemClock.uptimeMillis()+2500;
        while(!task(id).getBoolean("focused")&&SystemClock.uptimeMillis()<until)SystemClock.sleep(50);
        require(task(id).getBoolean("focused"),"Fixture focus failed: "+id);
    }
    private static void pin(int id,boolean on)throws Exception{
        require(fixtures.contains(id),"Non-fixture operation rejected");
        if(integrated)action(id,on?"pin":"unpin");else nativePin.invoke(manager,id,on);
        SystemClock.sleep(150);
        require(task(id).getBoolean("pinActive")==on,"RunningTaskInfo pin flag differs: "+task(id));
        boolean found=false;
        for(FrameworkTaskAccess.Root root:access.roots(display))if(root.task.id==id){
            found=true;require(root.task.alwaysOnTop==on,"Root pin flag differs");
        }
        require(found,"Fixture is not a standalone root");
    }
    private static int launch(String activity,int l,int top,int r,int bottom)throws Exception{
        String component="net.fuyumori.stellashell.test/net.fuyumori.stellashell."+activity;
        JSONObject launched=new JSONObject(check(bridge.launchProfile(component,"",display,5,l,top,r,bottom,true)));
        int id=launched.getJSONObject("task").getInt("id");fixtures.add(id);
        for(FrameworkTaskAccess.Entry entry:access.query(display))if(entry.id==id)fixtureIdentity.put(id,entry);
        focus(id);return id;
    }
    private static int color(int x,int y)throws Exception{
        Class<?> capture=Class.forName("android.window.ScreenCapture");Object listener=capture.getMethod("createSyncCaptureListener").invoke(null);
        Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        Class.forName("android.view.IWindowManager").getMethod("captureDisplay",int.class,Class.forName("android.window.ScreenCapture$CaptureArgs"),Class.forName("android.window.ScreenCapture$ScreenCaptureListener")).invoke(wm,display,null,listener);
        Object buffer=Class.forName("android.window.ScreenCapture$SynchronousScreenCaptureListener").getMethod("getBuffer").invoke(listener);
        Bitmap hardware=(Bitmap)buffer.getClass().getMethod("asBitmap").invoke(buffer);Bitmap pixels=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();
        try{
            return pixels.getPixel(x,y);
        }finally{pixels.recycle();}
    }
    private static void pixel(int x,int y,int expected)throws Exception{
        int actual=0;long until=SystemClock.uptimeMillis()+2500;
        do{actual=color(x,y);if(closeColor(actual,expected))return;SystemClock.sleep(80);}while(SystemClock.uptimeMillis()<until);
        JSONArray rows=snapshot().getJSONArray("tasks"),own=new JSONArray();
        for(int i=0;i<rows.length();i++)if(fixtures.contains(rows.getJSONObject(i).getInt("id")))own.put(rows.getJSONObject(i));
        System.out.println("probe: fixtures="+own);
        throw new AssertionError("Pixel "+x+","+y+" expected="+Integer.toHexString(expected)+" actual="+Integer.toHexString(actual));
    }
    private static boolean closeColor(int actual,int expected){
        // Physical-display color conversion may round a solid channel by one unit.
        for(int shift:new int[]{0,8,16,24})if(Math.abs(((actual>>>shift)&255)-((expected>>>shift)&255))>2)return false;
        return true;
    }
    private static void tap(int x,int y)throws Exception{
        Class<?> input=Class.forName("android.hardware.input.InputManager");Object service=input.getMethod("getInstance").invoke(null);
        Method inject=input.getMethod("injectInputEvent",InputEvent.class,int.class);
        long now=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){
            MotionEvent event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            InputEvent.class.getMethod("setDisplayId",int.class).invoke(event,display);
            try{require((Boolean)inject.invoke(service,event,2),"Input injection rejected");}finally{event.recycle();}
        }
    }
    public static void main(String[] args)throws Exception{
        try{run(args);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
    private static void run(String[] args)throws Exception{
        System.out.println("probe: entry");
        integrated=args.length>0&&args[0].startsWith("integrated");
        mainDisplay=args.length>0&&args[0].contains("main");
        require(android.os.Process.myUid()==2000,"Run only as shell");
        Looper.prepareMainLooper();Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        System.out.println("probe: shell context");
        Class<?> api=Class.forName("android.app.IActivityTaskManager");
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"activity_task");
        manager=Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        nativePin=api.getMethod("setRootTaskAlwaysOnTop",int.class,boolean.class);access=new FrameworkTaskAccess(api,manager);
        System.out.println("probe: native method resolved");
        HandlerThread drain=new HandlerThread("pin-fixture-surface");drain.start();
        ImageReader reader=ImageReader.newInstance(1000,800,PixelFormat.RGBA_8888,3);
        reader.setOnImageAvailableListener(r->{try(Image image=r.acquireLatestImage()){}},new Handler(drain.getLooper()));
        VirtualDisplay screen=null;
        try{
            System.out.println("probe: creating display");
            // TRUSTED and DESTROY_CONTENT_ON_REMOVAL are shell-only framework flags.
            Context shell=context.createPackageContext("com.android.shell",0);
            if(mainDisplay){
                require("SOG06".equals(Build.MODEL)&&Build.VERSION.SDK_INT==34,"Main-display probe is calibrated for SOG06 Android 14");
                require(!shell.getSystemService(android.app.KeyguardManager.class).isDeviceLocked(),"Unlock normally before probing");
                android.graphics.Point size=new android.graphics.Point();shell.getSystemService(DisplayManager.class).getDisplay(0).getRealSize(size);
                require(size.x==1096&&size.y==2560,"Unexpected screen geometry; no fixture input injected");
                display=0;
            }
            else{
                screen=shell.getSystemService(DisplayManager.class).createVirtualDisplay("StellaShell pin fixture",1000,800,160,reader.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY|1024|256);
                require(screen!=null,"Fixture display creation failed");display=screen.getDisplay().getDisplayId();require(display>0,"Expected isolated display");
            }
            System.out.println("probe: display="+display+" integrated="+integrated);
            bridge=new DesktopBridgeService(context);bridge.setPrimaryMode(true);bridge.setWorkArea(display,0,60,mainDisplay?1080:1000,mainDisplay?2460:760);
            check(bridge.syncWindowPins(true,owner));
            int first=mainDisplay?launch("PinInputActivity",100,500,850,1500):launch("PinInputActivity",100,100,650,550);
            int second=mainDisplay?launch("PinOtherActivity",350,900,1050,2000):launch("PinOtherActivity",350,250,900,700);
            int ox=mainDisplay?600:500,oy=mainDisplay?1100:400,ex=mainDisplay?950:800,ey=mainDisplay?1800:600;
            Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Class.forName("android.view.IWindowManager").getMethod("syncInputTransactions",boolean.class).invoke(wm,true);
            if(args.length>0&&args[0].contains("flags")){
                pin(first,true);pin(first,false);
                System.out.println("PASS: shell native root-task pin/unpin and both framework flags");
                if(integrated){
                    require("SONY_ROOT".equals(snapshot().getJSONObject("capabilities").getString("pinRoute")),"Wrong backend route");
                    pin(first,true);pin(second,true);pin(first,false);
                    require(task(second).getBoolean("pinActive"),"Unpin cleared another window");
                    check(bridge.syncWindowPins(false,owner));
                    require(!task(first).getBoolean("pinActive")&&!task(second).getBoolean("pinActive"),"Session cleanup left native pins");
                    System.out.println("PASS: integrated SONY_ROOT route + independent ownership + session native cleanup (flags only)");
                }
                return;
            }
            if(integrated&&!mainDisplay){
                require(!snapshot().getJSONObject("capabilities").getBoolean("alwaysOnTop"),"Unsupported display advertised pin");
                String result=bridge.taskOperation(display,first,"pin",0,0,0,0);
                require(result!=null&&result.startsWith("ERROR:"),"Secondary display pin must be rejected");
                require(!task(first).getBoolean("pinActive"),"Rejected pin changed native state");
                System.out.println("PASS: secondary-display capability and mutation guards; no native pin applied");
                return;
            }
            focus(second);
            pixel(ox,oy,PinInputActivity.SECOND);
            pin(first,true);focus(second);
            pixel(ox,oy,PinInputActivity.FIRST);
            tap(ex,ey);pixel(ex,ey,PinInputActivity.SECOND_TOUCHED);
            require(task(second).getBoolean("focused"),"Pinned window stole focus");
            pixel(ox,oy,PinInputActivity.FIRST);
            tap(ox,oy);pixel(ox,oy,PinInputActivity.FIRST_TOUCHED);
            System.out.println("PASS: native pin + actual overlap pixels + input to exposed other window and pinned window");
            pin(second,true);pin(first,false);focus(first);pixel(ox,oy,PinInputActivity.SECOND_TOUCHED);
            pin(second,false);focus(first);pixel(ox,oy,PinInputActivity.FIRST_TOUCHED);
            System.out.println("PASS: independent multiple pins and unpin restores ordering");
            if(integrated){
                pin(second,true);action(first,"fullscreen");focus(first);
                pixel(ox,oy,PinInputActivity.SECOND_TOUCHED);require(task(first).getBoolean("focused"),"Fullscreen background lost focus");
                action(second,"minimize");SystemClock.sleep(400);
                require(task(second).getBoolean("alwaysOnTop")&&!task(second).getBoolean("pinActive")&&!task(second).getBoolean("visible"),"Minimize pin suspension");
                focus(second);require(task(second).getBoolean("pinActive"),"Restore did not repin");
                action(second,"maximize");require(task(second).getBoolean("pinActive"),"Resize lost pin");
                action(second,"fullscreen");require(!task(second).getBoolean("alwaysOnTop"),"Fullscreen retained pin");
                action(second,"restore");pin(second,true);check(bridge.syncWindowPins(false,owner));
                require(!task(second).getBoolean("pinActive"),"Session cleanup retained pin");
                System.out.println("PASS: fullscreen background + minimize/restore + resize + fullscreen release + session cleanup");
                if(mainDisplay){
                    screen=shell.getSystemService(DisplayManager.class).createVirtualDisplay("StellaShell pin transfer fixture",1000,800,160,reader.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY|1024|256);
                    require(screen!=null,"Transfer fixture display failed");int other=screen.getDisplay().getDisplayId();
                    String component=fixtureIdentity.get(second).component.flattenToString();
                    check(bridge.syncWindowPins(true,owner));pin(second,true);
                    check(bridge.moveWorkspaceTask(0,other,second,component));
                    for(FrameworkTaskAccess.Entry entry:access.query(other))if(entry.id==second)require(!entry.alwaysOnTop,"Workspace handoff leaked main-only pin");
                    check(bridge.moveWorkspaceTask(other,0,second,component));pin(second,true);
                    api.getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(manager,second,other);
                    check(bridge.taskSnapshot(other));
                    for(FrameworkTaskAccess.Entry entry:access.query(other))if(entry.id==second)require(!entry.alwaysOnTop,"Reconciliation missed externally moved pin");
                    System.out.println("PASS: workspace and external moves clear owned pins on unsupported display");
                }
            }
        }finally{
            try{
                if(bridge!=null){
                    try{check(bridge.syncWindowPins(false,owner));}catch(Throwable failure){System.err.println("cleanup session: "+failure);}
                    for(int id:fixtures)try{
                        FrameworkTaskAccess.Entry expected=fixtureIdentity.get(id);
                        for(FrameworkTaskAccess.Entry actual:access.query(-1))if(actual.id==id&&expected!=null&&Objects.equals(expected.token,actual.token)&&Objects.equals(expected.component,actual.component)&&expected.userId==actual.userId){
                            try{nativePin.invoke(manager,id,false);}catch(Throwable failure){System.err.println("cleanup unpin: "+failure);}
                            api.getMethod("removeTask",int.class).invoke(manager,id);break;
                        }
                    }catch(Throwable failure){System.err.println("cleanup fixture: "+failure);}
                }
            }finally{if(screen!=null)screen.release();reader.close();drain.quitSafely();}
        }
        System.out.println("PASS: fixture cleanup");System.exit(0);
    }
}
