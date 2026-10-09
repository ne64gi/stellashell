package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Execute actual Setup preparation without altering a saved restoration record or enabled policy. */
final class DesktopPreparationChecks {
    static void run(Instrumentation test)throws Exception {
        check(Build.VERSION.SDK_INT<=34,"This scope checks the legacy shared-pointer policy");
        Bridge bridge=Bridge.get(test.getTargetContext());
        CountDownLatch ready=new CountDownLatch(1),done=new CountDownLatch(1);
        Runnable observer=()->{if(bridge.ready())ready.countDown();};
        Activity activity=null;
        try{
            test.runOnMainSync(()->{
                check(bridge.authorized(),"Requires existing authorization");
                bridge.observe(observer);bridge.connect();observer.run();
            });
            check(ready.await(20,TimeUnit.SECONDS),"Preparation connection unavailable");
            check(Launches.prefs(test.getTargetContext()).contains("before_desktop"),"Existing restoration record required; fixture does not create one");
            Intent intent=new Intent(test.getTargetContext(),SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=test.startActivitySync(intent,ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            SetupActivity setup=(SetupActivity)activity;
            Method apply=SetupActivity.class.getDeclaredMethod("apply",boolean.class);apply.setAccessible(true);
            Field busy=SetupActivity.class.getDeclaredField("busy");busy.setAccessible(true);
            Throwable[] failure={null};
            test.runOnMainSync(()->{
                bridge.read(server->server.settingsSnapshot(),(snapshot,error)->{
                    try{
                        check(error==null&&"1,1".equals(snapshot),"Enabled OS desktop and freeform policy required");
                        apply.invoke(setup,false);
                        check(busy.getBoolean(setup),"Actual preparation did not start");
                        // Single Bridge worker and main reply queue provide an event barrier, no polling.
                        bridge.read(server->server.settingsSnapshot(),(after,readError)->{
                            try{check(readError==null&&"1,1".equals(after),"Preparation cleared the legacy pointer policy");
                                check(!busy.getBoolean(setup),"Preparation did not finish before its read barrier");}
                            catch(Throwable problem){failure[0]=problem;}finally{done.countDown();}
                        });
                    }catch(Throwable problem){failure[0]=problem;done.countDown();}
                });
            });
            check(done.await(25,TimeUnit.SECONDS),"Preparation/read barrier timed out");
            if(failure[0]!=null)throw new AssertionError(failure[0]);
        }finally{
            Activity owned=activity;
            test.runOnMainSync(()->{bridge.remove(observer);if(owned!=null)owned.finish();});
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
