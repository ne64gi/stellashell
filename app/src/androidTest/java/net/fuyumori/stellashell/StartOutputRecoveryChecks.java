package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Restore exactly the three selected-output keys changed by this run's HOME bootstrap. */
final class StartOutputRecoveryChecks {
    static void run(Instrumentation test,Bundle arguments)throws Exception {
        Context context=test.getTargetContext();
        SharedPreferences prefs=Launches.prefs(context);
        int previous=Integer.parseInt(arguments.getString("recovery_previous_preferred"));
        int external=Integer.parseInt(arguments.getString("recovery_connected_display"));
        check(previous>0&&external>0,"Explicit external recovery identity required");
        check(Displays.ids(context).contains(external),"Recovery external display disconnected");
        boolean phoneBootstrap=prefs.getInt("preferred_display",-1)==0&&prefs.getInt("workspace_display",-1)==0
                &&prefs.getBoolean("primary_mode",false);
        boolean resumedExternal=prefs.getInt("preferred_display",-1)==external&&!prefs.contains("workspace_display")
                &&!prefs.getBoolean("primary_mode",true);
        check((phoneBootstrap||resumedExternal)&&ShellRuntime.enabled(context),"Unexpected post-bootstrap state; refusing recovery");
        CountDownLatch running=new CountDownLatch(1),ready=new CountDownLatch(1),bridgeReady=new CountDownLatch(1),bridgeDone=new CountDownLatch(1);
        Bridge bridge=Bridge.get(context);
        Runnable bridgeObserver=()->{if(bridge.ready())bridgeReady.countDown();};
        Runnable observer=()->{if(ShellRuntime.running()&&ShellRuntime.selectedDisplay()>=0)running.countDown();
            if(ShellRuntime.selectedDisplay()==external)ready.countDown();};
        try{
            test.runOnMainSync(()->{
                bridge.observe(bridgeObserver);bridge.connect();bridgeObserver.run();ShellRuntime.observeNavigation(observer);
                if(!ShellRuntime.running())context.startForegroundService(new Intent(context,DockService.class).putExtra("show_home",false));
                observer.run();
            });
            check(running.await(15,TimeUnit.SECONDS),"Existing enabled shell did not resume");
            // Keep the runtime alive while restoring: stopping it lets HOME's fallback bootstrap
            // race this transaction. No Activity, input event, or user task operation is needed.
            test.runOnMainSync(()->{
                check(prefs.edit().putInt("preferred_display",previous).remove("workspace_display")
                        .putBoolean("primary_mode",false).commit(),"Selection restore failed");
            });
            check(ready.await(15,TimeUnit.SECONDS),"Existing external output did not recover");
            check(bridgeReady.await(20,TimeUnit.SECONDS),"Existing authorization connection did not recover");
            test.waitForIdleSync();
            String[] error={null};
            test.runOnMainSync(()->Bridge.get(context).call(service->service.settingsSnapshot(),(result,failure)->{error[0]=failure;bridgeDone.countDown();}));
            check(bridgeDone.await(15,TimeUnit.SECONDS)&&error[0]==null,"Input synchronization barrier failed");
            check(prefs.getInt("preferred_display",-1)==previous&&!prefs.contains("workspace_display")
                    &&!prefs.getBoolean("primary_mode",true),"Original selection keys not preserved");
        }finally{test.runOnMainSync(()->{ShellRuntime.unobserveNavigation(observer);bridge.remove(bridgeObserver);});}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
