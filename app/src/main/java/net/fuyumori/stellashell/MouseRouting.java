package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.view.Display;
import android.view.InputDevice;
import java.lang.reflect.Method;
import java.util.*;

/** Shell-UID, session-scoped routing of otherwise unassigned physical mice. */
final class MouseRouting {
    private final Context context;
    private Object service;
    private Method add,remove,associated,uniqueId,deviceIds,deviceInfo;
    private boolean probed;
    private String unavailable="";
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private int target=-1;
    private final Set<String> owned=new HashSet<>();
    MouseRouting(Context context){this.context=context;}
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
                release();return owned.isEmpty()?"inactive":"cleanup pending";
            }
            if(!probe())return "unavailable: "+unavailable;
            if(target!=displayId || owner!=client){
                release();
                if(!owned.isEmpty())return "cleanup pending";
                owner=client;target=displayId;
                IBinder lease=client;
                death=()->{synchronized(MouseRouting.this){if(owner==lease)release();}};
                client.linkToDeath(death,0);
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
            for(String descriptor:new HashSet<>(owned))if(!mice.containsKey(descriptor)){
                unroute(descriptor);
            }
            int skipped=0;
            for(Map.Entry<String,InputDevice> entry:mice.entrySet()){
                if(owned.contains(entry.getKey()))continue;
                // Preserve device-specific associations installed by another owner.
                if((Integer)associated.invoke(entry.getValue())>=0){skipped++;continue;}
                add.invoke(service,entry.getKey(),(String)uniqueId.invoke(display));owned.add(entry.getKey());
            }
            if(!client.isBinderAlive()){release();return "inactive";}
            return "display="+displayId+", mice="+owned.size()+", preserved="+skipped;
        }catch(Exception e){release();return "unavailable: "+TaskBackend.reason(e);}
    }
    private int association(String descriptor)throws Exception{
        // Read the service directly: InputManager client caches can lag reconfiguration.
        for(int id:(int[])deviceIds.invoke(service)){
            InputDevice device=(InputDevice)deviceInfo.invoke(service,id);
            if(device!=null && descriptor.equals(device.getDescriptor()))return (Integer)associated.invoke(device);
        }
        return -1;
    }
    private boolean awaitReleased(String descriptor,int timeout)throws Exception{
        long until=android.os.SystemClock.uptimeMillis()+timeout;
        do{
            if(association(descriptor)!=target)return true;
            android.os.SystemClock.sleep(20);
        }while(android.os.SystemClock.uptimeMillis()<until);
        return false;
    }
    private void unroute(String descriptor)throws Exception{
        int current=association(descriptor);
        if(current>=0 && current!=target){owned.remove(descriptor);return;}
        remove.invoke(service,descriptor);
        if(!awaitReleased(descriptor,160)){
            // Some Android 16 input readers retain the removed descriptor value.
            // An empty value clears that cached viewport. Wait for native reconfigure
            // before removing the temporary value, or the two changes may coalesce.
            add.invoke(service,descriptor,"");
            if(!awaitReleased(descriptor,500))throw new IllegalStateException("Mouse routing cleanup did not complete");
            remove.invoke(service,descriptor);
        }
        owned.remove(descriptor);
    }
    synchronized void release(){
        for(String descriptor:new HashSet<>(owned))try{unroute(descriptor);}catch(Exception ignored){/* Retain ownership for the next cleanup attempt. */}
        if(owner!=null && death!=null)try{owner.unlinkToDeath(death,0);}catch(RuntimeException ignored){}
        owner=null;death=null;if(owned.isEmpty())target=-1;
    }
}
