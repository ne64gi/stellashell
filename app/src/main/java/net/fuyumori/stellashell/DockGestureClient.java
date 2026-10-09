package net.fuyumori.stellashell;

import android.content.*;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.Display;

/** PhoneSidebar owns this subscription. All lifecycle decisions happen on the main thread. */
final class DockGestureClient implements AutoCloseable,DisplayManager.DisplayListener,InputManager.InputDeviceListener {
    interface Listener { void availabilityChanged();void gesture(float x,float y,boolean right); }
    private final Context context;
    private final Bridge bridge;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final DisplayManager displays;
    private final InputManager inputs;
    private final Runnable bridgeChanged=this::reconcile;
    private boolean enabled,available,closed;
    private boolean displayObserved,inputObserved,bridgeObserved,powerObserved;
    private Registration registration;
    private final BroadcastReceiver power=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){reconcile();}};
    DockGestureClient(Context context,Listener listener){
        this.context=context;this.listener=listener;bridge=Bridge.get(context);
        displays=context.getSystemService(DisplayManager.class);inputs=context.getSystemService(InputManager.class);
        try{
            displays.registerDisplayListener(this,main);displayObserved=true;
            inputs.registerInputDeviceListener(this,main);inputObserved=true;
            bridge.observe(bridgeChanged);bridgeObserved=true;
            IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_USER_PRESENT);
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(power,filter,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(power,filter);
            powerObserved=true;
        }catch(RuntimeException failure){close();throw failure;}
    }
    boolean available(){return available;}
    void enabled(boolean value){enabled=value;reconcile();}
    private void setAvailable(boolean value){if(available==value)return;available=value;main.post(()->{if(!closed)listener.availabilityChanged();});}
    private void reconcile(){
        if(closed||!enabled||!bridge.ready()||!ShellTaskEvents.awake(context,0)){stop();return;}
        Display display=displays.getDisplay(0);DisplayMetrics metrics=new DisplayMetrics();display.getRealMetrics(metrics);
        String key=metrics.widthPixels+":"+metrics.heightPixels+":"+metrics.densityDpi+":"+display.getRotation();
        if(registration!=null&&registration.key.equals(key))return;
        stop();Registration next=new Registration(key,display.getRotation());registration=next;
        bridge.read(server->{
            if(next.cancelled)return "CANCELLED";
            String result=server.observeDockGesture(next,metrics.widthPixels,metrics.heightPixels,metrics.densityDpi,next.rotation);
            if(next.cancelled)server.removeDockGesture(next);
            return result;
        },(result,error)->{if(registration==next&&(error!=null||!"OK".equals(result)))setAvailable(false);});
    }
    private void stop(){
        Registration old=registration;registration=null;
        if(old!=null){old.cancelled=true;bridge.read(server->{server.removeDockGesture(old);return "OK";},(result,error)->{});}
        setAvailable(false);
    }
    private final class Registration extends IDockGestureListener.Stub {
        final String key;final int rotation;volatile boolean cancelled;
        Registration(String key,int rotation){this.key=key;this.rotation=rotation;}
        @Override public void onAvailability(boolean value){main.post(()->{if(registration==this&&!cancelled)setAvailable(value);});}
        @Override public void onGesture(float x,float y,boolean right,int reportedRotation){main.post(()->{
            if(registration!=this||cancelled||!available||!enabled||!ShellTaskEvents.awake(context,0))return;
            Display display=displays.getDisplay(0);
            if(display==null||display.getRotation()!=rotation||reportedRotation!=rotation||!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>1||y<0||y>1)return;
            listener.gesture(x,y,right);
        });}
    }
    public void onDisplayAdded(int id){if(id==0)reconcile();}
    public void onDisplayRemoved(int id){if(id==0)reconcile();}
    public void onDisplayChanged(int id){if(id==0)reconcile();}
    private void inputChanged(){if(registration!=null){stop();reconcile();}}
    public void onInputDeviceAdded(int id){inputChanged();}
    public void onInputDeviceRemoved(int id){inputChanged();}
    public void onInputDeviceChanged(int id){inputChanged();}
    @Override public void close(){
        if(closed)return;closed=true;
        if(bridgeObserved)bridge.remove(bridgeChanged);
        if(displayObserved)displays.unregisterDisplayListener(this);
        if(inputObserved)inputs.unregisterInputDeviceListener(this);
        if(powerObserved)context.unregisterReceiver(power);
        stop();main.removeCallbacksAndMessages(null);
    }
}
