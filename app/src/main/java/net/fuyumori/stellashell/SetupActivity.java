package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Daily dashboard; onboarding and diagnostic tools are deliberately separate surfaces. */
public final class SetupActivity extends Activity implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private int selectedOutput=0,selectedPage;
    private TextView homeStatus,shizukuChip,enabledChip,sessionTitle,sessionNote,outputValue,detail;
    private LinearLayout outputSelector;private Button primaryAction,stop,transfer,enable,reset,restore;
    private final LinearLayout[] pages=new LinearLayout[3];private final Button[] tabs=new Button[2];
    private ScrollView pageScroll;private Bridge bridge;private DisplayManager displays;
    private boolean busy,resumed,setupOpen;private final Runnable refreshListener=this::refresh;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);bridge=Bridge.get(this);displays=getSystemService(DisplayManager.class);
        // Preserve scrcpy's explicit launcher target, but never skip required permissions.
        if(!Displays.primary(this)&&getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)&&getDisplay()!=null&&getDisplay().getDisplayId()>0
                &&Displays.ids(this).contains(getDisplay().getDisplayId())&&PrerequisitesActivity.satisfied(this)){
            DockService.start(this);finish();return;
        }
        int saved=Launches.prefs(this).getInt("preferred_display",-1);
        selectedOutput=state!=null?state.getInt("selected_output",0):Displays.primary(this)?0:Displays.ids(this).contains(saved)?saved:Displays.ids(this).isEmpty()?0:Displays.ids(this).get(0);
        if(state==null&&getDisplay()!=null&&getDisplay().getDisplayId()>0&&getIntent().hasCategory(Intent.CATEGORY_LAUNCHER))selectedOutput=getDisplay().getDisplayId();
        setupOpen=state!=null&&state.getBoolean("setup_open",false);
        LinearLayout content=DashboardUi.page(this);
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this);icon.setImageResource(R.mipmap.ic_launcher);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(icon,new LinearLayout.LayoutParams(Ui.dp(this,36),Ui.dp(this,36)));
        TextView name=DashboardUi.title(this,"StellaShell",26);name.setPadding(Ui.dp(this,12),0,0,0);brand.addView(name);content.addView(brand);
        DashboardUi.space(content,20);
        LinearLayout navigation=new LinearLayout(this);navigation.setBackground(Ui.rounded(this,(Ui.TEXT&0xffffff)|0x0a000000,14));navigation.setPadding(Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4),Ui.dp(this,4));content.addView(navigation);
        int[] labels={R.string.dashboard_overview,R.string.setup_settings_tab};
        for(int i=0;i<2;i++){final int page=i;tabs[i]=Ui.toolbarButton(this,getString(labels[i]),()->showPage(page));tabs[i].setTextSize(15);navigation.addView(tabs[i],new LinearLayout.LayoutParams(0,Ui.dp(this,46),1));}
        DashboardUi.space(content,20);
        pageScroll=new ScrollView(this);pageScroll.setClipToPadding(false);content.addView(pageScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.column(this);body.setPadding(0,0,0,Ui.dp(this,28));pageScroll.addView(body);
        for(int i=0;i<3;i++){pages[i]=Ui.column(this);body.addView(pages[i]);}
        buildOverview();buildSettings();buildDiagnostics();
        displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);bridge.observe(refreshListener);
        showPage(state==null?0:state.getInt("setup_page",0));
    }
    private void buildOverview(){
        LinearLayout root=pages[0],health=new LinearLayout(this);health.setGravity(Gravity.CENTER_VERTICAL);
        shizukuChip=DashboardUi.chip(this);enabledChip=DashboardUi.chip(this);
        shizukuChip.setOnClickListener(v->openPrerequisites(true));shizukuChip.setFocusable(true);shizukuChip.setMinimumHeight(Ui.dp(this,48));
        health.addView(shizukuChip,new LinearLayout.LayoutParams(0,-2,1));View gap=new View(this);health.addView(gap,new LinearLayout.LayoutParams(Ui.dp(this,8),1));health.addView(enabledChip,new LinearLayout.LayoutParams(0,-2,1));root.addView(health);
        DashboardUi.space(root,18);LinearLayout home=DashboardUi.card(root);
        home.addView(DashboardUi.title(this,getString(R.string.home_title),20));
        homeStatus=DashboardUi.text(this,"",14,Ui.MUTED);home.addView(homeStatus);
        home.addView(DashboardUi.action(this,getString(R.string.home_open),()->startActivity(new Intent(this,HomeActivity.class)),false));
        home.addView(DashboardUi.action(this,getString(R.string.home_set_default),()->HomeRegistration.select(this),false));
        DashboardUi.space(root,18);LinearLayout session=DashboardUi.card(root);
        sessionTitle=DashboardUi.title(this,"",23);session.addView(sessionTitle);DashboardUi.space(session,6);
        sessionNote=DashboardUi.text(this,"",14,Ui.MUTED);session.addView(sessionNote);DashboardUi.space(session,20);
        outputSelector=Ui.column(this);outputSelector.setPadding(Ui.dp(this,16),Ui.dp(this,13),Ui.dp(this,16),Ui.dp(this,13));outputSelector.setBackground(Ui.rounded(this,Ui.BG,14));
        outputSelector.addView(DashboardUi.text(this,getString(R.string.dashboard_output),12,Ui.MUTED));
        outputValue=DashboardUi.title(this,"",16);outputValue.setPadding(0,Ui.dp(this,4),0,0);outputSelector.addView(outputValue);outputSelector.setOnClickListener(v->chooseOutput());outputSelector.setFocusable(true);session.addView(outputSelector);
        DashboardUi.space(session,16);primaryAction=DashboardUi.action(this,"",()->{
            if(Launches.prefs(this).getBoolean("enabled",false)){
                int active=Launches.prefs(this).getInt("active_display",-1);if(active>=0)Launches.home(this,active);
            }else startDesktop();
        },true);session.addView(primaryAction);
        DashboardUi.space(session,8);stop=DashboardUi.action(this,getString(R.string.dashboard_stop),()->{DockService.stop(this,false);refresh();},false);session.addView(stop);
        transfer=DashboardUi.action(this,getString(R.string.output_transfer),this::chooseTransfer,false);session.addView(transfer);
        DashboardUi.section(root,getString(R.string.dashboard_manage));LinearLayout manage=DashboardUi.card(root);
        manage.addView(DashboardUi.row(this,android.R.drawable.ic_menu_slideshow,getString(R.string.displays_title),getString(R.string.dashboard_displays_note),()->DisplayManagementActivity.open(this)));
        DashboardUi.divider(manage);
        manage.addView(DashboardUi.row(this,android.R.drawable.ic_menu_edit,getString(R.string.appearance_title),getString(R.string.dashboard_appearance_note),()->AppearanceActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId())));
        DashboardUi.space(root,12);root.addView(DashboardUi.action(this,getString(R.string.dashboard_connection_help),()->new AlertDialog.Builder(this).setTitle(R.string.dashboard_connection_help).setMessage(R.string.external_start_note).setPositiveButton(android.R.string.ok,null).show(),false));
    }
    private Switch toggle(LinearLayout parent,int label,String key,boolean fallback,java.util.function.Consumer<Boolean> change){
        Switch control=new Switch(this);control.setText(label);control.setTypeface(Appearance.face);control.setTextColor(Ui.TEXT);control.setTextSize(14);control.setPadding(0,Ui.dp(this,14),0,Ui.dp(this,14));control.setMinHeight(Ui.dp(this,56));
        control.setChecked(Launches.prefs(this).getBoolean(key,fallback));control.setOnCheckedChangeListener((v,checked)->change.accept(checked));parent.addView(control,new LinearLayout.LayoutParams(-1,-2));return control;
    }
    private void buildSettings(){
        LinearLayout root=pages[1],card=DashboardUi.card(root);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_gallery,getString(R.string.icons_title),getString(R.string.icons_settings_note),()->IconSettingsActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId(),"")));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_myplaces,getString(R.string.home_change),getString(R.string.home_change_note),()->HomeRegistration.settings(this)));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_lock_lock,getString(R.string.dashboard_permissions),getString(R.string.dashboard_permissions_note),()->openPrerequisites(true)));
        DashboardUi.section(root,getString(R.string.setup_display_section));card=DashboardUi.card(root);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_edit,getString(R.string.appearance_title),getString(R.string.dashboard_appearance_note),()->AppearanceActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId())));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_view,getString(R.string.shell_layout),getString(R.string.dashboard_layout_note),()->{
            String[] values={"auto","desktop","compact"};int selected=Arrays.asList(values).indexOf(Launches.prefs(this).getString("shell_layout","auto"));
            new AlertDialog.Builder(this).setTitle(R.string.shell_layout).setSingleChoiceItems(new String[]{getString(R.string.shell_layout_auto),getString(R.string.shell_layout_desktop),getString(R.string.shell_layout_compact)},Math.max(0,selected),(dialog,which)->{Launches.prefs(this).edit().putString("shell_layout",values[which]).apply();dialog.dismiss();}).setNegativeButton(R.string.ui_cancel,null).show();
        }));
        DashboardUi.section(root,getString(R.string.dashboard_workspace));card=DashboardUi.card(root);
        final Switch[] workspace={null};workspace[0]=toggle(card,R.string.workspace_enable,"compact_workspace",false,checked->{
            if(checked==Launches.prefs(this).getBoolean("compact_workspace",false))return;
            if(Launches.prefs(this).getBoolean("enabled",false)){workspace[0].setChecked(!checked);Ui.message(this,getString(R.string.workspace_stop_first));return;}
            Workspace.reset(this);Launches.prefs(this).edit().putBoolean("compact_workspace",checked).apply();
        });DashboardUi.divider(card);
        toggle(card,R.string.workspace_auto,"workspace_auto",false,checked->Launches.prefs(this).edit().putBoolean("workspace_auto",checked).apply());DashboardUi.divider(card);
        toggle(card,R.string.hide_virtual_keyboard,"hide_virtual_ime",false,checked->{
            Launches.prefs(this).edit().putBoolean("hide_virtual_ime",checked).apply();bridge.call(s->"OK",(result,error)->{
                if(checked&&(error!=null||Launches.prefs(this).getString("ime_diagnostics","").startsWith("unavailable")))Ui.message(this,getString(R.string.virtual_keyboard_unavailable));
            });
        });
        DashboardUi.section(root,getString(R.string.dashboard_windows));card=DashboardUi.card(root);
        toggle(card,R.string.ui_launch_in_windows_experimental,"freeform",false,checked->Launches.prefs(this).edit().putBoolean("freeform",checked).apply());
        enable=DashboardUi.action(this,getString(R.string.dashboard_configure),()->new AlertDialog.Builder(this).setTitle(R.string.ui_enable_desktop_features)
                .setMessage(R.string.ui_enable_freeform_windows_and_turn_off_android_s_force_desktop_mode).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_enable,(d,w)->apply(false)).show(),false);card.addView(enable);
        DashboardUi.section(root,getString(R.string.dashboard_support));card=DashboardUi.card(root);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_info_details,getString(R.string.dashboard_diagnostics),getString(R.string.dashboard_diagnostics_note),()->showPage(2)));
    }
    private void buildDiagnostics(){
        LinearLayout root=pages[2];root.addView(DashboardUi.action(this,getString(R.string.dashboard_back_settings),()->showPage(1),false));DashboardUi.space(root,16);
        LinearLayout card=DashboardUi.card(root);card.addView(DashboardUi.title(this,getString(R.string.dashboard_diagnostics),22));DashboardUi.space(card,16);
        reset=DashboardUi.action(this,getString(R.string.reset_connection),()->DockService.resetConnection(this),false);card.addView(reset);
        restore=DashboardUi.action(this,getString(R.string.ui_restore_previous_device_settings),()->new AlertDialog.Builder(this).setTitle(R.string.ui_restore_device_settings)
                .setMessage(R.string.ui_stop_the_external_desktop_and_restore_the_two_device_settings_sav).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_restore,(d,w)->apply(true)).show(),false);card.addView(restore);
        detail=DashboardUi.text(this,"",12,Ui.MUTED);detail.setTextIsSelectable(true);DashboardUi.space(card,16);card.addView(detail);DashboardUi.space(card,16);
        card.addView(DashboardUi.action(this,getString(R.string.ui_copy_diagnostics),()->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("StellaShell diagnostics",diagnostics()));Ui.message(this,getString(R.string.ui_diagnostics_copied));},false));
    }
    private void showPage(int page){
        selectedPage=Math.max(0,Math.min(2,page));for(int i=0;i<3;i++)pages[i].setVisibility(i==selectedPage?View.VISIBLE:View.GONE);
        for(int i=0;i<2;i++){boolean selected=i==Math.min(1,selectedPage);tabs[i].setSelected(selected);tabs[i].setTextColor(selected?Ui.ACCENT:Ui.MUTED);}
        pageScroll.scrollTo(0,0);refresh();
    }
    @Override public void onBackPressed(){if(selectedPage==2)showPage(1);else super.onBackPressed();}
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("setup_page",selectedPage);state.putInt("selected_output",selectedOutput);state.putBoolean("setup_open",setupOpen);}
    private String outputName(int id){
        if(id<0)return getString(R.string.dashboard_wait_output);
        Display display=displays.getDisplay(id);if(display==null)return getString(R.string.dashboard_output_disconnected,id);
        return getString(R.string.output_display,id==0?getString(R.string.output_device):display.getName(),id);
    }
    private void chooseOutput(){
        if(Launches.prefs(this).getBoolean("enabled",false))return;
        List<Integer> ids=new ArrayList<>();ids.add(0);ids.addAll(Displays.ids(this));if(ids.size()==1)ids.add(-1);
        String[] labels=new String[ids.size()];int checked=ids.indexOf(selectedOutput);for(int i=0;i<ids.size();i++)labels[i]=outputName(ids.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.dashboard_output).setSingleChoiceItems(labels,checked,(dialog,index)->{selectedOutput=ids.get(index);dialog.dismiss();refresh();}).setNegativeButton(R.string.ui_cancel,null).show();
    }
    private void chooseTransfer(){
        List<Integer> targets=Displays.allIds(this);targets.remove(Integer.valueOf(Workspace.target(this)));
        if(targets.isEmpty()){Ui.message(this,getString(R.string.ui_waiting_for_a_display));return;}
        String[] labels=new String[targets.size()];for(int i=0;i<labels.length;i++)labels[i]=outputName(targets.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.output_transfer).setItems(labels,(dialog,index)->{
            int target=targets.get(index);
            if(!Displays.allIds(this).contains(target)){Ui.message(this,getString(R.string.ui_the_external_display_is_disconnected));return;}
            DockService.handoff(this,target);
        }).setNegativeButton(R.string.ui_cancel,null).show();
    }
    private void startDesktop(){
        if(busy||Launches.prefs(this).getBoolean("enabled",false))return;
        if(!PrerequisitesActivity.satisfied(this)){openPrerequisites(false);return;}
        if(!bridge.ready()){openPrerequisites(true);return;}
        if(selectedOutput>=0&&!Displays.allIds(this).contains(selectedOutput)){Ui.message(this,getString(R.string.ui_the_external_display_is_disconnected));refresh();return;}
        boolean primary=selectedOutput==0;if(primary)Workspace.reset(this);
        Launches.prefs(this).edit().putBoolean("primary_mode",primary).apply();
        DockService.start(this,selectedOutput);refresh();
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
        s.append("\nVirtual keyboard: ").append(Launches.prefs(this).getString("ime_diagnostics","inactive"));
        s.append("\nMouse routing: ").append(Launches.prefs(this).getString("mouse_diagnostics","inactive"));
        return s.append(this.getString(R.string.ui_window_management)).append(Launches.prefs(this).getString("task_diagnostics",this.getString(R.string.ui_not_connected))).toString();
    }
    private void openPrerequisites(boolean manual){
        if(setupOpen||isFinishing())return;setupOpen=true;
        startActivityForResult(new Intent(this,PrerequisitesActivity.class).putExtra("manual",manual),manual?901:900);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request==900||request==901){setupOpen=false;}
    }
    private void refresh(){
        if(sessionTitle==null||isDestroyed()||isFinishing())return;
        homeStatus.setText(HomeRegistration.selected(this)?R.string.home_selected:R.string.home_available);
        boolean running=Launches.prefs(this).getBoolean("enabled",false);int active=Launches.prefs(this).getInt("active_display",-1);
        DashboardUi.state(shizukuChip,getString(bridge.ready()?R.string.dashboard_shizuku_ready:R.string.dashboard_shizuku_connecting),bridge.ready());
        DashboardUi.state(enabledChip,getString(running?R.string.dashboard_enabled:R.string.dashboard_disabled),running);
        sessionTitle.setText(running?(active>=0?R.string.dashboard_running:R.string.dashboard_waiting):R.string.dashboard_ready);
        sessionNote.setText(running?(active>=0?R.string.dashboard_running_note:R.string.dashboard_waiting_note):R.string.dashboard_ready_note);
        String destination=outputName(running?active:selectedOutput);outputValue.setText(running?destination:getString(R.string.dashboard_output_choice,destination));
        outputSelector.setEnabled(!running&&!busy);outputSelector.setContentDescription(getString(R.string.dashboard_output)+": "+destination);
        primaryAction.setText(running?R.string.reopen_desktop:(selectedOutput<0?R.string.external_wait_explicit:R.string.dashboard_start));
        primaryAction.setEnabled(!busy&&(running?active>=0:(selectedOutput<0||Displays.allIds(this).contains(selectedOutput))));
        stop.setVisibility(running?View.VISIBLE:View.GONE);stop.setEnabled(!busy);
        transfer.setVisibility(running&&Workspace.enabled(this)&&(Workspace.target(this)>0||!Displays.ids(this).isEmpty())?View.VISIBLE:View.GONE);transfer.setEnabled(!busy&&!Workspace.isBusy()&&(Workspace.target(this)>0||!Displays.ids(this).isEmpty()));
        enable.setEnabled(!busy&&bridge.ready());reset.setEnabled(running&&!busy&&bridge.ready());restore.setEnabled(!busy&&bridge.ready()&&Launches.prefs(this).contains("before_desktop"));
        if(selectedPage==2)detail.setText(diagnostics());
    }
    @Override public void onResume(){
        super.onResume();resumed=true;if(bridge!=null){bridge.connect();
            if(!setupOpen&&PrerequisitesActivity.satisfied(this)&&Launches.prefs(this).getBoolean("enabled",false)&&!DockService.running())
                DockService.start(this,Launches.prefs(this).getInt("preferred_display",-1),false);
            refresh();
        }
    }
    @Override public void onPause(){resumed=false;super.onPause();}
    @Override public void onDisplayAdded(int id){refresh();}
    @Override public void onDisplayRemoved(int id){refresh();}
    @Override public void onDisplayChanged(int id){refresh();}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){refresh();}
    @Override public void onDestroy(){
        if(bridge!=null)bridge.remove(refreshListener);if(displays!=null)displays.unregisterDisplayListener(this);
        Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);super.onDestroy();
    }
}
