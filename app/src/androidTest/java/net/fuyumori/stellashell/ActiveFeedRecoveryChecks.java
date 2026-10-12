package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.json.JSONArray;
import org.json.JSONObject;

/** Attached nonce Sidebar + explicit isolated service replacement; no production feed/SDK injection. */
final class ActiveFeedRecoveryChecks {
    private final Instrumentation test;private final Context context;
    ActiveFeedRecoveryChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static Field field(Class<?> type,String name)throws ReflectiveOperationException{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private void main(Runnable action){Throwable[] error={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void await(java.util.function.BooleanSupplier condition,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<until){boolean[] ok={false};main(()->ok[0]=condition.getAsBoolean());if(ok[0])return;Thread.sleep(100);}
        throw new AssertionError(message);
    }
    void run()throws Exception{
        BridgeFixtureContext[] fixture={null};PhoneSidebar[] sidebar={null};PhoneRunningTasks[] feed={null};
        SnapshotServer server=new SnapshotServer(context);int[] connections={0};
        try{
            main(()->{
                fixture[0]=new BridgeFixtureContext(context,true,server.service);
                Bridge bridge=fixture[0].bridge();check(bridge.ready()&&bridge.authorized(),"Isolated service did not become ready");
                Context window=fixture[0].createDisplayContext(context.getSystemService(DisplayManager.class).getDisplay(0))
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                WorkArea geometry=WorkArea.read(window,window.getSystemService(WindowManager.class).getCurrentWindowMetrics().getWindowInsets());
                PhoneRunningTasks.Backend backend=new PhoneRunningTasks.Backend(){
                    public boolean ready(){return bridge.ready()&&bridge.authorized();}
                    public void connect(){connections[0]++;bridge.connect();}
                    public void observe(Runnable listener){bridge.observe(listener);}
                    public void remove(Runnable listener){bridge.remove(listener);}
                    public void snapshot(Bridge.Reply reply){bridge.read(IDesktopBridge::phoneTaskSnapshot,reply);}
                    public void focus(TaskSnapshot.Task task,Bridge.Reply reply){throw new AssertionError("Recovery fixture must not focus tasks");}
                    public void operation(TaskSnapshot.Task task,String action,Rect bounds,Bridge.Reply reply){throw new AssertionError("Recovery fixture must not operate tasks");}
                };
                sidebar[0]=new PhoneSidebar(fixture[0],()->{},backend,()->geometry);
                try{feed[0]=(PhoneRunningTasks)field(PhoneSidebar.class,"running").get(sidebar[0]);}catch(ReflectiveOperationException error){throw new AssertionError(error);}
                sidebar[0].show(true);
            });
            await(()->rendered(sidebar[0],feed[0],1),"Attached Sidebar did not render isolated task snapshot");
            main(()->{
                fixture[0].bridge().replaceService(null);check(!fixture[0].bridge().ready(),"Isolated service did not disconnect");
                feed[0].refresh();check(feed[0].state()==PhoneRunningTasks.State.UNAVAILABLE,"Disconnected feed lost unavailable state");
                check(rendered(sidebar[0],feed[0],0),"Unavailable section retained separator, text or stale icons");
                check(connections[0]>0,"Visible unavailable feed did not request reconnection");
            });
            // A synthetic reconnect event replaces only the owned port, never SDK/client globals.
            main(()->fixture[0].bridge().replaceService(server.service));
            await(()->rendered(sidebar[0],feed[0],1),"Explicit isolated reconnect did not recover task rows");
            main(()->{server.snapshot="{\"tasks\":[]}";feed[0].refresh();});
            await(()->feed[0].state()==PhoneRunningTasks.State.READY&&rendered(sidebar[0],feed[0],0),"True empty snapshot retained a separator or stale task row");
            check(server.snapshots>=3,"Recovery fixture did not read initial/recovered/empty snapshots");
        }finally{
            main(()->{if(sidebar[0]!=null)sidebar[0].close();if(fixture[0]!=null)fixture[0].close();});
        }
    }
    private static boolean rendered(PhoneSidebar sidebar,PhoneRunningTasks feed,int expected){
        try{
            LinearLayout section=(LinearLayout)field(PhoneSidebar.class,"activeSection").get(sidebar);
            LinearLayout rows=(LinearLayout)field(PhoneSidebar.class,"activeTasks").get(sidebar);
            return (expected==0||feed.state()==PhoneRunningTasks.State.READY)&&rows.getChildCount()==expected
                    &&section.getVisibility()==(expected==0?View.GONE:View.VISIBLE);
        }catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static final class SnapshotServer {
        final IDesktopBridge service;volatile String snapshot;volatile int snapshots;
        SnapshotServer(Context context)throws Exception{
            JSONObject task=new JSONObject().put("id",801).put("component",new ComponentName(context,SetupActivity.class).flattenToString())
                    .put("mode",1).put("visible",true).put("focused",true).put("left",0).put("top",0).put("right",360).put("bottom",720);
            snapshot=new JSONObject().put("tasks",new JSONArray().put(task)).toString();Binder binder=new Binder();
            service=(IDesktopBridge)Proxy.newProxyInstance(IDesktopBridge.class.getClassLoader(),new Class<?>[]{IDesktopBridge.class},(ignored,method,args)->{
                switch(method.getName()){
                    case "asBinder":return binder;
                    case "setPrimaryMode":return null; // Isolated read adapter state only, never an OS policy change.
                    case "phoneTaskSnapshot":snapshots++;return snapshot;
                    default:throw new IllegalStateException("Unexpected fixture server operation: "+method.getName());
                }
            });
        }
    }
}
