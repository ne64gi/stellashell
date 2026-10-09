package net.fuyumori.stellashell;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Real Android configuration dispatch + native metrics, in an independent app_process VM only.
 * No native Window, app lifecycle, input, rotation/settings mutation, or production preference file.
 * An optional "landscape" argument requires an already-landscape native display; never rotates it.
 */
public final class WorkAreaRotationChecks {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final MemoryPreferences preferences=new MemoryPreferences();
    private final boolean requireLandscape;
    private final Context context;
    private final Display display;
    private WorkAreaObserver observer;
    private View probe;
    private WorkArea expected;
    private int additions,removals,callbacks,metricsReads;
    private boolean finished;

    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    /** Invoke in a disposable app_process process, with main + androidTest APKs on CLASSPATH. */
    public static void main(String[] args){
        try{
            if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
            check(Looper.myLooper()==Looper.getMainLooper(),"Fixture must own its process main looper");
            Class<?> thread=Class.forName("android.app.ActivityThread");
            Method bootstrap=thread.getDeclaredMethod("systemMain");bootstrap.setAccessible(true);
            Object systemThread=bootstrap.invoke(null);
            Method systemContext=thread.getDeclaredMethod("getSystemContext");systemContext.setAccessible(true);
            Context base=(Context)systemContext.invoke(systemThread);
            boolean landscape=args.length==1&&"landscape".equals(args[0]);
            check(args.length==0||landscape,"Only optional argument is landscape (prerequisite, not a rotation command)");
            WorkAreaRotationChecks checks=new WorkAreaRotationChecks(base,landscape);
            checks.start();Looper.loop();
            throw new AssertionError("Fixture main looper unexpectedly returned");
        }catch(Throwable error){error.printStackTrace(System.err);System.exit(1);}
    }

    private WorkAreaRotationChecks(Context base,boolean requireLandscape){
        this.requireLandscape=requireLandscape;
        display=base.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        check(display!=null&&display.isValid(),"Native display-0 prerequisite unavailable");
        Context nativeContext=base.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        WindowManager actual=nativeContext.getSystemService(WindowManager.class);
        WindowManager proxy=(WindowManager)Proxy.newProxyInstance(WindowManager.class.getClassLoader(),new Class<?>[]{WindowManager.class},(owner,method,args)->{
            switch(method.getName()){
                case "addView":
                    check(probe==null,"Observer added more than one probe");probe=(View)args[0];additions++;return null;
                case "removeView":case "removeViewImmediate":
                    check(args[0]==probe,"Observer tried to remove another View");removals++;return null;
                case "updateViewLayout":throw new AssertionError("Observer fixture must not update native windows");
                case "getCurrentWindowMetrics":metricsReads++;break;
            }
            try{return method.invoke(actual,args);}catch(InvocationTargetException error){throw error.getCause();}
        });
        context=new Sandbox(nativeContext,preferences,proxy,ShellSettings.isolated(preferences));
    }

    private void start(){
        main.postDelayed(()->finish(new AssertionError("Configuration fixture exceeded 8-second bound")),8000);
        phase(()->{
            expected=readNative();
            check(!requireLandscape||expected.physical.width()>expected.physical.height(),
                "Prerequisite failed: native display is not landscape; fixture will not rotate it");
            observer=new WorkAreaObserver(context,display.getDisplayId(),()->callbacks++);
            check(additions==1&&probe!=null&&!probe.isAttachedToWindow(),"Proxy accidentally attached a native Window");
            drainPosts();
            main.postDelayed(()->phase(this::configurationEvent),260);
        });
    }

    private WorkArea readNative(){
        WindowManager windows=context.getSystemService(WindowManager.class);
        WorkArea value=WorkArea.read(context,windows.getCurrentWindowMetrics().getWindowInsets());
        Point size=new Point();display.getRealSize(size);
        check(value.physical.equals(new Rect(0,0,size.x,size.y)),"Oracle does not reflect native Display geometry");
        return value;
    }
    private void stableNative(){check(readNative().same(expected),"Native geometry/insets changed during bounded fixture; no rotation commands were issued");}

