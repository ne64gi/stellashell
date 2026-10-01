package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import org.json.*;

/** Shell-only, opt-in repair/verification of two explicitly named, already-running tasks. */
public final class WindowRoleProbe {
    private static void check(String reply){if(reply==null||reply.startsWith("ERROR:"))throw new AssertionError(reply);}
    private static JSONObject task(JSONArray rows,String component)throws Exception{
        JSONObject found=null;
        for(int i=0;i<rows.length();i++)if(component.equals(rows.getJSONObject(i).getString("component"))){
            if(found!=null)throw new AssertionError("Ambiguous existing task");found=rows.getJSONObject(i);
        }
        if(found==null)throw new AssertionError("Requested task is not running");return found;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2||args[0].equals(args[1]))throw new IllegalArgumentException("Exact primary and secondary components required");
        Policy.component(args[0]);Policy.component(args[1]);Looper.prepareMainLooper();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context system=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        android.view.Display display=system.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
        android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();display.getRealMetrics(metrics);
        Rect physical=new Rect(0,0,size.x,size.y);
        // A shell probe is not an Activity: do not create an application WindowContext.
        WorkArea area=new WorkArea(physical,physical,true,Math.round(32*metrics.density),0);
        DesktopBridgeService bridge=new DesktopBridgeService(system);bridge.setPrimaryMode(true);area.sync(bridge,0);
        JSONArray before=new JSONObject(bridge.taskSnapshot(0)).getJSONArray("tasks");
        int primary=task(before,args[0]).getInt("id"),secondary=task(before,args[1]).getInt("id");
        check(bridge.taskOperation(0,primary,"fullscreen",0,0,0,0));check(bridge.taskOperation(0,primary,"focus",0,0,0,0));
        Rect a=area.content;
        check(bridge.taskOperation(0,secondary,"bounds",a.left+a.width()/6,a.top+a.height()/6,a.right-a.width()/6,a.bottom-a.height()/6));
        check(bridge.taskOperation(0,secondary,"focus",0,0,0,0));
        for(int i=0;i<5;i++){
            Thread.sleep(1000);JSONArray rows=new JSONObject(bridge.taskSnapshot(0)).getJSONArray("tasks");
            JSONObject p=task(rows,args[0]),s=task(rows,args[1]);
            if(p.getInt("id")!=primary||s.getInt("id")!=secondary||p.getInt("mode")!=1||s.getInt("mode")!=5
                    ||!p.getBoolean("visible")||!s.getBoolean("visible")||s.getInt("right")-s.getInt("left")>=p.getInt("right")-p.getInt("left"))
                throw new AssertionError("Role/visibility mismatch: primary="+p.getInt("mode")+" secondary="+s.getInt("mode"));
        }
        System.out.println("PASS: existing primary fullscreen=1, secondary freeform=5; both visible at distinct sizes for 5 samples; task IDs retained");
        System.exit(0);
    }
}
