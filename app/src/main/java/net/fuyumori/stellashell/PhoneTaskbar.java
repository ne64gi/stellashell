package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.navigation.NavigationScale;
import net.fuyumori.stellashell.core.tasks.TaskModes;

import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** Display-0 taskbar, independently enabled from the phone Dock and Desktop output. */
final class PhoneTaskbar implements AutoCloseable {
    private final Context context,receiverContext;private final WindowManager windows;private final java.util.function.Supplier<WorkArea> area;private final ShellSettings settings;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    final AppMenu menu;
    private final PhoneRunningTasks running;private final PhoneTaskMenu taskMenu;
    private final WorkAreaObserver observer;
    private HorizontalScrollView panel;private LinearLayout row,pinnedTasks,activeTasks;private View backButton;
    private TextView battery;private int batteryPercent=-1;private boolean charging,closed,registered,backPending;
    private long startDownTime;
    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
        int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
        batteryPercent=level>=0&&scale>0?Math.min(100,Math.round(level*100f/scale)):-1;charging=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;updateBattery();
    }};
    PhoneTaskbar(Context service,Runnable areaChanged){this(service,areaChanged,null);}
    PhoneTaskbar(Context service,Runnable areaChanged,PhoneRunningTasks.Backend backend){this(service,areaChanged,backend,null);}
    PhoneTaskbar(Context service,Runnable areaChanged,PhoneRunningTasks.Backend backend,java.util.function.Supplier<WorkArea> areaSupplier){
        receiverContext=service.getApplicationContext();
        context=new ContextThemeWrapper(service.createDisplayContext(service.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null),Appearance.theme());
        settings=ShellSettings.of(context);windows=context.getSystemService(WindowManager.class);area=areaSupplier==null?()->WorkArea.get(context,0):areaSupplier;
        menu=new AppMenu(context,windows,0);taskMenu=new PhoneTaskMenu(context);
        running=backend==null?new PhoneRunningTasks(context,this::renderRunning):new PhoneRunningTasks(backend,this::renderRunning);
        observer=areaSupplier==null?new WorkAreaObserver(context,0,()->{relayout();areaChanged.run();}):null;
        try{
            IntentFilter filter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent initial=Build.VERSION.SDK_INT>=33?receiverContext.registerReceiver(batteryReceiver,filter,Context.RECEIVER_NOT_EXPORTED):receiverContext.registerReceiver(batteryReceiver,filter);
            registered=true;if(initial!=null)batteryReceiver.onReceive(context,initial);build();running.start();
        }catch(RuntimeException failure){close();throw failure;}
    }
    boolean ready(){return !closed&&panel!=null;}
    void toggleStart(){if(!closed)menu.toggle(loader,startDownTime);startDownTime=0;}
    void rebuild(){if(closed)return;removeViews();build();}
    void refreshArea(){if(!closed){if(observer!=null)observer.refresh();relayout();}}
    private void build(){
        row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(4),dp(6),dp(4));
        ImageButton start=new ImageButton(context);start.setImageResource(R.mipmap.ic_launcher);start.setScaleType(ImageView.ScaleType.FIT_CENTER);
        start.setBackground(Ui.toolbarBackground(context,12));start.setPadding(dp(7),dp(4),dp(7),dp(4));start.setContentDescription(context.getString(R.string.ui_app_menu));start.setTooltipText(context.getString(R.string.start_menu_label));
        start.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)startDownTime=e.getDownTime();return false;});start.setOnClickListener(v->toggleStart());item(start,56);
        backButton=action("‹",R.string.external_back,this::back,44);
        action("▱",R.string.home_open,()->Launches.home(context,0),48);
        pinnedTasks=new LinearLayout(context);pinnedTasks.setGravity(Gravity.CENTER_VERTICAL);row.addView(pinnedTasks,new LinearLayout.LayoutParams(-2,dp(44)));
        activeTasks=new LinearLayout(context);activeTasks.setGravity(Gravity.CENTER_VERTICAL);row.addView(activeTasks,new LinearLayout.LayoutParams(-2,dp(44)));renderRunning();
        action("▣",R.string.screenshot_take,()->{menu.close();DesktopScreenshot.take(context,0);},44);
        battery=Ui.text(context,"",13,Ui.TEXT);battery.setTextSize(13*factor());battery.setGravity(Gravity.CENTER);battery.setBackground(Ui.toolbarBackground(context,12));battery.setOnClickListener(v->{menu.close();QuickSettingsActivity.open(context,0);});item(battery,76);updateBattery();
        action("⚙",R.string.menu_stella_settings,()->Launches.settings(context,0),44);
        TextClock clock=new TextClock(context);clock.setBackground(Ui.toolbarBackground(context,12));clock.setTypeface(Appearance.face);clock.setFormat24Hour("HH:mm");clock.setFormat12Hour("HH:mm");clock.setTextColor(Ui.TEXT);clock.setTextSize(16*factor());clock.setGravity(Gravity.CENTER);clock.setContentDescription(context.getString(R.string.hub_title));clock.setTooltipText(context.getString(R.string.hub_title));clock.setOnClickListener(v->{menu.close();HubActivity.open(context,0);});item(clock,58);
        panel=new HorizontalScrollView(context);panel.setFillViewport(true);panel.setBackground(Appearance.surface(context,18));panel.setClipToOutline(true);panel.addView(row,new FrameLayout.LayoutParams(-2,-1));
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle("StellaShell main taskbar");windows.addView(panel,p);relayout();
    }
    private void item(View view,int width){row.addView(view,new LinearLayout.LayoutParams(dp(width),dp(44)));}
    private Button action(String text,int description,Runnable action,int width){
        Button button=Ui.toolbarButton(context,text,action);button.setPadding(0,0,0,0);button.setTextSize(14*factor());button.setMinHeight(dp(48));button.setContentDescription(context.getString(description));button.setTooltipText(context.getString(description));item(button,width);return button;
    }
    private void pin(String component,TaskSnapshot.Task task){
        try{
            ComponentName name=ComponentName.unflattenFromString(component);if(name==null)return;
            android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(name.getPackageName(),0);CharSequence label=info.loadLabel(context.getPackageManager());
            ImageButton icon=new ImageButton(context);icon.setImageDrawable(AppIcons.forApp(context,component,info.loadIcon(context.getPackageManager())));icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setBackground(Ui.toolbarBackground(context,10));icon.setPadding(dp(8),dp(6),dp(8),dp(6));icon.setContentDescription(label);icon.setTooltipText(label);
            icon.setSelected(task!=null&&task.focused);
            icon.setOnClickListener(v->{if(task==null)Launches.app(context,component,0);else running.focus(task,running::refresh,error->Launches.problem(context,error));});
            icon.setOnLongClickListener(v->{if(task==null)AppContextMenu.show(context,icon,component,0,()->{},null,null);else taskMenu(icon,task,component);return true;});icon.setOnContextClickListener(v->v.performLongClick());pinnedTasks.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(44)));
        }catch(android.content.pm.PackageManager.NameNotFoundException|IllegalArgumentException ignored){}
    }
    private void renderRunning(){
        if(closed||activeTasks==null)return;taskMenu.close();
        if(backButton!=null)backButton.setVisibility(running.state()==PhoneRunningTasks.State.READY?View.VISIBLE:View.GONE);
        java.util.List<TaskSnapshot.Task> tasks=running.state()==PhoneRunningTasks.State.READY?running.tasks():java.util.Collections.emptyList();
        pinnedTasks.removeAllViews();
        for(String component:Launches.taskbarPins(context)){
            ComponentName name=ComponentName.unflattenFromString(component);if(name==null)continue;TaskSnapshot.Task match=null;
            for(TaskSnapshot.Task task:tasks)if(name.getPackageName().equals(task.packageName())){match=task;if(task.focused)break;}
            pin(component,match);
        }
        PhoneSidebar.renderTasks(context,activeTasks,PhoneSidebar.unpinnedTasks(tasks,Launches.taskbarPins(context)),
            task->running.focus(task,running::refresh,error->Launches.problem(context,error)),this::taskMenu);
        for(int i=0;i<activeTasks.getChildCount();i++){
            View icon=activeTasks.getChildAt(i);icon.setPadding(dp(8),dp(6),dp(8),dp(6));icon.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(44)));
        }
    }
    private void taskMenu(View anchor,TaskSnapshot.Task task){taskMenu(anchor,task,null);}
    private void taskMenu(View anchor,TaskSnapshot.Task task,String pin){
        if(closed)return;
        taskMenu.show(anchor,TaskModes.canReturnToMain(task.mode),pin!=null,action->{
            if(closed)return;
            if("unpin".equals(action)){if(pin!=null&&Launches.taskbarPins(context).contains(pin))Launches.toggleTaskbarPin(context,pin);return;}
            android.graphics.Rect bounds="float".equals(action)?PhoneTaskMenu.floatingBounds(area.get().content):new android.graphics.Rect();
            running.operation(task,action,bounds,running::refresh,error->Launches.problem(context,error));
        });
    }
    private void updateBattery(){
        if(battery==null)return;String label=batteryPercent<0?context.getString(R.string.battery_unknown):context.getString(R.string.battery_percent,batteryPercent);
        battery.setText((charging?"⚡ ":"")+label);battery.setTextColor(batteryPercent>=0&&batteryPercent<=20&&!charging?0xffffad79:Ui.TEXT);
        String description=batteryPercent<0?label:context.getString(charging?R.string.battery_charging:R.string.battery_remaining,batteryPercent);battery.setContentDescription(description);battery.setTooltipText(description);
    }
    private void back(){
        if(menu.isOpen()){menu.back();return;}if(backPending)return;Bridge bridge=Bridge.get(context);if(!bridge.ready())return;
        backPending=true;bridge.call(service->service.back(0),(result,error)->{backPending=false;if(error!=null&&!closed)Launches.problem(context,error);});
    }
    void relayout(){
        if(closed||panel==null)return;WorkArea area=this.area.get();WindowManager.LayoutParams p=(WindowManager.LayoutParams)panel.getLayoutParams();
        p.width=area.usable.width();p.height=Math.min(dp(52),area.usable.height());p.x=area.usable.left;p.y=Math.max(area.usable.top,area.usable.bottom-dp(56));windows.updateViewLayout(panel,p);menu.relayout();
    }
    private int scale(){return settings.snapshot().phoneTaskbar.scalePercent;}
    private float factor(){return NavigationScale.factor(scale());}
    private int dp(int value){return NavigationScale.pixels(context.getResources().getDisplayMetrics().density,value,scale());}
    private void removeViews(){taskMenu.close();menu.close();activeTasks=null;pinnedTasks=null;battery=null;if(panel!=null)try{windows.removeViewImmediate(panel);}catch(RuntimeException ignored){}panel=null;row=null;}
    @Override public void close(){if(closed)return;closed=true;running.close();removeViews();if(observer!=null)observer.close();if(registered){receiverContext.unregisterReceiver(batteryReceiver);registered=false;}loader.shutdownNow();}
}
