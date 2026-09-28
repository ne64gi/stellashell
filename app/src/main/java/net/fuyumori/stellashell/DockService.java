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
    private static final String STOP="net.fuyumori.stellashell.STOP";
    private DisplayManager displays; private WindowManager windows; private View dock; private int displayId=-1;
    private AppMenu menu;private boolean collapsed;
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
    private View batteryView(Context c){batteryText=Ui.text(c,"",13,Ui.TEXT);batteryText.setGravity(Gravity.CENTER);updateBattery();return batteryText;}
    private void back(){
        if(menu!=null&&menu.isOpen()){menu.close();return;}
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
        if(!Settings.canDrawOverlays(c)){Ui.message(c,c.getString(R.string.ui_allow_the_taskbar_overlay_first));return;}
        if(c instanceof Activity && c.getDisplay()!=null && c.getDisplay().getDisplayId()>0)
            Launches.prefs(c).edit().putInt("preferred_display",c.getDisplay().getDisplayId()).apply();
        enableHome(c,true);Launches.prefs(c).edit().putBoolean("enabled",true).apply();
        try {c.startForegroundService(new Intent(c,DockService.class));}
        catch(RuntimeException e){Launches.prefs(c).edit().putBoolean("enabled",false).apply();enableHome(c,false);Launches.problem(c,e.getMessage());}
    }
    static void stop(Context c) {
        Launches.prefs(c).edit().putBoolean("enabled",false).apply();c.stopService(new Intent(c,DockService.class));enableHome(c,false);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){
        super.onConfigurationChanged(configuration);removeDock();update(false);
    }
    @Override public void onCreate() {
        super.onCreate();
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
        update(true);return START_STICKY;
    }
    private void removeDock() {
        removeDock(false);
    }
    private void removeDock(boolean keepChrome) {
        if(!keepChrome){if(chrome!=null)chrome.clear();chrome=null;}
        if(menu!=null)menu.close();menu=null;
        if(dock!=null && windows!=null)try{windows.removeViewImmediate(dock);}catch(RuntimeException ignored){}
        dock=null;batteryText=null;if(!keepChrome)windows=null;
    }
    private void update(boolean openHome) {
        int preferred=displayId>0?displayId:Launches.prefs(this).getInt("preferred_display",-1);
        int next=Policy.selectDisplay(preferred,Displays.ids(this));
        boolean changed=next!=displayId;
        if(changed){removeDock();if(tasks!=null)tasks.close();tasks=null;taskSignature="";displayId=next;}
        if(next<0){getSystemService(NotificationManager.class).notify(41,notification(this.getString(R.string.ui_waiting_for_a_display)));return;}
        if(!Settings.canDrawOverlays(this)){Launches.problem(this,this.getString(R.string.ui_overlay_permission_was_revoked));stop(this);return;}
        if(tasks==null)tasks=new TaskSession(this,next,this::tasksChanged);
        if(dock==null)attachDock();
        if(dock==null){getSystemService(NotificationManager.class).notify(41,notification(this.getString(R.string.ui_could_not_show_the_taskbar_check_permissions)));return;}
        if(dock!=null && (changed || openHome))Launches.home(this,next);
        getSystemService(NotificationManager.class).notify(41,notification(this.getString(R.string.ui_running_on_an_external_display_tap_for_settings)));
    }
    private void attachDock() {
        try {
            Context c=createDisplayContext(Displays.require(this,displayId)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            c=new android.view.ContextThemeWrapper(c,R.style.AppTheme);
            windows=c.getSystemService(WindowManager.class);
            menu=new AppMenu(c,windows,displayId);
            if(chrome==null)chrome=new WindowChrome(c,windows,tasks);
            LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(c,6),Ui.dp(c,4),Ui.dp(c,6),Ui.dp(c,4));
            row.setBackground(Ui.rounded(c,Ui.PANEL,18));
            Button apps=Ui.button(c,collapsed?"▦":getString(R.string.start_menu_label),()->{
                if(collapsed){collapsed=false;removeDock();update(false);}
                toggleMenu();
            });apps.setContentDescription(this.getString(R.string.ui_app_menu));apps.setTextColor(Ui.ACCENT);
            row.addView(apps,new LinearLayout.LayoutParams(Ui.dp(c,collapsed?56:106),Ui.dp(c,44)));
            if(!collapsed){
            Button back=Ui.button(c,"‹",this::back);back.setContentDescription(getString(R.string.external_back));back.setTooltipText(getString(R.string.external_back));
            row.addView(back,new LinearLayout.LayoutParams(Ui.dp(c,44),Ui.dp(c,44)));
            Button home=Ui.button(c,"▱",()->Launches.home(this,displayId));home.setContentDescription(this.getString(R.string.ui_show_desktop));home.setTooltipText(this.getString(R.string.ui_show_desktop));
            row.addView(home,new LinearLayout.LayoutParams(Ui.dp(c,48),Ui.dp(c,44)));
            int widthDp=Math.round(c.getResources().getDisplayMetrics().widthPixels/c.getResources().getDisplayMetrics().density);
            android.widget.HorizontalScrollView strip=new android.widget.HorizontalScrollView(c);strip.setHorizontalScrollBarEnabled(false);
            LinearLayout entries=new LinearLayout(c);entries.setGravity(Gravity.CENTER_VERTICAL);strip.addView(entries);
            java.util.List<TaskSession.Task> running=tasks==null?new java.util.ArrayList<>():tasks.tasks();
            java.util.Set<Integer> represented=new java.util.HashSet<>();
            for(String component:Launches.pins(this)){
                String pkg=ComponentName.unflattenFromString(component).getPackageName();
                TaskSession.Task match=null;for(TaskSession.Task t:running)if(t.packageName().equals(pkg)){match=t;break;}
                addEntry(c,entries,component,match,true);if(match!=null)represented.add(match.id);
            }
            for(TaskSession.Task t:running)if(!represented.contains(t.id))addEntry(c,entries,t.component,t,false);
            row.addView(strip,new LinearLayout.LayoutParams(0,Ui.dp(c,44),1));

            row.addView(batteryView(c),new LinearLayout.LayoutParams(Ui.dp(c,76),Ui.dp(c,44)));
            TextView connection=Ui.text(c,Bridge.get(this).ready()?"●":"○",12,Bridge.get(this).ready()?Ui.ACCENT:Ui.MUTED);
            connection.setGravity(Gravity.CENTER);connection.setContentDescription(Bridge.get(this).status());connection.setTooltipText(Bridge.get(this).status());
            connection.setOnClickListener(v->Launches.settings(this,displayId));row.addView(connection,new LinearLayout.LayoutParams(Ui.dp(c,28),-1));
            if(widthDp>=500){TextClock clock=new TextClock(c);clock.setFormat24Hour("HH:mm");clock.setFormat12Hour("HH:mm");clock.setTextColor(Ui.TEXT);clock.setTextSize(16);row.addView(clock,new LinearLayout.LayoutParams(Ui.dp(c,58),-2));}
            Button hide=Ui.button(c,"−",()->{collapsed=true;removeDock();update(false);});hide.setContentDescription(this.getString(R.string.ui_collapse_taskbar));
            row.addView(hide,new LinearLayout.LayoutParams(Ui.dp(c,44),Ui.dp(c,44)));
            }
            if(collapsed)row.addView(batteryView(c),new LinearLayout.LayoutParams(Ui.dp(c,76),Ui.dp(c,44)));
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(collapsed?-2:-1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            p.gravity=Gravity.BOTTOM|Gravity.LEFT;p.setFitInsetsTypes(0);p.y=Ui.dp(c,4);p.setTitle("StellaShell dock");
            windows.addView(row,p);dock=row;
        }catch(RuntimeException e){removeDock();Launches.problem(this,this.getString(R.string.ui_could_not_show_the_taskbar)+e.getMessage());}
    }
    private void tasksChanged(){
        if(tasks==null||displayId<0)return;
        StringBuilder signature=new StringBuilder(Bridge.get(this).ready()?"ready":"offline");
        for(TaskSession.Task t:tasks.tasks())signature.append(t.id).append(t.component).append(t.focused).append(t.visible);
        boolean menuOpen=menu!=null&&menu.isOpen();
        if(!signature.toString().equals(taskSignature)&&!menuOpen){taskSignature=signature.toString();removeDock(true);attachDock();}
        if(chrome!=null)try{if(menuOpen)chrome.clear();else chrome.update(tasks.tasks());}catch(RuntimeException e){chrome.clear();Launches.problem(this,e.getMessage());}
    }
    private void addEntry(Context c,LinearLayout row,String component,TaskSession.Task task,boolean pinned){
        try{
            ComponentName name=ComponentName.unflattenFromString(component);
            android.content.pm.ApplicationInfo info=getPackageManager().getApplicationInfo(name.getPackageName(),0);
            CharSequence label=info.loadLabel(getPackageManager());
            LinearLayout item=Ui.column(c);item.setGravity(Gravity.CENTER);item.setPadding(Ui.dp(c,7),Ui.dp(c,3),Ui.dp(c,7),0);
            item.setBackground(Ui.rounded(c,task!=null&&task.focused?0xff355461:Ui.PANEL,9));
            ImageView icon=new ImageView(c);icon.setImageDrawable(info.loadIcon(getPackageManager()));item.addView(icon,new LinearLayout.LayoutParams(Ui.dp(c,29),Ui.dp(c,29)));
            TextView mark=Ui.text(c,task==null?"":task.visible?"━":"·",10,Ui.ACCENT);mark.setGravity(Gravity.CENTER);item.addView(mark,new LinearLayout.LayoutParams(-1,Ui.dp(c,11)));
            item.setContentDescription(label+(task!=null?this.getString(R.string.ui_running):this.getString(R.string.ui_pinned)));item.setTooltipText(label);
            item.setOnClickListener(v->{if(menu!=null)menu.close();if(task!=null)tasks.action(task,"focus");else Launches.app(this,component,displayId);});
            item.setOnLongClickListener(v->{
                AppContextMenu.show(c,item,component,displayId,()->{},task,tasks);return true;
            });
            item.setOnContextClickListener(v->v.performLongClick());
            row.addView(item,new LinearLayout.LayoutParams(Ui.dp(c,48),Ui.dp(c,44)));
        }catch(Exception ignored){}
    }
    private void toggleMenu(){try{if(chrome!=null)chrome.clear();if(menu!=null)menu.open(menuLoader);}catch(RuntimeException e){Launches.problem(this,this.getString(R.string.ui_could_not_open_the_app_menu)+e.getMessage());}}
    @Override public void onDisplayAdded(int id){update(true);}
    @Override public void onDisplayRemoved(int id){update(false);}
    @Override public void onDisplayChanged(int id){if(id==displayId)removeDock();update(false);}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){
        if("enabled".equals(key) && !p.getBoolean("enabled",false)){stopSelf();return;}
        if("pinned".equals(key)){removeDock();if(displayId>0)attachDock();}
    }
    @Override public void onDestroy(){
        if(batteryRegistered){unregisterReceiver(batteryReceiver);batteryRegistered=false;}
        if(displays!=null)displays.unregisterDisplayListener(this);
        Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);if(tasks!=null)tasks.close();removeDock();menuLoader.shutdownNow();super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
