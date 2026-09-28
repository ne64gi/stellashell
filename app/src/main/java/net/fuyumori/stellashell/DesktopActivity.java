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
public final class DesktopActivity extends Activity implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private int displayId;private DisplayManager displays;private DesktopShortcuts shortcuts;
    private DesktopWallpaper imageWallpaper;
    private DesktopWidgets widgets;private FrameLayout root;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);displayId=getDisplay()==null?-1:getDisplay().getDisplayId();
        try{Displays.require(this,displayId);}catch(RuntimeException e){finish();return;}
        if(!Launches.prefs(this).getBoolean("enabled",false)){finish();return;}
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));
        Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);
        root=new FrameLayout(this);
        wallpaper();
        imageWallpaper=new DesktopWallpaper(this);root.addView(imageWallpaper.view,new FrameLayout.LayoutParams(-1,-1));imageWallpaper.reload();
        FrameLayout widgetCanvas=new FrameLayout(this);
        FrameLayout.LayoutParams canvasParams=new FrameLayout.LayoutParams(-1,-1);canvasParams.bottomMargin=Ui.dp(this,64);
        root.addView(widgetCanvas,canvasParams);widgets=new DesktopWidgets(this,widgetCanvas);
        FrameLayout shortcutCanvas=new FrameLayout(this);
        FrameLayout.LayoutParams shortcutParams=new FrameLayout.LayoutParams(-1,-1);shortcutParams.bottomMargin=Ui.dp(this,64);
        root.addView(shortcutCanvas,shortcutParams);shortcuts=new DesktopShortcuts(this,shortcutCanvas,displayId);
        TextView menu=Ui.text(this,"⋮",26,Ui.MUTED);menu.setGravity(Gravity.CENTER);menu.setContentDescription(this.getString(R.string.ui_desktop_menu));menu.setOnClickListener(v->desktopMenu(menu));
        root.addView(menu,new FrameLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48),Gravity.TOP|Gravity.RIGHT));
        root.setOnLongClickListener(v->{desktopMenu(menu);return true;});
        root.setOnGenericMotionListener((v,event)->{if((event.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS)&&(event.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){desktopMenu(menu);return true;}return false;});
        setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
    private void desktopMenu(View anchor){
        PopupMenu popup=new PopupMenu(this,anchor);
        popup.getMenu().add(this.getString(R.string.ui_snap_icons_to_grid)).setCheckable(true).setChecked(Launches.prefs(this).getBoolean("shortcut_snap",false)).setOnMenuItemClickListener(item->{Launches.prefs(this).edit().putBoolean("shortcut_snap",!item.isChecked()).apply();return true;});
        popup.getMenu().add(this.getString(R.string.ui_new_shortcut)).setOnMenuItemClickListener(item->{
            List<Launches.App> apps=Launches.catalog(this);String[] labels=new String[apps.size()];for(int i=0;i<labels.length;i++)labels[i]=apps.get(i).label;
            new android.app.AlertDialog.Builder(this).setTitle(this.getString(R.string.ui_add_shortcut)).setItems(labels,(d,n)->{String component=apps.get(n).component;if(!Launches.desktop(this).contains(component))Launches.toggleDesktop(this,component);}).setNegativeButton(this.getString(R.string.ui_cancel),null).show();return true;
        });
        popup.getMenu().add(this.getString(R.string.ui_wallpaper)).setOnMenuItemClickListener(item->{
            new android.app.AlertDialog.Builder(this).setTitle(this.getString(R.string.ui_wallpaper)).setItems(new String[]{this.getString(R.string.ui_choose_image),this.getString(R.string.ui_fill_screen_with_image),this.getString(R.string.ui_fit_entire_image_with_margins),this.getString(R.string.ui_deep_ocean),this.getString(R.string.ui_dusk),this.getString(R.string.ui_graphite)},(d,n)->{
                if(n==0)imageWallpaper.choose();
                else if(n<3){Launches.prefs(this).edit().putBoolean("wallpaper_fit",n==2).apply();}
                else Launches.prefs(this).edit().putInt("wallpaper",n-3).putBoolean("wallpaper_image",false).apply();
            }).setNegativeButton(this.getString(R.string.ui_close),null).show();return true;
        });
        popup.getMenu().add(this.getString(R.string.ui_display_settings)).setOnMenuItemClickListener(item->{try{startActivity(new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS),android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());}catch(RuntimeException e){Launches.problem(this,e.getMessage());}return true;});
        popup.getMenu().add(getString(R.string.ui_desktop_settings)).setOnMenuItemClickListener(item->{Launches.settings(this,displayId);return true;});
        popup.getMenu().add(this.getString(R.string.ui_add_widget)).setOnMenuItemClickListener(item->{widgets.choose();return true;});
        popup.getMenu().add(widgets.isEditing()?this.getString(R.string.ui_finish_editing_widgets):this.getString(R.string.ui_edit_widgets)).setOnMenuItemClickListener(item->{widgets.setEditing(!widgets.isEditing());return true;});
        popup.show();
    }
    private void wallpaper(){
        int[][] colors={{Color.rgb(12,27,42),Color.rgb(24,58,67),Color.rgb(12,19,33)},{0xff392b50,0xff824652,0xff222139},{0xff30343b,0xff1c2028,0xff11151b}};
        int selected=Math.max(0,Math.min(2,Launches.prefs(this).getInt("wallpaper",0)));root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors[selected]));
    }
    @Override protected void onStart(){super.onStart();if(widgets!=null)widgets.start();}
    @Override protected void onStop(){if(widgets!=null)widgets.stop();super.onStop();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(imageWallpaper!=null&&imageWallpaper.result(request,result,data))return;if(widgets!=null)widgets.result(request,result);}
    @Override public void onBackPressed(){if(shortcuts!=null)shortcuts.cancelMove();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    @Override public void onDisplayRemoved(int id){if(id==displayId)finishAndRemoveTask();}
    @Override public void onDisplayAdded(int id){}
    @Override public void onDisplayChanged(int id){}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){if(key!=null&&key.startsWith("wallpaper")){wallpaper();if(imageWallpaper!=null)imageWallpaper.reload();}if("enabled".equals(key)&&!p.getBoolean("enabled",false))finishAndRemoveTask();if(shortcuts!=null&&("desktop_shortcuts".equals(key)||"shortcut_snap".equals(key)))shortcuts.refresh();}
    @Override public void onDestroy(){if(imageWallpaper!=null)imageWallpaper.destroy();if(widgets!=null)widgets.destroy();if(displays!=null)displays.unregisterDisplayListener(this);Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);super.onDestroy();}
}
