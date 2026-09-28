package net.fuyumori.stellashell;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class SetupActivity extends Activity implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private TextView status,detail;private Button start,enable,restore;private Bridge bridge;private DisplayManager displays;
    private boolean busy;private final Runnable refreshListener=this::refresh;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        // scrcpy --start-app opens the launcher entry on its own display.
        // Explicit settings launches have no LAUNCHER category and stay here.
        if(!Displays.primary(this) && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER) && getDisplay()!=null && getDisplay().getDisplayId()>0
                && Displays.ids(this).contains(getDisplay().getDisplayId()) && Settings.canDrawOverlays(this)) {
            DockService.start(this);finish();return;
        }
        bridge=Bridge.get(this);bridge.observe(refreshListener);
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));
        Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Ui.BG);scroll.setFillViewport(true);
        LinearLayout root=Ui.column(this);root.setPadding(Ui.dp(this,24),Ui.dp(this,30),Ui.dp(this,24),Ui.dp(this,30));root.setFitsSystemWindows(true);scroll.addView(root);
        Ui.heading(root,"StellaShell");Ui.note(root,this.getString(R.string.ui_turn_usb_c_or_a_separate_scrcpy_display_into_a_desktop_keep_your));
        status=Ui.text(this,"",17,Ui.ACCENT);status.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,12));root.addView(status);
        root.addView(Ui.button(this,this.getString(R.string.ui_1_allow_taskbar_overlay),()->{
            try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
            catch(RuntimeException e){Launches.problem(this,e.getMessage());}
        }));
        root.addView(Ui.button(this,this.getString(R.string.ui_2_connect_authorize_shizuku_retry),()->{bridge.request();refresh();}));
        enable=Ui.button(this,this.getString(R.string.ui_3_configure_desktop_mode),()->new AlertDialog.Builder(this)
                .setTitle(this.getString(R.string.ui_enable_desktop_features))
                .setMessage(this.getString(R.string.ui_enable_freeform_windows_and_turn_off_android_s_force_desktop_mode))
                .setNegativeButton(this.getString(R.string.ui_cancel),null).setPositiveButton(this.getString(R.string.ui_enable),(d,w)->apply(false)).show());
        root.addView(enable);
        Switch primary=new Switch(this);primary.setText(R.string.primary_mode);primary.setTextColor(Ui.TEXT);
        primary.setChecked(Displays.primary(this));primary.setOnCheckedChangeListener((button,checked)->{
            if(Launches.prefs(this).getBoolean("enabled",false))DockService.stop(this,false);
            Launches.prefs(this).edit().putBoolean("primary_mode",checked).apply();refresh();
        });root.addView(primary);Ui.note(root,getString(R.string.primary_note));
        start=Ui.button(this,this.getString(R.string.ui_start_external_desktop),()->{
            if(Launches.prefs(this).getBoolean("enabled",false)){DockService.stop(this);refresh();return;}
            if(!bridge.ready()){Ui.message(this,this.getString(R.string.ui_connect_to_shizuku_first));return;}
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);
            DockService.start(this);refresh();
        });start.setTextColor(Ui.ACCENT);root.addView(start);
        Ui.note(root,this.getString(R.string.ui_after_starting_display_connections_are_detected_automatically_aft));
        Switch mode=new Switch(this);mode.setText(this.getString(R.string.ui_launch_in_windows_experimental));mode.setTextColor(Ui.TEXT);mode.setTextSize(15);mode.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));
        mode.setChecked(Launches.prefs(this).getBoolean("freeform",false));mode.setOnCheckedChangeListener((button,checked)->Launches.prefs(this).edit().putBoolean("freeform",checked).apply());root.addView(mode);
        Ui.note(root,this.getString(R.string.ui_when_off_apps_open_fullscreen_drag_the_active_window_s_title_bar));
        root.addView(Ui.button(this,this.getString(R.string.reopen_desktop),()->{
            int id=Displays.target(this,Launches.prefs(this).getInt("preferred_display",-1));
            if(id<0){Ui.message(this,this.getString(R.string.ui_connect_an_external_display_first));return;}
            if(!Launches.prefs(this).getBoolean("enabled",false)){Ui.message(this,this.getString(R.string.ui_start_the_external_desktop_first));return;}
            Launches.home(this,id);
        }));
        restore=Ui.button(this,this.getString(R.string.ui_restore_previous_device_settings),()->new AlertDialog.Builder(this).setTitle(this.getString(R.string.ui_restore_device_settings))
                .setMessage(this.getString(R.string.ui_stop_the_external_desktop_and_restore_the_two_device_settings_sav))
                .setNegativeButton(this.getString(R.string.ui_cancel),null).setPositiveButton(this.getString(R.string.ui_restore),(d,w)->apply(true)).show());root.addView(restore);
        detail=Ui.text(this,"",13,Ui.MUTED);detail.setTextIsSelectable(true);detail.setPadding(0,Ui.dp(this,20),0,0);root.addView(detail);
        root.addView(Ui.button(this,this.getString(R.string.ui_copy_diagnostics),()->{
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("StellaShell diagnostics",diagnostics()));Ui.message(this,this.getString(R.string.ui_diagnostics_copied));
        }));
        setContentView(scroll);refresh();
    }
    private void apply(boolean restoring){
        if(busy)return;
        if(!bridge.ready()){Ui.message(this,this.getString(R.string.ui_connect_to_shizuku));return;}
        SharedPreferences prefs=Launches.prefs(this);
        if(restoring && !prefs.contains("before_desktop")){Ui.message(this,this.getString(R.string.ui_no_settings_to_restore));return;}
        busy=true;refresh();
        if(restoring)DockService.stop(this);
        bridge.call(s->{
            if(!restoring && !prefs.contains("before_desktop")){
                String snapshot=s.settingsSnapshot();
                String[] values=snapshot.split(",",-1);
                if(values.length!=2)throw new IllegalStateException(snapshot);
                Policy.setting(values[0]);Policy.setting(values[1]);
                // Persist before mutation, so a crash cannot erase the restoration record.
                if(!prefs.edit().putString("before_desktop",values[0]).putString("before_freeform",values[1]).commit())
                    throw new IllegalStateException(this.getString(R.string.ui_could_not_save_the_previous_settings));
            }
            return s.applySettings(restoring?prefs.getString("before_desktop","null"):"0",restoring?prefs.getString("before_freeform","null"):"1");
        },(result,error)->{
            busy=false;
            if(error!=null)Launches.problem(this,error);
            else{
                if(restoring)prefs.edit().remove("before_desktop").remove("before_freeform").putBoolean("freeform",false).apply();
                prefs.edit().remove("last_error").apply();
                Ui.message(this,restoring?this.getString(R.string.ui_previous_settings_restored):this.getString(R.string.ui_enabled_reconnect_the_display));
            }
            if(!isDestroyed())refresh();
        });
    }
    private String diagnostics(){
        String version="?";
        try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException ignored){}
        StringBuilder s=new StringBuilder("StellaShell ").append(version).append(this.getString(R.string.ui_model)).append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
                .append("\nPrimary mode: ").append(Displays.primary(this)).append("\n").append(bridge.status()).append(this.getString(R.string.ui_overlay_permission)).append(Settings.canDrawOverlays(this))
                .append(this.getString(R.string.ui_session)).append(Launches.prefs(this).getBoolean("enabled",false))
                .append(this.getString(R.string.ui_restore_record)).append(Launches.prefs(this).contains("before_desktop"));
        for(Display d:Displays.available(this))s.append(this.getString(R.string.ui_display)).append(d.getDisplayId()).append(": ").append(d.getName())
                .append(" / ").append(d.getMode().getPhysicalWidth()).append("×").append(d.getMode().getPhysicalHeight());
        String error=Launches.prefs(this).getString("last_error","");if(!error.isEmpty())s.append(this.getString(R.string.ui_last_error)).append(error);
        return s.append(this.getString(R.string.ui_window_management)).append(Launches.prefs(this).getString("task_diagnostics",this.getString(R.string.ui_not_connected))).toString();
    }
    private void refresh(){
        if(status==null || isDestroyed())return;
        List<Display> monitors=Displays.available(this);
        String display=monitors.isEmpty()?getString(R.string.ui_no_external_display_connected):getString(R.string.display_connected,monitors.get(0).getName());
        if(Displays.primary(this))display=getString(R.string.primary_mode);
        status.setText(getString(R.string.setup_status,display,bridge.status(),getString(Settings.canDrawOverlays(this)?R.string.ui_allowed:R.string.ui_permission_required)));
        start.setText(Launches.prefs(this).getBoolean("enabled",false)?getString(R.string.exit_desktop):getString(Displays.primary(this)?R.string.primary_start:R.string.ui_start_external_desktop));
        enable.setEnabled(!busy && bridge.ready());restore.setEnabled(!busy && bridge.ready() && Launches.prefs(this).contains("before_desktop"));
        detail.setText(diagnostics());
    }
    @Override public void onResume(){super.onResume();if(bridge!=null){bridge.connect();refresh();}}
    @Override public void onDisplayAdded(int id){refresh();}
    @Override public void onDisplayRemoved(int id){refresh();}
    @Override public void onDisplayChanged(int id){refresh();}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){refresh();}
    @Override public void onDestroy(){
        if(bridge!=null)bridge.remove(refreshListener);if(displays!=null)displays.unregisterDisplayListener(this);
        Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);super.onDestroy();
    }
}
