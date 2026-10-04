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
 * External-session checks, primary-only selection, Binder lease, watchdog,
 * wake lock and restoration are StellaShell additions.
 * Upstream: https://github.com/Genymobile/scrcpy/blob/
 * f01231dff8294fe2c99045a4f9a14b233a71bb86/server/src/main/java/
 * com/genymobile/scrcpy/wrappers/DisplayControl.java
 * See THIRD_PARTY_NOTICES.md and assets/licenses/scrcpy-Apache-2.0.txt.
 */
package net.fuyumori.stellashell;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;
import java.lang.reflect.Method;
import java.util.concurrent.*;

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
    private ScheduledExecutorService watchdog;
    PrimaryScreenPower(Context c){context=c;}
    synchronized String sync(int displayId,boolean off,IBinder client){
        try{
            if(!off){release();return token==null?"on":"ERROR: Could not restore primary screen";}
            Display ext=context.getSystemService(DisplayManager.class).getDisplay(displayId);
            if(displayId<=0||ext==null||!ext.isValid()||(ext.getFlags()&Display.FLAG_PRIVATE)!=0||client==null||!client.isBinderAlive())throw new IllegalStateException("External desktop required");
            if(context.getSystemService(KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("Unlock the phone first");
            if(token!=null&&owner==client&&external==displayId)return "off";
            release();if(token!=null)throw new IllegalStateException("Previous screen lease could not be released");
            PowerManager pm=context.getSystemService(PowerManager.class);
            if(!pm.isInteractive())throw new IllegalStateException("Wake the phone first");
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
            owner=client;external=displayId;death=()->release();client.linkToDeath(death,0);
            wake=pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"StellaShell:ExternalDesktop");wake.setReferenceCounted(false);wake.acquire();
            token=candidate;power.invoke(null,token,0);
            watchdog=Executors.newSingleThreadScheduledExecutor();watchdog.scheduleWithFixedDelay(()->{
                synchronized(PrimaryScreenPower.this){Display d=context.getSystemService(DisplayManager.class).getDisplay(external);if(owner==null||!owner.isBinderAlive()||d==null||!d.isValid()||context.getSystemService(KeyguardManager.class).isDeviceLocked())release();}
            },1,1,TimeUnit.SECONDS);
            return "off";
        }catch(Exception e){release();return "ERROR: "+TaskBackend.reason(e);}
    }
    synchronized void release(){
        if(watchdog!=null){watchdog.shutdown();watchdog=null;}
        if(token!=null)try{power.invoke(null,token,2);token=null;}catch(Exception ignored){}
        if(wake!=null){if(wake.isHeld())wake.release();wake=null;}
        if(owner!=null&&death!=null)owner.unlinkToDeath(death,0);owner=null;death=null;external=-1;
    }
}
