package net.fuyumori.stellashell;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Display;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Owned transport, preference and task owners survive display/window context wrapping. */
final class BridgeFixtureContext extends ContextWrapper implements Bridge.Provider,PowerMode.Provider,ShellSettings.Provider,TaskState.Provider,AutoCloseable {
    private final BridgeFixtureContext owner;private final Context actual;private final String prefix;private final Set<String> stores;
    private Bridge bridge;private PowerMode power;private ShellSettings settings;private TaskState tasks;
    BridgeFixtureContext(Context base,boolean requested,IDesktopBridge service){
        super(base);owner=this;actual=base;prefix="bridge_boundary_"+UUID.randomUUID()+"_";stores=new HashSet<>();
        SharedPreferences prefs=getSharedPreferences("desktop",0);
        if(!prefs.edit().putBoolean("power_mode",requested).putBoolean("enabled",false).putBoolean("phone_profile_initialized",true)
                .putBoolean("phone_sidebar",true).putBoolean("phone_sidebar_over_apps",true).putString("phone_sidebar_side","both").putString("phone_pinned","").commit())throw new AssertionError("Fixture preferences failed");
        power=PowerMode.forPreferences(prefs,new PowerMode.Backend(){
            public void connect(){}
            public void resumeHome(){}
            public void powerChanged(boolean value,Bridge.Reply reply){reply.done("OK",null);}
            public void retry(Bridge.Reply reply){reply.done("OK",null);}
        });
        bridge=Bridge.forService(this,service,power);
    }
    private BridgeFixtureContext(Context base,BridgeFixtureContext owner){super(base);this.owner=owner;actual=owner.actual;prefix=owner.prefix;stores=owner.stores;}
    public Bridge bridge(){return owner.bridge;}
    public PowerMode powerMode(){return owner.power;}
    public ShellSettings shellSettings(){if(owner.settings==null)owner.settings=ShellSettings.isolated(getSharedPreferences("desktop",0));return owner.settings;}
    public TaskState taskState(){if(owner.tasks==null)owner.tasks=TaskState.isolated(this);return owner.tasks;}
    @Override public Context getApplicationContext(){return owner;}
    @Override public SharedPreferences getSharedPreferences(String name,int mode){String store=prefix+name;stores.add(store);return actual.getSharedPreferences(store,mode);}
    @Override public Context createDisplayContext(Display display){return new BridgeFixtureContext(super.createDisplayContext(display),owner);}
    @Override public Context createWindowContext(int type,Bundle options){return new BridgeFixtureContext(super.createWindowContext(type,options),owner);}
    @Override public void startActivity(Intent intent){throw new AssertionError("Isolated bridge fixture cannot launch activities");}
    @Override public void startActivity(Intent intent,Bundle options){throw new AssertionError("Isolated bridge fixture cannot launch activities");}
    @Override public ComponentName startService(Intent intent){throw new AssertionError("Isolated bridge fixture cannot start services");}
    @Override public ComponentName startForegroundService(Intent intent){throw new AssertionError("Isolated bridge fixture cannot start services");}
    @Override public boolean stopService(Intent intent){throw new AssertionError("Isolated bridge fixture cannot stop services");}
    @Override public void close(){
        if(owner.tasks!=null)owner.tasks.closeOwner();if(owner.settings!=null)owner.settings.close();owner.bridge.closeIsolated();
        for(String store:stores)if(!actual.deleteSharedPreferences(store))throw new AssertionError("Fixture preference cleanup failed");
    }
}
