package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.input.MouseRoutingLease;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.InputDevice;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Shell-UID, session-scoped routing of otherwise unassigned physical mice. */
final class MouseRouting {
    private final Context context;
    private Object service;
    private Method add,remove,associated,uniqueId,deviceIds,deviceInfo,deviceGeneration;
    private boolean probed;
    private String unavailable="";
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private int target=-1;private String targetUniqueId="";
    private HandlerThread eventThread;
    private Handler events;
    private DisplayManager.DisplayListener displayListener;
    private InputManager.InputDeviceListener inputListener;
    private Runnable cleanupRetry,inputRefresh;
    private final Set<String> activeCleanupDebt=new HashSet<>();
    private long eventGeneration;
    private int cleanupFailures;
    private final MouseRoutingLease lease;
    MouseRouting(Context context){
        this.context=context;
        lease=new MouseRoutingLease(new MouseRoutingLease.Backend(){
            public int association(String descriptor)throws Exception{return MouseRouting.this.association(descriptor);}
            public int generation(String descriptor)throws Exception{InputDevice device=device(descriptor);return device==null?-1:MouseRouting.this.generation(device);}
            public boolean targetAvailable(int id)throws Exception{
                Display display=context.getSystemService(DisplayManager.class).getDisplay(id);
                return display!=null&&display.isValid()&&(display.getFlags()&Display.FLAG_PRIVATE)==0&&targetUniqueId.equals((String)uniqueId.invoke(display));
            }
            public void add(String descriptor,String id)throws Exception{add.invoke(service,descriptor,id);}
            public void remove(String descriptor)throws Exception{remove.invoke(service,descriptor);}
            public boolean awaitReleased(String descriptor,int target,int generation,boolean reconfigured,int timeout)throws Exception{return MouseRouting.this.awaitReleased(descriptor,target,generation,reconfigured,timeout);}
        });
    }
    private boolean probe(){
        if(probed)return add!=null;
        probed=true;
        try{
            Class<?> api=Class.forName("android.hardware.input.IInputManager");
            Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"input");
            service=Class.forName("android.hardware.input.IInputManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            Method candidate=api.getMethod("addUniqueIdAssociationByDescriptor",String.class,String.class);
            remove=api.getMethod("removeUniqueIdAssociationByDescriptor",String.class);
            associated=InputDevice.class.getMethod("getAssociatedDisplayId");
            deviceGeneration=InputDevice.class.getMethod("getGeneration");
            uniqueId=Display.class.getMethod("getUniqueId");
            deviceIds=api.getMethod("getInputDeviceIds");deviceInfo=api.getMethod("getInputDevice",int.class);
            add=candidate;
        }catch(Exception e){unavailable=TaskBackend.reason(e);}
        return add!=null;
    }
    synchronized String sync(int displayId,IBinder client){return sync(displayId,client,true);}
    private String sync(int displayId,IBinder client,boolean retryCleanup){
        try{
            Display display=context.getSystemService(DisplayManager.class).getDisplay(displayId);
            if(displayId<=0 || display==null || !display.isValid() || (display.getFlags()&Display.FLAG_PRIVATE)!=0 || client==null || !client.isBinderAlive()){
                release();return lease.isEmpty()?"inactive":"cleanup pending";
            }
            if(!probe())return "unavailable: "+unavailable;
            String identity=(String)uniqueId.invoke(display);
            if(target!=displayId || owner!=client || !targetUniqueId.equals(identity)){
                release();
                if(!lease.isEmpty())return "cleanup pending";
                owner=client;target=displayId;targetUniqueId=identity;
                long epoch=++eventGeneration;
                IBinder clientLease=client;
                death=()->{synchronized(MouseRouting.this){if(epoch==eventGeneration&&owner==clientLease)release();}};
                client.linkToDeath(death,0);startEvents(epoch);
                if(owner!=client)return lease.isEmpty()?"inactive":"cleanup pending";
            }
            InputManager manager=context.getSystemService(InputManager.class);
            Map<String,InputDevice> mice=new HashMap<>();
            for(int id:manager.getInputDeviceIds()){
                InputDevice device=manager.getInputDevice(id);
                // Mouse side buttons may advertise non-alphabetic KEYBOARD. Exclude real
                // typing keyboards, not those auxiliary mouse interfaces.
                if(device!=null && device.isExternal() && !device.isVirtual() && device.supportsSource(InputDevice.SOURCE_MOUSE)
                        && device.getKeyboardType()!=InputDevice.KEYBOARD_TYPE_ALPHABETIC && !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
                        && !device.supportsSource(InputDevice.SOURCE_TOUCHPAD))mice.put(device.getDescriptor(),device);
            }
            for(String descriptor:lease.descriptors())if(!mice.containsKey(descriptor))activeCleanupDebt.add(descriptor);
            // A failed removal may already have changed the native mapping. Even
            // if that mouse reappears, prove release before considering it routed.
            // InputReader reconfiguration callbacks must not defeat retry backoff.
            if(retryCleanup||cleanupRetry==null){
                cancelCleanupRetry();
                for(String descriptor:new HashSet<>(activeCleanupDebt)){
                    try{lease.release(descriptor);}catch(Exception ignored){/* Retain owned debt. */}
                    if(!lease.contains(descriptor))activeCleanupDebt.remove(descriptor);
                }
            }
            scheduleActiveCleanup();
            int skipped=0;
            for(Map.Entry<String,InputDevice> entry:mice.entrySet()){
                if(lease.contains(entry.getKey()))continue;
                // Preserve device-specific associations installed by another owner.
                if(!lease.route(entry.getKey(),displayId,targetUniqueId))skipped++;
            }
            if(!client.isBinderAlive()){release();return "inactive";}
            return "display="+displayId+", mice="+lease.size()+", preserved="+skipped
                    +(activeCleanupDebt.isEmpty()?"":", cleanup pending="+activeCleanupDebt.size());
        }catch(Exception e){release();return "unavailable: "+TaskBackend.reason(e);}
    }
    private InputDevice device(String descriptor)throws Exception{
        // Read the service directly: InputManager client caches can lag reconfiguration.
        for(int id:(int[])deviceIds.invoke(service)){
            InputDevice device=(InputDevice)deviceInfo.invoke(service,id);
            if(device!=null&&descriptor.equals(device.getDescriptor()))return device;
        }
        return null;
    }
    private int generation(InputDevice device)throws Exception{return (Integer)deviceGeneration.invoke(device);}
    private int association(String descriptor)throws Exception{
        InputDevice device=device(descriptor);return device==null?-1:(Integer)associated.invoke(device);
    }
    private boolean awaitReleased(String descriptor,int routingTarget,int generation,boolean reconfigured,int timeout)throws Exception{
        long started=android.os.SystemClock.uptimeMillis(),until=started+timeout;
        boolean dumped=false;
        do{
            InputDevice device=device(descriptor);
            if(device==null)return true;
            if((Integer)associated.invoke(device)!=routingTarget){
                Display display=context.getSystemService(DisplayManager.class).getDisplay(routingTarget);
                boolean targetLive=display!=null&&display.isValid()&&targetUniqueId.equals((String)uniqueId.invoke(display));
                if(!reconfigured||((Integer)associated.invoke(device)>=0&&generation(device)!=generation)
                        ||(targetLive&&generation(device)!=generation))return true;
                // A missing viewport can leave -1 both before and after native
                // reconfigure, with no generation bump. Verify the descriptor
                // cache itself once, after allowing the input thread to consume
                // the empty-ID write. Never persist/log the input dump.
                if(!dumped&&android.os.SystemClock.uptimeMillis()-started>=80){
                    dumped=true;if(readerCacheCleared(device.getId()))return true;
                }
            }
            android.os.SystemClock.sleep(20);
        }while(android.os.SystemClock.uptimeMillis()<until);
        return false;
    }
    private boolean readerCacheCleared(int deviceId)throws Exception{
        Process process=new ProcessBuilder("/system/bin/dumpsys","input").redirectErrorStream(true).start();
        FutureTask<String> output=new FutureTask<>(()->{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            try(InputStream input=process.getInputStream()){
                while((count=input.read(buffer))!=-1){if(bytes.size()+count>1024*1024)return "";bytes.write(buffer,0,count);}
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        });
        Thread reader=new Thread(output,"StellaMouseNativeRead");reader.setDaemon(true);reader.start();
        try{
            if(!process.waitFor(750,TimeUnit.MILLISECONDS)||process.exitValue()!=0)return false;
            return MouseRoutingLease.readerCacheCleared(output.get(100,TimeUnit.MILLISECONDS),deviceId);
        }catch(TimeoutException error){return false;}
        finally{process.destroy();output.cancel(true);}
    }
    private void ensureEventThread(){
        if(events!=null)return;
        eventThread=new HandlerThread("StellaMouseLease");eventThread.start();
        events=new Handler(eventThread.getLooper());
    }
    private void startEvents(long epoch){
        ensureEventThread();
        displayListener=new DisplayManager.DisplayListener(){
            public void onDisplayAdded(int id){checkDisplay(epoch,id,false);}
            public void onDisplayChanged(int id){checkDisplay(epoch,id,false);}
            public void onDisplayRemoved(int id){checkDisplay(epoch,id,true);}
        };
        inputListener=new InputManager.InputDeviceListener(){
            public void onInputDeviceAdded(int id){refreshInputs(epoch);}
            public void onInputDeviceChanged(int id){refreshInputs(epoch);}
            public void onInputDeviceRemoved(int id){refreshInputs(epoch);}
        };
        context.getSystemService(DisplayManager.class).registerDisplayListener(displayListener,events);
        context.getSystemService(InputManager.class).registerInputDeviceListener(inputListener,events);
        // Registration precedes routing, closing the hotplug gap without a poll.
        checkDisplay(epoch,target,false);
    }
    private synchronized void checkDisplay(long epoch,int id,boolean removed){
        if(epoch!=eventGeneration||owner==null||id!=target)return;
        try{
            Display display=context.getSystemService(DisplayManager.class).getDisplay(target);
            if(removed||!owner.isBinderAlive()||display==null||!display.isValid()||(display.getFlags()&Display.FLAG_PRIVATE)!=0
                    ||!targetUniqueId.equals((String)uniqueId.invoke(display)))release();
        }catch(Exception ignored){release();}
    }
    private synchronized void refreshInputs(long epoch){
        if(epoch!=eventGeneration||owner==null||inputRefresh!=null)return;
        inputRefresh=()->{
            synchronized(MouseRouting.this){
                if(epoch!=eventGeneration||owner==null)return;
                inputRefresh=null;
                sync(target,owner,false);
            }
        };
        events.post(inputRefresh);
    }
    private void cancelCleanupRetry(){
        if(cleanupRetry!=null&&events!=null)events.removeCallbacks(cleanupRetry);
        cleanupRetry=null;
    }
    private void scheduleActiveCleanup(){
        if(activeCleanupDebt.isEmpty()){
            cancelCleanupRetry();cleanupFailures=0;return;
        }
        if(cleanupRetry!=null)return;
        long epoch=eventGeneration;
        IBinder clientLease=owner;int targetLease=target;
        cleanupRetry=()->{
            synchronized(MouseRouting.this){
                if(epoch!=eventGeneration||owner!=clientLease||target!=targetLease)return;
                cleanupRetry=null;
                sync(targetLease,clientLease,true);
            }
        };
        long delay=Math.min(30000,1000L<<cleanupFailures);cleanupFailures=Math.min(5,cleanupFailures+1);
        events.postDelayed(cleanupRetry,delay);
    }
    private void stopEvents(){
        eventGeneration++;
        if(displayListener!=null){
            try{context.getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);}catch(RuntimeException ignored){}
            displayListener=null;
        }
        if(inputListener!=null){
            try{context.getSystemService(InputManager.class).unregisterInputDeviceListener(inputListener);}catch(RuntimeException ignored){}
            inputListener=null;
        }
        if(events!=null)events.removeCallbacksAndMessages(null);
        cleanupRetry=null;inputRefresh=null;activeCleanupDebt.clear();
    }
    synchronized void release(){
        stopEvents();
        if(owner!=null&&death!=null)try{owner.unlinkToDeath(death,0);}catch(RuntimeException ignored){}
        owner=null;death=null;
        lease.releaseAll();
        if(lease.isEmpty()){
            target=-1;targetUniqueId="";cleanupFailures=0;
            if(eventThread!=null){eventThread.quitSafely();eventThread=null;events=null;}
        }else {
            // Debt alone gets one actual delayed attempt, never idle health checks.
            // Keep target identity for the existing native-cache release proof.
            ensureEventThread();
            long epoch=eventGeneration;
            cleanupRetry=()->{synchronized(MouseRouting.this){if(epoch==eventGeneration&&owner==null)release();}};
            long delay=Math.min(30000,1000L<<cleanupFailures);cleanupFailures=Math.min(5,cleanupFailures+1);
            events.postDelayed(cleanupRetry,delay);
        }
    }
}
