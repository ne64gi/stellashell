package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.launch.AppLaunchProfile;
import net.fuyumori.stellashell.core.navigation.NavigationScale;

import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** Owns one output's surfaces and their lifetime; no Service or global View state. */
final class SelectedOutputSurface implements SharedPreferences.OnSharedPreferenceChangeListener, AutoCloseable {
    private final Context context;
    private final int displayId;
    private final DisplayManager displays;
    private final PhoneNavigationOwner phone;
    private final TaskState tasks;
    private final TaskState.OutputLease taskLease;
    private WindowManager windows;
    private View dock;
    private LinearLayout taskbarEntries;
    private boolean taskbarRefreshPending,taskbarContextMenuOpen;
    private PopupMenu taskbarPopup;
    private DesktopDock desktopDock;
    private AppMenu menu;
    private WindowChrome chrome;
    private WorkAreaObserver areaObserver;
    private int observedDisplay=-1;
    private boolean renderedCompact,collapsed,closed,backPending,batteryRegistered;
    private long startDownTime;
    private String displayGeometry="",taskSignature="";
    private TextView batteryText;
    private int batteryPercent=-1;
    private boolean batteryCharging;
    private final ExecutorService menuLoader=Executors.newSingleThreadExecutor();
    private final Runnable flushTaskbar=this::refreshTaskbarTasks;
    private final Runnable panelsChanged=()->{updatePanelChrome();scheduleTaskbarRefresh();};
    private final BroadcastReceiver batteryReceiver=new BroadcastReceiver(){public void onReceive(Context ignored,Intent intent){
        int level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
        batteryPercent=level>=0&&scale>0?Math.min(100,Math.round(level*100f/scale)):-1;
        batteryCharging=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;updateBattery();
    }};
    SelectedOutputSurface(Context context,int displayId,PhoneNavigationOwner phone){
        this(context,displayId,phone,true);
    }
    /** Isolated layout fixture: builds owned views without polling or operating any OS tasks. */
    SelectedOutputSurface(Context context,int displayId,PhoneNavigationOwner phone,boolean observeTasks){
        this.context=context;this.displayId=displayId;this.phone=phone;
        displays=context.getSystemService(DisplayManager.class);
        tasks=TaskState.of(context);
        taskLease=observeTasks?tasks.openOutput(context,displayId,this::tasksChanged,this::isStartOpen):null;
        ShellPanels.observe(panelsChanged);
        Launches.prefs(context).registerOnSharedPreferenceChangeListener(this);
        IntentFilter filter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery=Build.VERSION.SDK_INT>=33?context.registerReceiver(batteryReceiver,filter,Context.RECEIVER_NOT_EXPORTED):context.registerReceiver(batteryReceiver,filter);
        batteryRegistered=true;if(battery!=null)batteryReceiver.onReceive(context,battery);
        attachDock();
    }
    boolean ready(){return !closed&&dock!=null;}
    int displayId(){return displayId;}
    boolean geometryChanged(){return !geometry(displayId).equals(displayGeometry);}
    int[] navigationBounds(){return !closed&&desktopDock!=null?desktopDock.bounds():null;}
    boolean isStartOpen(){return !closed&&(menu!=null&&menu.isOpen()||displayId==0&&phone.isStartOpen());}
    boolean toggleStart(){if(!ready())return false;toggleMenu();return true;}
    void phoneAreaChanged(){if(!closed&&displayId==0){tasks.areaChanged();updatePanelChrome();}}
    void rebuild(){if(closed)return;closeAreaObserver();removeDock();attachDock();}
    void settingsChanged(java.util.EnumSet<ShellSettings.Change> changes){
        if(closed)return;
        // Geometry and presentation are recomputed from the settings owner's snapshot.
        if(areaObserver!=null)areaObserver.refresh();
        if(displayId==0){phoneAreaChanged();return;}
        if(changes.contains(ShellSettings.Change.EXTERNAL_TASKBAR_SCALE)){removeDock(true);attachDock();}
        if(dock!=null&&(changes.contains(ShellSettings.Change.EXTERNAL_DOCK_ENABLED)||changes.contains(ShellSettings.Change.EXTERNAL_DOCK_EDGE)))syncDesktopDock(dock.getContext());
        if(desktopDock!=null&&(changes.contains(ShellSettings.Change.EXTERNAL_DOCK_EDGE)||changes.contains(ShellSettings.Change.EXTERNAL_DOCK_SCALE)||changes.contains(ShellSettings.Change.EXTERNAL_DOCK_REVEAL)||changes.contains(ShellSettings.Change.EXTERNAL_DOCK_OPEN_METHOD)))desktopDock.rebuild();
        areaChanged();
    }
    private String getString(int id,Object... args){return context.getString(id,args);}
    private PackageManager getPackageManager(){return context.getPackageManager();}
    private void updatePanelChrome(){if(chrome!=null&&tasks!=null)try{chrome.update(tasks.snapshot().tasks);}catch(RuntimeException e){chrome.clear();Launches.problem(context,e.getMessage());}}
    private void watchStart(View view){view.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)startDownTime=e.getDownTime();return false;});}
    private void closeAreaObserver(){if(areaObserver!=null){areaObserver.close();areaObserver=null;}observedDisplay=-1;}
    private void areaChanged(){
        if(closed||displayId<0)return;
        WorkArea area=WorkArea.get(context,displayId);
        if(area.compact!=renderedCompact){removeDock();attachDock();}
        else positionDock();
        if(desktopDock!=null)desktopDock.position();
        if(menu!=null)menu.relayout();
        if(tasks!=null)tasks.areaChanged();
        if(chrome!=null&&tasks!=null)chrome.update(tasks.snapshot().tasks);
    }
    private void positionDock(){
        if(displayId==0||dock==null||windows==null)return;
        WorkArea a=WorkArea.get(context,displayId);Context c=dock.getContext();
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)dock.getLayoutParams();
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);
        // Presentation changes app/workspace behavior, never external Taskbar visibility.
        p.width=collapsed?Math.min(taskbarDp(c,144),a.usable.width()):a.usable.width();p.height=Math.min(taskbarDp(c,52),a.usable.height());
        p.x=a.usable.left;p.y=Math.max(a.usable.top,a.usable.bottom-taskbarDp(c,56));dock.setVisibility(View.VISIBLE);
        windows.updateViewLayout(dock,p);
    }
    private void updateBattery(){
        if(batteryText==null)return;
        String label=batteryPercent<0?getString(R.string.battery_unknown):getString(R.string.battery_percent,batteryPercent);
        batteryText.setText((batteryCharging?"⚡ ":"")+label);
        batteryText.setTextColor(batteryPercent>=0&&batteryPercent<=20&&!batteryCharging?0xffffad79:Ui.TEXT);
        String description=batteryPercent<0?label:getString(batteryCharging?R.string.battery_charging:R.string.battery_remaining,batteryPercent);
        batteryText.setContentDescription(description);batteryText.setTooltipText(description);
    }
    private View batteryView(Context c){batteryText=Ui.text(c,"",13,Ui.TEXT);batteryText.setGravity(Gravity.CENTER);batteryText.setBackground(Ui.toolbarBackground(c,12));updateBattery();batteryText.setOnClickListener(v->{if(menu!=null)menu.close();QuickSettingsActivity.open(context,displayId);});return batteryText;}
    private void back(){
        if(menu!=null&&menu.isOpen()){menu.back();return;}
        if(backPending)return;
        Bridge bridge=Bridge.get(context);if(!bridge.ready()){Ui.message(context,bridge.status());return;}
        int target=displayId;backPending=true;
        bridge.call(service->service.back(target),(result,error)->{backPending=false;if(error!=null)Launches.problem(context,error);});
    }
    private void removeDock() {
        removeDock(false);
    }
    private void removeDock(boolean keepChrome) {
        taskbarRefreshPending=false;taskbarContextMenuOpen=false;
        if(dock!=null)dock.removeCallbacks(flushTaskbar);
        if(taskbarPopup!=null){taskbarPopup.dismiss();taskbarPopup=null;}
        if(!keepChrome){if(desktopDock!=null)desktopDock.close();desktopDock=null;if(chrome!=null)chrome.clear();chrome=null;}
        if(menu!=null)menu.close();menu=null;
        if(dock!=null && windows!=null)try{windows.removeViewImmediate(dock);}catch(RuntimeException ignored){}
        dock=null;taskbarEntries=null;taskSignature="";batteryText=null;if(!keepChrome)windows=null;
    }
    private void attachDock() {
        try {
            Context c=context.createDisplayContext(Displays.require(context,displayId)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            c=new android.view.ContextThemeWrapper(c,Appearance.theme());
            windows=c.getSystemService(WindowManager.class);
            if(displayId==0){
                renderedCompact=true;menu=new AppMenu(c,windows,0);
                if(chrome==null)chrome=new WindowChrome(c,windows,tasks);
                dock=new View(c);dock.setVisibility(View.GONE);
                WindowManager.LayoutParams placeholder=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);
                windows.addView(dock,placeholder);
                // Main-display overlays need the same baseline as external docks:
                // refresh-rate/brightness-only events must not close Start.
                displayGeometry=geometry(displayId);return;
            }
            if(areaObserver==null||observedDisplay!=displayId){closeAreaObserver();observedDisplay=displayId;areaObserver=new WorkAreaObserver(c,displayId,this::areaChanged);}
            menu=new AppMenu(c,windows,displayId);
            if(chrome==null)chrome=new WindowChrome(c,windows,tasks);
            syncDesktopDock(c);
            // Keep context baseline aligned with presentation, not Taskbar shape,
            // so observer callbacks do not repeatedly rebuild a Compact screen.
            renderedCompact=WorkArea.get(context,displayId).compact;
            LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(taskbarDp(c,6),taskbarDp(c,4),taskbarDp(c,6),taskbarDp(c,4));
            row.setBackground(Appearance.surface(c,18));
            ImageButton apps=new ImageButton(c);apps.setImageDrawable(AppIcons.stella(c));apps.setScaleType(ImageView.ScaleType.FIT_CENTER);
            apps.setBackgroundTintList(null);apps.setBackground(Ui.toolbarBackground(c,12));apps.setPadding(taskbarDp(c,7),taskbarDp(c,4),taskbarDp(c,7),taskbarDp(c,4));
            watchStart(apps);apps.setOnClickListener(v->{if(collapsed){collapsed=false;removeDock(true);attachDock();}toggleMenu();});
            apps.setContentDescription(getString(R.string.ui_app_menu));apps.setTooltipText(getString(R.string.start_menu_label));
            row.addView(apps,new LinearLayout.LayoutParams(taskbarDp(c,56),taskbarDp(c,44)));
            if(!collapsed){
            Button back=Ui.toolbarButton(c,"‹",this::back);back.setContentDescription(getString(R.string.external_back));back.setTooltipText(getString(R.string.external_back));
            row.addView(back,new LinearLayout.LayoutParams(taskbarDp(c,44),taskbarDp(c,44)));
            Button home=Ui.toolbarButton(c,"▱",()->Launches.home(context,displayId));home.setContentDescription(getString(R.string.ui_show_desktop));home.setTooltipText(getString(R.string.ui_show_desktop));
            row.addView(home,new LinearLayout.LayoutParams(taskbarDp(c,48),taskbarDp(c,44)));
            LinearLayout entries=new LinearLayout(c);entries.setGravity(Gravity.CENTER_VERTICAL);
            taskbarEntries=entries;populateTaskbarEntries(c,entries);
            row.addView(entries,new LinearLayout.LayoutParams(-2,taskbarDp(c,44)));
            row.addView(new View(c),new LinearLayout.LayoutParams(0,1,1));

            Button shot=Ui.toolbarButton(c,"▣",()->{if(menu!=null)menu.close();DesktopScreenshot.take(context,displayId);});shot.setContentDescription(getString(R.string.screenshot_take));shot.setTooltipText(getString(R.string.screenshot_take));row.addView(shot,new LinearLayout.LayoutParams(taskbarDp(c,44),taskbarDp(c,44)));
            row.addView(batteryView(c),new LinearLayout.LayoutParams(taskbarDp(c,76),taskbarDp(c,44)));
            {TextClock clock=new TextClock(c);clock.setBackground(Ui.toolbarBackground(c,12));clock.setTypeface(Appearance.face);clock.setFormat24Hour("HH:mm");clock.setFormat12Hour("HH:mm");clock.setTextColor(Ui.TEXT);clock.setTextSize(16);clock.setContentDescription(getString(R.string.hub_title));clock.setTooltipText(getString(R.string.hub_title));clock.setOnClickListener(v->{if(menu!=null)menu.close();HubActivity.open(context,displayId);});row.addView(clock,new LinearLayout.LayoutParams(taskbarDp(c,58),-2));}
            Button hide=Ui.toolbarButton(c,"−",()->{collapsed=true;removeDock(true);attachDock();});hide.setContentDescription(getString(R.string.ui_collapse_taskbar));
            row.addView(hide,new LinearLayout.LayoutParams(taskbarDp(c,44),taskbarDp(c,44)));
            }
            if(collapsed)row.addView(batteryView(c),new LinearLayout.LayoutParams(taskbarDp(c,76),taskbarDp(c,44)));
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(collapsed?-2:-1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            p.gravity=Gravity.BOTTOM|Gravity.LEFT;p.setFitInsetsTypes(displayId==0?WindowInsets.Type.navigationBars():0);p.y=taskbarDp(c,4);p.setTitle("StellaShell taskbar");
            scaleTaskbarWidgets(c,row);
            TaskbarViewport viewport=new TaskbarViewport(c);viewport.setFillViewport(true);viewport.setBackground(Appearance.surface(c,18));viewport.setClipToOutline(true);viewport.addView(row,new FrameLayout.LayoutParams(-2,-1));
            windows.addView(viewport,p);dock=viewport;positionDock();displayGeometry=geometry(displayId);taskSignature=taskbarSignature();
        }catch(RuntimeException e){removeDock();Launches.problem(context,getString(R.string.ui_could_not_show_the_taskbar)+e.getMessage());}finally{}
    }
    private int taskbarDp(Context c,int value){return NavigationScale.pixels(c.getResources().getDisplayMetrics().density,value,ShellSettings.of(context).snapshot().externalTaskbar.scalePercent);}
    private void scaleTaskbarWidgets(Context c,View view){
        float factor=NavigationScale.factor(ShellSettings.of(context).snapshot().externalTaskbar.scalePercent);
        if(view instanceof TextView){TextView text=(TextView)view;text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,text.getTextSize()*factor);}
        if(view instanceof Button){Button button=(Button)view;button.setPadding(0,0,0,0);button.setMinHeight(taskbarDp(c,48));}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)scaleTaskbarWidgets(c,group.getChildAt(i));}
    }
    private void syncDesktopDock(Context c){
        if(ShellSettings.of(context).snapshot().externalDock.enabled){
            if(desktopDock==null)desktopDock=new DesktopDock(c,windows,displayId,tasks,downTime->{startDownTime=downTime;toggleMenu();});
            else desktopDock.position();
        }else if(desktopDock!=null){desktopDock.close();desktopDock=null;}
    }
    private void tasksChanged(){
        if(closed||tasks==null||displayId<0)return;
        refreshTaskbarTasks();updatePanelChrome();
    }
    private String taskbarSignature(){
        StringBuilder signature=new StringBuilder(Bridge.get(context).ready()?"ready":"offline");signature.append(TaskState.of(context).primary());
        for(TaskSnapshot.Task t:tasks.snapshot().tasks)signature.append(t.id).append(t.component).append(t.mode).append(t.focused).append(t.visible).append(t.alwaysOnTop).append(TaskState.of(context).label(context,t));
        return signature.toString();
    }
    private void populateTaskbarEntries(Context c,LinearLayout entries){
        java.util.List<TaskSnapshot.Task> running=tasks.snapshot().tasks;
        java.util.Set<Integer> represented=new java.util.HashSet<>();
        for(String component:Launches.pins(c)){
            ComponentName name=ComponentName.unflattenFromString(component);if(name==null)continue;
            TaskSnapshot.Task match=null;for(TaskSnapshot.Task t:running)if(t.packageName().equals(name.getPackageName())){match=t;break;}
            addEntry(c,entries,component,match,true);if(match!=null)represented.add(match.id);
        }
        for(TaskSnapshot.Task t:running)if(!represented.contains(t.id))addEntry(c,entries,t.component,t,false);
    }
    /** Task events replace only icons/state, never the native taskbar Window or Start owner. */
    private void refreshTaskbarTasks(){
        if(closed||displayId<=0||!(dock instanceof TaskbarViewport))return;
        String signature=taskbarSignature();
        if(signature.equals(taskSignature)){taskbarRefreshPending=false;return;}
        TaskbarViewport viewport=(TaskbarViewport)dock;
        if(viewport.tracking()||taskbarContextMenuOpen||isStartOpen()||ShellPanels.isOpen(displayId)){
            taskbarRefreshPending=true;return;
        }
        taskbarRefreshPending=false;
        if(taskbarEntries!=null){
            int scrollX=viewport.getScrollX(),scrollY=viewport.getScrollY();
            taskbarEntries.removeAllViews();populateTaskbarEntries(taskbarEntries.getContext(),taskbarEntries);
            // Only fresh children are scaled; the retained row/clock must not accumulate scaling.
            scaleTaskbarWidgets(taskbarEntries.getContext(),taskbarEntries);viewport.scrollTo(scrollX,scrollY);
        }
        if(desktopDock!=null)desktopDock.refresh();
        taskSignature=signature;
    }
    private void scheduleTaskbarRefresh(){
        if(closed||!taskbarRefreshPending||dock==null)return;
        // Menu.close releases ShellPanels before clearing its root; run after that stack unwinds.
        dock.removeCallbacks(flushTaskbar);dock.post(flushTaskbar);
    }
    private final class TaskbarViewport extends HorizontalScrollView {
        private boolean touchTracking;private int heldButtons;
        TaskbarViewport(Context c){super(c);}
        boolean tracking(){return touchTracking||heldButtons!=0;}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN){touchTracking=true;heldButtons=event.getButtonState();}
            try{return super.dispatchTouchEvent(event);}finally{
                if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){touchTracking=false;heldButtons=action==MotionEvent.ACTION_CANCEL?0:event.getButtonState();if(dock==this)scheduleTaskbarRefresh();}
            }
        }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_BUTTON_PRESS||action==MotionEvent.ACTION_BUTTON_RELEASE)heldButtons=event.getButtonState();
            try{return super.dispatchGenericMotionEvent(event);}finally{
                if(action==MotionEvent.ACTION_BUTTON_RELEASE&&dock==this)scheduleTaskbarRefresh();
            }
        }
        @Override protected void onDetachedFromWindow(){touchTracking=false;heldButtons=0;removeCallbacks(flushTaskbar);super.onDetachedFromWindow();}
    }
    private void addEntry(Context c,LinearLayout row,String component,TaskSnapshot.Task task,boolean pinned){
        try{
            ComponentName name=ComponentName.unflattenFromString(component);
            android.content.pm.ApplicationInfo info=getPackageManager().getApplicationInfo(name.getPackageName(),0);
            CharSequence label=info.loadLabel(getPackageManager());
            LinearLayout item=Ui.column(c);item.setGravity(Gravity.CENTER);item.setPadding(taskbarDp(c,7),taskbarDp(c,3),taskbarDp(c,7),0);
            item.setBackground(Ui.toolbarBackground(c,9));item.setSelected(task!=null&&task.focused);
            ImageView icon=new ImageView(c);icon.setImageDrawable(AppIcons.forApp(c,component,info.loadIcon(getPackageManager())));item.addView(icon,new LinearLayout.LayoutParams(taskbarDp(c,29),taskbarDp(c,29)));
            String role=task!=null&&TaskState.of(context).compact(context,displayId)?TaskState.of(context).label(c,task):"";
            if(task!=null&&task.alwaysOnTop)role+=(role.isEmpty()?"":" · ")+c.getString(R.string.window_pin);
            TextView mark=Ui.text(c,task!=null&&task.alwaysOnTop?"↑":!role.isEmpty()?c.getString(task.id==TaskState.of(context).primary()?R.string.workspace_primary_badge:R.string.workspace_secondary_badge):task==null?"":task.visible?"━":"·",10,Ui.ACCENT);mark.setGravity(Gravity.CENTER);mark.setSingleLine(true);mark.setIncludeFontPadding(false);item.addView(mark,new LinearLayout.LayoutParams(-1,taskbarDp(c,11)));
            item.setContentDescription(label+(task!=null?getString(R.string.ui_running):getString(R.string.ui_pinned))+(role.isEmpty()?"":" · "+role));item.setTooltipText(role.isEmpty()?label:label+" · "+role);
            item.setOnClickListener(v->{ShellPanels.dismiss(displayId);if(task!=null){
                if(TaskState.of(context).compact(context,displayId)){Launches.focus(context,task,displayId,tasks);return;}
                String requested=Profiles.requestedComponent(context,component);AppLaunchProfile.Mode mode=Profiles.get(context,requested).launchMode;
                if(task.mode==1&&(mode==AppLaunchProfile.Mode.WINDOWED||mode==AppLaunchProfile.Mode.MAXIMIZED))Launches.app(context,requested,displayId);
                else tasks.action(task,"focus");
            }else Launches.app(context,component,displayId);});
            item.setOnLongClickListener(v->{
                ShellPanels.dismiss(displayId);View owner=dock;taskbarContextMenuOpen=true;
                try{taskbarPopup=AppContextMenu.show(c,item,component,displayId,()->{},task,tasks,null,()->{
                    if(dock!=owner)return;taskbarPopup=null;taskbarContextMenuOpen=false;scheduleTaskbarRefresh();
                });}catch(RuntimeException error){if(dock==owner){taskbarContextMenuOpen=false;scheduleTaskbarRefresh();}throw error;}
                return true;
            });
            item.setOnContextClickListener(v->v.performLongClick());
            row.addView(item,new LinearLayout.LayoutParams(taskbarDp(c,48),taskbarDp(c,44)));
        }catch(Exception ignored){}
    }
    private void toggleMenu(){if(closed)return;if(displayId==0&&phone.toggleStart())return;try{long downTime=startDownTime;startDownTime=0;if(menu!=null)menu.toggle(menuLoader,downTime);}catch(RuntimeException e){Launches.problem(context,getString(R.string.ui_could_not_open_the_app_menu)+e.getMessage());}}
    private String geometry(int id){
        Display d=displays.getDisplay(id);if(d==null)return "missing";
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();d.getRealMetrics(metrics);
        return metrics.widthPixels+":"+metrics.heightPixels+":"+metrics.densityDpi+":"+d.getRotation();
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){
        if(closed)return;
        if(WorkspaceProfile.changed(key,"dock_pinned")){if(desktopDock!=null)desktopDock.refresh();return;}
        if(Appearance.KEY.equals(key))Appearance.load(context);
        if(WorkspaceProfile.changed(key,"pinned")||Appearance.KEY.equals(key)||IconTheme.changed(key))rebuild();
    }
    @Override public void close(){
        if(closed)return;closed=true;
        ShellPanels.unobserve(panelsChanged);
        Launches.prefs(context).unregisterOnSharedPreferenceChangeListener(this);
        closeAreaObserver();removeDock();tasks.closeOutput(taskLease);
        if(batteryRegistered){context.unregisterReceiver(batteryReceiver);batteryRegistered=false;}
        menuLoader.shutdownNow();
    }
}
