package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.navigation.NavigationScale;
import net.fuyumori.stellashell.core.tasks.TaskModes;
import net.fuyumori.stellashell.core.layout.DockPlacement;
import net.fuyumori.stellashell.core.layout.EdgeDockReveal;
import net.fuyumori.stellashell.core.layout.FloatingDockPlacement;

import android.content.*;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Display-0 navigation, independent of the Desktop session's current output. */
final class PhoneSidebar implements AutoCloseable {
    private final Context context;
    private final ShellSettings settings;
    private final WindowManager windows;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    final AppMenu menu;
    final List<View> handles=new ArrayList<>();
    private final WorkAreaObserver observer;
    private final java.util.function.Supplier<WorkArea> area;
    private final PhoneRunningTasks running;
    private final PhoneTaskMenu taskMenu;
    private DockGestureClient gestures;
    private final boolean monitorGestures;
    private boolean gestureAttempted;
    private android.graphics.PointF gestureAnchor;
    private boolean gestureRight;
    private LinearLayout activeTasks,activeSection;
    private View panel,strip;
    private LinearLayout entries;
    private boolean horizontal,rebuilding,clearingHandles;
    private android.graphics.Rect revealArea;
    private int revealWidth,revealHeight,revealPosition,revealRotation=-1;
    private String revealConfig;
    private Float revealProgress;
    private String revealEdge="right";
    private Float pullX;
    private HubActivity.SidebarDrag drag;
    private boolean shown,right=true,closed,home,taskbarReady;
    PhoneSidebar(Context service,Runnable areaChanged){this(service,areaChanged,null,null);}
    PhoneSidebar(Context service,Runnable areaChanged,PhoneRunningTasks.Backend backend,java.util.function.Supplier<WorkArea> geometry){
        context=new android.view.ContextThemeWrapper(service.createDisplayContext(service.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null),Appearance.theme());
        settings=ShellSettings.of(context);
        windows=context.getSystemService(WindowManager.class);
        menu=new AppMenu(context,windows,0);
        taskMenu=new PhoneTaskMenu(context);
        running=backend==null?new PhoneRunningTasks(context,this::renderRunning):new PhoneRunningTasks(backend,this::renderRunning);
        area=geometry==null?()->WorkArea.get(context,0):geometry;
        monitorGestures=geometry==null;
        observer=geometry==null?new WorkAreaObserver(context,0,()->{relayout();areaChanged.run();}):null;
        try{
            build();
            relayout();
        }catch(RuntimeException e){close();throw e;}
    }
    boolean ready(){return !closed&&panel!=null&&handles.size()==2;}
    void homeVisible(boolean visible){home=visible;relayout();}
    void taskbarReady(boolean ready){if(closed||taskbarReady==ready)return;taskbarReady=ready;rebuild();}
    private boolean allowed(){return settings.snapshot().phoneDock.overApps||home;}
    void toggleStart(){hide();menu.toggle(loader,0);}
    void show(boolean fromRight){
        if(!allowed())return;ShellSettings.Snapshot snapshot=settings.snapshot();
        gestureAnchor=null;
        entries.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ShellSettings.PhoneSide side=snapshot.phoneDock.phoneSide;
        right=side==ShellSettings.PhoneSide.RIGHT||side!=ShellSettings.PhoneSide.LEFT&&fromRight;
        revealEdge=landscape()?snapshot.phoneLandscapeDock.edge.storedValue():right?"right":"left";
        revealProgress=null;shown=true;relayout();
    }
    void hide(){taskMenu.close();shown=false;revealProgress=null;pullX=null;gestureAnchor=null;relayout();}
    private boolean gestureMode(){return settings.snapshot().phoneDock.phoneSide==ShellSettings.PhoneSide.GESTURE;}
    private void reconcileGestures(){
        if(!monitorGestures)return;
        if(!gestureMode()){
            if(gestures!=null){gestures.close();gestures=null;}
            gestureAttempted=false;return;
        }
        if(gestures==null&&!gestureAttempted){
            gestureAttempted=true;
            try{gestures=new DockGestureClient(context,new DockGestureClient.Listener(){
                public void availabilityChanged(){relayout();}
                public void gesture(float x,float y,boolean right){showGesture(x,y,right);}
            });}catch(RuntimeException unsupported){/* Optional mode must never remove the fallback handles. */}
        }
        if(gestures!=null)gestures.enabled(allowed());
    }
    void showGesture(float x,float y,boolean towardRight){
        if(closed||!gestureMode()||!allowed()||shown||menu.isOpen()||taskMenu.showing())return;
        WorkArea bounds=area.get();
        gestureAnchor=new android.graphics.PointF(bounds.physical.left+x*bounds.physical.width(),bounds.physical.top+y*bounds.physical.height());
        gestureRight=towardRight;revealProgress=null;pullX=null;shown=true;
        entries.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        relayout();
        if(strip instanceof ScrollView){
            ScrollView scroll=(ScrollView)strip;
            android.graphics.PointF anchor=gestureAnchor;
            scroll.post(()->{if(!closed&&strip==scroll&&shown&&gestureAnchor==anchor)scroll.fullScroll(View.FOCUS_UP);});
        }
    }
    private HubActivity.SidebarDrag pull(boolean fromRight,float distance){
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)panel.getLayoutParams();
        Float origin=gestureAnchor==null?null:(float)(fromRight?p.x+p.width:p.x);
        return HubActivity.beginPull(context,fromRight,distance,origin,leading->{
            if(closed)return;pullX=fromRight?leading-dp(76):leading;relayout();
        },()->{drag=null;hide();});
    }
    void widgets(){hide();HubActivity.open(context,0,right);}
    void rebuild(){
        if(closed||rebuilding)return;rebuilding=true;
        try{shown=false;revealProgress=null;pullX=null;gestureAnchor=null;if(drag!=null){drag.release(true);drag=null;}removeViews();build();}
        finally{rebuilding=false;}relayout();
    }
    private boolean landscape(){WorkArea a=area.get();return EdgeDockReveal.landscape(a.physical.width(),a.physical.height());}
    private boolean horizontalDock(){return !gestureMode()&&landscape()&&!DockPlacement.vertical(settings.snapshot().phoneLandscapeDock.edge.storedValue());}
    private LinearLayout strip(){LinearLayout view=new LinearLayout(context);view.setOrientation(horizontal?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);return view;}
    private LinearLayout.LayoutParams itemParams(int length){return horizontal?new LinearLayout.LayoutParams(dp(length),-1):new LinearLayout.LayoutParams(-1,dp(length));}
    private void preview(String edge,float progress){
        if(clearingHandles||!allowed())return;shown=false;revealEdge=edge;revealProgress=progress;
        gestureAnchor=null;entries.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        if("left".equals(edge)||"right".equals(edge))right="right".equals(edge);
        relayout();
    }
    private void releaseHandle(String edge,boolean open){
        if(clearingHandles)return;revealEdge=edge;revealProgress=null;shown=open&&allowed();
        if("left".equals(edge)||"right".equals(edge))right="right".equals(edge);
        relayout();
    }
    private void build(){
        horizontal=horizontalDock();
        LinearLayout column=strip();entries=column;column.setGravity(horizontal?Gravity.CENTER_VERTICAL:Gravity.CENTER_HORIZONTAL);
        if(!taskbarReady){
            ImageButton start=new ImageButton(context);start.setImageDrawable(AppIcons.stella(context));start.setScaleType(ImageView.ScaleType.FIT_CENTER);
            start.setBackground(null);start.setPadding(dp(horizontal?6:14),dp(horizontal?14:6),dp(horizontal?6:14),dp(horizontal?14:6));start.setContentDescription(context.getString(R.string.ui_app_menu));
            start.setOnClickListener(v->toggleStart());column.addView(start,itemParams(48));
            partition(column);
        }
        for(String component:Launches.pins(context))try{
            android.content.ComponentName name=android.content.ComponentName.unflattenFromString(component);
            if(name==null)continue;
            android.content.pm.ApplicationInfo app=context.getPackageManager().getApplicationInfo(name.getPackageName(),0);
            ImageButton icon=new ImageButton(context);icon.setImageDrawable(AppIcons.forApp(context,component,app.loadIcon(context.getPackageManager())));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setBackground(Ui.toolbarBackground(context,10));icon.setPadding(dp(horizontal?8:14),dp(horizontal?14:8),dp(horizontal?8:14),dp(horizontal?14:8));
            icon.setContentDescription(app.loadLabel(context.getPackageManager()));icon.setTooltipText(app.loadLabel(context.getPackageManager()));
            icon.setOnClickListener(v->{hide();Launches.app(context,component,0);});
            icon.setOnLongClickListener(v->{AppContextMenu.show(context,icon,component,0,this::hide,null,null);return true;});
            icon.setOnGenericMotionListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS&&(event.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){AppContextMenu.show(context,icon,component,0,this::hide,null,null);return true;}return false;});
            column.addView(icon,itemParams(52));
        }catch(android.content.pm.PackageManager.NameNotFoundException|IllegalArgumentException ignored){}
        activeSection=strip();column.addView(activeSection,horizontal?new LinearLayout.LayoutParams(-2,-1):new LinearLayout.LayoutParams(-1,-2));
        partition(activeSection);
        activeTasks=strip();activeSection.addView(activeTasks,horizontal?new LinearLayout.LayoutParams(-2,-1):new LinearLayout.LayoutParams(-1,-2));renderRunning();
        partition(column);
        if(horizontal){
            // Horizontal scrolling cannot also mean "pull the widget panel".
            ImageButton widgets=new ImageButton(context);widgets.setImageResource(android.R.drawable.ic_menu_agenda);
            widgets.setScaleType(ImageView.ScaleType.FIT_CENTER);widgets.setBackground(Ui.toolbarBackground(context,10));widgets.setPadding(dp(horizontal?10:16),dp(horizontal?16:10),dp(horizontal?10:16),dp(horizontal?16:10));
            widgets.setContentDescription(context.getString(R.string.hub_widgets));widgets.setTooltipText(context.getString(R.string.hub_widgets));
            widgets.setOnClickListener(v->widgets());column.addView(widgets,itemParams(48));
        }
        Button homeButton=Ui.toolbarButton(context,"▱",()->{hide();Launches.home(context,0);});homeButton.setTextSize(14*factor());homeButton.setMinHeight(dp(48));homeButton.setPadding(dp(16),dp(8),dp(16),dp(8));homeButton.setContentDescription(context.getString(R.string.home_open));homeButton.setTooltipText(context.getString(R.string.home_open));column.addView(homeButton,itemParams(48));
        ViewGroup scroll;
        if(horizontal){
            HorizontalScrollView horizontalScroll=new HorizontalScrollView(context){
                @Override public boolean dispatchTouchEvent(MotionEvent event){
                    if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE){if(!taskMenu.showing())hide();return true;}
                    return super.dispatchTouchEvent(event);
                }
            };horizontalScroll.addView(column,new FrameLayout.LayoutParams(-2,-1));scroll=horizontalScroll;
        }else{ScrollView verticalScroll=new ScrollView(context){float x,y;boolean opening;
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
        verticalScroll.addView(column);scroll=verticalScroll;}
        scroll.setBackground(Appearance.surface(context,18));scroll.setClipToOutline(true);strip=scroll;
        FrameLayout viewport=new FrameLayout(context);viewport.setClipChildren(true);viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        viewport.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE){if(!taskMenu.showing())hide();return true;}return false;});panel=viewport;
        windows.addView(panel,params(1,1,"StellaShell main sidebar"));
        for(int side=0;side<2;side++){
            final boolean fromRight=side==1;
            DockHandleView handle=new DockHandleView(context,new DockHandleView.Listener(){
                private String edge(){return landscape()?settings.snapshot().phoneLandscapeDock.edge.storedValue():fromRight?"right":"left";}
                public void onDrag(float progress){preview(edge(),progress);}
                public void onRelease(boolean open){releaseHandle(edge(),open);}
            });
            handle.setOnClickListener(v->show(fromRight));
            windows.addView(handle,params(dp(16),dp(64),"StellaShell edge handle "+side));handles.add(handle);
        }
        relayout();
    }
    private WindowManager.LayoutParams params(int width,int height,String title){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle(title);return p;
    }
    void relayout(){
        if(closed||rebuilding||panel==null)return;
        reconcileGestures();
        if(horizontal!=horizontalDock()){rebuild();return;}
        WorkArea bounds=area.get();android.graphics.Rect available=bounds.dockAvailable;
        if(!allowed()){shown=false;revealProgress=null;resetHandles();}
        ShellSettings.Snapshot snapshot=settings.snapshot();ShellSettings.PhoneSide side=snapshot.phoneDock.phoneSide;
        boolean wide=landscape();
        String config=side.storedValue()+":"+(wide?snapshot.phoneLandscapeDock.edge.storedValue():"")+":"+snapshot.phoneDock.openMethod.storedValue();
        int position=wide?snapshot.phoneLandscapeDock.positionPercent:snapshot.phoneDock.triggerPercent;
        int rotation=context.getDisplay().getRotation();
        boolean changed=revealArea!=null&&(!revealArea.equals(available)||revealWidth!=bounds.physical.width()
                ||revealHeight!=bounds.physical.height()||!config.equals(revealConfig)||position!=revealPosition||rotation!=revealRotation);
        revealArea=new android.graphics.Rect(available);revealWidth=bounds.physical.width();revealHeight=bounds.physical.height();revealConfig=config;revealPosition=position;revealRotation=rotation;
        if(changed){shown=false;revealProgress=null;pullX=null;gestureAnchor=null;resetHandles();}
        if(!wide){if(side==ShellSettings.PhoneSide.LEFT)right=false;else if(side==ShellSettings.PhoneSide.RIGHT)right=true;}
        String edge=wide?snapshot.phoneLandscapeDock.edge.storedValue():revealProgress!=null?revealEdge:right?"right":"left";
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)panel.getLayoutParams();
        if(horizontal)entries.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(dp(76),View.MeasureSpec.EXACTLY));
        else if(wide||gestureMode())entries.measure(View.MeasureSpec.makeMeasureSpec(dp(76),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int width=Math.min(horizontal?Math.min(dp(500),Math.max(1,entries.getMeasuredWidth())):dp(76),Math.max(1,available.width()));
        int height=Math.min(horizontal?dp(76):gestureMode()?Math.min(dp(360),Math.max(1,entries.getMeasuredHeight())):wide?Math.min(dp(500),Math.max(1,entries.getMeasuredHeight())):dp(500),Math.max(1,available.height()));
        int[] panelBounds=wide?EdgeDockReveal.panel(available.left,available.top,available.right,available.bottom,
                edge,snapshot.phoneLandscapeDock.positionPercent,width,height)
                :new int[]{right?available.right-width:available.left,available.bottom-height,right?available.right:available.left+width,available.bottom};
        if(gestureAnchor!=null){
            panelBounds=FloatingDockPlacement.bounds(available.left,available.top,available.right,available.bottom,width,height,gestureAnchor.x,gestureAnchor.y,gestureRight);
            // Pull toward the screen center from the actual clamped Dock, not the L-stroke direction.
            // Keep that side while dragging, even after the moving panel crosses the center.
            if(drag==null&&pullX==null)right=(panelBounds[0]+panelBounds[2])*.5f>=bounds.physical.exactCenterX();
        }
        boolean visible=shown||revealProgress!=null&&revealProgress>0;
        int oldWidth=p.width,oldHeight=p.height,oldX=p.x,oldY=p.y,oldFlags=p.flags;
        p.width=visible?width:1;p.height=visible?height:1;p.x=pullX==null?panelBounds[0]:Math.round(pullX);p.y=panelBounds[1];
        if(revealProgress!=null)p.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;else p.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        panel.setVisibility(visible?View.VISIBLE:View.GONE);
        float offset=revealProgress==null?0:1-revealProgress;
        strip.setTranslationX("left".equals(edge)?-width*offset:"right".equals(edge)?width*offset:0);
        strip.setTranslationY("top".equals(edge)?-height*offset:"bottom".equals(edge)?height*offset:0);
        if(oldWidth!=p.width||oldHeight!=p.height||oldX!=p.x||oldY!=p.y||oldFlags!=p.flags)windows.updateViewLayout(panel,p);
        for(int i=0;i<handles.size();i++){
            DockHandleView handle=(DockHandleView)handles.get(i);WindowManager.LayoutParams h=(WindowManager.LayoutParams)handle.getLayoutParams();
            int previousWidth=h.width,previousHeight=h.height,previousX=h.x,previousY=h.y,previousFlags=h.flags;
            String handleEdge=wide?edge:i==0?"left":"right";
            android.graphics.Rect safe=new android.graphics.Rect(available);
            safe.left=Math.min(safe.right-1,Math.max(safe.left,bounds.gestureLeft));
            safe.right=Math.max(safe.left+1,Math.min(safe.right,bounds.physical.right-bounds.gestureRight));
            int[] rect=EdgeDockReveal.handle(safe.left,safe.top,safe.right,safe.bottom,handleEdge,
                    wide?snapshot.phoneLandscapeDock.positionPercent:snapshot.phoneDock.triggerPercent,dp(EdgeDockReveal.handleThickness(snapshot.phoneDock.openMethod)),dp(64),dp(8));
            boolean hidden=wide?i!=0:i==0?side==ShellSettings.PhoneSide.RIGHT:side==ShellSettings.PhoneSide.LEFT;
            boolean active=allowed()&&!hidden&&!(gestureMode()&&gestures!=null&&gestures.available());
            h.x=rect[0];h.y=rect[1];h.width=active?rect[2]-rect[0]:1;h.height=active?rect[3]-rect[1]:1;
            if(active)h.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;else h.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            handle.configure(handleEdge,DockPlacement.vertical(handleEdge)?width:height,snapshot.phoneDock.openMethod);
            handle.setVisibility(active?View.VISIBLE:View.GONE);
            if(previousWidth!=h.width||previousHeight!=h.height||previousX!=h.x||previousY!=h.y||previousFlags!=h.flags)windows.updateViewLayout(handle,h);
        }
        menu.relayout();
        if(shown)running.start();else{taskMenu.close();running.stop();}
    }
    private void partition(LinearLayout column){
        View divider=new View(context);divider.setBackgroundColor(Ui.MUTED&0xffffff|0x33000000);
        LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(dp(horizontal?1:44),dp(horizontal?44:1));
        if(horizontal){line.leftMargin=dp(8);line.rightMargin=dp(6);}else{line.topMargin=dp(8);line.bottomMargin=dp(6);}column.addView(divider,line);
    }
    private void renderRunning(){
        taskMenu.close();if(activeTasks==null)return;
        List<TaskSnapshot.Task> allTasks=running.tasks();
        List<TaskSnapshot.Task> tasks=running.state()==PhoneRunningTasks.State.READY?unpinnedTasks(allTasks,Launches.pins(context)):Collections.emptyList();
        activeSection.setVisibility(tasks.isEmpty()?View.GONE:View.VISIBLE);
        renderTasks(context,activeTasks,tasks,task->running.focus(task,this::hide,error->Launches.problem(context,error)),this::taskMenu);
        for(int i=0;i<activeTasks.getChildCount();i++){
            View icon=activeTasks.getChildAt(i);icon.setPadding(dp(horizontal?8:14),dp(horizontal?14:8),dp(horizontal?8:14),dp(horizontal?14:8));icon.setLayoutParams(itemParams(52));
        }
        if(!rebuilding&&panel!=null&&(horizontal||landscape()||gestureMode()))relayout();
    }
    static List<TaskSnapshot.Task> unpinnedTasks(List<TaskSnapshot.Task> tasks,List<String> pins){
        Set<String> packages=new HashSet<>();
        for(String pin:pins){ComponentName name=ComponentName.unflattenFromString(pin);if(name!=null)packages.add(name.getPackageName());}
        List<TaskSnapshot.Task> visible=new ArrayList<>();
        // Launcher aliases and internal task Activities can differ within the same app.
        for(TaskSnapshot.Task task:tasks)if(!packages.contains(task.packageName()))visible.add(task);
        return visible;
    }
    private void taskMenu(View anchor,TaskSnapshot.Task task){
        if(closed||!shown)return;
        taskMenu.show(anchor,TaskModes.canReturnToMain(task.mode),false,action->{
            if(closed||!shown)return;
            android.graphics.Rect bounds="float".equals(action)?PhoneTaskMenu.floatingBounds(area.get().content):new android.graphics.Rect();
            running.operation(task,action,bounds,"close".equals(action)?running::refresh:this::hide,error->Launches.problem(context,error));
        },task.component,this::hide);
    }
    static void renderTasks(Context context,LinearLayout rows,List<TaskSnapshot.Task> tasks,java.util.function.Consumer<TaskSnapshot.Task> focus){
        renderTasks(context,rows,tasks,focus,null);
    }
    static void renderTasks(Context context,LinearLayout rows,List<TaskSnapshot.Task> tasks,java.util.function.Consumer<TaskSnapshot.Task> focus,java.util.function.BiConsumer<View,TaskSnapshot.Task> menu){
        rows.removeAllViews();Map<String,Integer> totals=new HashMap<>(),seen=new HashMap<>();
        for(TaskSnapshot.Task task:tasks)totals.put(task.component,totals.getOrDefault(task.component,0)+1);
        for(TaskSnapshot.Task task:tasks){
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
    private int scale(){return settings.snapshot().phoneDock.scalePercent;}
    private float factor(){return NavigationScale.factor(scale());}
    private int dp(int value){return NavigationScale.pixels(context.getResources().getDisplayMetrics().density,value,scale());}
    private void resetHandles(){clearingHandles=true;try{for(View handle:handles)((DockHandleView)handle).reset();}finally{clearingHandles=false;}}
    private void removeViews(){resetHandles();taskMenu.close();running.stop();activeTasks=null;activeSection=null;menu.close();if(panel!=null){windows.removeViewImmediate(panel);panel=null;}strip=null;entries=null;for(View v:handles)windows.removeViewImmediate(v);handles.clear();}
    @Override public void close(){if(closed)return;closed=true;if(gestures!=null)gestures.close();if(drag!=null)drag.release(true);running.close();removeViews();if(observer!=null)observer.close();loader.shutdownNow();}
}
