package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import java.util.EnumSet;
import net.fuyumori.stellashell.core.display.AutoOutputReconciler;

/** Owns the selected output lifetime, not its Views, settings, or task state. */
final class ShellController implements ShellRuntime.Session,DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener,AutoCloseable {
    interface Host {void status(int message);void stop();}
    private final Context context;
    private final Host host;
    private final DisplayManager displays;
    private final PhoneNavigationOwner phone;
    private final ShellRuntime.Binding runtime;
    private final AutoCloseable settingsSubscription;
    private final AutoCloseable routingSubscription;
    private final Runnable runtimeChanged=this::runtimeChanged;
    private final Runnable externalModeChanged=this::externalModeChanged;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AutoOutputReconciler automaticOutput=new AutoOutputReconciler();
    private Runnable automaticDecision;
    private AutoCloseable automaticIdle,manualIdle;
    private long pendingManualChoice=-1,activeManualChoice=-1,manualSession=-1;
    private long explicitOutputChange;
    private SelectedOutputSurface output;
    private int displayId=-1;
    private boolean closed,resetting;
    ShellController(Context context,Host host){
        this.context=context;this.host=host;
        displays=context.getSystemService(DisplayManager.class);
        phone=new PhoneNavigationOwner(context,this::publish,this::phoneAreaChanged);
        runtime=ShellRuntime.bind(context,this);
        settingsSubscription=ShellSettings.of(context).observe(this::settingsChanged);
        routingSubscription=TaskState.of(context).observeRouting(this::routingChanged);
        ShellRuntime.observeNavigation(runtimeChanged);
        ExternalAppMode.observe(externalModeChanged);
        phone.suspend(ExternalAppMode.active());
        displays.registerDisplayListener(this,main);
        Launches.prefs(context).registerOnSharedPreferenceChangeListener(this);
        Bridge.get(context).connect();
    }
    private void publish(){if(runtime!=null&&!closed)runtime.publish(displayId,phone.ready());}
    private void phoneAreaChanged(){if(output!=null)output.phoneAreaChanged();}
    void start(Intent intent){
        if(closed)return;
        if(intent!=null&&ShellRuntime.STOP.equals(intent.getAction())||!ShellRuntime.enabled(context)){cancelAutomaticOutput();cancelManualOutput();explicitOutputChange++;ShellRuntime.stop(context);host.stop();return;}
        if(intent!=null&&ShellRuntime.RESET.equals(intent.getAction())){reset();return;}
        if(intent!=null&&ShellRuntime.HANDOFF.equals(intent.getAction())){
            cancelAutomaticOutput();cancelManualOutput();explicitOutputChange++;
            requestManualOutput(intent.getIntExtra("destination",0));return;
        }
        boolean showHome=intent==null||intent.getBooleanExtra("show_home",true);update(showHome,!showHome);
        reconcileAutomaticOutput();
    }
    private void reset(){
        if(resetting)return;cancelAutomaticOutput();cancelManualOutput();explicitOutputChange++;resetting=true;closeOutput();
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
    private void externalModeChanged(){
        if(closed)return;
        phone.suspend(ExternalAppMode.active());
        if(ExternalAppMode.active())cancelAutomaticOutput();
        update(false,true);
        if(!ExternalAppMode.active())reconcileAutomaticOutput();
    }
    private void update(boolean openHome,boolean keepDashboard){
        if(closed||resetting)return;
        phone.reconcile();
        int preferred=displayId>0?displayId:ShellSettings.of(context).snapshot().preferredDisplayId;
        int next=Displays.target(context,preferred);boolean changed=next!=displayId;
        if(changed){closeOutput();displayId=next;}
        Bridge.get(context).mouseDisplay(next);publish();
        if(ExternalAppMode.active()){
            // Suspend surfaces and their task arrangement subscription, not settings or workspace.
            if(output!=null){ShellPanels.dismiss(displayId);output.close();output=null;}
            return;
        }
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
    @Override public boolean showStart(int requested){
        if(closed)return false;
        if(requested==0&&phone.ready())return phone.isStartOpen()||phone.toggleStart();
        return output!=null&&(requested<0||requested==displayId)&&(output.isStartOpen()||output.toggleStart());
    }
    @Override public int[] navigationBounds(int requested){return output!=null&&requested==displayId?output.navigationBounds():null;}
    @Override public void homeVisible(boolean value){phone.homeVisible(value);}
    private void settingsChanged(EnumSet<ShellSettings.Change> changes,ShellSettings.Snapshot snapshot){
        if(closed)return;
        boolean autoChanged=intersects(changes,ShellSettings.Change.WORKSPACE_AUTO,ShellSettings.Change.PRIMARY_MODE,
                ShellSettings.Change.COMPACT_WORKSPACE,ShellSettings.Change.PREFERRED_DISPLAY);
        if(autoChanged)cancelAutomaticOutput();
        phone.reconcile();
        if(intersects(changes,ShellSettings.Change.PHONE_DOCK_SCALE,ShellSettings.Change.PHONE_TASKBAR_SCALE))phone.rebuild();
        else if(intersects(changes,ShellSettings.Change.PHONE_DOCK_SIDE,ShellSettings.Change.PHONE_DOCK_OVER_APPS,ShellSettings.Change.PHONE_DOCK_TRIGGER_POSITION,
                ShellSettings.Change.PHONE_DOCK_LANDSCAPE_EDGE,ShellSettings.Change.PHONE_DOCK_LANDSCAPE_POSITION,ShellSettings.Change.PHONE_DOCK_OPEN_METHOD))phone.relayout();
        if(output!=null&&intersects(changes,ShellSettings.Change.EXTERNAL_DOCK_ENABLED,ShellSettings.Change.EXTERNAL_DOCK_EDGE,
                ShellSettings.Change.EXTERNAL_DOCK_POSITION,ShellSettings.Change.EXTERNAL_DOCK_SCALE,ShellSettings.Change.EXTERNAL_DOCK_REVEAL,ShellSettings.Change.EXTERNAL_DOCK_OPEN_METHOD,
                ShellSettings.Change.EXTERNAL_TASKBAR_SCALE,ShellSettings.Change.SHELL_LAYOUT))output.settingsChanged(changes);
        if(intersects(changes,ShellSettings.Change.PHONE_TASKBAR_ENABLED,ShellSettings.Change.PHONE_TASKBAR_SCALE))phoneAreaChanged();
        update(false,true);
        if(autoChanged)reconcileAutomaticOutput();
    }
    private static boolean intersects(EnumSet<ShellSettings.Change> changes,ShellSettings.Change... candidates){
        for(ShellSettings.Change candidate:candidates)if(changes.contains(candidate))return true;return false;
    }
    @Override public void onDisplayAdded(int id){
        if(closed)return;
        reconcileAutomaticOutput();
        if(!Displays.primary(context))update(true,false);
    }
    @Override public void onDisplayRemoved(int id){
        if(closed)return;
        boolean decisionQueued=automaticDecision!=null||automaticIdle!=null;
        invalidateAutomaticRouting();
        TaskState state=TaskState.of(context);
        if(state.enabled(context)&&id==state.target(context)){
            long session=state.session(),choice=explicitOutputChange;
            boolean manualChoiceInProgress=pendingManualChoice>=0||activeManualChoice>=0;
            state.recoverDisconnectedOutput(context,id,session,
                    ()->!closed&&!resetting&&runtime.current()&&ShellRuntime.enabled(context)&&choice==explicitOutputChange,()->{
                if(closed||resetting||!runtime.current()||!state.currentSession(session)||!ShellRuntime.enabled(context))return;
                update(true,false);
                // DisplayAdded may have arrived while the old external target was still committed.
                if(!manualChoiceInProgress&&choice==explicitOutputChange)reconcileAutomaticOutput();
            });
        }else {update(false,true);if(decisionQueued||automaticOutput.inFlight())reconcileAutomaticOutput();}
    }
    private void cancelAutomaticOutput(){
        automaticOutput.cancel();
        clearAutomaticDecision();
    }
    private void invalidateAutomaticRouting(){
        automaticOutput.routingChanged(TaskState.of(context).session());
        clearAutomaticDecision();
    }
    private void clearAutomaticDecision(){
        if(automaticDecision!=null)main.removeCallbacks(automaticDecision);
        automaticDecision=null;
        closeIdle(automaticIdle);automaticIdle=null;
    }
    private void closeIdle(AutoCloseable subscription){
        if(subscription==null)return;
        try{subscription.close();}catch(Exception error){Launches.problem(context,error.getMessage());}
    }
    private void cancelManualOutput(){
        closeIdle(manualIdle);manualIdle=null;pendingManualChoice=-1;activeManualChoice=-1;manualSession=-1;
    }
    private void routingChanged(){
        invalidateAutomaticRouting();
        if((pendingManualChoice>=0||activeManualChoice>=0)&&!TaskState.of(context).currentSession(manualSession))cancelManualOutput();
    }
    private void runtimeChanged(){
        if(closed)return;
        if(!runtime.current()||!ShellRuntime.enabled(context)){cancelAutomaticOutput();cancelManualOutput();explicitOutputChange++;}
    }
    private void reconcileAutomaticOutput(){
        if(closed||resetting||ExternalAppMode.active()||!runtime.current()||pendingManualChoice>=0||activeManualChoice>=0)return;
        TaskState state=TaskState.of(context);
        AutoOutputReconciler.Ticket ticket=automaticOutput.request(state.session(),state.target(context));
        if(ticket==null)return;
        if(automaticDecision!=null)main.removeCallbacks(automaticDecision);
        closeIdle(automaticIdle);automaticIdle=null;
        automaticDecision=()->{
            automaticDecision=null;
            decideAutomaticOutput(ticket);
        };
        main.postDelayed(automaticDecision,600);
    }
    private void decideAutomaticOutput(AutoOutputReconciler.Ticket ticket){
        TaskState state=TaskState.of(context);
        if(!automaticOutput.current(ticket,state.session(),state.target(context)))return;
        ShellSettings.Snapshot settings=ShellSettings.of(context).snapshot();
        boolean eligible=!closed&&!resetting&&!ExternalAppMode.active()&&runtime.current()&&ShellRuntime.enabled(context)
                &&settings.primaryMode&&settings.workspaceAuto&&state.enabled(context)&&state.target(context)==0;
        if(eligible&&state.isBusy()){
            automaticIdle=state.whenIdle(()->{automaticIdle=null;decideAutomaticOutput(ticket);});return;
        }
        int preferred=displayId>0?displayId:settings.preferredDisplayId;
        int destination=automaticOutput.begin(ticket,state.session(),state.target(context),eligible,preferred,Displays.ids(context));
        if(destination>0)transferOutput(destination,ticket);
    }
    private void requestManualOutput(int destination){
        TaskState state=TaskState.of(context);
        long choice=explicitOutputChange,session=state.session();
        pendingManualChoice=choice;manualSession=session;
        manualIdle=state.whenIdle(()->{
            manualIdle=null;
            if(pendingManualChoice!=choice)return;
            pendingManualChoice=-1;manualSession=-1;
            if(closed||resetting||!runtime.current()||!ShellRuntime.enabled(context)
                    ||!state.currentSession(session)||choice!=explicitOutputChange)return;
            transferOutput(destination,null);
        });
    }
    private void transferOutput(int destination,AutoOutputReconciler.Ticket automatic){
        TaskState state=TaskState.of(context);
        long session=state.session(),choice=explicitOutputChange;
        if(automatic==null){activeManualChoice=choice;manualSession=session;}
        state.transfer(context,destination,()->{
            boolean reconcile=automatic!=null&&automaticOutput.finished(automatic);
            if(automatic==null&&activeManualChoice==choice){activeManualChoice=-1;if(pendingManualChoice<0)manualSession=-1;}
            if(closed||resetting||!runtime.current()||!state.currentSession(session)
                    ||!ShellRuntime.enabled(context)||choice!=explicitOutputChange)return;
            update(true,false);
            if(reconcile){
                int target=state.target(context);
                // A real removal can arrive before AUTO's target commit; replay its recovery first.
                if(target>0&&!Displays.ids(context).contains(target))onDisplayRemoved(target);
                else reconcileAutomaticOutput();
            }
            if(state.target(context)>0)context.startActivity(new Intent(context,SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        });
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
        if(closed)return;boolean current=runtime.current();cancelAutomaticOutput();cancelManualOutput();explicitOutputChange++;closed=true;main.removeCallbacksAndMessages(null);
        ShellRuntime.unobserveNavigation(runtimeChanged);
        ExternalAppMode.unobserve(externalModeChanged);
        displays.unregisterDisplayListener(this);
        Launches.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
        try{settingsSubscription.close();}catch(Exception error){Launches.problem(context,error.getMessage());}
        try{routingSubscription.close();}catch(Exception error){Launches.problem(context,error.getMessage());}
        closeOutput();phone.close();runtime.close();if(current)Bridge.get(context).mouseDisplay(-1);
    }
}
