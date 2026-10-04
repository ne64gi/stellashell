package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import java.util.EnumSet;

/** Owns the selected output lifetime, not its Views, settings, or task state. */
final class ShellController implements ShellRuntime.Session,DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener,AutoCloseable {
    interface Host {void status(int message);void stop();}
    private final Context context;
    private final Host host;
    private final DisplayManager displays;
    private final PhoneNavigationOwner phone;
    private final ShellRuntime.Binding runtime;
    private final AutoCloseable settingsSubscription;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SelectedOutputSurface output;
    private int displayId=-1;
    private boolean closed,resetting;
    ShellController(Context context,Host host){
        this.context=context;this.host=host;
        displays=context.getSystemService(DisplayManager.class);
        phone=new PhoneNavigationOwner(context,this::publish,this::phoneAreaChanged);
        runtime=ShellRuntime.bind(context,this);
        settingsSubscription=ShellSettings.of(context).observe(this::settingsChanged);
        displays.registerDisplayListener(this,main);
        Launches.prefs(context).registerOnSharedPreferenceChangeListener(this);
        Bridge.get(context).connect();
    }
    private void publish(){if(runtime!=null&&!closed)runtime.publish(displayId,phone.ready());}
    private void phoneAreaChanged(){if(output!=null)output.phoneAreaChanged();}
    void start(Intent intent){
        if(closed)return;
        if(intent!=null&&ShellRuntime.STOP.equals(intent.getAction())||!ShellRuntime.enabled(context)){ShellRuntime.stop(context);host.stop();return;}
        if(intent!=null&&ShellRuntime.RESET.equals(intent.getAction())){reset();return;}
        if(intent!=null&&ShellRuntime.HANDOFF.equals(intent.getAction())){
            TaskState.of(context).transfer(context,intent.getIntExtra("destination",0),()->{
                if(closed)return;update(true,false);
                if(TaskState.of(context).target(context)>0)context.startActivity(new Intent(context,SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            });return;
        }
        boolean showHome=intent==null||intent.getBooleanExtra("show_home",true);update(showHome,!showHome);
    }
    private void reset(){
        if(resetting)return;resetting=true;closeOutput();
        ShellSettings.of(context).clearPreferredDisplay();publish();
        Bridge.get(context).resetMouseRouting((result,error)->{
            if(closed)return;resetting=false;
            if(!ShellRuntime.enabled(context))return;
            if(error!=null){Launches.problem(context,error);ShellRuntime.stop(context,false);return;}
            update(true,false);
        });
    }
    private void closeOutput(){
        if(output!=null){if(runtime.current())ShellPanels.dismiss(displayId);output.close();output=null;}displayId=-1;
    }
    private void update(boolean openHome,boolean keepDashboard){
        if(closed||resetting)return;
        phone.reconcile();
        int preferred=displayId>0?displayId:ShellSettings.of(context).snapshot().preferredDisplayId;
        int next=Displays.target(context,preferred);boolean changed=next!=displayId;
        if(changed){closeOutput();displayId=next;}
        Bridge.get(context).mouseDisplay(next);publish();
        if(next<0){host.status(R.string.ui_waiting_for_a_display);return;}
        if(!Settings.canDrawOverlays(context)){Launches.problem(context,context.getString(R.string.ui_overlay_permission_was_revoked));ShellRuntime.stop(context);return;}
        if(output==null)output=new SelectedOutputSurface(context,next,phone);
        if(!output.ready()){host.status(R.string.ui_could_not_show_the_taskbar_check_permissions);return;}
        if(!keepDashboard&&(openHome||changed&&next>0))Launches.home(context,next);
        host.status(Displays.primary(context)?R.string.primary_running:R.string.ui_running_on_an_external_display_tap_for_settings);
    }
    void configurationChanged(){if(closed)return;phone.rebuild();if(output!=null)output.rebuild();update(false,true);}
    @Override public boolean toggleStart(int requested){
        if(closed)return false;
        if(requested==0&&phone.toggleStart())return true;
        return output!=null&&(requested<0||requested==displayId)&&output.toggleStart();
    }
    @Override public int[] navigationBounds(int requested){return output!=null&&requested==displayId?output.navigationBounds():null;}
    @Override public void homeVisible(boolean value){phone.homeVisible(value);}
    private void settingsChanged(EnumSet<ShellSettings.Change> changes,ShellSettings.Snapshot snapshot){
        if(closed)return;
        phone.reconcile();
        if(intersects(changes,ShellSettings.Change.PHONE_DOCK_SCALE,ShellSettings.Change.PHONE_TASKBAR_SCALE))phone.rebuild();
        else if(intersects(changes,ShellSettings.Change.PHONE_DOCK_SIDE,ShellSettings.Change.PHONE_DOCK_OVER_APPS,ShellSettings.Change.PHONE_DOCK_TRIGGER_POSITION))phone.relayout();
        if(output!=null&&intersects(changes,ShellSettings.Change.EXTERNAL_DOCK_ENABLED,ShellSettings.Change.EXTERNAL_DOCK_EDGE,
                ShellSettings.Change.EXTERNAL_DOCK_POSITION,ShellSettings.Change.EXTERNAL_DOCK_SCALE,
                ShellSettings.Change.EXTERNAL_TASKBAR_SCALE,ShellSettings.Change.SHELL_LAYOUT))output.settingsChanged(changes);
        if(intersects(changes,ShellSettings.Change.PHONE_TASKBAR_ENABLED,ShellSettings.Change.PHONE_TASKBAR_SCALE))phoneAreaChanged();
        update(false,true);
    }
    private static boolean intersects(EnumSet<ShellSettings.Change> changes,ShellSettings.Change... candidates){
        for(ShellSettings.Change candidate:candidates)if(changes.contains(candidate))return true;return false;
    }
    @Override public void onDisplayAdded(int id){
        TaskState state=TaskState.of(context);
        if(state.enabled(context)&&state.target(context)==0&&ShellSettings.of(context).snapshot().workspaceAuto)
            main.postDelayed(()->{if(!closed&&Displays.ids(context).contains(id))ShellRuntime.handoff(context,id);},600);
        else if(!Displays.primary(context))update(true,false);
    }
    @Override public void onDisplayRemoved(int id){
        TaskState state=TaskState.of(context);
        if(state.enabled(context)&&id==state.target(context))state.transfer(context,0,()->{if(!closed)update(true,false);});else update(false,true);
    }
    @Override public void onDisplayChanged(int id){
        if(closed)return;
        if(id==displayId&&output!=null&&output.geometryChanged()){if(id==0)phone.geometryChanged();output.rebuild();update(false,true);}
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){
        if(closed)return;
        if("phone_taskbar_pinned".equals(key)){phone.rebuild();return;}
        if("phone_window_management".equals(key)){closeOutput();update(false,true);return;}
        if(Appearance.KEY.equals(key))Appearance.load(context);
        if(WorkspaceProfile.changed(key,"pinned")||Appearance.KEY.equals(key)||IconTheme.changed(key))phone.rebuild();
    }
    @Override public void close(){
        if(closed)return;boolean current=runtime.current();closed=true;main.removeCallbacksAndMessages(null);
        displays.unregisterDisplayListener(this);
        Launches.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
        try{settingsSubscription.close();}catch(Exception error){Launches.problem(context,error.getMessage());}
        closeOutput();phone.close();runtime.close();if(current)Bridge.get(context).mouseDisplay(-1);
    }
}
