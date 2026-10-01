package net.fuyumori.stellashell;

import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Always-available primary HOME. Desktop/bridge shutdown must never disable this Activity. */
public final class HomeActivity extends DesktopActivity {
    private HomeAppsDialog apps;
    private TextView status;
    private boolean resumed,returnPending=true;
    private final Runnable bridgeChanged=this::refreshHome;
    @Override protected boolean homeSurface(){return true;}
    static boolean extensions(Context c){
        return HomeMode.extensions(Launches.prefs(c).getBoolean("enabled",false),
                Displays.primary(c)&&Workspace.target(c)==0,Bridge.get(c).ready(),Settings.canDrawOverlays(c));
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(root==null)return;
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this,8),Ui.dp(this,4),Ui.dp(this,8),Ui.dp(this,4));bar.setBackground(Appearance.surface(this,16));
        Button open=Ui.toolbarButton(this,getString(R.string.home_apps),this::openApps);
        bar.addView(open,new LinearLayout.LayoutParams(Ui.dp(this,100),-1));
        status=Ui.text(this,"",12,Ui.MUTED);status.setGravity(Gravity.CENTER);status.setMaxLines(2);
        bar.addView(status,new LinearLayout.LayoutParams(0,-1,1));
        Button settings=Ui.toolbarButton(this,getString(R.string.setup_settings_tab),()->Launches.settings(this,0));
        bar.addView(settings,new LinearLayout.LayoutParams(Ui.dp(this,84),-1));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,Ui.dp(this,56),Gravity.BOTTOM);root.addView(bar,p);
        bar.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.mandatorySystemGestures());
            FrameLayout.LayoutParams bounds=(FrameLayout.LayoutParams)bar.getLayoutParams();
            bounds.setMargins(safe.left,safe.top,safe.right,safe.bottom);bar.setLayoutParams(bounds);return insets;
        });
        Bridge.get(this).observe(bridgeChanged);
    }
    void openApps(){if(apps==null||!apps.isShowing()){apps=new HomeAppsDialog(this);apps.show();}}
    private void refreshHome(){
        if(status==null||isFinishing()||isDestroyed())return;
        boolean extended=extensions(this);
        status.setText(extended?R.string.home_extended:R.string.home_basic);
        if(!resumed)return;
        if(extended&&!DockService.running())DockService.start(this,0,false);
        if(returnPending&&hasWindowFocus()&&extended){returnPending=false;ShellPanels.dismiss(0);Launches.returnedHome(this);}
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);if(apps!=null)apps.dismiss();returnPending=true;refreshHome();
    }
    @Override protected void onResume(){super.onResume();resumed=true;Bridge.get(this).connect();refreshHome();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)refreshHome();}
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){super.onSharedPreferenceChanged(prefs,key);if("enabled".equals(key)||"primary_mode".equals(key)||"workspace_display".equals(key))refreshHome();}
    @Override public void onBackPressed(){if(apps!=null&&apps.isShowing())apps.dismiss();else super.onBackPressed();}
    @Override public void onDestroy(){Bridge.get(this).remove(bridgeChanged);if(apps!=null)apps.dismiss();super.onDestroy();}
}
