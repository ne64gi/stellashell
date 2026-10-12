package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Daily dashboard; onboarding and diagnostic tools are deliberately separate surfaces. */
public final class SetupActivity extends Activity implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private int selectedOutput=0,selectedPage;
    private TextView homeStatus,sessionTitle,sessionNote,outputValue;
    private LinearLayout outputSelector;private Button primaryAction,stop,transfer;
    private final LinearLayout[] pages=new LinearLayout[2];private final Button[] tabs=new Button[2];
    private ScrollView pageScroll;private PowerMode power;private DisplayManager displays;
    private AutoCloseable settingsSubscription;
    private boolean setupOpen;private final Runnable refreshListener=this::refresh;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);power=PowerMode.of(this);displays=getSystemService(DisplayManager.class);
        // Preserve scrcpy's explicit launcher target, but never skip required permissions.
        if(power.active()&&!Displays.primary(this)&&getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)&&getDisplay()!=null&&getDisplay().getDisplayId()>0
                &&Displays.ids(this).contains(getDisplay().getDisplayId())&&PrerequisitesActivity.satisfied(this)){
            ShellRuntime.start(this);finish();return;
        }
        int saved=ShellSettings.of(this).snapshot().preferredDisplayId;
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
        for(int i=0;i<2;i++){pages[i]=Ui.column(this);body.addView(pages[i]);}
        buildOverview();buildSettings();
        displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);power.observe(refreshListener);ShellRuntime.observeNavigation(refreshListener);settingsSubscription=ShellSettings.of(this).observe((changes,snapshot)->refresh());
        showPage(state==null?0:state.getInt("setup_page",0));
    }
    private void buildOverview(){
        LinearLayout root=pages[0],home=DashboardUi.card(root);
        home.addView(DashboardUi.title(this,getString(R.string.home_title),20));
        homeStatus=DashboardUi.text(this,"",14,Ui.MUTED);home.addView(homeStatus);
        home.addView(DashboardUi.action(this,getString(R.string.home_open),()->startActivity(new Intent(this,HomeActivity.class)),false));
        home.addView(DashboardUi.action(this,getString(R.string.home_set_default),()->HomeRegistration.select(this),false));
        DashboardUi.space(root,18);LinearLayout session=DashboardUi.card(root);
        sessionTitle=DashboardUi.title(this,"",23);session.addView(sessionTitle);DashboardUi.space(session,6);
        sessionNote=DashboardUi.text(this,"",14,Ui.MUTED);session.addView(sessionNote);DashboardUi.space(session,20);
        outputSelector=Ui.column(this);outputSelector.setPadding(Ui.dp(this,16),Ui.dp(this,13),Ui.dp(this,16),Ui.dp(this,13));outputSelector.setBackground(Ui.rounded(this,Ui.BG,14));
        outputSelector.addView(DashboardUi.text(this,getString(R.string.setup_desktop_screen),12,Ui.MUTED));
        outputValue=DashboardUi.title(this,"",16);outputValue.setPadding(0,Ui.dp(this,4),0,0);outputSelector.addView(outputValue);outputSelector.setOnClickListener(v->chooseOutput());outputSelector.setFocusable(true);session.addView(outputSelector);
        DashboardUi.space(session,16);primaryAction=DashboardUi.action(this,"",()->{
            if(ShellRuntime.enabled(this)){
                int active=ShellRuntime.selectedDisplay();if(active>=0)Launches.home(this,active);
            }else startDesktop();
        },true);session.addView(primaryAction);
        DashboardUi.space(session,8);stop=DashboardUi.action(this,getString(R.string.setup_desktop_stop),()->{ShellRuntime.stop(this,false);refresh();},false);session.addView(stop);
        transfer=DashboardUi.action(this,getString(R.string.output_transfer),this::chooseTransfer,false);session.addView(transfer);
        DashboardUi.section(root,getString(R.string.dashboard_manage));LinearLayout manage=DashboardUi.card(root);
        manage.addView(DashboardUi.row(this,android.R.drawable.ic_menu_edit,getString(R.string.appearance_title),getString(R.string.dashboard_appearance_note),()->AppearanceActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId())));
        DashboardUi.space(root,12);root.addView(DashboardUi.action(this,getString(R.string.dashboard_connection_help),()->new AlertDialog.Builder(this).setTitle(R.string.dashboard_connection_help).setMessage(R.string.external_start_note).setPositiveButton(android.R.string.ok,null).show(),false));
    }
    private void buildSettings(){
        LinearLayout root=pages[1],card=DashboardUi.card(root);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_gallery,getString(R.string.icons_title),getString(R.string.icons_settings_note),()->IconSettingsActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId(),"")));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_search,getString(net.fuyumori.stellashell.feature.search.R.string.web_search_settings_title),getString(net.fuyumori.stellashell.feature.search.R.string.web_search_settings_summary),()->SearchSettingsActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId())));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_myplaces,getString(R.string.home_change),getString(R.string.home_change_note),()->HomeRegistration.settings(this)));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_lock_lock,getString(R.string.dashboard_permissions),getString(R.string.dashboard_permissions_note),()->openPrerequisites(true)));
        DashboardUi.section(root,getString(R.string.setup_display_section));card=DashboardUi.card(root);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_edit,getString(R.string.appearance_title),getString(R.string.dashboard_appearance_note),()->AppearanceActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId())));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_sort_by_size,getString(R.string.sidebar_settings_title),getString(R.string.sidebar_settings_summary),()->SidebarSettingsActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId())));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_more,getString(R.string.taskbar_settings_title),getString(R.string.taskbar_settings_summary),()->TaskbarSettingsActivity.open(this,getDisplay()==null?0:getDisplay().getDisplayId())));
        DashboardUi.divider(card);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_view,getString(R.string.shell_layout),getString(R.string.dashboard_layout_note),()->{
            ShellSettings.Layout[] values=ShellSettings.Layout.values();int selected=ShellSettings.of(this).snapshot().shellLayout.ordinal();
            new AlertDialog.Builder(this).setTitle(R.string.shell_layout).setSingleChoiceItems(new String[]{getString(R.string.shell_layout_auto),getString(R.string.shell_layout_desktop),getString(R.string.shell_layout_compact)},Math.max(0,selected),(dialog,which)->{ShellSettings.of(this).setShellLayout(values[which]);dialog.dismiss();}).setNegativeButton(R.string.ui_cancel,null).show();
        }));
        DashboardUi.section(root,getString(R.string.power_mode_title));card=DashboardUi.card(root);
        card.addView(DashboardUi.row(this,android.R.drawable.ic_menu_manage,getString(R.string.power_mode_title),getString(R.string.power_mode_summary),()->PowerModeActivity.open(this)));
    }
    private void showPage(int page){
        selectedPage=Math.max(0,Math.min(1,page));for(int i=0;i<2;i++)pages[i].setVisibility(i==selectedPage?View.VISIBLE:View.GONE);
        for(int i=0;i<2;i++){boolean selected=i==Math.min(1,selectedPage);tabs[i].setSelected(selected);tabs[i].setTextColor(selected?Ui.ACCENT:Ui.MUTED);}
        pageScroll.scrollTo(0,0);refresh();
    }
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("setup_page",selectedPage);state.putInt("selected_output",selectedOutput);state.putBoolean("setup_open",setupOpen);}
    private String outputName(int id){
        if(id<0)return getString(R.string.setup_external_screen_later);
        Display display=displays.getDisplay(id);if(display==null)return getString(R.string.setup_screen_disconnected);
        if(id==0)return getString(R.string.output_device);
        String name=display.getName();int count=0,ordinal=0;
        for(Display other:Displays.available(this))if(name.equals(other.getName())){count++;if(other.getDisplayId()<=id)ordinal++;}
        // Multiple scrcpy screens can share a name; number them without exposing backend IDs.
        return count>1?getString(R.string.setup_screen_numbered,name,ordinal):name;
    }
    private void chooseOutput(){
        if(ShellRuntime.enabled(this)||!power.active())return;
        List<Integer> ids=new ArrayList<>();ids.add(0);ids.addAll(Displays.ids(this));if(ids.size()==1)ids.add(-1);
        String[] labels=new String[ids.size()];int checked=ids.indexOf(selectedOutput);for(int i=0;i<ids.size();i++)labels[i]=outputName(ids.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.setup_desktop_screen).setSingleChoiceItems(labels,checked,(dialog,index)->{selectedOutput=ids.get(index);dialog.dismiss();refresh();}).setNegativeButton(R.string.ui_cancel,null).show();
    }
    private void chooseTransfer(){
        if(!power.active())return;
        List<Integer> targets=Displays.allIds(this);targets.remove(Integer.valueOf(TaskState.of(this).target(this)));
        if(targets.isEmpty()){Ui.message(this,getString(R.string.ui_waiting_for_a_display));return;}
        String[] labels=new String[targets.size()];for(int i=0;i<labels.length;i++)labels[i]=outputName(targets.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.output_transfer).setItems(labels,(dialog,index)->{
            int target=targets.get(index);
            if(!Displays.allIds(this).contains(target)){Ui.message(this,getString(R.string.ui_the_external_display_is_disconnected));return;}
            ShellRuntime.handoff(this,target);
        }).setNegativeButton(R.string.ui_cancel,null).show();
    }
    private void startDesktop(){
        if(ShellRuntime.enabled(this))return;
        if(!PrerequisitesActivity.satisfied(this)){openPrerequisites(false);return;}
        if(!power.active()){ShellRuntime.startBase(this,true);refresh();return;}
        if(selectedOutput>=0&&!Displays.allIds(this).contains(selectedOutput)){Ui.message(this,getString(R.string.ui_the_external_display_is_disconnected));refresh();return;}
        boolean primary=selectedOutput==0;if(primary)TaskState.of(this).reset(this);
        ShellSettings.of(this).setPrimaryMode(primary);
        ShellRuntime.start(this,selectedOutput);refresh();
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
        boolean running=ShellRuntime.enabled(this);int active=ShellRuntime.selectedDisplay();
        boolean advanced=power.active();
        sessionTitle.setText(advanced?R.string.setup_desktop_title:R.string.base_shell_title);
        outputSelector.setVisibility(advanced?View.VISIBLE:View.GONE);
        List<Integer> available=Displays.allIds(this);boolean activeConnected=active>=0&&available.contains(active);
        boolean disconnected=!running&&selectedOutput>=0&&!available.contains(selectedOutput);
        sessionNote.setText(!advanced?R.string.base_shell_note:running?(activeConnected?R.string.setup_desktop_open_note:active>=0?R.string.setup_desktop_reconnect_note:R.string.setup_desktop_connect_note):(disconnected?R.string.setup_desktop_disconnected_note:R.string.setup_desktop_choose_note));
        String destination=outputName(running?active:selectedOutput);outputValue.setText(running?destination:getString(R.string.dashboard_output_choice,destination));
        outputSelector.setEnabled(!running);outputSelector.setContentDescription(getString(R.string.setup_desktop_screen)+": "+destination);
        primaryAction.setText(!advanced?(running?R.string.home_open:R.string.base_shell_start):running?R.string.setup_desktop_open:(selectedOutput<0?R.string.setup_desktop_start_on_connection:R.string.setup_desktop_start));
        primaryAction.setEnabled(!advanced||(running?activeConnected:(selectedOutput<0||available.contains(selectedOutput))));
        stop.setVisibility(running?View.VISIBLE:View.GONE);stop.setText(advanced?R.string.setup_desktop_stop:R.string.base_shell_stop);
        transfer.setVisibility(advanced&&running&&TaskState.of(this).enabled(this)&&(TaskState.of(this).target(this)>0||!Displays.ids(this).isEmpty())?View.VISIBLE:View.GONE);transfer.setEnabled(advanced&&!TaskState.of(this).isBusy()&&(TaskState.of(this).target(this)>0||!Displays.ids(this).isEmpty()));
    }
    @Override public void onResume(){
        super.onResume();
        if(!setupOpen&&PrerequisitesActivity.satisfied(this)&&ShellRuntime.enabled(this)&&!ShellRuntime.running())
            ShellRuntime.start(this,ShellSettings.of(this).snapshot().preferredDisplayId,false);
        refresh();
    }
    @Override public void onDisplayAdded(int id){refresh();}
    @Override public void onDisplayRemoved(int id){refresh();}
    @Override public void onDisplayChanged(int id){refresh();}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){refresh();}
    @Override public void onDestroy(){
        if(power!=null)power.remove(refreshListener);if(displays!=null)displays.unregisterDisplayListener(this);
        Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);ShellRuntime.unobserveNavigation(refreshListener);if(settingsSubscription!=null)try{settingsSubscription.close();}catch(Exception e){Launches.problem(this,e.getMessage());}super.onDestroy();
    }
}
