package net.fuyumori.stellashell;

import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Always-available primary HOME. Desktop/bridge shutdown must never disable this Activity. */
public final class HomeActivity extends DesktopActivity {
    private AppMenu apps;
    private LinearLayout fallback;
    private final java.util.concurrent.ExecutorService menuLoader=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean resumed,returnPending=true;
    private final Runnable bridgeChanged=this::refreshHome;
    @Override protected boolean homeSurface(){return true;}
    static boolean extensions(Context c){
        return !WorkspaceProfile.standard(c,0)&&HomeMode.extensions(Launches.prefs(c).getBoolean("enabled",false),
                Displays.primary(c)&&Workspace.target(c)==0,Bridge.get(c).ready(),Settings.canDrawOverlays(c));
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(root==null)return;
        // No permanent HOME toolbar: the existing Edge Sidebar owns navigation.
        // When overlays are unavailable, keep the same Start menu reachable from HOME.
        fallback=Ui.column(this);fallback.setPadding(Ui.dp(this,16),Ui.dp(this,12),Ui.dp(this,16),Ui.dp(this,12));fallback.setBackground(Appearance.surface(this,16));
        fallback.addView(Ui.toolbarButton(this,getString(R.string.home_apps),this::openApps));
        fallback.addView(Ui.toolbarButton(this,getString(R.string.setup_settings_tab),()->Launches.settings(this,0)));
        FrameLayout.LayoutParams fallbackBox=new FrameLayout.LayoutParams(Ui.dp(this,220),-2,Gravity.CENTER);root.addView(fallback,fallbackBox);fallback.setVisibility(View.GONE);
        Bridge.get(this).observe(bridgeChanged);
    }
    void openApps(){if(apps==null)apps=new AppMenu(this,getWindowManager(),0);if(!apps.isOpen())apps.open(menuLoader);}
    private void refreshHome(){
        if(fallback==null||isFinishing()||isDestroyed())return;
        boolean extended=extensions(this);
        fallback.setVisibility(!Settings.canDrawOverlays(this)||!Launches.prefs(this).getBoolean("phone_sidebar",true)||Workspace.target(this)>0?View.VISIBLE:View.GONE);
        if(!resumed)return;
        if(WorkspaceProfile.standard(this,0)&&Launches.prefs(this).getBoolean("phone_sidebar",true)&&Settings.canDrawOverlays(this)&&!DockService.running()) {
            Launches.prefs(this).edit().putBoolean("primary_mode",true).putInt("workspace_display",0).apply();
            if(!Launches.prefs(this).contains("workspace_auto"))Launches.prefs(this).edit().putBoolean("workspace_auto",true).apply();
            DockService.start(this,0,false);
        } else if(extended&&!DockService.running())DockService.start(this,0,false);
        if(returnPending&&hasWindowFocus()&&extended){returnPending=false;ShellPanels.dismiss(0);Launches.returnedHome(this);}
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);if(apps!=null)apps.close();returnPending=true;refreshHome();
    }
    @Override protected void onStart(){super.onStart();DockService.homeVisible(true);}
    @Override protected void onStop(){DockService.homeVisible(false);super.onStop();}
    @Override protected void onResume(){super.onResume();resumed=true;Bridge.get(this).connect();refreshHome();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)refreshHome();}
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){super.onSharedPreferenceChanged(prefs,key);if("enabled".equals(key)||"primary_mode".equals(key)||"workspace_display".equals(key)||"phone_sidebar".equals(key)||"phone_window_management".equals(key))refreshHome();}
    @Override public void onBackPressed(){if(apps!=null&&apps.isOpen())apps.close();else super.onBackPressed();}
    @Override public void onDestroy(){Bridge.get(this).remove(bridgeChanged);if(apps!=null)apps.close();menuLoader.shutdownNow();super.onDestroy();}
}
