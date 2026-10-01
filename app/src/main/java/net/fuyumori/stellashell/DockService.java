package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

public final class DockService extends Service implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private static boolean alive;
    private static DockService instance;
    private static boolean homeVisible;
    static void homeVisible(boolean visible){homeVisible=visible;if(instance!=null){instance.positionDock();instance.positionHandles();}}
    private boolean sidebarAllowed(){return displayId!=0||Launches.prefs(this).getBoolean("phone_sidebar_over_apps",true)||homeVisible;}
    static boolean running(){return alive;}
    private static final String STOP="net.fuyumori.stellashell.STOP";
    private static final String RESET="net.fuyumori.stellashell.RESET_CONNECTION";
    private boolean resetting,destroyed;
    private String displayGeometry="";
    private DisplayManager displays; private WindowManager windows; private View dock; private int displayId=-1;
    private AppMenu menu;private boolean collapsed;private long startDownTime;
    private final Runnable panelsChanged=this::updatePanelChrome;
    private void updatePanelChrome(){if(chrome!=null&&tasks!=null)try{chrome.update(tasks.tasks());}catch(RuntimeException e){chrome.clear();Launches.problem(this,e.getMessage());}}
    private void watchStart(View view){view.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)startDownTime=e.getDownTime();return false;});}
    private WorkAreaObserver areaObserver;private int observedDisplay=-1;private boolean compactShown,compactRight=true,renderedCompact;
    private final java.util.List<View> edgeHandles=new java.util.ArrayList<>();
    private void closeAreaObserver(){if(areaObserver!=null){areaObserver.close();areaObserver=null;}observedDisplay=-1;}
    private void areaChanged(){
        if(destroyed||displayId<0)return;
        WorkArea area=WorkArea.get(this,displayId);
        if(area.compact!=renderedCompact){removeDock();attachDock();}
        else {positionDock();positionHandles();}
        if(menu!=null)menu.relayout();
        if(tasks!=null)tasks.areaChanged();
        if(chrome!=null&&tasks!=null)chrome.update(tasks.tasks());
    }
    private void positionDock(){
        if(dock==null||windows==null)return;
        WorkArea a=WorkArea.get(this,displayId);Context c=dock.getContext();
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)dock.getLayoutParams();
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);
        if(a.compact){
            if(!sidebarAllowed())compactShown=false;
            int width=Ui.dp(c,76),height=Math.min(Ui.dp(c,500),a.usable.height());
            if(dock instanceof ScrollView){View content=((ScrollView)dock).getChildAt(0);android.view.ViewGroup.LayoutParams child=content.getLayoutParams();int h=Math.max(Ui.dp(c,176),height);if(child.height!=h){child.height=h;content.setLayoutParams(child);}}
            p.width=compactShown?width:1;p.height=compactShown?height:1;
            p.x=compactRight?a.usable.right-width:a.usable.left;p.y=a.usable.bottom-height;
            dock.setVisibility(compactShown?View.VISIBLE:View.GONE);
        }else{
            p.width=collapsed?Ui.dp(c,144):a.usable.width();p.height=Ui.dp(c,52);
            p.x=a.usable.left;p.y=Math.max(a.usable.top,a.usable.bottom-Ui.dp(c,56));
        }
        windows.updateViewLayout(dock,p);
    }
    private void positionHandles(){
        if(windows==null||displayId<0)return;WorkArea a=WorkArea.get(this,displayId);
        for(int i=0;i<edgeHandles.size();i++){
            View v=edgeHandles.get(i);WindowManager.LayoutParams p=(WindowManager.LayoutParams)v.getLayoutParams();
            p.x=i==0?Math.max(a.usable.left,a.gestureLeft)+Ui.dp(v.getContext(),8):Math.min(a.usable.right,a.physical.right-a.gestureRight)-Ui.dp(v.getContext(),24);
            int travel=Math.max(0,a.usable.height()-p.height);
            int percent=Math.max(0,Math.min(100,Launches.prefs(this).getInt("sidebar_height",80)));
            p.y=a.usable.top+Math.round(travel*percent/100f);
            v.setVisibility(sidebarAllowed()?View.VISIBLE:View.GONE);windows.updateViewLayout(v,p);
        }
    }
    private void showCompact(boolean right){if(!sidebarAllowed())return;compactRight=right;compactShown=true;positionDock();}
    private void attachCompact(Context c){
        renderedCompact=true;
        LinearLayout column=Ui.column(c);column.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        TextClock clock=new TextClock(c);clock.setTypeface(Appearance.face);clock.setTextColor(Ui.TEXT);clock.setTextSize(16);clock.setFormat24Hour("HH:mm");clock.setFormat12Hour("HH:mm");clock.setGravity(Gravity.CENTER);clock.setContentDescription(getString(R.string.hub_title));
        clock.setOnClickListener(v->{compactShown=false;positionDock();HubActivity.open(this,displayId);});column.addView(clock,new LinearLayout.LayoutParams(-1,Ui.dp(c,44)));
        column.addView(batteryView(c),new LinearLayout.LayoutParams(-1,Ui.dp(c,44)));
        ScrollView scroll=new ScrollView(c);LinearLayout entries=Ui.column(c);entries.setGravity(Gravity.CENTER_HORIZONTAL);scroll.addView(entries);
        java.util.List<TaskSession.Task> running=tasks.tasks();java.util.Set<Integer> used=new java.util.HashSet<>();
        for(String component:Launches.pins(c)){
            TaskSession.Task match=null;for(TaskSession.Task t:running)if(t.packageName().equals(ComponentName.unflattenFromString(component).getPackageName())){match=t;break;}
            addEntry(c,entries,component,match,true);if(match!=null)used.add(match.id);
        }
        for(TaskSession.Task t:running)if(!used.contains(t.id))addEntry(c,entries,t.component,t,false);
        column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button home=Ui.toolbarButton(c,"▱",()->{compactShown=false;positionDock();Launches.home(this,displayId);});
        home.setContentDescription(getString(R.string.ui_show_desktop));home.setTooltipText(getString(R.string.ui_show_desktop));
        column.addView(home,new LinearLayout.LayoutParams(-1,Ui.dp(c,48)));
        ImageButton start=new ImageButton(c);start.setImageResource(R.mipmap.ic_launcher);start.setScaleType(ImageView.ScaleType.FIT_CENTER);start.setBackground(Ui.toolbarBackground(c,12));start.setPadding(Ui.dp(c,12),Ui.dp(c,5),Ui.dp(c,12),Ui.dp(c,5));start.setContentDescription(getString(R.string.ui_app_menu));
        watchStart(start);start.setOnClickListener(v->{compactShown=false;positionDock();toggleMenu();});column.addView(start,new LinearLayout.LayoutParams(-1,Ui.dp(c,48)));
        column.addView(Ui.toolbarButton(c,"×",()->{compactShown=false;positionDock();}),new LinearLayout.LayoutParams(-1,Ui.dp(c,40)));
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);p.setTitle("StellaShell edge dock");
        ScrollView viewport=new ScrollView(c){@Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE){compactShown=false;positionDock();return true;}return super.dispatchTouchEvent(event);}};
        viewport.setFillViewport(true);viewport.setClipToOutline(true);viewport.setBackground(Appearance.surface(c,18));viewport.addView(column,new FrameLayout.LayoutParams(-1,Ui.dp(c,500)));
        windows.addView(viewport,p);dock=viewport;positionDock();
        for(int side=0;side<2;side++){
            final boolean right=side==1;View handle=new View(c){@Override public boolean performClick(){super.performClick();return true;}private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);@Override protected void onDraw(android.graphics.Canvas canvas){paint.setColor((Ui.TEXT&0xffffff)|0x66000000);float half=Ui.dp(getContext(),1),length=Ui.dp(getContext(),16);canvas.drawRoundRect(getWidth()/2f-half,getHeight()/2f-length,getWidth()/2f+half,getHeight()/2f+length,half,half,paint);}};handle.setContentDescription(getString(R.string.edge_dock_open));
            handle.setOnClickListener(v->showCompact(right));handle.setOnTouchListener(new View.OnTouchListener(){float x,y;public boolean onTouch(View v,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-x;if((right?-dx:dx)>Ui.dp(c,16)&&Math.abs(dx)>Math.abs(e.getRawY()-y))showCompact(right);return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_UP){float dx=e.getRawX()-x;if(Math.abs(dx)<Ui.dp(c,8)&&Math.abs(e.getRawY()-y)<Ui.dp(c,8))v.performClick();else if((right?-dx:dx)>Ui.dp(c,16))showCompact(right);return true;}return true;
            }});
            WindowManager.LayoutParams h=new WindowManager.LayoutParams(Ui.dp(c,16),Ui.dp(c,64),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            h.gravity=Gravity.TOP|Gravity.LEFT;h.setFitInsetsTypes(0);h.setTitle("StellaShell edge handle "+side);windows.addView(handle,h);edgeHandles.add(handle);
        }
        positionHandles();displayGeometry=geometry(displayId);
    }
    private TextView batteryText;private int batteryPercent=-1;private boolean batteryCharging,backPending,batteryRegistered;
    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
        int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
        batteryPercent=level>=0&&scale>0?Math.min(100,Math.round(level*100f/scale)):-1;
        batteryCharging=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;updateBattery();
    }};
    private void updateBattery(){
        if(batteryText==null)return;
        String label=batteryPercent<0?getString(R.string.battery_unknown):getString(R.string.battery_percent,batteryPercent);
        batteryText.setText((batteryCharging?"⚡ ":"")+label);
        batteryText.setTextColor(batteryPercent>=0&&batteryPercent<=20&&!batteryCharging?0xffffad79:Ui.TEXT);
        String description=batteryPercent<0?label:getString(batteryCharging?R.string.battery_charging:R.string.battery_remaining,batteryPercent);
        batteryText.setContentDescription(description);batteryText.setTooltipText(description);
    }
    private View batteryView(Context c){batteryText=Ui.text(c,"",13,Ui.TEXT);batteryText.setGravity(Gravity.CENTER);batteryText.setBackground(Ui.toolbarBackground(c,12));updateBattery();batteryText.setOnClickListener(v->{compactShown=false;positionDock();if(menu!=null)menu.close();QuickSettingsActivity.open(this,displayId);});return batteryText;}
    private void back(){
        if(menu!=null&&menu.isOpen()){menu.back();return;}
        if(backPending)return;
        Bridge bridge=Bridge.get(this);if(!bridge.ready()){Ui.message(this,bridge.status());return;}
        int target=displayId;backPending=true;
        bridge.call(service->service.back(target),(result,error)->{backPending=false;if(error!=null)Launches.problem(this,error);});
    }

    private TaskSession tasks;private WindowChrome chrome;private String taskSignature="";
    private final ExecutorService menuLoader=Executors.newSingleThreadExecutor();
    static void enableHome(Context c,boolean enabled) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c,DesktopActivity.class),
                enabled?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
    }
    static void start(Context c) {
        int target=c instanceof Activity&&c.getDisplay()!=null&&c.getDisplay().getDisplayId()>0
                ?c.getDisplay().getDisplayId():Launches.prefs(c).getInt("preferred_display",-1);
        start(c,target);
    }
    static void start(Context c,int target) {start(c,target,true);}
    static void start(Context c,int target,boolean showHome) {
        if(!Settings.canDrawOverlays(c)){Ui.message(c,c.getString(R.string.ui_allow_the_taskbar_overlay_first));return;}
        Launches.prefs(c).edit().putInt("preferred_display",target).remove("active_display").apply();
        enableHome(c,true);Launches.prefs(c).edit().putBoolean("enabled",true).apply();
        try {c.startForegroundService(new Intent(c,DockService.class).putExtra("show_home",showHome));}
        catch(RuntimeException e){Launches.prefs(c).edit().putBoolean("enabled",false).apply();enableHome(c,false);Launches.problem(c,e.getMessage());}
    }
    static void handoff(Context c,int display){
        if(!Workspace.enabled(c)||!Launches.prefs(c).getBoolean("enabled",false))return;
        c.startService(new Intent(c,DockService.class).setAction("net.fuyumori.stellashell.HANDOFF").putExtra("destination",display));
    }
    static void resetConnection(Context c){
        if(!Launches.prefs(c).getBoolean("enabled",false)){
            Launches.prefs(c).edit().remove("preferred_display").apply();return;
        }
        try{c.startService(new Intent(c,DockService.class).setAction(RESET));}
        catch(RuntimeException e){Launches.problem(c,e.getMessage());}
    }
    static void stop(Context c) { stop(c,true); }
    static void stop(Context c,boolean showHome) {
        boolean returnHome=showHome && Displays.primaryActive(c);
        Launches.prefs(c).edit().putBoolean("enabled",false).apply();
        Bridge.get(c).mouseDisplay(-1);
        Bridge.get(c).call(s->{s.setPrimaryMode(false);return "OK";},(result,error)->{});
        if(returnHome)try {
            android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0);
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options.toBundle());
        }catch(RuntimeException e){Launches.problem(c,e.getMessage());}
        c.stopService(new Intent(c,DockService.class));enableHome(c,false);Workspace.reset(c);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){
        super.onConfigurationChanged(configuration);closeAreaObserver();removeDock();update(false);
    }
    @Override public void onCreate() {
        super.onCreate();alive=true;instance=this;ShellPanels.observe(panelsChanged);
        Launches.prefs(this).edit().remove("active_display").apply();
        IntentFilter batteryFilter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery=Build.VERSION.SDK_INT>=33?registerReceiver(batteryReceiver,batteryFilter,Context.RECEIVER_NOT_EXPORTED):registerReceiver(batteryReceiver,batteryFilter);
        batteryRegistered=true;if(battery!=null)batteryReceiver.onReceive(this,battery);
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("desktop",this.getString(R.string.ui_external_desktop),NotificationManager.IMPORTANCE_LOW));
        startForeground(41,notification(this.getString(R.string.ui_waiting_for_a_display)));
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));
        Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);Bridge.get(this).connect();
    }
    private Notification notification(String message) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,SetupActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,DockService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent desktop=PendingIntent.getService(this,2,new Intent(this,DockService.class).setAction("net.fuyumori.stellashell.SHOW_DESKTOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"desktop").setSmallIcon(R.drawable.ic_desktop).setContentTitle("StellaShell")
                .setContentText(message).setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,this.getString(R.string.ui_back_to_desktop),desktop).build()).addAction(new Notification.Action.Builder(null,this.getString(R.string.ui_stop),stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if((intent!=null && STOP.equals(intent.getAction())) || !Launches.prefs(this).getBoolean("enabled",false)) {
            stop(this);return START_NOT_STICKY;
        }
        if(intent!=null && RESET.equals(intent.getAction())){
            if(!resetting){
                resetting=true;ShellPanels.dismiss(displayId);closeAreaObserver();removeDock();if(tasks!=null)tasks.close();tasks=null;taskSignature="";displayId=-1;
                Launches.prefs(this).edit().remove("preferred_display").remove("active_display").apply();
                Bridge.get(this).resetMouseRouting((result,error)->{
                    if(destroyed)return;
                    resetting=false;
                    if(!Launches.prefs(this).getBoolean("enabled",false))return;
                    if(error!=null){Launches.problem(this,error);stop(this,false);return;}
                    update(true);
                });
            }
            return START_STICKY;
        }
        if(intent!=null&&"net.fuyumori.stellashell.HANDOFF".equals(intent.getAction())){
            Workspace.transfer(this,intent.getIntExtra("destination",0),()->{
                update(true);
                if(Workspace.target(this)>0)startActivity(new Intent(this,SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            });return START_STICKY;
        }
        boolean showHome=intent==null||intent.getBooleanExtra("show_home",true);
        update(showHome,!showHome);return START_STICKY;
    }
    private void removeDock() {
        removeDock(false);
    }
    private void removeDock(boolean keepChrome) {
        if(!keepChrome){if(chrome!=null)chrome.clear();chrome=null;}
        if(menu!=null)menu.close();menu=null;
        if(dock!=null && windows!=null)try{windows.removeViewImmediate(dock);}catch(RuntimeException ignored){}
        for(View handle:edgeHandles)if(windows!=null)try{windows.removeViewImmediate(handle);}catch(RuntimeException ignored){}edgeHandles.clear();
        dock=null;batteryText=null;if(!keepChrome)windows=null;
    }
    private void update(boolean openHome) {update(openHome,false);}
    private void update(boolean openHome,boolean keepDashboard) {
        if(resetting || destroyed)return;
        int preferred=displayId>0?displayId:Launches.prefs(this).getInt("preferred_display",-1);
        int next=Displays.target(this,preferred);
        Launches.prefs(this).edit().putInt("active_display",next).apply();
        Bridge.get(this).mouseDisplay(next);
        boolean changed=next!=displayId;
        if(changed){ShellPanels.dismiss(displayId);closeAreaObserver();removeDock();if(tasks!=null)tasks.close();tasks=null;taskSignature="";displayId=next;}
        if(next<0){getSystemService(NotificationManager.class).notify(41,notification(this.getString(R.string.ui_waiting_for_a_display)));return;}
        if(!Settings.canDrawOverlays(this)){Launches.problem(this,this.getString(R.string.ui_overlay_permission_was_revoked));stop(this);return;}
        if(tasks==null)tasks=new TaskSession(this,next,this::tasksChanged,()->menu!=null&&menu.isOpen());
        if(dock==null)attachDock();
        if(dock==null){getSystemService(NotificationManager.class).notify(41,notification(this.getString(R.string.ui_could_not_show_the_taskbar_check_permissions)));return;}
        if(dock!=null && !keepDashboard && (changed || openHome))Launches.home(this,next);
        getSystemService(NotificationManager.class).notify(41,notification(this.getString(Displays.primary(this)?R.string.primary_running:R.string.ui_running_on_an_external_display_tap_for_settings)));
    }
    private void attachDock() {
        try {
            Context c=createDisplayContext(Displays.require(this,displayId)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            c=new android.view.ContextThemeWrapper(c,Appearance.theme());
            windows=c.getSystemService(WindowManager.class);
            if(areaObserver==null||observedDisplay!=displayId){closeAreaObserver();observedDisplay=displayId;areaObserver=new WorkAreaObserver(c,displayId,this::areaChanged);}
            menu=new AppMenu(c,windows,displayId);
            if(chrome==null)chrome=new WindowChrome(c,windows,tasks);
            if(WorkArea.get(this,displayId).compact){attachCompact(c);return;}
            renderedCompact=false;
            LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(c,6),Ui.dp(c,4),Ui.dp(c,6),Ui.dp(c,4));
            row.setBackground(Appearance.surface(c,18));
            int widthDp=Math.round(c.getResources().getDisplayMetrics().widthPixels/c.getResources().getDisplayMetrics().density);
            ImageButton apps=new ImageButton(c);apps.setImageResource(R.mipmap.ic_launcher);apps.setScaleType(ImageView.ScaleType.FIT_CENTER);
            apps.setBackgroundTintList(null);apps.setBackground(Ui.toolbarBackground(c,12));apps.setPadding(Ui.dp(c,7),Ui.dp(c,4),Ui.dp(c,7),Ui.dp(c,4));
            watchStart(apps);apps.setOnClickListener(v->{if(collapsed){collapsed=false;removeDock();update(false);}toggleMenu();});
            apps.setContentDescription(getString(R.string.ui_app_menu));apps.setTooltipText(getString(R.string.start_menu_label));
            row.addView(apps,new LinearLayout.LayoutParams(Ui.dp(c,56),Ui.dp(c,44)));
            if(!collapsed){
            Button back=Ui.toolbarButton(c,"‹",this::back);back.setContentDescription(getString(R.string.external_back));back.setTooltipText(getString(R.string.external_back));
            row.addView(back,new LinearLayout.LayoutParams(Ui.dp(c,44),Ui.dp(c,44)));
            Button home=Ui.toolbarButton(c,"▱",()->Launches.home(this,displayId));home.setContentDescription(this.getString(R.string.ui_show_desktop));home.setTooltipText(this.getString(R.string.ui_show_desktop));
            row.addView(home,new LinearLayout.LayoutParams(Ui.dp(c,48),Ui.dp(c,44)));
            android.widget.HorizontalScrollView strip=new android.widget.HorizontalScrollView(c);strip.setHorizontalScrollBarEnabled(false);
            LinearLayout entries=new LinearLayout(c);entries.setGravity(Gravity.CENTER_VERTICAL);strip.addView(entries);
            java.util.List<TaskSession.Task> running=tasks==null?new java.util.ArrayList<>():tasks.tasks();
            java.util.Set<Integer> represented=new java.util.HashSet<>();
            for(String component:Launches.pins(c)){
                String pkg=ComponentName.unflattenFromString(component).getPackageName();
                TaskSession.Task match=null;for(TaskSession.Task t:running)if(t.packageName().equals(pkg)){match=t;break;}
                addEntry(c,entries,component,match,true);if(match!=null)represented.add(match.id);
            }
            for(TaskSession.Task t:running)if(!represented.contains(t.id))addEntry(c,entries,t.component,t,false);
            row.addView(strip,new LinearLayout.LayoutParams(0,Ui.dp(c,44),1));

            Button shot=Ui.toolbarButton(c,"▣",()->{if(menu!=null)menu.close();DesktopScreenshot.take(this,displayId);});shot.setContentDescription(getString(R.string.screenshot_take));shot.setTooltipText(getString(R.string.screenshot_take));row.addView(shot,new LinearLayout.LayoutParams(Ui.dp(c,44),Ui.dp(c,44)));
            row.addView(batteryView(c),new LinearLayout.LayoutParams(Ui.dp(c,76),Ui.dp(c,44)));
            TextView connection=Ui.text(c,Bridge.get(this).ready()?"●":"○",12,Bridge.get(this).ready()?Ui.ACCENT:Ui.MUTED);
            connection.setBackground(Ui.toolbarBackground(c,12));connection.setGravity(Gravity.CENTER);connection.setContentDescription(Bridge.get(this).status());connection.setTooltipText(Bridge.get(this).status());
            connection.setOnClickListener(v->Launches.settings(this,displayId));row.addView(connection,new LinearLayout.LayoutParams(Ui.dp(c,28),-1));
            {TextClock clock=new TextClock(c);clock.setBackground(Ui.toolbarBackground(c,12));clock.setTypeface(Appearance.face);clock.setFormat24Hour("HH:mm");clock.setFormat12Hour("HH:mm");clock.setTextColor(Ui.TEXT);clock.setTextSize(16);clock.setContentDescription(getString(R.string.hub_title));clock.setTooltipText(getString(R.string.hub_title));clock.setOnClickListener(v->{if(menu!=null)menu.close();HubActivity.open(this,displayId);});row.addView(clock,new LinearLayout.LayoutParams(Ui.dp(c,58),-2));}
            Button hide=Ui.toolbarButton(c,"−",()->{collapsed=true;removeDock();update(false);});hide.setContentDescription(this.getString(R.string.ui_collapse_taskbar));
            row.addView(hide,new LinearLayout.LayoutParams(Ui.dp(c,44),Ui.dp(c,44)));
            }
            if(collapsed)row.addView(batteryView(c),new LinearLayout.LayoutParams(Ui.dp(c,76),Ui.dp(c,44)));
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(collapsed?-2:-1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            p.gravity=Gravity.BOTTOM|Gravity.LEFT;p.setFitInsetsTypes(displayId==0?WindowInsets.Type.navigationBars():0);p.y=Ui.dp(c,4);p.setTitle("StellaShell dock");
            windows.addView(row,p);dock=row;positionDock();displayGeometry=geometry(displayId);
        }catch(RuntimeException e){removeDock();Launches.problem(this,this.getString(R.string.ui_could_not_show_the_taskbar)+e.getMessage());}
    }
    private void tasksChanged(){
        if(tasks==null||displayId<0)return;
        StringBuilder signature=new StringBuilder(Bridge.get(this).ready()?"ready":"offline");signature.append(Workspace.primary());
        for(TaskSession.Task t:tasks.tasks())signature.append(t.id).append(t.component).append(t.focused).append(t.visible).append(t.alwaysOnTop).append(Workspace.label(this,t));
        boolean menuOpen=menu!=null&&menu.isOpen();
        if(!signature.toString().equals(taskSignature)&&!menuOpen){taskSignature=signature.toString();removeDock(true);attachDock();}
        if(chrome!=null)try{chrome.update(tasks.tasks());}catch(RuntimeException e){chrome.clear();Launches.problem(this,e.getMessage());}
    }
    private void addEntry(Context c,LinearLayout row,String component,TaskSession.Task task,boolean pinned){
        try{
            ComponentName name=ComponentName.unflattenFromString(component);
            android.content.pm.ApplicationInfo info=getPackageManager().getApplicationInfo(name.getPackageName(),0);
            CharSequence label=info.loadLabel(getPackageManager());
            LinearLayout item=Ui.column(c);item.setGravity(Gravity.CENTER);item.setPadding(Ui.dp(c,7),Ui.dp(c,3),Ui.dp(c,7),0);
            item.setBackground(Ui.toolbarBackground(c,9));item.setSelected(task!=null&&task.focused);
            ImageView icon=new ImageView(c);icon.setImageDrawable(AppIcons.forApp(c,component,info.loadIcon(getPackageManager())));item.addView(icon,new LinearLayout.LayoutParams(Ui.dp(c,29),Ui.dp(c,29)));
            String role=task!=null&&Workspace.compact(this,displayId)?Workspace.label(c,task):"";
            if(task!=null&&task.alwaysOnTop)role+=(role.isEmpty()?"":" · ")+c.getString(R.string.window_pin);
            TextView mark=Ui.text(c,task!=null&&task.alwaysOnTop?"↑":!role.isEmpty()?c.getString(task.id==Workspace.primary()?R.string.workspace_primary_badge:R.string.workspace_secondary_badge):task==null?"":task.visible?"━":"·",10,Ui.ACCENT);mark.setGravity(Gravity.CENTER);mark.setSingleLine(true);mark.setIncludeFontPadding(false);item.addView(mark,new LinearLayout.LayoutParams(-1,Ui.dp(c,11)));
            item.setContentDescription(label+(task!=null?this.getString(R.string.ui_running):this.getString(R.string.ui_pinned))+(role.isEmpty()?"":" · "+role));item.setTooltipText(role.isEmpty()?label:label+" · "+role);
            item.setOnClickListener(v->{ShellPanels.dismiss(displayId);if(task!=null){
                if(Workspace.compact(this,displayId)){Launches.focus(this,task,displayId,tasks);return;}
                String requested=Profiles.requestedComponent(this,component);AppLaunchProfile.Mode mode=Profiles.get(this,requested).launchMode;
                if(task.mode==1&&(mode==AppLaunchProfile.Mode.WINDOWED||mode==AppLaunchProfile.Mode.MAXIMIZED))Launches.app(this,requested,displayId);
                else tasks.action(task,"focus");
            }else Launches.app(this,component,displayId);});
            item.setOnLongClickListener(v->{
                ShellPanels.dismiss(displayId);AppContextMenu.show(c,item,component,displayId,()->{},task,tasks);return true;
            });
            item.setOnContextClickListener(v->v.performLongClick());
            row.addView(item,new LinearLayout.LayoutParams(Ui.dp(c,48),Ui.dp(c,44)));
        }catch(Exception ignored){}
    }
    private void toggleMenu(){try{long downTime=startDownTime;startDownTime=0;if(menu!=null)menu.toggle(menuLoader,downTime);}catch(RuntimeException e){Launches.problem(this,this.getString(R.string.ui_could_not_open_the_app_menu)+e.getMessage());}}
    @Override public void onDisplayAdded(int id){
        if(Workspace.enabled(this)&&Workspace.target(this)==0&&Launches.prefs(this).getBoolean("workspace_auto",false))
            new Handler(Looper.getMainLooper()).postDelayed(()->{if(!destroyed&&Displays.ids(this).contains(id))handoff(this,id);},600);
        else if(!Displays.primary(this))update(true);
    }
    @Override public void onDisplayRemoved(int id){if(Workspace.enabled(this)&&id==Workspace.target(this))Workspace.transfer(this,0,()->update(true));else update(false);}
    private String geometry(int id){
        Display d=displays.getDisplay(id);if(d==null)return "missing";
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();d.getRealMetrics(metrics);
        return metrics.widthPixels+":"+metrics.heightPixels+":"+metrics.densityDpi+":"+d.getRotation();
    }
    @Override public void onDisplayChanged(int id){
        // Refresh-rate/brightness changes are not layout changes. Recreating an
        // overlay between pointer down/up loses taps on adaptive-refresh phones.
        if(id==displayId && !geometry(id).equals(displayGeometry)){closeAreaObserver();removeDock();update(false);}
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){
        if("sidebar_height".equals(key)||"phone_sidebar_over_apps".equals(key)){positionDock();positionHandles();return;}
        if("enabled".equals(key) && !p.getBoolean("enabled",false)){stopSelf();return;}
        if("phone_sidebar".equals(key)&&!p.getBoolean(key,true)&&displayId==0&&WorkspaceProfile.standard(this,0)){stop(this,false);return;}
        if("phone_window_management".equals(key)){closeAreaObserver();removeDock();if(tasks!=null)tasks.close();tasks=null;update(false,true);return;}
        if("shell_layout".equals(key)){if(areaObserver!=null)areaObserver.refresh();areaChanged();return;}
        if(Appearance.KEY.equals(key))Appearance.load(this);
        if(WorkspaceProfile.changed(key,"pinned")||Appearance.KEY.equals(key)||IconTheme.changed(key)){removeDock();if(displayId>=0)attachDock();}
    }
    @Override public void onDestroy(){
        destroyed=true;alive=false;if(instance==this)instance=null;ShellPanels.unobserve(panelsChanged);ShellPanels.dismiss(displayId);Launches.prefs(this).edit().remove("active_display").apply();closeAreaObserver();Bridge.get(this).mouseDisplay(-1);
        if(batteryRegistered){unregisterReceiver(batteryReceiver);batteryRegistered=false;}
        if(displays!=null)displays.unregisterDisplayListener(this);
        Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);if(tasks!=null)tasks.close();removeDock();menuLoader.shutdownNow();super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
