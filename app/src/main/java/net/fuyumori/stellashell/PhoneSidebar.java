package net.fuyumori.stellashell;

import android.content.*;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Display-0 navigation, independent of the Desktop session's current output. */
final class PhoneSidebar implements AutoCloseable {
    private final Context context;
    private final WindowManager windows;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    final AppMenu menu;
    final List<View> handles=new ArrayList<>();
    private final WorkAreaObserver observer;
    private final java.util.function.Supplier<WorkArea> area;
    private final PhoneRunningTasks running;
    private final PhoneTaskMenu taskMenu;
    private LinearLayout activeTasks,activeSection;
    private View panel;
    private Float pullX;
    private HubActivity.SidebarDrag drag;
    private boolean shown,right=true,closed,home;
    PhoneSidebar(Context service,Runnable areaChanged){this(service,areaChanged,null,null);}
    PhoneSidebar(Context service,Runnable areaChanged,PhoneRunningTasks.Backend backend,java.util.function.Supplier<WorkArea> geometry){
        context=new android.view.ContextThemeWrapper(service.createDisplayContext(service.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null),Appearance.theme());
        windows=context.getSystemService(WindowManager.class);
        menu=new AppMenu(context,windows,0);
        taskMenu=new PhoneTaskMenu(context);
        running=backend==null?new PhoneRunningTasks(context,this::renderRunning):new PhoneRunningTasks(backend,this::renderRunning);
        area=geometry==null?()->WorkArea.get(context,0):geometry;
        observer=geometry==null?new WorkAreaObserver(context,0,()->{relayout();areaChanged.run();}):null;
        try{build();}catch(RuntimeException e){close();throw e;}
    }
    boolean ready(){return !closed&&panel!=null&&handles.size()==2;}
    void homeVisible(boolean visible){home=visible;relayout();}
    private boolean allowed(){return Launches.prefs(context).getBoolean("phone_sidebar_over_apps",true)||home;}
    void toggleStart(){hide();menu.toggle(loader,0);}
    void show(boolean fromRight){if(!allowed())return;String side=Launches.prefs(context).getString("phone_sidebar_side","both");right="right".equals(side)||!"left".equals(side)&&fromRight;shown=true;relayout();}
    void hide(){taskMenu.close();shown=false;pullX=null;relayout();}
    private HubActivity.SidebarDrag pull(boolean fromRight,float distance){
        return HubActivity.beginPull(context,fromRight,distance,leading->{
            if(closed)return;pullX=fromRight?leading-dp(76):leading;relayout();
        },()->{drag=null;hide();});
    }
    void widgets(){hide();HubActivity.open(context,0,right);}
    void rebuild(){removeViews();build();}
    private void build(){
        LinearLayout column=Ui.column(context);column.setGravity(Gravity.CENTER_HORIZONTAL);
        if(!Launches.prefs(context).getBoolean("phone_taskbar",false)){
            ImageButton start=new ImageButton(context);start.setImageResource(R.mipmap.ic_launcher);start.setScaleType(ImageView.ScaleType.FIT_CENTER);
            start.setBackground(null);start.setPadding(dp(14),dp(6),dp(14),dp(6));start.setContentDescription(context.getString(R.string.ui_app_menu));
            start.setOnClickListener(v->toggleStart());column.addView(start,new LinearLayout.LayoutParams(-1,dp(48)));
            partition(column);
        }
        for(String component:Launches.pins(context))try{
            android.content.ComponentName name=android.content.ComponentName.unflattenFromString(component);
            if(name==null)continue;
            android.content.pm.ApplicationInfo app=context.getPackageManager().getApplicationInfo(name.getPackageName(),0);
            ImageButton icon=new ImageButton(context);icon.setImageDrawable(AppIcons.forApp(context,component,app.loadIcon(context.getPackageManager())));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setBackground(Ui.toolbarBackground(context,10));icon.setPadding(dp(14),dp(8),dp(14),dp(8));
            icon.setContentDescription(app.loadLabel(context.getPackageManager()));icon.setTooltipText(app.loadLabel(context.getPackageManager()));
            icon.setOnClickListener(v->{hide();Launches.app(context,component,0);});
            icon.setOnLongClickListener(v->{AppContextMenu.show(context,icon,component,0,this::hide,null,null);return true;});
            icon.setOnGenericMotionListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS&&(event.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){AppContextMenu.show(context,icon,component,0,this::hide,null,null);return true;}return false;});
            column.addView(icon,new LinearLayout.LayoutParams(-1,dp(52)));
        }catch(android.content.pm.PackageManager.NameNotFoundException|IllegalArgumentException ignored){}
        activeSection=Ui.column(context);column.addView(activeSection,new LinearLayout.LayoutParams(-1,-2));
        partition(activeSection);
        activeTasks=Ui.column(context);activeSection.addView(activeTasks,new LinearLayout.LayoutParams(-1,-2));renderRunning();
        partition(column);
        Button homeButton=Ui.toolbarButton(context,"▱",()->{hide();Launches.home(context,0);});homeButton.setTextSize(14*factor());homeButton.setMinHeight(dp(48));homeButton.setPadding(dp(16),dp(8),dp(16),dp(8));homeButton.setContentDescription(context.getString(R.string.home_open));homeButton.setTooltipText(context.getString(R.string.home_open));column.addView(homeButton,new LinearLayout.LayoutParams(-1,dp(48)));
        ScrollView scroll=new ScrollView(context){float x,y;boolean opening;
            @Override public boolean dispatchTouchEvent(MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){if(!taskMenu.showing())hide();return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();opening=false;}
                float dx=e.getRawX()-x,dy=e.getRawY()-y;
                if(!opening&&e.getActionMasked()==MotionEvent.ACTION_MOVE&&(right?-dx:dx)>dp(24)&&Math.abs(dx)>Math.abs(dy)*1.2f){
                    opening=true;MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
                    drag=pull(right,Math.max(0,right?-dx:dx));
                }
                if(opening){
                    if(drag!=null){
                        drag.update(Math.max(0,right?-dx:dx));
                        if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)drag.release(e.getActionMasked()==MotionEvent.ACTION_CANCEL);
                    }
                    return true;
                }
                return super.dispatchTouchEvent(e);
            }
        };
        scroll.setBackground(Appearance.surface(context,18));scroll.setClipToOutline(true);scroll.addView(column);panel=scroll;
        windows.addView(panel,params(1,1,"StellaShell main sidebar"));
        for(int side=0;side<2;side++){
            final boolean fromRight=side==1;
            View handle=new View(context){final android.graphics.Paint paint=new android.graphics.Paint(3);
                @Override protected void onDraw(android.graphics.Canvas canvas){paint.setColor((Ui.TEXT&0xffffff)|0x66000000);float cx=getWidth()/2f,cy=getHeight()/2f;canvas.drawRoundRect(cx-dp(1),cy-dp(16),cx+dp(1),cy+dp(16),dp(1),dp(1),paint);}
                @Override public boolean performClick(){super.performClick();return true;}
            };
            handle.setContentDescription(context.getString(R.string.edge_dock_open));handle.setOnClickListener(v->show(fromRight));
            handle.setOnTouchListener(new View.OnTouchListener(){float x,y;boolean opened;
                public boolean onTouch(View v,MotionEvent e){
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();opened=false;return true;}
                    float dx=e.getRawX()-x,dy=e.getRawY()-y;
                    if(!opened&&(e.getActionMasked()==MotionEvent.ACTION_MOVE||e.getActionMasked()==MotionEvent.ACTION_UP)&&(fromRight?-dx:dx)>dp(16)&&Math.abs(dx)>Math.abs(dy)){opened=true;show(fromRight);}
                    if(!opened&&e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(dx)<dp(8)&&Math.abs(dy)<dp(8))v.performClick();return true;
                }
            });windows.addView(handle,params(dp(16),dp(64),"StellaShell edge handle "+side));handles.add(handle);
        }
        relayout();
    }
    private WindowManager.LayoutParams params(int width,int height,String title){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle(title);return p;
    }
    void relayout(){
        if(closed||panel==null)return;WorkArea bounds=area.get();android.graphics.Rect available=bounds.dockAvailable;
        if(!allowed())shown=false;
        String side=Launches.prefs(context).getString("phone_sidebar_side","both");
        if("left".equals(side))right=false;else if("right".equals(side))right=true;
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)panel.getLayoutParams();
        int width=Math.min(dp(76),available.width()),height=Math.min(dp(500),available.height());p.width=shown?width:1;p.height=shown?height:1;
        p.x=pullX==null?(right?available.right-width:available.left):Math.round(pullX);p.y=available.bottom-height;panel.setVisibility(shown?View.VISIBLE:View.GONE);windows.updateViewLayout(panel,p);
        int percent=Math.max(0,Math.min(100,Launches.prefs(context).getInt("sidebar_height",80)));
        for(int i=0;i<handles.size();i++){
            View handle=handles.get(i);WindowManager.LayoutParams h=(WindowManager.LayoutParams)handle.getLayoutParams();
            h.width=Math.min(dp(16),available.width());h.height=Math.min(dp(64),available.height());
            h.x=i==0?Math.max(available.left,bounds.gestureLeft)+dp(8):Math.min(available.right,bounds.physical.right-bounds.gestureRight)-dp(24);
            h.x=Math.max(available.left,Math.min(h.x,available.right-h.width));
            h.y=available.top+Math.round(Math.max(0,available.height()-h.height)*percent/100f);handle.setVisibility(allowed()&&!(i==0?"right":"left").equals(side)?View.VISIBLE:View.GONE);windows.updateViewLayout(handle,h);
        }
        menu.relayout();
        if(shown)running.start();else{taskMenu.close();running.stop();}
    }
    private void partition(LinearLayout column){
        View divider=new View(context);divider.setBackgroundColor(Ui.MUTED&0xffffff|0x33000000);
        LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(dp(44),dp(1));line.topMargin=dp(8);line.bottomMargin=dp(6);column.addView(divider,line);
    }
    private void renderRunning(){
        taskMenu.close();if(activeTasks==null)return;
        List<TaskSession.Task> allTasks=running.tasks();
        List<TaskSession.Task> tasks=running.state()==PhoneRunningTasks.State.READY?unpinnedTasks(allTasks,Launches.pins(context)):Collections.emptyList();
        activeSection.setVisibility(tasks.isEmpty()?View.GONE:View.VISIBLE);
        renderTasks(context,activeTasks,tasks,task->running.focus(task,this::hide,error->Launches.problem(context,error)),this::taskMenu);
        for(int i=0;i<activeTasks.getChildCount();i++){
            View icon=activeTasks.getChildAt(i);icon.setPadding(dp(14),dp(8),dp(14),dp(8));icon.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(52)));
        }
    }
    static List<TaskSession.Task> unpinnedTasks(List<TaskSession.Task> tasks,List<String> pins){
        Set<String> packages=new HashSet<>();
        for(String pin:pins){ComponentName name=ComponentName.unflattenFromString(pin);if(name!=null)packages.add(name.getPackageName());}
        List<TaskSession.Task> visible=new ArrayList<>();
        // Launcher aliases and internal task Activities can differ within the same app.
        for(TaskSession.Task task:tasks)if(!packages.contains(task.packageName()))visible.add(task);
        return visible;
    }
    private void taskMenu(View anchor,TaskSession.Task task){
        if(closed||!shown)return;
        taskMenu.show(anchor,TaskModes.canReturnToMain(task.mode),action->{
            if(closed||!shown)return;
            android.graphics.Rect bounds="float".equals(action)?PhoneTaskMenu.floatingBounds(area.get().content):new android.graphics.Rect();
            running.operation(task,action,bounds,"close".equals(action)?running::refresh:this::hide,error->Launches.problem(context,error));
        });
    }
    static void renderTasks(Context context,LinearLayout rows,List<TaskSession.Task> tasks,java.util.function.Consumer<TaskSession.Task> focus){
        renderTasks(context,rows,tasks,focus,null);
    }
    static void renderTasks(Context context,LinearLayout rows,List<TaskSession.Task> tasks,java.util.function.Consumer<TaskSession.Task> focus,java.util.function.BiConsumer<View,TaskSession.Task> menu){
        rows.removeAllViews();Map<String,Integer> totals=new HashMap<>(),seen=new HashMap<>();
        for(TaskSession.Task task:tasks)totals.put(task.component,totals.getOrDefault(task.component,0)+1);
        for(TaskSession.Task task:tasks){
            android.content.ComponentName name=android.content.ComponentName.unflattenFromString(task.component);if(name==null)continue;
            CharSequence label=name.getPackageName();android.graphics.drawable.Drawable image=context.getDrawable(android.R.drawable.sym_def_app_icon);
            try{android.content.pm.ApplicationInfo app=context.getPackageManager().getApplicationInfo(name.getPackageName(),0);label=app.loadLabel(context.getPackageManager());image=AppIcons.forApp(context,task.component,app.loadIcon(context.getPackageManager()));}
            catch(android.content.pm.PackageManager.NameNotFoundException|RuntimeException ignored){}
            int window=seen.getOrDefault(task.component,0)+1;seen.put(task.component,window);
            if(totals.get(task.component)>1)label=context.getString(R.string.phone_sidebar_task_window,label,window);
            ImageButton icon=new ImageButton(context);icon.setImageDrawable(image);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setBackgroundTintList(null);icon.setBackground(Ui.toolbarBackground(context,10));icon.setPadding(Ui.dp(context,14),Ui.dp(context,8),Ui.dp(context,14),Ui.dp(context,8));
            String description=context.getString(task.focused?R.string.phone_sidebar_focused_task:R.string.phone_sidebar_running_task,label);
            icon.setContentDescription(description);icon.setTooltipText(description);icon.setSelected(task.focused);icon.setStateDescription(context.getString(task.focused?R.string.phone_sidebar_focused:R.string.phone_sidebar_running));
            icon.setOnClickListener(v->focus.accept(task));
            if(menu!=null){
                icon.setOnLongClickListener(v->{menu.accept(icon,task);return true;});
                icon.setOnGenericMotionListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS&&(event.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){menu.accept(icon,task);return true;}return false;});
            }
            rows.addView(icon,new LinearLayout.LayoutParams(-1,Ui.dp(context,52)));
        }
    }
    private int scale(){return Launches.prefs(context).getInt(NavigationScale.key(true,false),NavigationScale.DEFAULT);}
    private float factor(){return NavigationScale.factor(scale());}
    private int dp(int value){return NavigationScale.pixels(context.getResources().getDisplayMetrics().density,value,scale());}
    private void removeViews(){taskMenu.close();running.stop();activeTasks=null;activeSection=null;menu.close();if(panel!=null){windows.removeViewImmediate(panel);panel=null;}for(View v:handles)windows.removeViewImmediate(v);handles.clear();}
    @Override public void close(){if(closed)return;closed=true;if(drag!=null)drag.release(true);running.close();removeViews();if(observer!=null)observer.close();loader.shutdownNow();}
}
