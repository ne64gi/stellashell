package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import org.json.*;

/** Shell-only regression on an explicitly supplied disposable display; no personal captures saved. */
public final class WindowModeProbe {
    static DesktopBridgeService bridge;static int display,id;
    static void check(String value){if(value==null||value.startsWith("ERROR:"))throw new AssertionError(value);}
    static void operation(String action){check(bridge.taskOperation(display,id,action,200,180,800,650));}
    static JSONObject task()throws Exception {
        String result=bridge.taskSnapshot(display);check(result);JSONArray rows=new JSONObject(result).getJSONArray("tasks");
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id)return rows.getJSONObject(i);
        throw new AssertionError("fixture missing");
    }
    static void fullscreen(int width,int height)throws Exception {
        Thread.sleep(1200);JSONObject row=task();
        if(row.getInt("mode")!=1||row.getInt("left")!=0||row.getInt("top")!=0||row.getInt("right")!=width||row.getInt("bottom")!=height)throw new AssertionError("fullscreen bounds: "+row);
        Class<?> capture=Class.forName("android.window.ScreenCapture");Object listener=capture.getMethod("createSyncCaptureListener").invoke(null);
        Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        Class.forName("android.view.IWindowManager").getMethod("captureDisplay",int.class,Class.forName("android.window.ScreenCapture$CaptureArgs"),Class.forName("android.window.ScreenCapture$ScreenCaptureListener")).invoke(wm,display,null,listener);
        Object buffer=Class.forName("android.window.ScreenCapture$SynchronousScreenCaptureListener").getMethod("getBuffer").invoke(listener);
        Bitmap hardware=(Bitmap)buffer.getClass().getMethod("asBitmap").invoke(buffer);Bitmap pixels=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();
        try{for(int x:new int[]{width/20,width*19/20}){int color=pixels.getPixel(x,height*7/10);if(Math.abs(android.graphics.Color.red(color)-214)>4||Math.abs(android.graphics.Color.green(color)-233)>4||Math.abs(android.graphics.Color.blue(color)-239)>4)throw new AssertionError("app surface did not fill display edge: "+Integer.toHexString(color));}}finally{pixels.recycle();}
    }
    public static void main(String[] args)throws Exception {
        display=Integer.parseInt(args[0]);if(display<=0)throw new IllegalArgumentException("Use a disposable secondary display");
        Looper.prepareMainLooper();Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        android.graphics.Point size=new android.graphics.Point();context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(display).getRealSize(size);
        bridge=new DesktopBridgeService(context);bridge.setPrimaryMode(true);bridge.setWorkArea(display,0,60,size.x,size.y-40);
        String component="net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishPrimaryActivity";
        String result=bridge.launchProfile(component,"",display,5,200,180,800,650,true);check(result);id=new JSONObject(result).getJSONObject("task").getInt("id");
        try{
            for(int n=0;n<3;n++){
                operation("bounds");Thread.sleep(400);if(task().getInt("mode")!=5)throw new AssertionError("not windowed");
                operation("fullscreen");operation("focus");operation("resize");fullscreen(size.x,size.y);
                operation("bounds");Thread.sleep(400);
                result=bridge.launchProfile(component,"",display,1,200,180,800,650,false);check(result);
                if(new JSONObject(result).getJSONObject("task").getInt("id")!=id)throw new AssertionError("task replaced");
                fullscreen(size.x,size.y);
            }
            System.out.println("PASS: 6 secondary-to-main transitions; fullscreen task bounds and rendered app edge pixels; stale resize ignored; task identity retained");
        }finally{operation("close");}
        System.exit(0);
    }
}
