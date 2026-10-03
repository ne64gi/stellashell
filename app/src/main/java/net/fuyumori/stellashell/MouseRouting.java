package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.IBinder;
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
    private ScheduledExecutorService watchdog;private long watchdogGeneration,nextCleanup;private int cleanupFailures;
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
    synchronized String sync(int displayId,IBinder client){
        try{
            Display display=context.getSystemService(DisplayManager.class).getDisplay(displayId);
            if(displayId<=0 || display==null || !display.isValid() || (display.getFlags()&Display.FLAG_PRIVATE)!=0 || client==null || !client.isBinderAlive()){
                release();return lease.isEmpty()?"inactive":"cleanup pending";
            }
            if(!probe())return "unavailable: "+unavailable;
            if(target!=displayId || owner!=client){
                release();
                if(!lease.isEmpty())return "cleanup pending";
                owner=client;target=displayId;targetUniqueId=(String)uniqueId.invoke(display);
                IBinder clientLease=client;
                death=()->{synchronized(MouseRouting.this){if(owner==clientLease)release();}};
                client.linkToDeath(death,0);startWatchdog();
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
            for(String descriptor:lease.descriptors())if(!mice.containsKey(descriptor)){
                lease.release(descriptor);
            }
            int skipped=0;
            for(Map.Entry<String,InputDevice> entry:mice.entrySet()){
                if(lease.contains(entry.getKey()))continue;
                // Preserve device-specific associations installed by another owner.
                if(!lease.route(entry.getKey(),displayId,targetUniqueId))skipped++;
            }
            if(!client.isBinderAlive()){release();return "inactive";}
            return "display="+displayId+", mice="+lease.size()+", preserved="+skipped;
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
    private void startWatchdog(){
        if(watchdog!=null)return;
        long epoch=++watchdogGeneration;
        watchdog=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"StellaMouseLease");thread.setDaemon(true);return thread;});
        watchdog.scheduleWithFixedDelay(()->{
            synchronized(MouseRouting.this){
                if(epoch!=watchdogGeneration||owner==null&&android.os.SystemClock.uptimeMillis()<nextCleanup)return;
                try{
                    Display display=context.getSystemService(DisplayManager.class).getDisplay(target);
                    if(owner==null||!owner.isBinderAlive()||display==null||!display.isValid()||(display.getFlags()&Display.FLAG_PRIVATE)!=0
                            ||!targetUniqueId.equals((String)uniqueId.invoke(display)))release();
                }catch(Exception ignored){release();}
            }
        },1,1,TimeUnit.SECONDS);
    }
    synchronized void release(){
        lease.releaseAll();
        if(owner!=null&&death!=null)try{owner.unlinkToDeath(death,0);}catch(RuntimeException ignored){}
        owner=null;death=null;
        if(lease.isEmpty()){
            target=-1;targetUniqueId="";nextCleanup=0;cleanupFailures=0;
            if(watchdog!=null){ScheduledExecutorService previous=watchdog;watchdog=null;watchdogGeneration++;previous.shutdown();}
        }else {
            // Each native attempt is bounded. Idle cleanup retries back off to
            // avoid repeated dumps on an unsupported/OEM reader format; explicit
            // sync/reset still retries immediately.
            nextCleanup=android.os.SystemClock.uptimeMillis()+Math.min(30000,1000L<<cleanupFailures);cleanupFailures=Math.min(5,cleanupFailures+1);startWatchdog();
        }
    }
}