    private void configurationEvent(){
        stableNative();check(callbacks==1,"Constructor baseline did not debounce to one callback");
        int width=expected.physical.width(),height=expected.physical.height();
        // On a landscape target, seed the exact transposed stale-portrait dimensions from the bug.
        // Portrait targets still exercise invalid portrait cache replacement, not a landscape claim.
        Rect portrait=width>height?new Rect(0,0,height,width):new Rect(0,0,width+1,height+2);
        WorkArea stale=new WorkArea(portrait,portrait,false,0,0);
        check(!stale.same(expected)&&portrait.height()>portrait.width(),"Fixture did not seed distinct stale portrait geometry");
        WorkArea.put(display.getDisplayId(),stale);
        check(WorkArea.get(context,display.getDisplayId())==stale,"Stale cache prerequisite failed");
        int reads=metricsReads;
        probe.dispatchConfigurationChanged(nativeConfiguration()); // Sole production update trigger.
        check(metricsReads>reads,"View configuration notification never reached native metrics refresh");
        check(WorkArea.get(context,display.getDisplayId()).same(expected),"Configuration event retained stale portrait WorkArea cache");
        check(callbacks==1,"Configuration callback bypassed the existing debounce");
        drainPosts();main.postDelayed(()->phase(this::duplicateEvent),260);
    }

    private void duplicateEvent(){
        stableNative();check(callbacks==2,"Changed configuration did not deliver exactly one debounced callback");
        int reads=metricsReads;
        probe.dispatchConfigurationChanged(nativeConfiguration());
        probe.dispatchConfigurationChanged(nativeConfiguration());
        check(metricsReads>reads,"Duplicate configuration events did not exercise production dispatch");
        check(WorkArea.get(context,display.getDisplayId()).same(expected),"Duplicate event corrupted fresh geometry");
        drainPosts();main.postDelayed(()->phase(this::closedEvent),260);
    }

    private void closedEvent(){
        stableNative();check(callbacks==2,"Unchanged geometry posted an unnecessary changed callback");
        observer.close();check(removals==1&&!probe.isAttachedToWindow(),"Observer close did not remove only the synthetic probe");
        WorkArea sentinel=new WorkArea(new Rect(0,0,321,654),new Rect(0,0,321,654),false,0,0);
        WorkArea.put(display.getDisplayId(),sentinel);
        Map<String,?> before=preferences.getAll();int reads=metricsReads;
        probe.dispatchConfigurationChanged(nativeConfiguration()); // Late event after observer close.
        check(metricsReads==reads,"Closed observer read native metrics on late configuration");
        check(WorkArea.get(context,display.getDisplayId())==sentinel,"Closed observer rewrote/removed sentinel cache");
        check(preferences.getAll().equals(before),"Closed observer wrote diagnostics/preferences on late configuration");
        drainPosts();main.postDelayed(()->phase(()->{
            check(callbacks==2&&WorkArea.get(context,display.getDisplayId())==sentinel,"Closed observer delivered a late callback or cache write");
            check(preferences.getAll().equals(before),"Late callback changed isolated preferences");
            finish(null);
        }),260);
    }

