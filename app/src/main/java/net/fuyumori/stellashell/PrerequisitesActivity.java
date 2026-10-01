package net.fuyumori.stellashell;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

/** First-run/recovery flow. Optional notification permission never blocks the dashboard. */
public final class PrerequisitesActivity extends Activity {
    private Bridge bridge;private TextView shizukuState,overlayState,notificationState;
    private Button connect,overlay,notifications,done;private boolean manual,resumed;
    private final Runnable changed=this::refresh;
    static boolean satisfied(android.content.Context c){return Settings.canDrawOverlays(c)&&Bridge.get(c).authorized();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);manual=getIntent().getBooleanExtra("manual",false);bridge=Bridge.get(this);
        LinearLayout page=DashboardUi.page(this);
        page.addView(DashboardUi.text(this,"StellaShell",16,Ui.ACCENT));DashboardUi.space(page,12);
        page.addView(DashboardUi.title(this,getString(R.string.onboard_title),28));DashboardUi.space(page,8);
        page.addView(DashboardUi.text(this,getString(R.string.onboard_intro),14,Ui.MUTED));DashboardUi.space(page,24);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=Ui.column(this);content.setPadding(0,0,0,Ui.dp(this,24));scroll.addView(content);
        LinearLayout card=DashboardUi.card(content);card.addView(DashboardUi.title(this,getString(R.string.onboard_shizuku),18));
        shizukuState=DashboardUi.text(this,"",14,Ui.MUTED);card.addView(shizukuState);DashboardUi.space(card,12);
        card.addView(DashboardUi.action(this,getString(R.string.onboard_open_shizuku),()->{
            Intent launch=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if(launch==null){new AlertDialog.Builder(this).setTitle(R.string.onboard_shizuku).setMessage(R.string.onboard_install_shizuku).setPositiveButton(android.R.string.ok,null).show();return;}
            try{startActivity(launch);}catch(RuntimeException e){Launches.problem(this,e.getMessage());}
        },false));
        connect=DashboardUi.action(this,getString(R.string.onboard_connect),()->{bridge.request();refresh();},true);card.addView(connect);
        DashboardUi.space(content,16);card=DashboardUi.card(content);card.addView(DashboardUi.title(this,getString(R.string.onboard_overlay),18));
        overlayState=DashboardUi.text(this,"",14,Ui.MUTED);card.addView(overlayState);DashboardUi.space(card,12);
        overlay=DashboardUi.action(this,getString(R.string.onboard_allow_overlay),()->{
            try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}catch(RuntimeException e){Launches.problem(this,e.getMessage());}
        },true);card.addView(overlay);
        DashboardUi.space(content,16);card=DashboardUi.card(content);card.addView(DashboardUi.title(this,getString(R.string.onboard_notifications),18));
        notificationState=DashboardUi.text(this,"",14,Ui.MUTED);card.addView(notificationState);DashboardUi.space(card,12);
        notifications=DashboardUi.action(this,getString(R.string.onboard_allow_notifications),()->{
            if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);
        },false);card.addView(notifications);
        DashboardUi.space(content,24);done=DashboardUi.action(this,getString(R.string.onboard_continue),this::complete,true);content.addView(done);
        bridge.observe(changed);refresh();
    }
    private void complete(){if(satisfied(this)){setResult(RESULT_OK);finish();}}
    private void refresh(){
        if(done==null||isDestroyed()||isFinishing())return;
        boolean authorized=bridge.authorized(),draw=Settings.canDrawOverlays(this);
        shizukuState.setText(bridge.status());shizukuState.setTextColor(authorized?Ui.ACCENT:Ui.MUTED);
        connect.setText(authorized?R.string.onboard_retry:R.string.onboard_connect);connect.setVisibility(bridge.ready()?android.view.View.GONE:android.view.View.VISIBLE);
        overlayState.setText(draw?R.string.onboard_allowed:R.string.onboard_overlay_note);overlayState.setTextColor(draw?Ui.ACCENT:Ui.MUTED);overlay.setVisibility(draw?android.view.View.GONE:android.view.View.VISIBLE);
        boolean notify=Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        notificationState.setText(notify?R.string.onboard_allowed:R.string.onboard_notifications_note);notifications.setVisibility(notify?android.view.View.GONE:android.view.View.VISIBLE);
        done.setEnabled(authorized&&draw);
        if(!manual&&resumed&&authorized&&draw)complete();
    }
    @Override public void onResume(){super.onResume();resumed=true;if(bridge!=null){bridge.connect();refresh();}}
    @Override public void onPause(){resumed=false;super.onPause();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);refresh();}
    @Override public void onDestroy(){if(bridge!=null)bridge.remove(changed);super.onDestroy();}
}
