package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opens then releases only our passive reader. No input injection, recording or gesture claim. */
final class DockGestureBackendChecks {
    static void run(Instrumentation test)throws Exception{
        Bridge bridge=Bridge.get(test.getTargetContext());
        CountDownLatch connected=new CountDownLatch(1),submitted=new CountDownLatch(1),availability=new CountDownLatch(1),removed=new CountDownLatch(1);
        IDesktopBridge[] service={null};boolean[] supported={false};String[] failure={null};
        Runnable observer=()->{if(bridge.ready())connected.countDown();};
        IDockGestureListener listener=new IDockGestureListener.Stub(){
            public void onAvailability(boolean value){supported[0]=value;availability.countDown();}
            public void onGesture(float x,float y,boolean right,int rotation){/* Do not record user input. */}
        };
        try{
            test.runOnMainSync(()->{bridge.observe(observer);bridge.connect();observer.run();});
            check(connected.await(20,TimeUnit.SECONDS),"Native bridge did not connect");
            test.runOnMainSync(()->{
                check(ShellSettings.of(test.getTargetContext()).snapshot().phoneDock.phoneSide!=ShellSettings.PhoneSide.GESTURE,"Do not replace a user's active gesture subscription");
                Display display=test.getTargetContext().getSystemService(DisplayManager.class).getDisplay(0);
                DisplayMetrics metrics=new DisplayMetrics();display.getRealMetrics(metrics);
                bridge.read(server->{service[0]=server;return server.observeDockGesture(listener,metrics.widthPixels,metrics.heightPixels,metrics.densityDpi,display.getRotation());},
                        (result,error)->{failure[0]=error!=null?error:!"OK".equals(result)?"Registration rejected":null;submitted.countDown();});
            });
            check(submitted.await(10,TimeUnit.SECONDS)&&failure[0]==null,"Gesture registration failed");
            check(availability.await(12,TimeUnit.SECONDS)&&supported[0],"Built-in touchscreen backend unavailable");
        }finally{
            test.runOnMainSync(()->{bridge.remove(observer);bridge.read(server->{if(service[0]!=null)service[0].removeDockGesture(listener);return "OK";},(result,error)->removed.countDown());});
            check(removed.await(10,TimeUnit.SECONDS),"Gesture reader cleanup did not complete");
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
