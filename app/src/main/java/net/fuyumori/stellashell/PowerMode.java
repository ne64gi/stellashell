package net.fuyumori.stellashell;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fuyumori.stellashell.core.power.PowerSession;

/** Sole stored owner of optional Extensions intent and its cleanup transition. */
public final class PowerMode {
    public interface Provider { PowerMode powerMode(); }
    interface Backend {
        void connect();
        void resumeHome();
        void powerChanged(boolean requested,Bridge.Reply reply);
        void retry(Bridge.Reply reply);
    }
    private static PowerMode shared;
    private final SharedPreferences preferences;
    private final Backend backend;
    private final PowerSession state;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    public static synchronized PowerMode of(Context context){
        if(context instanceof Provider)return ((Provider)context).powerMode();
        Context app=context.getApplicationContext();
        if(app instanceof Provider)return ((Provider)app).powerMode();
        if(shared==null){initializeLegacy(context);shared=new PowerMode(Launches.prefs(app),production(app));}
        return shared;
    }
    /** Call before any startup adapter writes defaults, so a clean install stays OFF. */
    public static synchronized void initializeLegacy(Context context){
        if(context instanceof Provider||context.getApplicationContext() instanceof Provider)return;
        migrate(Launches.prefs(context));
    }
    private static void migrate(SharedPreferences prefs){
        if(prefs.contains("power_mode"))return;
        boolean legacy=false;
        for(String key:new String[]{"enabled","primary_mode","before_desktop","phone_profile_initialized","workspace_auto"})
            legacy|=prefs.contains(key);
        if(!prefs.edit().putBoolean("power_mode",PowerSession.migrate(null,legacy)).commit())
            prefs.edit().putBoolean("power_mode",false).commit();
    }
    /** Isolated preference owner for nonce-context instrumentation; no singleton/global writes. */
    static PowerMode forStore(Context context,String name){
        return forStore(context,name,new Backend(){
            public void connect(){}
            public void resumeHome(){}
            public void powerChanged(boolean requested,Bridge.Reply reply){reply.done(null,"Isolated Extensions requires a backend");}
            public void retry(Bridge.Reply reply){reply.done(null,"Isolated Extensions requires a backend");}
        });
    }
    static PowerMode forStore(Context context,String name,Backend backend){
        SharedPreferences prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);
        migrate(prefs);return new PowerMode(prefs,backend);
    }
    static PowerMode forPreferences(SharedPreferences preferences,Backend backend){
        migrate(preferences);return new PowerMode(preferences,backend);
    }
    private static Backend production(Context context){
        return new Backend(){
            public void connect(){Bridge.get(context).connect();}
            public void resumeHome(){Bridge bridge=Bridge.get(context);bridge.connect();bridge.startShortcut();}
            public void powerChanged(boolean requested,Bridge.Reply reply){Bridge.get(context).powerChanged(requested,reply);}
            public void retry(Bridge.Reply reply){Bridge.get(context).retryReleasePower(reply);}
        };
    }
    private PowerMode(SharedPreferences preferences,Backend backend){
        this.backend=backend;this.preferences=preferences;
        state=new PowerSession(preferences.getBoolean("power_mode",false),
                preferences.getBoolean("power_cleanup_pending",false));
    }
    public boolean requested(){return state.requested();}
    public boolean active(){return state.active();}
    public boolean transition(){return state.transition();}
    public void connect(){if(requested()&&!transition())backend.connect();}
    public void resumeHome(){if(requested()&&!transition())backend.resumeHome();}
    public void observe(Runnable listener){listeners.add(listener);}
    public void remove(Runnable listener){listeners.remove(listener);}
    public void setRequested(boolean value,Bridge.Reply reply){
        if(transition()){reply.done(null,"Extensions cleanup is pending");return;}
        if(requested()==value){reply.done("OK",null);return;}
        if(!persistIntent(value,!value)){reply.done(null,"Could not save Extensions state");return;}
        if(!state.request(value)){reply.done("OK",null);return;}
        changed();backend.powerChanged(value,value?reply:cleanupReply(reply));
    }
    public void retry(Bridge.Reply reply){
        if(!transition()||requested()){reply.done("OK",null);return;}
        if(state.phase()==PowerSession.Phase.RELEASING){reply.done(null,"Extensions cleanup is in progress");return;}
        if(!persistIntent(false,true)){reply.done(null,"Could not save Extensions cleanup state");return;}
        state.retry();changed();backend.retry(cleanupReply(reply));
    }
    long generation(){return state.generation();}
    boolean permits(long generation){return state.permits(generation);}
    void connected(boolean value){state.connected(value);changed();}
    private Bridge.Reply cleanupReply(Bridge.Reply reply){
        long generation=state.generation();
        AtomicBoolean completed=new AtomicBoolean();
        return (result,error)->{
            if(!completed.compareAndSet(false,true))return;
            if(state.generation()!=generation||state.requested()){
                reply.done(null,"Extensions request changed");return;
            }
            boolean success=error==null&&"OK".equals(result);
            if(success&&!persistIntent(false,false)){
                success=false;error="Could not save completed Extensions cleanup";
            }
            state.released(success);changed();
            reply.done(success?"OK":null,error==null&&!success?"Extensions cleanup is pending":error);
        };
    }
    private boolean persistIntent(boolean requested,boolean pending){
        boolean hadRequested=preferences.contains("power_mode"),oldRequested=preferences.getBoolean("power_mode",false);
        boolean hadPending=preferences.contains("power_cleanup_pending"),oldPending=preferences.getBoolean("power_cleanup_pending",false);
        if(preferences.edit().putBoolean("power_mode",requested).putBoolean("power_cleanup_pending",pending).commit())return true;
        // commit(false) still changes SharedPreferences' memory map. Reject the command there too.
        SharedPreferences.Editor restore=preferences.edit();
        if(hadRequested)restore.putBoolean("power_mode",oldRequested);else restore.remove("power_mode");
        if(hadPending)restore.putBoolean("power_cleanup_pending",oldPending);else restore.remove("power_cleanup_pending");
        restore.commit();return false;
    }
    private void changed(){main.post(()->{for(Runnable listener:listeners)listener.run();});}
}
