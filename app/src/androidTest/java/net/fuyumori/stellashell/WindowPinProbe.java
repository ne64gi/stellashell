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
    private static FrameworkTaskAccess access;
    private static final Map<Integer,Object> identities=new HashMap<>();
    private static boolean emptyBaseline;
    private static final String PREFIX="net.fuyumori.stellashell.";
    private static boolean fixture(FrameworkTaskAccess.Entry t){
        return t.component!=null&&"net.fuyumori.stellashell.test".equals(t.component.getPackageName())
            &&(t.component.getClassName().equals(PREFIX+"PinInputActivity")||t.component.getClassName().equals(PREFIX+"PinOtherActivity"));
    }
    private static FrameworkTaskAccess.Entry owned(int id)throws Exception {
        for(FrameworkTaskAccess.Entry t:access.query(display))if(t.id==id&&fixture(t)&&t.token.equals(identities.get(id)))return t;
        throw new AssertionError("Fixture task identity changed");
    }
    private static void adoptFixtures()throws Exception {
        for(FrameworkTaskAccess.Entry t:access.query(display))if(fixture(t)&&!identities.containsKey(t.id)){identities.put(t.id,t.token);fixtures.add(t.id);}
    }
    /** Exercise the service's registered death callback without killing a personal app process. */
    private static final class Lease extends Binder {
        DeathRecipient recipient;
        @Override public void linkToDeath(DeathRecipient recipient,int flags){this.recipient=recipient;}
        @Override public boolean unlinkToDeath(DeathRecipient recipient,int flags){this.recipient=null;return true;}
        void die(){require(recipient!=null,"Death callback not registered");recipient.binderDied();}
    }
    private static void settled()throws Exception {Thread.sleep(650);} // One bounded settle per explicit action; no polling.
    private static void check(String result){if(result==null||result.startsWith("ERROR:"))throw new AssertionError(result);}
    private static JSONObject snapshot()throws Exception {String result=bridge.taskSnapshot(display);check(result);return new JSONObject(result);}
    private static JSONObject task(JSONObject snapshot,int id)throws Exception {JSONArray rows=snapshot.getJSONArray("tasks");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id)return rows.getJSONObject(i);throw new AssertionError("fixture missing "+id);}
    private static int position(JSONObject snapshot,int id)throws Exception {JSONArray rows=snapshot.getJSONArray("stack");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id)return i;throw new AssertionError("fixture missing from stack");}
    private static void operation(int id,String action)throws Exception {System.out.println("probe: before "+action+" mode="+owned(id).windowMode);System.out.println("probe: "+action+" "+id);check(bridge.taskOperation(display,id,action,0,0,0,0));}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static int launch(String activity,Rect b)throws Exception {
        String component="net.fuyumori.stellashell.test/net.fuyumori.stellashell."+activity;
        String result=bridge.launchProfile(component,"",display,5,b.left,b.top,b.right,b.bottom,true);check(result);
        int id=new JSONObject(result).getJSONObject("task").getInt("id");adoptFixtures();owned(id);settled();System.out.println("probe: launched "+id+" mode="+owned(id).windowMode);return id;
    }
    public static void main(String[] args){
        Throwable failure=null;
        try{run(args);}catch(Throwable error){failure=error;System.out.println("FAIL: "+error);error.printStackTrace(System.out);}
        finally{
            try{if(bridge!=null)check(bridge.syncWindowPins(false,null));}
            catch(Throwable error){if(failure==null)failure=error;System.out.println("FAIL pin release: "+error);}
            if(emptyBaseline){
                try{adoptFixtures();}catch(Throwable error){if(failure==null)failure=error;System.out.println("FAIL fixture recovery: "+error);}
                for(int id:fixtures)try{owned(id);operation(id,"close");}
                catch(Throwable error){if(failure==null)failure=error;System.out.println("FAIL fixture cleanup: "+error);}
                try{
                    settled();
                    for(FrameworkTaskAccess.Entry task:access.query(display))require(!fixture(task),"Fixture remains after cleanup");
                    System.out.println("PASS: owned fixture cleanup");
                }catch(Throwable error){if(failure==null)failure=error;System.out.println("FAIL cleanup verification: "+error);}
            }
        }
        System.exit(failure==null?0:1);
    }
    private static void run(String[] args)throws Exception {
        display=args.length==0?0:Integer.parseInt(args[0]);Looper.prepareMainLooper();
        Class<?> api=Class.forName("android.app.IActivityTaskManager");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"activity_task");
        Object manager=Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        access=new FrameworkTaskAccess(api,manager);
        if("NX809J".equals(Build.MODEL)){
            int[] wr=(int[])api.getMethod("rootTaskListIdFreeformWrForMulti").invoke(manager);
            require(wr==null||wr.length==0,"Close OEM game windows before testing");
        }
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        require(!context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked(),"Unlock normally before running");
        for(FrameworkTaskAccess.Entry t:access.query(-1))require(!fixture(t),"Refuse to reuse existing fixtures");
        emptyBaseline=true;
        bridge=new DesktopBridgeService(context);bridge.setPrimaryMode(true);
        JSONObject initial=snapshot();
        require(initial.getJSONObject("capabilities").getBoolean("alwaysOnTop"),"Pin capability not enabled on this display");
        android.view.Display screen=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(display);
        android.graphics.Point size=new android.graphics.Point();screen.getRealSize(size);
        Rect area=new Rect(0,80,size.x,Math.max(81,size.y-100));bridge.setWorkArea(display,area.left,area.top,area.right,area.bottom);
        Lease owner=new Lease();check(bridge.syncWindowPins(true,owner));
        int first=launch("PinInputActivity",new Rect(size.x/10,area.top+50,size.x*6/10,area.centerY()+100));
        int second=launch("PinOtherActivity",new Rect(size.x*3/10,area.top+130,size.x*8/10,area.centerY()+180));
        operation(first,"pin");operation(second,"focus");settled();
        JSONObject current=snapshot();JSONObject pinned=task(current,first),other=task(current,second);
        require(pinned.getBoolean("alwaysOnTop")&&pinned.getBoolean("pinActive"),"Pin state not native");
        require(pinned.getBoolean("visible")&&other.getBoolean("focused"),"Pin stole focus from other window");
        require(position(current,first)<position(current,second),"Pinned window behind focused ordinary window");
        System.out.println("PASS: native pin and focused ordinary window coexist");
        operation(first,"focus");settled();require(task(snapshot(),first).getBoolean("focused"),"Explicit focus failed for pinned task");
        operation(second,"pin");operation(first,"unpin");operation(first,"focus");settled();
        current=snapshot();require(!task(current,first).getBoolean("alwaysOnTop")&&task(current,second).getBoolean("pinActive"),"Independent multiple pins");
        require(position(current,second)<position(current,first),"Unpin did not restore native ordering");
        System.out.println("PASS: explicit pinned focus and independent multiple pins/unpin");
        operation(first,"fullscreen");operation(first,"focus");settled();
        current=snapshot();require(task(current,second).getBoolean("visible")&&task(current,first).getBoolean("focused"),"Pinned small window over fullscreen with input retained");
        operation(second,"minimize");settled();current=snapshot();
        require(task(current,second).getBoolean("alwaysOnTop")&&!task(current,second).getBoolean("pinActive")&&!task(current,second).getBoolean("visible"),"Minimize must suspend and hide pin");
        operation(second,"focus");settled();current=snapshot();require(task(current,second).getBoolean("pinActive")&&task(current,second).getBoolean("visible")&&task(current,second).getBoolean("focused"),"Restore must repin and focus");
        System.out.println("PASS: fullscreen background, minimize suspension and focused restore");
        operation(second,"maximize");settled();require(task(snapshot(),second).getBoolean("pinActive"),"Resize lost pin");
        operation(second,"fullscreen");settled();require(!task(snapshot(),second).getBoolean("alwaysOnTop"),"Fullscreen must release pin");
        operation(second,"restore");settled();operation(second,"pin");check(bridge.syncWindowPins(false,owner));require(!task(snapshot(),second).getBoolean("pinActive"),"Session cleanup left native flag");
        check(bridge.syncWindowPins(true,owner));operation(second,"pin");owner.die();require(!task(snapshot(),second).getBoolean("pinActive"),"Owner-death callback left native flag");
        check(bridge.syncWindowPins(true,owner));operation(second,"pin");operation(second,"close");fixtures.remove(Integer.valueOf(second));identities.remove(second);
        check(bridge.syncWindowPins(false,owner));
        System.out.println("PASS: resize/fullscreen/session release/owner-death callback/close; only owned fixtures changed");
        System.out.println("LIMIT: death callback simulated; actual remote process death, pixels, keyboard/IME and external displays not covered here");
    }
}
