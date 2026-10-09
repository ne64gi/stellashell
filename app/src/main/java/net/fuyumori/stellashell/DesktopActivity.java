package net.fuyumori.stellashell;

import android.app.Activity;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Wallpaper and opt-in shortcuts only; the app catalog belongs to Start. */
public class DesktopActivity extends Activity implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private boolean visualStarted,wallpaperDirty,shortcutsDirty;
    private int displayId;private DisplayManager displays;private DesktopShortcuts shortcuts;
    private ShellSettings shellSettings;private AutoCloseable settingsSubscription;
    private final Runnable runtimeChanged=this::runtimeChanged;
    private DesktopWallpaper imageWallpaper;
    private DesktopWidgets widgets;protected FrameLayout root;
    DesktopBackdrop backdrop;
    protected boolean homeSurface(){return false;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);displayId=getDisplay()==null?-1:getDisplay().getDisplayId();
        if(homeSurface()){
            if(displayId!=0){finish();return;}
        }else{
            try{Displays.require(this,displayId);}catch(RuntimeException e){finish();return;}
            if(!ShellRuntime.enabled(this)){finish();return;}
        }
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));
        WorkspaceProfile.initialize(this);
        shellSettings=ShellSettings.of(this);
        settingsSubscription=shellSettings.observe((changes,snapshot)->{
            if(root!=null&&affectsWorkArea(changes)){root.requestApplyInsets();if(backdrop!=null)backdrop.refresh();}
        });
        ShellRuntime.observeNavigation(runtimeChanged);
        Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);AppOrganization.prefs(this).registerOnSharedPreferenceChangeListener(this);
        root=new FrameLayout(this);
        wallpaper();
        imageWallpaper=new DesktopWallpaper(this);root.addView(imageWallpaper.view,new FrameLayout.LayoutParams(-1,-1));imageWallpaper.reload();
        FrameLayout widgetCanvas=new FrameLayout(this);
        FrameLayout.LayoutParams canvasParams=new FrameLayout.LayoutParams(-1,-1);
        root.addView(widgetCanvas,canvasParams);widgets=new DesktopWidgets(this,widgetCanvas,false,displayId==0);
        FrameLayout shortcutCanvas=new FrameLayout(this);
        FrameLayout.LayoutParams shortcutParams=new FrameLayout.LayoutParams(-1,-1);
        root.addView(shortcutCanvas,shortcutParams);shortcuts=new DesktopShortcuts(this,shortcutCanvas,displayId);
        View anchor=new View(this);root.addView(anchor,new FrameLayout.LayoutParams(1,1,Gravity.BOTTOM|Gravity.LEFT));
        widgets.onEditingChanged(()->{if(widgets.isEditing())widgetCanvas.bringToFront();else shortcutCanvas.bringToFront();if(backdrop!=null)backdrop.editing(widgets.isEditing());});
        // Empty-surface holds must not enter widget editing during window gestures.
        root.setOnGenericMotionListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS&&(event.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){desktopMenu(anchor,0);return true;}return false;});
        root.post(()->desktopAction(getIntent()));
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        root.setOnApplyWindowInsetsListener((view,insets)->{
            WorkArea area=WorkArea.read(this,insets);
            for(View canvas:new View[]{widgetCanvas,shortcutCanvas}){
                FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)canvas.getLayoutParams();
                android.graphics.Rect usable=homeSurface()?area.usable:area.application;
                p.setMargins(usable.left,usable.top,area.physical.right-usable.right,area.physical.bottom-usable.bottom);canvas.setLayoutParams(p);
            }
            return insets;
        });
        setContentView(root);
        if(!homeSurface())backdrop=new DesktopBackdrop(this);
        if(WorkspaceProfile.standard(this,displayId))getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
        else getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);root.post(()->desktopAction(intent));}
    private void desktopAction(Intent intent){
        if(!intent.hasExtra("desktop_action"))return;
        int action=intent.getIntExtra("desktop_action",0);intent.removeExtra("desktop_action");
        if(action>=0&&action<=7)desktopMenu(root,action);
    }
    private void desktopMenu(View anchor,int action){
        PopupMenu popup=new PopupMenu(this,anchor);
        popup.getMenu().add(0,1,0,this.getString(R.string.ui_snap_icons_to_grid)).setCheckable(true).setChecked(Launches.prefs(this).getBoolean(WorkspaceProfile.key(this,"shortcut_snap"),false)).setOnMenuItemClickListener(item->{Launches.prefs(this).edit().putBoolean(WorkspaceProfile.key(this,"shortcut_snap"),!item.isChecked()).apply();return true;});
        popup.getMenu().add(0,2,0,this.getString(R.string.ui_new_shortcut)).setOnMenuItemClickListener(item->{
            List<Launches.App> apps=Launches.catalog(this);String[] labels=new String[apps.size()];for(int i=0;i<labels.length;i++)labels[i]=apps.get(i).label;
            DesktopBackdrop.showDialog(this,new android.app.AlertDialog.Builder(this).setTitle(this.getString(R.string.ui_add_shortcut)).setItems(labels,(d,n)->{String component=apps.get(n).component;if(!Launches.desktop(this).contains(component))Launches.toggleDesktop(this,component);}).setNegativeButton(this.getString(R.string.ui_cancel),null).create());return true;
        });
        popup.getMenu().add(0,3,0,this.getString(R.string.ui_wallpaper)).setOnMenuItemClickListener(item->{
            DesktopBackdrop.showDialog(this,new android.app.AlertDialog.Builder(this).setTitle(this.getString(R.string.ui_wallpaper)).setItems(new String[]{getString(R.string.wallpaper_position),this.getString(R.string.ui_choose_image),this.getString(R.string.ui_fill_screen_with_image),this.getString(R.string.ui_fit_entire_image_with_margins),this.getString(R.string.ui_deep_ocean),this.getString(R.string.ui_dusk),this.getString(R.string.ui_graphite)},(d,n)->{
                if(n==0){imageWallpaper.position();return;}n--;
                if(n==0)imageWallpaper.choose();
                else if(n<3){Launches.prefs(this).edit().putBoolean(WorkspaceProfile.key(this,"wallpaper_fit"),n==2).apply();}
                else Launches.prefs(this).edit().putInt(WorkspaceProfile.key(this,"wallpaper"),n-3).putBoolean(WorkspaceProfile.key(this,"wallpaper_image"),false).apply();
            }).setNegativeButton(this.getString(R.string.ui_close),null).create());return true;
        });
        popup.getMenu().add(0,4,0,this.getString(R.string.ui_display_settings)).setOnMenuItemClickListener(item->{DisplaySettingsActivity.open(this,displayId);return true;});
        popup.getMenu().add(0,5,0,getString(R.string.ui_desktop_settings)).setOnMenuItemClickListener(item->{Launches.settings(this,displayId);return true;});
        popup.getMenu().add(0,6,0,this.getString(R.string.ui_add_widget)).setOnMenuItemClickListener(item->{widgets.choose();return true;});
        popup.getMenu().add(0,7,0,widgets.isEditing()?this.getString(R.string.ui_finish_editing_widgets):this.getString(R.string.ui_edit_widgets)).setOnMenuItemClickListener(item->{widgets.setEditing(!widgets.isEditing());return true;});
        if(action==0){if(backdrop!=null)backdrop.showPopup(popup);else popup.show();}else popup.getMenu().performIdentifierAction(action,0);
    }
    private void wallpaper(){
        int[][] colors={{Color.rgb(12,27,42),Color.rgb(24,58,67),Color.rgb(12,19,33)},{0xff392b50,0xff824652,0xff222139},{0xff30343b,0xff1c2028,0xff11151b}};
        int selected=Math.max(0,Math.min(2,Launches.prefs(this).getInt(WorkspaceProfile.key(this,"wallpaper"),0)));root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors[selected]));
    }
    @Override protected void onStart(){super.onStart();visualStarted=true;
        if(wallpaperDirty){wallpaperDirty=false;wallpaper();if(imageWallpaper!=null)imageWallpaper.reload();}
        if(shortcutsDirty){shortcutsDirty=false;if(shortcuts!=null)shortcuts.refresh();}
        if(widgets!=null)widgets.start();}
    @Override protected void onStop(){visualStarted=false;if(shortcuts!=null)shortcuts.closePanel();if(widgets!=null)widgets.stop();super.onStop();}
    @Override protected void onResume(){super.onResume();if(backdrop!=null)backdrop.refresh();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(imageWallpaper!=null&&imageWallpaper.result(request,result,data))return;if(widgets!=null)widgets.result(request,result,data);}
    @Override public void onBackPressed(){if(shortcuts!=null&&shortcuts.back())return;if(widgets!=null&&widgets.finishEditing())return;if(shortcuts!=null)shortcuts.cancelMove();}
    @Override public boolean onSearchRequested(){
        if(this instanceof HomeActivity){((HomeActivity)this).openApps();return true;}
        return ShellRuntime.toggleStart(displayId)||super.onSearchRequested();
    }
    boolean interactionKey(KeyEvent event){return event.getKeyCode()==KeyEvent.KEYCODE_ESCAPE&&event.getAction()==KeyEvent.ACTION_UP&&widgets!=null&&widgets.finishEditing();}
    void finishDesktopInteraction(){if(widgets!=null)widgets.finishEditing();if(shortcuts!=null)shortcuts.cancelMove();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getKeyCode()==KeyEvent.KEYCODE_ESCAPE&&event.getAction()==KeyEvent.ACTION_UP&&widgets!=null&&widgets.finishEditing())return true;return super.dispatchKeyEvent(event);}
    @Override public void onConfigurationChanged(Configuration c){
        super.onConfigurationChanged(c);
        if(widgets!=null)widgets.configurationChanged();
        if(shortcuts!=null){shortcuts.closePanel();shortcuts.refresh();}
        if(root!=null)root.requestApplyInsets();
        if(backdrop!=null)backdrop.refresh();
    }
    @Override public void onDisplayRemoved(int id){if(id==displayId)finishAndRemoveTask();}
    @Override public void onDisplayAdded(int id){}
    @Override public void onDisplayChanged(int id){}
    private boolean affectsWorkArea(java.util.EnumSet<ShellSettings.Change> changes){
        return changes.contains(ShellSettings.Change.PHONE_DOCK_SCALE)
                ||changes.contains(ShellSettings.Change.EXTERNAL_DOCK_SCALE)
                ||changes.contains(ShellSettings.Change.PHONE_TASKBAR_ENABLED)
                ||changes.contains(ShellSettings.Change.PHONE_TASKBAR_SCALE)
                ||changes.contains(ShellSettings.Change.EXTERNAL_TASKBAR_SCALE)
                ||changes.contains(ShellSettings.Change.SHELL_LAYOUT)
                ||changes.contains(ShellSettings.Change.EXTERNAL_DOCK_ENABLED)
                ||changes.contains(ShellSettings.Change.EXTERNAL_DOCK_EDGE)
                ||changes.contains(ShellSettings.Change.EXTERNAL_DOCK_POSITION);
    }
    private void runtimeChanged(){if(!homeSurface()&&!ShellRuntime.enabled(this))finishAndRemoveTask();}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){
        if(key!=null&&(key.startsWith("wallpaper")||key.startsWith("phone_wallpaper"))){
            if(visualStarted){wallpaper();if(imageWallpaper!=null)imageWallpaper.reload();}else wallpaperDirty=true;
        }
        if(shortcuts!=null&&(p==AppOrganization.prefs(this)||WorkspaceProfile.changed(key,"desktop_shortcuts")||WorkspaceProfile.changed(key,"shortcut_snap")||IconTheme.changed(key))){
            if(visualStarted)shortcuts.refresh();else shortcutsDirty=true;
        }
    }
    @Override public void onDestroy(){if(backdrop!=null)backdrop.close();ShellRuntime.unobserveNavigation(runtimeChanged);close(settingsSubscription);if(shortcuts!=null)shortcuts.destroy();AppOrganization.prefs(this).unregisterOnSharedPreferenceChangeListener(this);if(imageWallpaper!=null)imageWallpaper.destroy();if(widgets!=null)widgets.destroy();if(displays!=null)displays.unregisterDisplayListener(this);Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);super.onDestroy();}
    private static void close(AutoCloseable closeable){if(closeable!=null)try{closeable.close();}catch(Exception ignored){}}
}
