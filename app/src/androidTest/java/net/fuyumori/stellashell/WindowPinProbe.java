package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Rect;
import android.os.*;
import org.json.*;
import java.util.*;

/** Shell-only, disposable fixture tasks. Never pins an existing personal application. */
public final class WindowPinProbe {
    private static final List<Integer> fixtures=new ArrayList<>();
    private static DesktopBridgeService bridge;private static int display;
    private static void check(String result){if(result==null||result.startsWith("ERROR:"))throw new AssertionError(result);}
    private static JSONObject snapshot()throws Exception {String result=bridge.taskSnapshot(display);check(result);return new JSONObject(result);}
    private static JSONObject task(JSONObject snapshot,int id)throws Exception {JSONArray rows=snapshot.getJSONArray("tasks");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id)return rows.getJSONObject(i);throw new AssertionError("fixture missing "+id);}
    private static int position(JSONObject snapshot,int id)throws Exception {JSONArray rows=snapshot.getJSONArray("stack");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id)return i;throw new AssertionError("fixture missing from stack");}
    private static void operation(int id,String action){System.out.println("probe: "+action+" "+id);check(bridge.taskOperation(display,id,action,0,0,0,0));}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static int launch(String activity,Rect b)throws Exception {
        String component="net.fuyumori.stellashell.test/net.fuyumori.stellashell."+activity;
        String result=bridge.launchProfile(component,"",display,5,b.left,b.top,b.right,b.bottom,true);check(result);
        int id=new JSONObject(result).getJSONObject("task").getInt("id");fixtures.add(id);System.out.println("probe: launched "+id);return id;
    }
    public static void main(String[] args)throws Exception {
        System.out.println("probe: start");display=args.length==0?0:Integer.parseInt(args[0]);Looper.prepareMainLooper();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        bridge=new DesktopBridgeService(context);bridge.setPrimaryMode(true);
        System.out.println("probe: context");JSONObject initial=snapshot();System.out.println("probe: snapshot");
        if(!initial.getJSONObject("capabilities").getBoolean("alwaysOnTop")){System.out.println("PASS: native task pinning is advertised unsupported on this OS; no task was changed");System.exit(0);}
        android.view.Display screen=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(display);
        android.graphics.Point size=new android.graphics.Point();screen.getRealSize(size);
        Rect area=new Rect(0,80,size.x,Math.max(81,size.y-100));bridge.setWorkArea(display,area.left,area.top,area.right,area.bottom);
        Binder owner=new Binder();System.out.println("probe: owner");check(bridge.syncWindowPins(true,owner));System.out.println("probe: launch fixtures");
        try{
            int first=launch("PolishPrimaryActivity",new Rect(size.x/10,area.top+50,size.x*6/10,area.centerY()+100));
            int second=launch("PolishSecondaryActivity",new Rect(size.x*3/10,area.top+130,size.x*8/10,area.centerY()+180));
            operation(first,"pin");operation(second,"focus");Thread.sleep(400);
            for(int i=0;i<4;i++){
                JSONObject current=snapshot();JSONObject pinned=task(current,first),other=task(current,second);
                require(pinned.getBoolean("alwaysOnTop")&&pinned.getBoolean("pinActive"),"pin state not native");
                require(pinned.getBoolean("visible")&&other.getBoolean("focused"),"pin stole focus from other window");
                require(position(current,first)<position(current,second),"pinned window behind focused ordinary window");Thread.sleep(200);
            }
            operation(second,"pin");operation(first,"unpin");operation(first,"focus");
            JSONObject current=snapshot();require(!task(current,first).getBoolean("alwaysOnTop")&&task(current,second).getBoolean("pinActive"),"independent multiple pins");
            require(position(current,second)<position(current,first),"unpin did not restore native ordering");
            operation(first,"fullscreen");operation(first,"focus");Thread.sleep(300);
            current=snapshot();require(task(current,second).getBoolean("visible")&&task(current,first).getBoolean("focused"),"pinned small window over fullscreen with input retained");
            operation(second,"minimize");Thread.sleep(300);current=snapshot();
            require(task(current,second).getBoolean("alwaysOnTop")&&!task(current,second).getBoolean("pinActive")&&!task(current,second).getBoolean("visible"),"minimize must suspend and hide pin");
            operation(second,"focus");Thread.sleep(300);current=snapshot();require(task(current,second).getBoolean("pinActive")&&task(current,second).getBoolean("visible"),"restore must repin");
            operation(second,"maximize");Thread.sleep(300);require(task(snapshot(),second).getBoolean("pinActive"),"resize lost pin");
            operation(second,"fullscreen");require(!task(snapshot(),second).getBoolean("alwaysOnTop"),"fullscreen must release pin");
            operation(second,"restore");operation(second,"pin");check(bridge.syncWindowPins(false,owner));require(!task(snapshot(),second).getBoolean("pinActive"),"session cleanup left native flag");
            System.out.println("PASS: native pin/z-order + other-window focus + multiple/unpin + fullscreen background + minimize/restore + resize + session cleanup; fixture task identities retained");
        }finally{
            check(bridge.syncWindowPins(false,owner));for(int id:fixtures)check(bridge.taskOperation(display,id,"close",0,0,0,0));
        }
        System.exit(0);
    }
}
