package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standard intents and a real policy-routed Meta key. No personal task or app text is recorded. */
final class StartShortcutChecks {
    private final Instrumentation test;
    private int display;
    StartShortcutChecks(Instrumentation test){this.test=test;}
    void run()throws Exception {
        CountDownLatch ready=new CountDownLatch(1);
        CountDownLatch shortcutReady=new CountDownLatch(1);
        Bridge bridge=Bridge.get(test.getTargetContext());
        Runnable shortcut=()->{if(bridge.startShortcutReady())shortcutReady.countDown();};
        CountDownLatch homeResumed=new CountDownLatch(1),homeRecreated=new CountDownLatch(1);
        HomeActivity[] home={null};
        Application application=(Application)test.getTargetContext().getApplicationContext();
        Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){if(a instanceof HomeActivity){
                if(home[0]==null){home[0]=(HomeActivity)a;homeResumed.countDown();}
                else if(home[0]!=a){home[0]=(HomeActivity)a;homeRecreated.countDown();}
            }}
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){}
        };
        Runnable navigation=()->{if(ShellRuntime.running()&&ShellRuntime.selectedDisplay()>=0)ready.countDown();};
        test.runOnMainSync(()->{
            check(ShellRuntime.enabled(test.getTargetContext()),"Existing enabled shell required");
            application.registerActivityLifecycleCallbacks(lifecycle);ShellRuntime.observeNavigation(navigation);
            bridge.observe(shortcut);bridge.connect();bridge.startShortcut();shortcut.run();
            if(!ShellRuntime.running())test.getTargetContext().startForegroundService(
                    new Intent(test.getTargetContext(),DockService.class).putExtra("show_home",false));
            navigation.run();
        });
        boolean ownsPanel=false;
        try{
            check(ready.await(15,TimeUnit.SECONDS),"Existing shell navigation required");
            check(shortcutReady.await(20,TimeUnit.SECONDS),"Default HOME All Apps registration unavailable");
            boolean[] closed={false};
            test.runOnMainSync(()->{display=ShellRuntime.selectedDisplay();closed[0]=!ShellPanels.isOpen(display);});
            check(closed[0],"A user panel is already open; do not replace it");ownsPanel=true;
            expect(true,()->test.getTargetContext().startActivity(new Intent(Intent.ACTION_ALL_APPS)
                    .setComponent(new ComponentName(test.getTargetContext(),StartMenuActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            test.runOnMainSync(()->check(StartMenuRequests.route(new Intent(Intent.ACTION_ALL_APPS)),"Repeated show was rejected"));
            test.waitForIdleSync();
            test.runOnMainSync(()->check(ShellPanels.isOpen(display),"Repeated show closed Start"));
            expect(false,()->StartMenuRequests.route(new Intent(StartMenuRequests.LAUNCHER_TOGGLE)));
            expect(true,()->shell("input keyevent 118"),false);
            expect(false,()->StartMenuRequests.route(new Intent(StartMenuRequests.LAUNCHER_TOGGLE)));
            shell("input keycombination 117 59");test.waitForIdleSync();
            test.runOnMainSync(()->check(!ShellPanels.isStartOpen(display),"A modifier chord opened Start"));
            expect(true,()->test.getTargetContext().startActivity(new Intent(test.getTargetContext(),HomeActivity.class)
                    .setAction(StartMenuRequests.LAUNCHER_TOGGLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            check(homeResumed.await(10,TimeUnit.SECONDS),"HOME request did not resume its surface");
            test.runOnMainSync(()->{
                check(Intent.ACTION_MAIN.equals(home[0].getIntent().getAction()),"Consumed toggle was retained in HOME intent");
                home[0].recreate();
            });
            check(homeRecreated.await(10,TimeUnit.SECONDS),"HOME did not recreate");test.waitForIdleSync();
            test.runOnMainSync(()->check(ShellPanels.isStartOpen(display),"Recreation replayed an old toggle and closed Start"));
            expect(false,()->StartMenuRequests.route(new Intent(StartMenuRequests.LAUNCHER_TOGGLE)));
            // The actual framework policy consumes Meta and dispatches ALL_APPS. Activity key mocks
            // would not prove this path. Shell input is bounded and contains no typed user content.
            expect(true,()->{
                try(ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand("input keyevent 117");
                    InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){
                    byte[] output=new byte[256];check(input.read(output)<=0,"Meta injection returned an error");
                }catch(Exception error){throw new AssertionError(error);}
            },false);
            expect(false,()->StartMenuRequests.route(new Intent(StartMenuRequests.LAUNCHER_TOGGLE)));
        }finally{
            boolean cleanup=ownsPanel;
            test.runOnMainSync(()->{application.unregisterActivityLifecycleCallbacks(lifecycle);bridge.remove(shortcut);ShellRuntime.unobserveNavigation(navigation);if(cleanup)ShellPanels.dismiss(display);});
        }
    }
    private void shell(String command){
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(test.getUiAutomation().executeShellCommand(command))){
            byte[] output=new byte[256];check(input.read(output)<=0,"Key injection returned an error");
        }catch(Exception error){throw new AssertionError(error);}
    }
    private void expect(boolean open,Runnable action)throws Exception {expect(open,action,true);}
    private void expect(boolean open,Runnable action,boolean onMain)throws Exception {
        CountDownLatch changed=new CountDownLatch(1);
        Runnable observer=()->{if(ShellPanels.isOpen(display)==open)changed.countDown();};
        test.runOnMainSync(()->ShellPanels.observe(observer));
        try{
            if(onMain)test.runOnMainSync(action);else action.run();
            check(changed.await(10,TimeUnit.SECONDS),open?"Start did not open through requested route":"Start did not close through requested route");
            test.waitForIdleSync();
            test.runOnMainSync(()->check(ShellPanels.isOpen(display)==open,"Start state changed after the route settled"));
        }finally{test.runOnMainSync(()->ShellPanels.unobserve(observer));}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