    private Configuration nativeConfiguration(){
        Configuration value=new Configuration(context.getResources().getConfiguration());
        value.orientation=expected.physical.width()>expected.physical.height()?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
        return value;
    }
    /** Detached View posts have no ViewRootImpl to drain them; execute its actual framework queue. */
    private void drainPosts(){
        try{
            Method queueMethod=View.class.getDeclaredMethod("getRunQueue");queueMethod.setAccessible(true);
            Object queue=queueMethod.invoke(probe);
            Method execute=queue.getClass().getDeclaredMethod("executeActions",Handler.class);execute.setAccessible(true);execute.invoke(queue,main);
        }catch(ReflectiveOperationException error){throw new AssertionError("Android detached-View queue API unavailable; runtime fixture cannot be claimed",error);}
    }
    private void phase(Runnable action){if(finished)return;try{action.run();}catch(Throwable error){finish(error);}}
    private void finish(Throwable failure){
        if(finished)return;finished=true;
        try{if(observer!=null)observer.close();WorkArea.remove(display.getDisplayId());((Sandbox)context).settings.close();}
        catch(Throwable cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        if(failure!=null){failure.printStackTrace(System.err);System.exit(1);return;}
        System.out.println("PASS: WorkArea configuration dispatch refresh + changed debounce + duplicate suppression + closed late-event guard; native="
            +expected.physical.width()+"x"+expected.physical.height()+" landscape="+(expected.physical.width()>expected.physical.height())
            +"; no native windows, rotation/input/settings changes, app lifecycle or production preferences");
        System.exit(0);
    }

    private static final class Sandbox extends ContextWrapper implements ShellSettings.Provider {
        private final MemoryPreferences preferences;private final WindowManager windows;private final ShellSettings settings;
        Sandbox(Context base,MemoryPreferences preferences,WindowManager windows,ShellSettings settings){super(base);this.preferences=preferences;this.windows=windows;this.settings=settings;}
        public ShellSettings shellSettings(){return settings;}
        @Override public Context getApplicationContext(){return this;}
        @Override public Object getSystemService(String name){return Context.WINDOW_SERVICE.equals(name)?windows:super.getSystemService(name);}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){check("desktop".equals(name),"Unexpected preference domain: "+name);return preferences;}
        @Override public Context createDisplayContext(Display display){return new Sandbox(super.createDisplayContext(display),preferences,windows,settings);}
        @Override public Context createWindowContext(int type,Bundle options){return new Sandbox(super.createWindowContext(type,options),preferences,windows,settings);}
        @Override public void startActivity(Intent intent){throw new AssertionError("Fixture cannot launch activities");}
        @Override public void startActivity(Intent intent,Bundle options){throw new AssertionError("Fixture cannot launch activities");}
        @Override public ComponentName startService(Intent intent){throw new AssertionError("Fixture cannot start services");}
        @Override public ComponentName startForegroundService(Intent intent){throw new AssertionError("Fixture cannot start services");}
        @Override public boolean stopService(Intent intent){throw new AssertionError("Fixture cannot stop services");}
    }
    /** Android SharedPreferences contract backed only by this disposable process's memory. */
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String,Object> values=new HashMap<>();
        private final Set<OnSharedPreferenceChangeListener> listeners=new HashSet<>();
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String key,String fallback){return (String)values.getOrDefault(key,fallback);}
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> fallback){Set<String> value=(Set<String>)values.get(key);return value==null?fallback:new HashSet<>(value);}
        public int getInt(String key,int fallback){return (Integer)values.getOrDefault(key,fallback);}
        public long getLong(String key,long fallback){return (Long)values.getOrDefault(key,fallback);}
        public float getFloat(String key,float fallback){return (Float)values.getOrDefault(key,fallback);}
        public boolean getBoolean(String key,boolean fallback){return (Boolean)values.getOrDefault(key,fallback);}
        public boolean contains(String key){return values.containsKey(key);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){listeners.add(listener);}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){listeners.remove(listener);}
        public Editor edit(){return new Editor(){
            final Map<String,Object> pending=new HashMap<>();boolean clear;
            public Editor putString(String key,String value){pending.put(key,value);return this;}
            public Editor putStringSet(String key,Set<String> value){pending.put(key,value==null?null:new HashSet<>(value));return this;}
            public Editor putInt(String key,int value){pending.put(key,value);return this;}
            public Editor putLong(String key,long value){pending.put(key,value);return this;}
            public Editor putFloat(String key,float value){pending.put(key,value);return this;}
            public Editor putBoolean(String key,boolean value){pending.put(key,value);return this;}
            public Editor remove(String key){pending.put(key,null);return this;}
            public Editor clear(){clear=true;return this;}
            public boolean commit(){
                Map<String,Object> before=new HashMap<>(values);if(clear)values.clear();
                for(Map.Entry<String,Object> entry:pending.entrySet())if(entry.getValue()==null)values.remove(entry.getKey());else values.put(entry.getKey(),entry.getValue());
                Set<String> keys=new HashSet<>(before.keySet());keys.addAll(values.keySet());
                for(String key:keys)if(!Objects.equals(before.get(key),values.get(key)))for(OnSharedPreferenceChangeListener listener:new HashSet<>(listeners))listener.onSharedPreferenceChanged(MemoryPreferences.this,key);
                return true;
            }
            public void apply(){commit();}
        };}
    }
}
