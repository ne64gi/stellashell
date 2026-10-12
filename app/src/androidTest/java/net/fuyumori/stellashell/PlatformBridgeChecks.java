package net.fuyumori.stellashell;

import android.app.Instrumentation;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Installed-server read wire checks via client port; read reasserts runtime primary mode.
 * Version assertion is configured client39, not an observed server-upgrade proof. */
final class PlatformBridgeChecks {
    static void run(Instrumentation test)throws Exception {
        Bridge bridge=Bridge.get(test.getTargetContext());
        CountDownLatch ready=new CountDownLatch(1),read=new CountDownLatch(1);
        Runnable observer=()->{if(bridge.ready())ready.countDown();};
        Throwable[] failure={null};
        try{
            test.runOnMainSync(()->{
                check(bridge.authorized(),"Requires already enabled Power Mode and existing authorization");
                check(bridge.serviceVersion()==39,"Client service contract version is not the Power Mode cleanup version");
                bridge.observe(observer);bridge.connect();observer.run();
            });
            check(ready.await(20,TimeUnit.SECONDS),"Installed server did not connect");
            test.runOnMainSync(()->bridge.read(server->{
                try{
                check(server.asBinder().getInterfaceDescriptor().equals("net.fuyumori.stellashell.IDesktopBridge"),"Binder descriptor changed");
                String settings=server.settingsSnapshot();
                check(settings.matches("(?:null|0|1),(?:null|0|1)"),"Existing settings wire contract failed");
                JSONObject scale=new JSONObject(server.snapshotDisplayScale(0));
                check(scale.getInt("physicalDensity")>0&&scale.getInt("density")>0
                        &&scale.getInt("width")>0&&scale.getInt("height")>0,"Existing density snapshot wire contract failed");
                return "OK";
                }catch(AssertionError problem){throw new IllegalStateException(problem.getMessage(),problem);}
            },(result,error)->{if(error!=null)failure[0]=new AssertionError(error);else if(!"OK".equals(result))failure[0]=new AssertionError("Read wire check did not finish");read.countDown();}));
            check(read.await(20,TimeUnit.SECONDS),"Server read-model wire check did not complete");
            if(failure[0]!=null)throw new AssertionError(failure[0]);
        }finally{test.runOnMainSync(()->bridge.remove(observer));}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
