package net.fuyumori.stellashell;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import net.fuyumori.stellashell.core.launch.AppLaunchDecision;
import net.fuyumori.stellashell.core.launch.AppLaunchProfile;
import net.fuyumori.stellashell.core.launch.LaunchProfileSnapshot;
import net.fuyumori.stellashell.core.launch.Policy;
import net.fuyumori.stellashell.core.launch.SerialLaunchQueue;
import net.fuyumori.stellashell.feature.launch.PublicLauncher;

/** App-side adapter for normal Android and privileged launch commands, not a state owner. */
final class ShellLaunchExecutor {
    private ShellLaunchExecutor(){}
    static void normal(Context c,String component){
        new PublicLauncher(c).launch(component,0);Launches.remember(c,component);
    }
    static void app(Context c,TaskState state,AppLaunchDecision.Request request,SerialLaunchQueue.Completion done){
        String component=request.component;int display=request.displayId;boolean newWindow=request.newWindow;
        ShellPanels.dismiss(display);
        try{
            AppLaunchDecision decision=AppLaunchDecision.choose(request,Launches.basicHome(c,display),()->{
                Displays.require(c,display);
                LaunchProfileSnapshot profile=Profiles.snapshot(c,component);AppLaunchProfile.Plan planned=Profiles.plan(c,component,display);
                boolean compact=state.compact(c,display);
                boolean secondary=request.choice==AppLaunchDecision.Choice.AUTO&&state.secondaryLaunch(component,profile.resolvedComponent,newWindow);
                WorkArea area=WorkArea.get(c,display);
                return new AppLaunchDecision.Inputs(WorkspaceProfile.standard(c,display),compact,secondary,Bridge.get(c).ready(),
                        planned,profile.resolvedComponent,area.physical.width(),area.physical.height(),
                        area.content.left,area.content.top,area.content.right,area.content.bottom);
            });
            if(decision.route==AppLaunchDecision.Route.PUBLIC_LAUNCHER){normal(c,component);done.finish();return;}
            if(decision.route==AppLaunchDecision.Route.BRIDGE_REQUIRED)
                throw new IllegalStateException(c.getString(R.string.ui_launch_profiles_require_a_shizuku_connection));
            if(decision.route==AppLaunchDecision.Route.ANDROID_FULLSCREEN){simple(c,component,display,1,true);done.finish();return;}
            AppLaunchProfile.Plan plan=decision.plan;
            Profiles.begin(component);long session=state.session();
            Bridge.get(c).call(server->{
                if(!state.currentSession(session))throw new IllegalStateException("Workspace session ended");
                WorkArea.get(c,display).sync(server,display);
                return server.launchProfile(component,decision.resolvedComponent,display,plan.windowingMode,plan.left,plan.top,plan.right,plan.bottom,newWindow);
            },(result,error)->{
                Profiles.end(component);
                if(!state.currentSession(session)){done.finish();return;}
                if(error!=null){Launches.problem(c,error);done.finish();return;}
                try{
                    org.json.JSONObject data=new org.json.JSONObject(result);Profiles.launched(c,component,data,display);Launches.remember(c,component);
                    if(newWindow&&!data.optBoolean("created"))Ui.message(c,c.getString(R.string.ui_this_app_reused_its_existing_window));
                    state.launched(c,data,display,decision.floating,done::finish);
                }catch(Exception failure){Launches.problem(c,failure.getMessage());done.finish();}
            });
        }catch(RuntimeException failure){Profiles.end(component);Launches.problem(c,failure.getMessage());done.finish();}
    }
    static void simple(Context c,String component,int display,int mode,boolean remember){
        try{
            Displays.require(c,display);Policy.component(component);
            if(Bridge.get(c).ready()){
                Bridge.get(c).call(server->server.launch(component,display,mode),(result,error)->{
                    if(error!=null)Launches.problem(c,error);else if(remember)Launches.remember(c,component);
                });return;
            }
            if(mode==5)throw new IllegalStateException(c.getString(R.string.ui_reconnect_to_shizuku_to_launch_a_window));
            Intent intent=new Intent(Intent.ACTION_MAIN).setComponent(ComponentName.unflattenFromString(component))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
            if(remember)Launches.remember(c,component);
        }catch(RuntimeException failure){Launches.problem(c,failure.getMessage());}
    }
}
