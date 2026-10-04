package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.view.Display;
import java.lang.reflect.Method;

/** Scoped IME policy: never changes the default display or global keyboard settings. */
final class VirtualKeyboardPolicy {
    private static final int HIDE=2;
    private final Context context;
    private Object service;
    private Method get,set,unique,type;
    private int virtualType;
    private int target=-1,before;
    private String identity;
    private IBinder owner;
    private IBinder.DeathRecipient death;
    VirtualKeyboardPolicy(Context context){this.context=context;}
    private void probe()throws Exception{
        if(service!=null)return;
        Class<?> api=Class.forName("android.view.IWindowManager");
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
        get=api.getMethod("getDisplayImePolicy",int.class);set=api.getMethod("setDisplayImePolicy",int.class,int.class);
        unique=Display.class.getMethod("getUniqueId");type=Display.class.getMethod("getType");virtualType=Display.class.getField("TYPE_VIRTUAL").getInt(null);
        service=Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }
    synchronized String sync(int displayId,boolean hide,IBinder client){
        try{
            Display display=context.getSystemService(DisplayManager.class).getDisplay(displayId);
            if(!hide || displayId<=0 || display==null || !display.isValid()
                    || (display.getFlags()&Display.FLAG_PRIVATE)!=0 || client==null || !client.isBinderAlive()){
                release();return target<0?"inactive":"restore pending";
            }
            probe();
            if((Integer)type.invoke(display)!=virtualType){release();return target<0?"inactive (physical display)":"restore pending";}
            String id=(String)unique.invoke(display);
            if(target!=displayId || !id.equals(identity) || owner!=client){
                release();if(target>=0)return "restore pending";
                before=(Integer)get.invoke(service,displayId);target=displayId;identity=id;owner=client;
                IBinder lease=client;death=()->{synchronized(VirtualKeyboardPolicy.this){if(owner==lease)release();}};
                client.linkToDeath(death,0);
                set.invoke(service,displayId,HIDE);
            }
            if(!client.isBinderAlive()){release();return "inactive";}
            return (Integer)get.invoke(service,displayId)==HIDE?"hidden on virtual display="+displayId:"policy changed externally";
        }catch(Exception e){release();return "unavailable: "+TaskBackend.reason(e);}
    }
    synchronized void release(){
        if(target>=0)try{
            Display display=context.getSystemService(DisplayManager.class).getDisplay(target);
            // Never touch a reused ID or overwrite a subsequent policy from another owner.
            if(display!=null && display.isValid() && identity.equals(unique.invoke(display)) && (Integer)get.invoke(service,target)==HIDE)
                set.invoke(service,target,before);
            target=-1;identity=null;
        }catch(Exception e){return;}
        if(owner!=null&&death!=null)try{owner.unlinkToDeath(death,0);}catch(RuntimeException ignored){}
        owner=null;death=null;
    }
}
