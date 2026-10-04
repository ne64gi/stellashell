package net.fuyumori.stellashell;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.app.ActivityOptions;
import android.os.Handler;
import android.os.Looper;
import net.fuyumori.stellashell.core.launch.AppLaunchDecision;
import net.fuyumori.stellashell.core.launch.SerialLaunchQueue;
import net.fuyumori.stellashell.core.tasks.TaskModes;

/** Owns process-wide launch ordering; task/workspace state remains owned by TaskState. */
final class ShellLaunchCoordinator {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SerialLaunchQueue queue=new SerialLaunchQueue(new SerialLaunchQueue.Scheduler(){
        public void postDelayed(Runnable action,long delay){main.postDelayed(action,delay);}
        public void remove(Runnable action){main.removeCallbacks(action);}
    });
    private void enqueue(Context context,TaskState state,SerialLaunchQueue.Action action){
        queue.enqueue(state::isBusy,action,error->Launches.problem(context,error.getMessage()));
    }
    boolean pending(){return queue.pending();}
    void role(Context c,TaskSnapshot.Task task,int display,boolean primary){
        TaskState state=TaskState.of(c);
        enqueue(c,state,done->state.role(c,task,display,primary,done::finish));
    }
    void focus(Context c,TaskSnapshot.Task task,int display,TaskState state){
        enqueue(c,state,done->{
            TaskSnapshot snapshot=state.snapshot();TaskSnapshot.Task live=snapshot.find(task.identity());
            if(live==null||snapshot.displayId!=display){done.finish();return;}
            if(TaskModes.pictureInPicture(live.mode)){state.action(live,"fullscreen");done.finish();return;}
            if(state.compact(c,display)&&state.needsPrimary()&&!live.alwaysOnTop)
                state.role(c,live,display,true,done::finish);
            else{state.action(live,"focus");done.finish();}
        });
    }
    void home(Context c,int display){
        if(WorkspaceProfile.standard(c,display)){
            ShellPanels.dismiss(0);c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());return;
        }
        TaskState state=TaskState.of(c);
        enqueue(c,state,done->{
            ShellPanels.dismiss(display);
            String component=new ComponentName(c,display==0&&HomeRegistration.selected(c)?HomeActivity.class:DesktopActivity.class).flattenToString();
            if(!Bridge.get(c).ready()){
                try{ShellLaunchExecutor.simple(c,component,display,1,false);}finally{done.finish();}return;
            }
            Bridge.get(c).call(server->{
                // Existing HOME behavior is intentionally preserved; the visibility fix is separate work.
                state.minimizeVisible(server,display);return server.launch(component,display,1);
            },(result,error)->{
                try{if(error!=null)Launches.problem(c,error);else state.desktopShown(c,display);}
                finally{done.finish();}
            });
        });
    }
    void returnedHome(Context c){
        if(!HomeActivity.extensions(c))return;
        TaskState state=TaskState.of(c);
        enqueue(c,state,done->Bridge.get(c).call(server->{state.minimizeVisible(server,0);return "OK";},(result,error)->{
            try{if(error==null)state.desktopShown(c,0);else Launches.problem(c,error);}
            finally{done.finish();}
        }));
    }
    void app(Context c,String component,int display,boolean newWindow,Boolean explicitFloating){
        TaskState state=TaskState.of(c);
        AppLaunchDecision.Choice choice=explicitFloating==null?AppLaunchDecision.Choice.AUTO:
                explicitFloating?AppLaunchDecision.Choice.FLOATING:AppLaunchDecision.Choice.PRIMARY;
        AppLaunchDecision.Request request=new AppLaunchDecision.Request(component,display,newWindow,choice);
        enqueue(c,state,done->ShellLaunchExecutor.app(c,state,request,done));
    }
}
