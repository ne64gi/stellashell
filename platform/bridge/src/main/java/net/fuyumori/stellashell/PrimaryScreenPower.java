/*
 * Contains code adapted from scrcpy v3.3.1, DisplayControl.java.
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2025 Romain Vimont
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use the adapted code except in compliance with the License.
 * You may obtain a copy of the License at
 *     https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for StellaShell, 2026-09-29: the DisplayControl bootstrap uses
 * System.getenv and lazy tokenApi caching rather than a static initializer.
 * External-session checks, primary-only selection, Binder lease, event observers,
 * wake lock and restoration are StellaShell additions.
 * Upstream: https://github.com/Genymobile/scrcpy/blob/
 * f01231dff8294fe2c99045a4f9a14b233a71bb86/server/src/main/java/
 * com/genymobile/scrcpy/wrappers/DisplayControl.java
 * See THIRD_PARTY_NOTICES.md and assets/licenses/scrcpy-Apache-2.0.txt.
 */
package net.fuyumori.stellashell;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.ArrayList;
import java.util.List;

/** Session lease: physical primary panel only, never an external display or a sleep command. */
// Executed only in the Shizuku shell-UID user service, not in the application VM.
@android.annotation.SuppressLint({"BlockedPrivateApi","PrivateApi","SoonBlockedPrivateApi"})
final class PrimaryScreenPower {
    private final Context context;
    private IBinder token,owner;
    private IBinder.DeathRecipient death;
    private Method power;
    private static Class<?> tokenApi;
    private PowerManager.WakeLock wake;
    private int external=-1;
    private String externalUniqueId="";
    private Method uniqueId;
    private HandlerThread eventThread;
    private Handler events;
    private DisplayManager.DisplayListener displayListener;
    private BroadcastReceiver screenReceiver;
    private final List<Runnable> keyguardUnregister=new ArrayList<>();
    private long eventGeneration;
    private int restoreFailures;
    PrimaryScreenPower(Context c){context=c;}
    synchronized String sync(int displayId,boolean off,IBinder client){
        try{
            if(!off){release();return token==null&&wake==null?"on":"ERROR: Could not restore primary screen";}
            Display ext=context.getSystemService(DisplayManager.class).getDisplay(displayId);
            if(displayId<=0||ext==null||!ext.isValid()||(ext.getFlags()&Display.FLAG_PRIVATE)!=0||client==null||!client.isBinderAlive()
                    ||ext.getState()==Display.STATE_OFF||ext.getState()==Display.STATE_DOZE||ext.getState()==Display.STATE_DOZE_SUSPEND)throw new IllegalStateException("External desktop required");
            if(context.getSystemService(KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("Unlock the phone first");
            if(uniqueId==null)uniqueId=Display.class.getMethod("getUniqueId");
            String identity=(String)uniqueId.invoke(ext);
            PowerManager pm=context.getSystemService(PowerManager.class);
            if(!pm.isInteractive())throw new IllegalStateException("Wake the phone first");
            if(token!=null&&owner==client&&external==displayId&&externalUniqueId.equals(identity))return "off";
            release();if(token!=null||wake!=null)throw new IllegalStateException("Previous screen lease could not be released");
            Display primary=context.getSystemService(DisplayManager.class).getDisplay(0);
            Object address=Display.class.getMethod("getAddress").invoke(primary);
            long physical=(Long)address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
            Class<?> surface=Class.forName("android.view.SurfaceControl");
            power=surface.getMethod("setDisplayPowerMode",IBinder.class,int.class);
            Class<?> control=surface;
            if(Build.VERSION.SDK_INT>=34){
                if(tokenApi==null){
                // Android 14 moved physical-display tokens to services.jar (also used by scrcpy).
                Class<?> factory=Class.forName("com.android.internal.os.ClassLoaderFactory");
                ClassLoader loader=(ClassLoader)factory.getDeclaredMethod("createClassLoader",String.class,String.class,String.class,ClassLoader.class,int.class,boolean.class,String.class)
                    .invoke(null,System.getenv("SYSTEMSERVERCLASSPATH"),null,null,ClassLoader.getSystemClassLoader(),0,true,null);
                control=loader.loadClass("com.android.server.display.DisplayControl");
                Method load=Runtime.class.getDeclaredMethod("loadLibrary0",Class.class,String.class);load.setAccessible(true);load.invoke(Runtime.getRuntime(),control,"android_servers");tokenApi=control;
                }
                control=tokenApi;
            }
            IBinder candidate=(IBinder)control.getMethod("getPhysicalDisplayToken",long.class).invoke(null,physical);
            if(candidate==null)throw new IllegalStateException("Primary display token unavailable");
            owner=client;external=displayId;externalUniqueId=identity;
            long epoch=++eventGeneration;
            death=()->{synchronized(PrimaryScreenPower.this){if(epoch==eventGeneration&&owner==client)release();}};
            client.linkToDeath(death,0);startEvents(epoch);
            if(owner!=client)throw new IllegalStateException("Screen lease ended");
            wake=pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"StellaShell:ExternalDesktop");wake.setReferenceCounted(false);wake.acquire();
            token=candidate;power.invoke(null,token,0);
            return "off";
        }catch(Exception e){release();return "ERROR: "+TaskBackend.reason(e);}
    }
    private void ensureEventThread(){
        if(events!=null)return;
        eventThread=new HandlerThread("StellaPrimaryLease");eventThread.start();
        events=new Handler(eventThread.getLooper());
    }
    private void startEvents(long epoch){
        ensureEventThread();
        displayListener=new DisplayManager.DisplayListener(){
            public void onDisplayAdded(int id){checkSession(epoch,id,false);}
            public void onDisplayChanged(int id){checkSession(epoch,id,false);}
            public void onDisplayRemoved(int id){checkSession(epoch,id,true);}
        };
        context.getSystemService(DisplayManager.class).registerDisplayListener(displayListener,events);
        screenReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context ignored,Intent intent){
                synchronized(PrimaryScreenPower.this){
                    if(epoch!=eventGeneration||owner==null)return;
                    if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))release();
                    else checkSession(epoch,external,false);
                }
            }
        };
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_USER_PRESENT);
        // These are protected system broadcasts; the dedicated looper is also
        // valid in the shell user-service process without an application main.
        context.registerReceiver(screenReceiver,filter,null,events);
        registerKeyguardEvents(epoch,"KeyguardLocked");
        registerKeyguardEvents(epoch,"DeviceLocked");
        checkSession(epoch,external,false);
    }
    private void registerKeyguardEvents(long epoch,String kind){
        // Shell has the privileged keyguard permission on AOSP. Android 13+
        // has keyguard events; Android 16+ may also expose device-lock events.
        // Older/OEM APIs still have the protected screen/display events.
        try{
            Class<?> api=Class.forName("android.app.KeyguardManager$"+kind+"StateListener");
            Object listener=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)->{
                if(("on"+kind+"StateChanged").equals(method.getName())){
                    synchronized(PrimaryScreenPower.this){if(epoch==eventGeneration&&owner!=null)checkSession(epoch,external,false);}
                    return null;
                }
                if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);
                if("equals".equals(method.getName()))return proxy==args[0];
                return "StellaPrimary"+kind+"Listener";
            });
            KeyguardManager manager=context.getSystemService(KeyguardManager.class);
            Method remove=KeyguardManager.class.getMethod("remove"+kind+"StateListener",api);
            Handler callbackHandler=events;
            Executor executor=command->callbackHandler.post(command);
            // Registration can update the framework's local listener map before
            // a failed Binder reply. Always retain its matching unregister.
            keyguardUnregister.add(()->{try{remove.invoke(manager,listener);}catch(Exception ignored){}});
            KeyguardManager.class.getMethod("add"+kind+"StateListener",Executor.class,api).invoke(manager,executor,listener);
        }catch(Exception ignored){/* Screen-off remains a terminal event. */}
    }
    private synchronized void checkSession(long epoch,int id,boolean removed){
        if(epoch!=eventGeneration||owner==null||id!=external&&id!=Display.DEFAULT_DISPLAY)return;
        try{
            Display display=context.getSystemService(DisplayManager.class).getDisplay(external);
            if(removed||!owner.isBinderAlive()||display==null||!display.isValid()||(display.getFlags()&Display.FLAG_PRIVATE)!=0
                    ||display.getState()==Display.STATE_OFF||display.getState()==Display.STATE_DOZE||display.getState()==Display.STATE_DOZE_SUSPEND
                    ||!externalUniqueId.equals((String)uniqueId.invoke(display))
                    ||context.getSystemService(KeyguardManager.class).isDeviceLocked()
                    ||!context.getSystemService(PowerManager.class).isInteractive())release();
        }catch(Exception ignored){release();}
    }
    private void stopEvents(){
        eventGeneration++;
        if(displayListener!=null){
            try{context.getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);}catch(RuntimeException ignored){}
            displayListener=null;
        }
        if(screenReceiver!=null){
            try{context.unregisterReceiver(screenReceiver);}catch(RuntimeException ignored){}
            screenReceiver=null;
        }
        for(Runnable unregister:keyguardUnregister)unregister.run();
        keyguardUnregister.clear();
        if(events!=null)events.removeCallbacksAndMessages(null);
    }
    synchronized void release(){
        stopEvents();
        if(owner!=null&&death!=null)try{owner.unlinkToDeath(death,0);}catch(RuntimeException ignored){}
        owner=null;death=null;external=-1;externalUniqueId="";
        // The authorized panel-off mode deliberately holds SCREEN_BRIGHT while
        // active, but no restoration failure may keep that lease awake.
        if(wake!=null)try{if(wake.isHeld())wake.release();wake=null;}catch(RuntimeException ignored){/* Keep wake-release debt too. */}
        if(token!=null)try{power.invoke(null,token,2);token=null;}catch(Exception ignored){}
        if(token==null&&wake==null){
            restoreFailures=0;
            if(eventThread!=null){eventThread.quitSafely();eventThread=null;events=null;}
        }else{
            // Retain any debt and the exact physical token (never resolve a reused display ID)
            // and post only the next bounded restoration attempt.
            ensureEventThread();
            long epoch=eventGeneration;
            long delay=Math.min(30000,1000L<<restoreFailures);restoreFailures=Math.min(5,restoreFailures+1);
            events.postDelayed(()->{synchronized(PrimaryScreenPower.this){if(epoch==eventGeneration&&owner==null)release();}},delay);
        }
    }
}
