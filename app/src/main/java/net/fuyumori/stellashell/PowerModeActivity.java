package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import net.fuyumori.stellashell.core.launch.Policy;
import net.fuyumori.stellashell.core.settings.SettingTransaction;

/** Optional privileged controls. Its OFF action never stops the ordinary shell. */
public final class PowerModeActivity extends Activity implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private Bridge bridge;private PowerMode power;private DisplayManager displays;
    private LinearLayout settings,diagnosticsPage,configuration,cleanup;
    private TextView state,connectionState,detail;
    private Switch requested;
    private Button connect,enable,reset,restore,retry;
    private boolean busy,refreshing,diagnosticsOpen;
    private AutoCloseable settingsSubscription;
    private final Runnable changed=this::refresh;
    public static void open(Context context){
        Intent intent=new Intent(context,PowerModeActivity.class);
        if(!(context instanceof Activity))intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);power=PowerMode.of(this);bridge=Bridge.get(this);displays=getSystemService(DisplayManager.class);
        LinearLayout page=DashboardUi.page(this);
        page.addView(DashboardUi.action(this,getString(R.string.ui_back),this::onBackPressed,false));DashboardUi.space(page,16);
        page.addView(DashboardUi.title(this,getString(R.string.power_mode_title),28));DashboardUi.space(page,8);
        page.addView(DashboardUi.text(this,getString(R.string.power_mode_intro),14,Ui.MUTED));DashboardUi.space(page,20);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.column(this);body.setPadding(0,0,0,Ui.dp(this,28));scroll.addView(body);
        settings=Ui.column(this);body.addView(settings);diagnosticsPage=Ui.column(this);body.addView(diagnosticsPage);
        buildSettings();buildDiagnostics();
        bridge.observe(changed);power.observe(changed);ShellRuntime.observeNavigation(changed);
        displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);
        settingsSubscription=ShellSettings.of(this).observe((changes,snapshot)->refresh());
        showDiagnostics(saved!=null&&saved.getBoolean("power_diagnostics",false));
    }
    private Switch toggle(LinearLayout parent,int label,boolean checked,java.util.function.Consumer<Boolean> change){
        Switch control=new Switch(this);control.setText(label);control.setTypeface(Appearance.face);control.setTextColor(Ui.TEXT);control.setTextSize(14);control.setPadding(0,Ui.dp(this,14),0,Ui.dp(this,14));control.setMinHeight(Ui.dp(this,56));
        control.setChecked(checked);control.setOnCheckedChangeListener((v,value)->change.accept(value));parent.addView(control,new LinearLayout.LayoutParams(-1,-2));return control;
    }
    private void buildSettings(){
        LinearLayout card=DashboardUi.card(settings);
        requested=toggle(card,R.string.power_mode_enable,power.requested(),value->{
            if(refreshing||busy)return;
            busy=true;power.setRequested(value,(result,error)->{busy=false;if(error!=null)operationFailed(error);refresh();});refresh();
        });
        state=DashboardUi.text(this,"",14,Ui.MUTED);card.addView(state);
        DashboardUi.space(settings,16);cleanup=DashboardUi.card(settings);
        cleanup.addView(DashboardUi.title(this,getString(R.string.power_mode_cleanup_title),18));
        cleanup.addView(DashboardUi.text(this,getString(R.string.power_mode_cleanup_note),14,Ui.MUTED));DashboardUi.space(cleanup,12);
        cleanup.addView(DashboardUi.action(this,getString(R.string.onboard_open_shizuku),this::openShizuku,false));
        retry=DashboardUi.action(this,getString(R.string.power_mode_cleanup_retry),()->{
            if(busy)return;busy=true;power.retry((result,error)->{busy=false;if(error!=null)operationFailed(error);refresh();});refresh();
        },true);cleanup.addView(retry);
        configuration=Ui.column(this);settings.addView(configuration);
        DashboardUi.section(configuration,getString(R.string.power_mode_connection_title));card=DashboardUi.card(configuration);
        card.addView(DashboardUi.text(this,getString(R.string.power_mode_connection_note),14,Ui.MUTED));
        connectionState=DashboardUi.text(this,"",14,Ui.MUTED);DashboardUi.space(card,8);card.addView(connectionState);DashboardUi.space(card,12);
        card.addView(DashboardUi.action(this,getString(R.string.onboard_open_shizuku),this::openShizuku,false));
        connect=DashboardUi.action(this,getString(R.string.onboard_connect),()->{bridge.request();refresh();},true);card.addView(connect);
        DashboardUi.section(configuration,getString(R.string.setup_display_section));card=DashboardUi.card(configuration);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_slideshow,getString(R.string.displays_title),getString(R.string.setup_displays_note),()->DisplayManagementActivity.open(this)));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_sort_by_size,getString(R.string.display_settings_title),getString(R.string.display_settings_summary),()->DisplaySettingsActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId())));
        DashboardUi.section(configuration,getString(R.string.dashboard_workspace));card=DashboardUi.card(configuration);
        final Switch[] workspace={null};workspace[0]=toggle(card,R.string.workspace_enable,ShellSettings.of(this).snapshot().compactWorkspace,checked->{
            if(checked==ShellSettings.of(this).snapshot().compactWorkspace)return;
            if(ShellRuntime.enabled(this)){workspace[0].setChecked(!checked);Ui.message(this,getString(R.string.workspace_stop_first));return;}
            TaskState.of(this).reset(this);ShellSettings.of(this).setWorkspaceOptions(checked,ShellSettings.of(this).snapshot().workspaceAuto);
        });DashboardUi.divider(card);
        toggle(card,R.string.workspace_auto,ShellSettings.of(this).snapshot().workspaceAuto,checked->ShellSettings.of(this).setWorkspaceOptions(ShellSettings.of(this).snapshot().compactWorkspace,checked));DashboardUi.divider(card);
        toggle(card,R.string.hide_virtual_keyboard,Launches.prefs(this).getBoolean("hide_virtual_ime",false),checked->{
            Launches.prefs(this).edit().putBoolean("hide_virtual_ime",checked).apply();
            if(bridge.ready())bridge.call(server->"OK",(result,error)->{
                if(checked&&(error!=null||Launches.prefs(this).getString("ime_diagnostics","").startsWith("unavailable")))Ui.message(this,getString(R.string.virtual_keyboard_unavailable));
            });
        });
        DashboardUi.section(configuration,getString(R.string.dashboard_windows));card=DashboardUi.card(configuration);
        toggle(card,R.string.ui_launch_in_windows_experimental,Launches.prefs(this).getBoolean("freeform",false),checked->Launches.prefs(this).edit().putBoolean("freeform",checked).apply());
        enable=DashboardUi.action(this,getString(R.string.dashboard_configure),()->new AlertDialog.Builder(this).setTitle(R.string.ui_enable_desktop_features)
                .setMessage(Build.VERSION.SDK_INT<=34?R.string.ui_enable_freeform_preserve_desktop_mode:R.string.ui_enable_freeform_windows_and_turn_off_android_s_force_desktop_mode).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_enable,(d,w)->apply(false)).show(),false);card.addView(enable);
        DashboardUi.section(settings,getString(R.string.dashboard_support));card=DashboardUi.card(settings);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_info_details,getString(R.string.dashboard_diagnostics),getString(R.string.dashboard_diagnostics_note),()->showDiagnostics(true)));
    }
    private void openShizuku(){
        Intent launch=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if(launch==null){new AlertDialog.Builder(this).setTitle(R.string.onboard_shizuku).setMessage(R.string.onboard_install_shizuku).setPositiveButton(android.R.string.ok,null).show();return;}
        try{startActivity(launch);}catch(RuntimeException e){operationFailed(e.getMessage());}
    }
    private void buildDiagnostics(){
        diagnosticsPage.addView(DashboardUi.action(this,getString(R.string.power_mode_back_settings),()->showDiagnostics(false),false));DashboardUi.space(diagnosticsPage,16);
        LinearLayout card=DashboardUi.card(diagnosticsPage);card.addView(DashboardUi.title(this,getString(R.string.dashboard_diagnostics),22));DashboardUi.space(card,16);
        reset=DashboardUi.action(this,getString(R.string.reset_connection),()->ShellRuntime.resetConnection(this),false);card.addView(reset);
        restore=DashboardUi.action(this,getString(R.string.ui_restore_previous_device_settings),()->new AlertDialog.Builder(this).setTitle(R.string.ui_restore_device_settings)
                .setMessage(R.string.ui_stop_the_external_desktop_and_restore_the_two_device_settings_sav).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_restore,(d,w)->apply(true)).show(),false);card.addView(restore);
        detail=DashboardUi.text(this,"",12,Ui.MUTED);detail.setTextIsSelectable(true);DashboardUi.space(card,16);card.addView(detail);DashboardUi.space(card,16);
        card.addView(DashboardUi.action(this,getString(R.string.ui_copy_diagnostics),()->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("StellaShell diagnostics",diagnostics()));Ui.message(this,getString(R.string.ui_diagnostics_copied));},false));
    }
    private void showDiagnostics(boolean value){diagnosticsOpen=value;settings.setVisibility(value?View.GONE:View.VISIBLE);diagnosticsPage.setVisibility(value?View.VISIBLE:View.GONE);refresh();}
    @Override public void onBackPressed(){if(diagnosticsOpen)showDiagnostics(false);else super.onBackPressed();}
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("power_diagnostics",diagnosticsOpen);}
    /** Keep the preparation transaction and restoration record independent of the mode toggle. */
    private void apply(boolean restoring){
        if(busy)return;
        if(!bridge.ready()){Ui.message(this,getString(R.string.ui_connect_to_shizuku));return;}
        SharedPreferences prefs=Launches.prefs(this);
        if(restoring&&!prefs.contains("before_desktop")){Ui.message(this,getString(R.string.ui_no_settings_to_restore));return;}
        busy=true;refresh();
        if(restoring)ShellRuntime.stop(this);
        bridge.call(server->{
            String desktop=restoring?prefs.getString("before_desktop","null"):"0";
            if(!restoring){
                String snapshot=server.settingsSnapshot();String[] values=snapshot.split(",",-1);
                if(values.length!=2)throw new IllegalStateException(snapshot);
                Policy.setting(values[0]);Policy.setting(values[1]);
                // Persist before mutation, and never overwrite an existing restoration record.
                if(!prefs.contains("before_desktop")&&!prefs.edit().putString("before_desktop",values[0]).putString("before_freeform",values[1]).commit())
                    throw new IllegalStateException(getString(R.string.ui_could_not_save_the_previous_settings));
                desktop=SettingTransaction.desktopForPreparation(Build.VERSION.SDK_INT,values[0]);
            }
            return server.applySettings(desktop,restoring?prefs.getString("before_freeform","null"):"1");
        },(result,error)->{
            busy=false;
            if(error!=null)operationFailed(error);
            else{
                if(restoring)prefs.edit().remove("before_desktop").remove("before_freeform").putBoolean("freeform",false).apply();
                prefs.edit().remove("last_error").apply();
                Ui.message(this,getString(restoring?R.string.ui_previous_settings_restored:R.string.ui_enabled_reconnect_the_display));
            }
            refresh();
        });
    }
    private void operationFailed(String error){
        Launches.prefs(this).edit().putString("last_error",error==null?getString(R.string.ui_unknown_error):error).apply();
        if(!isDestroyed()&&!isFinishing())Ui.message(this,getString(R.string.power_mode_operation_failed));
    }
    private String diagnostics(){
        String version="?";try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException ignored){}
        StringBuilder text=new StringBuilder("StellaShell ").append(version).append(getString(R.string.ui_model)).append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
                .append("\nExtensions requested: ").append(power.requested()).append(" / active: ").append(power.active()).append(" / cleanup: ").append(power.transition())
                .append("\nPrimary mode: ").append(Displays.primary(this)).append("\n").append(bridge.status()).append(getString(R.string.ui_overlay_permission)).append(Settings.canDrawOverlays(this))
                .append(getString(R.string.ui_session)).append(ShellRuntime.enabled(this)).append(getString(R.string.ui_restore_record)).append(Launches.prefs(this).contains("before_desktop"));
        for(Display display:Displays.available(this))text.append(getString(R.string.ui_display)).append(display.getDisplayId()).append(": ").append(display.getName())
                .append(" / ").append(display.getMode().getPhysicalWidth()).append("×").append(display.getMode().getPhysicalHeight());
        String error=Launches.prefs(this).getString("last_error","");if(!error.isEmpty())text.append(getString(R.string.ui_last_error)).append(error);
        text.append("\nVirtual keyboard: ").append(Launches.prefs(this).getString("ime_diagnostics","inactive"));
        text.append("\nMouse routing: ").append(Launches.prefs(this).getString("mouse_diagnostics","inactive"));
        return text.append(getString(R.string.ui_window_management)).append(Launches.prefs(this).getString("task_diagnostics",getString(R.string.ui_not_connected))).toString();
    }
    private void refresh(){
        if(requested==null||isDestroyed()||isFinishing())return;
        refreshing=true;requested.setChecked(power.requested());refreshing=false;requested.setEnabled(!busy&&!power.transition());
        state.setText(power.transition()?R.string.power_mode_cleanup_note:power.active()?R.string.power_mode_active:power.requested()?R.string.power_mode_enabled:R.string.power_mode_disabled);
        state.setTextColor(power.active()?Ui.ACCENT:Ui.MUTED);
        cleanup.setVisibility(power.transition()?View.VISIBLE:View.GONE);retry.setEnabled(!busy);
        configuration.setVisibility(power.requested()?View.VISIBLE:View.GONE);
        connectionState.setText(bridge.ready()?R.string.power_mode_connected:R.string.power_mode_connect_note);
        connectionState.setTextColor(bridge.ready()?Ui.ACCENT:Ui.MUTED);
        connect.setText(bridge.permissionGranted()?R.string.onboard_retry:R.string.onboard_connect);
        connect.setVisibility(bridge.ready()?View.GONE:View.VISIBLE);connect.setEnabled(!busy&&!power.transition());
        enable.setEnabled(!busy&&bridge.ready());reset.setEnabled(ShellRuntime.enabled(this)&&!busy&&bridge.ready());
        restore.setEnabled(!busy&&bridge.ready()&&Launches.prefs(this).contains("before_desktop"));
        if(diagnosticsOpen)detail.setText(diagnostics());
    }
    @Override public void onResume(){super.onResume();if(power.requested()&&!power.transition())bridge.connect();refresh();}
    @Override public void onDisplayAdded(int id){refresh();}
    @Override public void onDisplayRemoved(int id){refresh();}
    @Override public void onDisplayChanged(int id){refresh();}
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){refresh();}
    @Override public void onDestroy(){
        bridge.remove(changed);power.remove(changed);ShellRuntime.unobserveNavigation(changed);displays.unregisterDisplayListener(this);Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        if(settingsSubscription!=null)try{settingsSubscription.close();}catch(Exception ignored){}super.onDestroy();
    }
}
