package net.fuyumori.stellashell;

import android.content.*;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Independent icon-centric desktop launcher. This is not the taskbar/status surface. */
final class DesktopDock implements AutoCloseable {
    private final Context context;private final WindowManager windows;private final int display;private final ShellSettings settings;
    private final TaskState tasks;
    private FrameLayout viewport;private LinearLayout entries;private boolean vertical;
    DesktopDock(Context c,WindowManager windows,int display,TaskState tasks,java.util.function.LongConsumer start){
        context=c;this.windows=windows;this.display=display;this.tasks=tasks;settings=ShellSettings.of(c);rebuild();
    }
    int[] bounds(){
        if(viewport==null)return null;WindowManager.LayoutParams p=(WindowManager.LayoutParams)viewport.getLayoutParams();
        return new int[]{p.x,p.y,p.x+p.width,p.y+p.height};
    }
    void rebuild(){
        close();vertical=DockPlacement.vertical(DockPlacement.edge(settings.snapshot().externalDock.edge.storedValue()));
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
            Button home=Ui.toolbarButton(context,"▱",()->Launches.home(context,display));home.setPadding(0,0,0,0);home.setTextSize(14*factor());home.setMinHeight(dp(48));home.setContentDescription(context.getString(R.string.ui_show_desktop));home.setTooltipText(context.getString(R.string.ui_show_desktop));item(home);
        }
        if(vertical){ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.addView(entries,new FrameLayout.LayoutParams(-1,-2));viewport=scroll;}
        else {HorizontalScrollView scroll=new HorizontalScrollView(context);scroll.setFillViewport(true);scroll.addView(entries,new FrameLayout.LayoutParams(-2,-1));viewport=scroll;}
        viewport.setBackground(Appearance.surface(context,18));viewport.setClipToOutline(true);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle("StellaShell desktop Dock");windows.addView(viewport,p);position();
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
            item.setOnClickListener(v->{ShellPanels.dismiss(display);if(task==null)Launches.app(context,component,display);else if(tasks.compact(context,display))Launches.focus(context,task,display,tasks);else {
                String requested=Profiles.requestedComponent(context,component);AppLaunchProfile.Mode mode=Profiles.get(context,requested).launchMode;
                if(task.mode==1&&(mode==AppLaunchProfile.Mode.WINDOWED||mode==AppLaunchProfile.Mode.MAXIMIZED))Launches.app(context,requested,display);else tasks.action(task,"focus");
            }});
            item.setOnLongClickListener(v->{ShellPanels.dismiss(display);AppContextMenu.show(context,item,component,display,()->{},task,tasks);return true;});item.setOnContextClickListener(v->v.performLongClick());item(item);
        }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
    }
    void position(){
        if(viewport==null)return;WorkArea a=WorkArea.get(context,display);
        entries.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        ShellSettings.Dock dockSettings=settings.snapshot().externalDock;
        int[] b=DockPlacement.bounds(a.dockAvailable.left,a.dockAvailable.top,a.dockAvailable.right,a.dockAvailable.bottom,
            vertical?dp(52):entries.getMeasuredWidth(),vertical?entries.getMeasuredHeight():dp(52),dockSettings.xPercent,dockSettings.yPercent);
        WindowManager.LayoutParams p=(WindowManager.LayoutParams)viewport.getLayoutParams();p.x=b[0];p.y=b[1];p.width=b[2]-b[0];p.height=b[3]-b[1];windows.updateViewLayout(viewport,p);
    }
    private int scale(){return settings.snapshot().externalDock.scalePercent;}
    private float factor(){return NavigationScale.factor(scale());}
    private int dp(int value){return NavigationScale.pixels(context.getResources().getDisplayMetrics().density,value,scale());}
    public void close(){if(viewport!=null)try{windows.removeViewImmediate(viewport);}catch(RuntimeException ignored){}viewport=null;entries=null;}
}
