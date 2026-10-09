package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.launch.AppLaunchProfile;
import net.fuyumori.stellashell.core.layout.DockPlacement;
import net.fuyumori.stellashell.core.layout.EdgeDockReveal;
import net.fuyumori.stellashell.core.navigation.NavigationScale;

import android.content.*;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Independent icon-centric desktop launcher. This is not the taskbar/status surface. */
final class DesktopDock implements AutoCloseable {
    private final Context context;private final WindowManager windows;private final int display;private final ShellSettings settings;
    private final TaskState tasks;
    private FrameLayout viewport;private View strip;private LinearLayout entries;private DockHandleView handle;
    private boolean vertical,revealByHandle,shown,previewing,refreshPending,positioning;
    private String edge="bottom";private int[] panelBounds,availableBounds;private int layoutRotation=-1,anchorPosition=-1;private float progress;
    DesktopDock(Context c,WindowManager windows,int display,TaskState tasks,java.util.function.LongConsumer start){
        context=c;this.windows=windows;this.display=display;this.tasks=tasks;settings=ShellSettings.of(c);try{rebuild();}catch(RuntimeException error){close();throw error;}
    }
    int[] bounds(){
        if(viewport==null||revealByHandle&&!shown)return null;WindowManager.LayoutParams p=(WindowManager.LayoutParams)viewport.getLayoutParams();
        return new int[]{p.x,p.y,p.x+p.width,p.y+p.height};
    }
    void rebuild(){
        close();ShellSettings.Dock dockSettings=settings.snapshot().externalDock;
        edge=DockPlacement.edge(dockSettings.edge.storedValue());vertical=DockPlacement.vertical(edge);revealByHandle=dockSettings.revealByHandle;
        populateEntries();
        if(vertical){ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.addView(entries,new FrameLayout.LayoutParams(-1,-2));strip=scroll;}
        else {HorizontalScrollView scroll=new HorizontalScrollView(context);scroll.setFillViewport(true);scroll.addView(entries,new FrameLayout.LayoutParams(-2,-1));strip=scroll;}
        viewport=new FrameLayout(context);viewport.setClipChildren(true);viewport.addView(strip,new FrameLayout.LayoutParams(-1,-1));
        strip.setBackground(Appearance.surface(context,18));strip.setClipToOutline(true);
        viewport.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE&&revealByHandle&&shown){hide();return true;}return false;});
        WindowManager.LayoutParams p=params("StellaShell desktop Dock");
        windows.addView(viewport,p);
        if(revealByHandle){
            handle=new DockHandleView(context,new DockHandleView.Listener(){
                public void onDrag(float value){preview(value);}
                public void onRelease(boolean open){if(open)show();else hide();}
                public void onGestureEnd(){flushRefresh();}
            });
            handle.setContentDescription(context.getString(R.string.dock_handle_open));handle.setTooltipText(context.getString(R.string.dock_handle_open));
            handle.setOnClickListener(v->show());windows.addView(handle,params("StellaShell desktop Dock handle"));
        }
        position();
    }
    /** Task/pin changes replace icons, never the handle or the opened panel lifetime. */
    void refresh(){
        if(viewport==null||strip==null)return;
        if(positioning||handle!=null&&handle.tracking()){refreshPending=true;return;}
        refreshPending=false;populateEntries();
        ViewGroup scroll=(ViewGroup)strip;scroll.removeAllViews();
        scroll.addView(entries,new FrameLayout.LayoutParams(vertical?-1:-2,vertical?-2:-1));position();
    }
    private void flushRefresh(){
        if(refreshPending&&!positioning&&(handle==null||!handle.tracking()))refresh();
    }
    private void populateEntries(){
        entries=new LinearLayout(context);entries.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);entries.setGravity(Gravity.CENTER);
        int pad=dp(4);entries.setPadding(pad,pad,pad,pad);
        List<TaskSnapshot.Task> running=tasks==null?Collections.emptyList():tasks.snapshot().tasks;Set<String> pinnedPackages=new HashSet<>();
        for(String component:Launches.dockPins(context)){
            ComponentName name=ComponentName.unflattenFromString(component);if(name==null)continue;
            pinnedPackages.add(name.getPackageName());TaskSnapshot.Task match=null;
            for(TaskSnapshot.Task task:running)if(name.getPackageName().equals(task.packageName())){match=task;if(task.focused)break;}
            app(component,match);
        }
        for(TaskSnapshot.Task task:running)if(!pinnedPackages.contains(task.packageName()))app(task.component,task);
        if(WorkArea.get(context,display).compact){
            Button home=Ui.toolbarButton(context,"▱",()->{hide();Launches.home(context,display);});home.setPadding(0,0,0,0);home.setTextSize(14*factor());home.setMinHeight(dp(48));home.setContentDescription(context.getString(R.string.ui_show_desktop));home.setTooltipText(context.getString(R.string.ui_show_desktop));item(home);
        }
    }
    private void item(View view){entries.addView(view,new LinearLayout.LayoutParams(dp(44),dp(44)));}
    private void app(String component,TaskSnapshot.Task task){
        try{
            ComponentName name=ComponentName.unflattenFromString(component);if(name==null)return;
            android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(name.getPackageName(),0);
            CharSequence label=info.loadLabel(context.getPackageManager());LinearLayout item=Ui.column(context);item.setGravity(Gravity.CENTER);item.setPadding(dp(4),dp(3),dp(4),0);
            item.setBackground(Ui.toolbarBackground(context,9));item.setSelected(task!=null&&task.focused);
            ImageView icon=new ImageView(context);icon.setImageDrawable(AppIcons.forApp(context,component,info.loadIcon(context.getPackageManager())));item.addView(icon,new LinearLayout.LayoutParams(dp(29),dp(29)));
            TextView mark=Ui.text(context,task==null?"":task.alwaysOnTop?"↑":task.visible?"━":"·",10,Ui.ACCENT);mark.setTextSize(10*factor());mark.setGravity(Gravity.CENTER);mark.setIncludeFontPadding(false);item.addView(mark,new LinearLayout.LayoutParams(-1,dp(11)));
            item.setContentDescription(label+(task==null?context.getString(R.string.ui_pinned):context.getString(R.string.ui_running)));item.setTooltipText(label);
            item.setOnClickListener(v->{hide();ShellPanels.dismiss(display);if(task==null)Launches.app(context,component,display);else if(tasks.compact(context,display))Launches.focus(context,task,display,tasks);else {
                String requested=Profiles.requestedComponent(context,component);AppLaunchProfile.Mode mode=Profiles.get(context,requested).launchMode;
                if(task.mode==1&&(mode==AppLaunchProfile.Mode.WINDOWED||mode==AppLaunchProfile.Mode.MAXIMIZED))Launches.app(context,requested,display);else tasks.action(task,"focus");
            }});
            item.setOnLongClickListener(v->{ShellPanels.dismiss(display);AppContextMenu.show(context,item,component,display,this::hide,task,tasks);return true;});item.setOnContextClickListener(v->v.performLongClick());item(item);
        }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
    }
    private WindowManager.LayoutParams params(String title){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle(title);return p;
    }
    void position(){
        if(viewport==null||positioning)return;positioning=true;
        try{
            WorkArea a=WorkArea.get(context,display);
            entries.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            ShellSettings.Dock dockSettings=settings.snapshot().externalDock;
            int position=vertical?dockSettings.yPercent:dockSettings.xPercent;
            int wantedWidth=vertical?dp(52):entries.getMeasuredWidth(),wantedHeight=vertical?entries.getMeasuredHeight():dp(52);
            int[] b=revealByHandle?EdgeDockReveal.panel(a.dockAvailable.left,a.dockAvailable.top,a.dockAvailable.right,a.dockAvailable.bottom,
                edge,position,wantedWidth,wantedHeight):DockPlacement.bounds(a.dockAvailable.left,a.dockAvailable.top,a.dockAvailable.right,a.dockAvailable.bottom,
                wantedWidth,wantedHeight,dockSettings.xPercent,dockSettings.yPercent);
            int[] available={a.dockAvailable.left,a.dockAvailable.top,a.dockAvailable.right,a.dockAvailable.bottom};
            Display output=context.getDisplay();int rotation=output==null?0:output.getRotation();
            // A changed safe area/rotation cancels the pull; brightness-only callbacks keep it.
            if(revealByHandle&&panelBounds!=null&&(!Arrays.equals(availableBounds,available)||layoutRotation!=rotation||anchorPosition!=position)){
                shown=false;previewing=false;progress=0;if(handle!=null)handle.reset();
            }
            panelBounds=b;availableBounds=available;layoutRotation=rotation;anchorPosition=position;layoutPanel();
            if(handle!=null){
                int[] h=EdgeDockReveal.handle(a.dockAvailable.left,a.dockAvailable.top,a.dockAvailable.right,a.dockAvailable.bottom,
                    edge,position,dp(EdgeDockReveal.handleThickness(dockSettings.openMethod)),dp(64),dp(8));
                WindowManager.LayoutParams p=(WindowManager.LayoutParams)handle.getLayoutParams();
                int width=h[2]-h[0],height=h[3]-h[1];boolean changed=p.x!=h[0]||p.y!=h[1]||p.width!=width||p.height!=height;
                p.x=h[0];p.y=h[1];p.width=width;p.height=height;
                handle.configure(edge,vertical?b[2]-b[0]:b[3]-b[1],dockSettings.openMethod);handle.setVisibility(shown?View.GONE:View.VISIBLE);
                if(changed)windows.updateViewLayout(handle,p);
            }
        }finally{positioning=false;flushRefresh();}
    }
    private void preview(float value){
        if(!revealByHandle||viewport==null)return;shown=false;progress=Math.max(0,Math.min(1,value));previewing=progress>0;layoutPanel();
    }
    private void show(){
        if(!revealByHandle||viewport==null)return;shown=true;previewing=false;progress=1;layoutPanel();if(handle!=null)handle.setVisibility(View.GONE);
    }
    private void hide(){
        if(!revealByHandle||viewport==null)return;shown=false;previewing=false;progress=0;if(handle!=null){handle.reset();handle.setVisibility(View.VISIBLE);}layoutPanel();
    }
    private void layoutPanel(){
        if(viewport==null||panelBounds==null)return;boolean visible=!revealByHandle||shown||previewing;
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)viewport.getLayoutParams();
        int width=visible?panelBounds[2]-panelBounds[0]:1,height=visible?panelBounds[3]-panelBounds[1]:1;
        int flags=p.flags&~(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        if(revealByHandle&&!shown)flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if(revealByHandle&&shown)flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        boolean changed=p.x!=panelBounds[0]||p.y!=panelBounds[1]||p.width!=width||p.height!=height||p.flags!=flags;
        p.x=panelBounds[0];p.y=panelBounds[1];p.width=width;p.height=height;p.flags=flags;
        float remaining=revealByHandle&&!shown?1-progress:0;
        strip.setTranslationX(vertical?("left".equals(edge)?-1:1)*(panelBounds[2]-panelBounds[0])*remaining:0);
        strip.setTranslationY(!vertical?("top".equals(edge)?-1:1)*(panelBounds[3]-panelBounds[1])*remaining:0);
        viewport.setVisibility(visible?View.VISIBLE:View.GONE);if(changed)windows.updateViewLayout(viewport,p);
    }
    private int scale(){return settings.snapshot().externalDock.scalePercent;}
    private float factor(){return NavigationScale.factor(scale());}
    private int dp(int value){return NavigationScale.pixels(context.getResources().getDisplayMetrics().density,value,scale());}
    public void close(){
        refreshPending=false;shown=false;previewing=false;progress=0;panelBounds=null;availableBounds=null;layoutRotation=-1;anchorPosition=-1;
        if(handle!=null){handle.reset();try{windows.removeViewImmediate(handle);}catch(RuntimeException ignored){}handle=null;}
        if(viewport!=null)try{windows.removeViewImmediate(viewport);}catch(RuntimeException ignored){}viewport=null;strip=null;entries=null;
    }
}
