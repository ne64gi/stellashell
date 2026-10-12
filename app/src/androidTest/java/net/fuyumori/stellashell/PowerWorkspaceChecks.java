package net.fuyumori.stellashell;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

/** Actual empty/deferred workspace routing, confined to a nonce store without Bridge calls. */
final class PowerWorkspaceChecks {
    private PowerWorkspaceChecks(){}
    static void verify(Context base){
        String name="power_workspace_boundary_"+System.nanoTime();
        SharedPreferences prefs=base.getSharedPreferences(name,Context.MODE_PRIVATE);
        check(prefs.edit().putBoolean("enabled",true).putBoolean("primary_mode",true)
                .putBoolean("power_mode",true).putInt("workspace_display",4).commit(),"Seed failed");
        PowerMode power=PowerMode.forPreferences(prefs,new PowerMode.Backend(){
            public void connect(){throw new AssertionError("Fixture connected a transport");}
            public void resumeHome(){throw new AssertionError("Fixture connected HOME transport");}
            public void powerChanged(boolean requested,Bridge.Reply reply){reply.done("OK",null);}
            public void retry(Bridge.Reply reply){reply.done("OK",null);}
        });
        class Sandbox extends ContextWrapper implements ShellSettings.Provider,TaskState.Provider,PowerMode.Provider {
            private final ShellSettings settings=ShellSettings.isolated(prefs);
            private final TaskState tasks=TaskState.isolated(this);
            Sandbox(){super(base);}
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String ignored,int mode){return prefs;}
            public ShellSettings shellSettings(){return settings;}
            public TaskState taskState(){return tasks;}
            public PowerMode powerMode(){return power;}
            void close(){tasks.closeOwner();settings.close();}
        }
        Sandbox source=new Sandbox();int[] completions={0};
        try{
            // Empty ownership may commit directly without Binder, but only while active.
            power.connected(true);source.taskState().transfer(source,0,()->completions[0]++);
            check(prefs.getInt("workspace_display",-1)==0&&completions[0]==1,"Active empty handoff did not commit");
            check(prefs.edit().putInt("workspace_display",4).commit(),"Seed failed");
            power.setRequested(false,(result,error)->check(error==null,"Injected OFF failed"));
            source.taskState().transfer(source,0,()->completions[0]++);
            check(prefs.getInt("workspace_display",-1)==4&&completions[0]==2,"OFF empty handoff changed saved target");
            power.setRequested(true,(result,error)->check(error==null,"Injected ON failed"));power.connected(true);
            long command=source.taskState().beginOutputCommand();
            source.taskState().transfer(source,0,()->completions[0]++);
            check(completions[0]==2,"Deferred handoff completed before command release");
            power.setRequested(false,(result,error)->check(error==null,"Injected OFF failed"));
            // A new activation cannot adopt the old queued request.
            power.setRequested(true,(result,error)->check(error==null,"Injected ON failed"));power.connected(true);
            source.taskState().finishOutputCommand(command);
            check(completions[0]==3&&!source.taskState().isBusy(),"Revoked deferred handoff lost completion");
            check(prefs.getInt("workspace_display",-1)==4,"Old queued generation changed saved target");
            check(prefs.getBoolean("enabled",false)&&prefs.getBoolean("primary_mode",false),"OFF changed saved shell preferences");
        }finally{
            source.close();check(base.deleteSharedPreferences(name),"Nonce store cleanup failed");
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
