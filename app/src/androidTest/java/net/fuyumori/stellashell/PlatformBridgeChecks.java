package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

/** Read-only calls through the installed Shizuku server; no task/input/settings operations. */
final class PlatformBridgeChecks {
    static void run(Instrumentation test)throws Exception {
        Bridge bridge=Bridge.get(test.getTargetContext());
        CountDownLatch ready=new CountDownLatch(1),connected=new CountDownLatch(1);
        Runnable observer=()->{if(bridge.ready())ready.countDown();};
        IDesktopBridge[] server={null};
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){
                server[0]=IDesktopBridge.Stub.asInterface(binder);connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name){}
        };
        Field field=Bridge.class.getDeclaredField("args");field.setAccessible(true);
        Shizuku.UserServiceArgs args=(Shizuku.UserServiceArgs)field.get(bridge);
        boolean[] peeked={false};
        try{
            test.runOnMainSync(()->{
                check(bridge.authorized(),"Requires existing Shizuku authorization");
                bridge.observe(observer);bridge.connect();observer.run();
            });
            check(ready.await(20,TimeUnit.SECONDS),"Installed server did not connect");
            test.runOnMainSync(()->{
                peeked[0]=true;
                check(Shizuku.peekUserService(args,connection)==33,"Old Shizuku user service survived event update");
            });
            check(connected.await(10,TimeUnit.SECONDS),"Read-only server connection unavailable");
            check(server[0].asBinder().getInterfaceDescriptor().equals("net.fuyumori.stellashell.IDesktopBridge"),"Binder descriptor changed");
            String settings=server[0].settingsSnapshot();
            check(settings.matches("(?:null|0|1),(?:null|0|1)"),"Existing settings wire contract failed");
            JSONObject scale=new JSONObject(server[0].snapshotDisplayScale(0));
            check(scale.getInt("physicalDensity")>0&&scale.getInt("density")>0
                    &&scale.getInt("width")>0&&scale.getInt("height")>0,"Existing density snapshot wire contract failed");
        }finally{
            test.runOnMainSync(()->{
                bridge.remove(observer);
                if(peeked[0])Shizuku.unbindUserService(args,connection,false);
            });
        }
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
