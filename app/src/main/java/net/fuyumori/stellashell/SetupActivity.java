package net.fuyumori.stellashell;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class SetupActivity extends Activity implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private TextView status,detail;private Button start,enable,restore;private Bridge bridge;private DisplayManager displays;
    private boolean busy;private final Runnable refreshListener=this::refresh;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        // scrcpy --start-app opens the launcher entry on its own display.
        // Explicit settings launches have no LAUNCHER category and stay here.
        if(getIntent().hasCategory(Intent.CATEGORY_LAUNCHER) && getDisplay()!=null && getDisplay().getDisplayId()>0
                && Displays.ids(this).contains(getDisplay().getDisplayId()) && Settings.canDrawOverlays(this)) {
            DockService.start(this);finish();return;
        }
        bridge=Bridge.get(this);bridge.observe(refreshListener);
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));
        Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Ui.BG);scroll.setFillViewport(true);
        LinearLayout root=Ui.column(this);root.setPadding(Ui.dp(this,24),Ui.dp(this,30),Ui.dp(this,24),Ui.dp(this,30));root.setFitsSystemWindows(true);scroll.addView(root);
        Ui.heading(root,"StellaShell");Ui.note(root,"USB-C や scrcpy の独立画面をデスクトップに。\nスマホのホームは、そのまま。");
        status=Ui.text(this,"",17,Ui.ACCENT);status.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,12));root.addView(status);
        root.addView(Ui.button(this,"1  操作バーの表示を許可",()->{
            try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
            catch(RuntimeException e){Launches.problem(this,e.getMessage());}
        }));
        root.addView(Ui.button(this,"2  Shizuku に接続・許可",()->{bridge.request();refresh();}));
        enable=Ui.button(this,"3  Desktop Shell 用に設定する",()->new AlertDialog.Builder(this)
                .setTitle("デスクトップ機能を有効にする")
                .setMessage("自由形式ウィンドウを有効にし、Android の強制デスクトップモードをオフにします。StellaShell が外部ホームとバーを担当します。元の設定は保存します。反映には scrcpy の再接続または USB の抜き差しが必要です。")
                .setNegativeButton("キャンセル",null).setPositiveButton("有効にする",(d,w)->apply(false)).show());
        root.addView(enable);
        start=Ui.button(this,"外部デスクトップを開始",()->{
            if(Launches.prefs(this).getBoolean("enabled",false)){DockService.stop(this);refresh();return;}
            if(!bridge.ready()){Ui.message(this,"先に Shizuku に接続してください");return;}
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);
            DockService.start(this);refresh();
        });start.setTextColor(Ui.ACCENT);root.addView(start);
        Ui.note(root,"開始後は接続・抜線を自動検出します。再起動後は Shizuku と本アプリを起動してください。停止だけでは端末設定は戻りません。復元は下のボタンから行えます。");
        Switch mode=new Switch(this);mode.setText("ウィンドウで起動する（試験）");mode.setTextColor(Ui.TEXT);mode.setTextSize(15);mode.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));
        mode.setChecked(Launches.prefs(this).getBoolean("freeform",false));mode.setOnCheckedChangeListener((button,checked)->Launches.prefs(this).edit().putBoolean("freeform",checked).apply());root.addView(mode);
        Ui.note(root,"オフは全画面起動。アクティブな窓は上のタイトル部分で移動、縁でサイズ変更できます。アプリの最小サイズは OS に制限される場合があります。");
        root.addView(Ui.button(this,"外部ホームを開き直す",()->{
            int id=Policy.selectDisplay(-1,Displays.ids(this));
            if(id<0){Ui.message(this,"先に外部ディスプレイを接続してください");return;}
            if(!Launches.prefs(this).getBoolean("enabled",false)){Ui.message(this,"先に外部デスクトップを開始してください");return;}
            Launches.home(this,id);
        }));
        restore=Ui.button(this,"変更前の端末設定に戻す",()->new AlertDialog.Builder(this).setTitle("端末設定を復元する")
                .setMessage("外部デスクトップを停止し、このアプリが保存した変更前の2項目に戻します。")
                .setNegativeButton("キャンセル",null).setPositiveButton("復元",(d,w)->apply(true)).show());root.addView(restore);
        detail=Ui.text(this,"",13,Ui.MUTED);detail.setTextIsSelectable(true);detail.setPadding(0,Ui.dp(this,20),0,0);root.addView(detail);
        root.addView(Ui.button(this,"診断情報をコピー",()->{
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("StellaShell diagnostics",diagnostics()));Ui.message(this,"診断情報をコピーしました");
        }));
        setContentView(scroll);refresh();
    }
    private void apply(boolean restoring){
        if(busy)return;
        if(!bridge.ready()){Ui.message(this,"Shizuku に接続してください");return;}
        SharedPreferences prefs=Launches.prefs(this);
        if(restoring && !prefs.contains("before_desktop")){Ui.message(this,"復元する変更はありません");return;}
        busy=true;refresh();
        if(restoring)DockService.stop(this);
        bridge.call(s->{
            if(!restoring && !prefs.contains("before_desktop")){
                String snapshot=s.settingsSnapshot();
                String[] values=snapshot.split(",",-1);
                if(values.length!=2)throw new IllegalStateException(snapshot);
                Policy.setting(values[0]);Policy.setting(values[1]);
                // Persist before mutation, so a crash cannot erase the restoration record.
                if(!prefs.edit().putString("before_desktop",values[0]).putString("before_freeform",values[1]).commit())
                    throw new IllegalStateException("変更前の設定を保存できません");
            }
            return s.applySettings(restoring?prefs.getString("before_desktop","null"):"0",restoring?prefs.getString("before_freeform","null"):"1");
        },(result,error)->{
            busy=false;
            if(error!=null)Launches.problem(this,error);
            else{
                if(restoring)prefs.edit().remove("before_desktop").remove("before_freeform").putBoolean("freeform",false).apply();
                prefs.edit().remove("last_error").apply();
                Ui.message(this,restoring?"変更前の設定に戻しました":"有効にしました。USB をつなぎ直してください");
            }
            if(!isDestroyed())refresh();
        });
    }
    private String diagnostics(){
        String version="?";
        try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException ignored){}
        StringBuilder s=new StringBuilder("StellaShell ").append(version).append("\n機種: ").append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
                .append("\n").append(bridge.status()).append("\n操作バー表示: ").append(Settings.canDrawOverlays(this))
                .append("\nセッション: ").append(Launches.prefs(this).getBoolean("enabled",false))
                .append("\n復元記録: ").append(Launches.prefs(this).contains("before_desktop"));
        for(Display d:Displays.available(this))s.append("\n画面 ").append(d.getDisplayId()).append(": ").append(d.getName())
                .append(" / ").append(d.getMode().getPhysicalWidth()).append("×").append(d.getMode().getPhysicalHeight());
        String error=Launches.prefs(this).getString("last_error","");if(!error.isEmpty())s.append("\n最終エラー: ").append(error);
        return s.append("\nウィンドウ操作: ").append(Launches.prefs(this).getString("task_diagnostics","未接続")).toString();
    }
    private void refresh(){
        if(status==null || isDestroyed())return;
        List<Display> monitors=Displays.available(this);
        status.setText((monitors.isEmpty()?"外部ディスプレイ未接続":monitors.get(0).getName()+" 接続済み")+"\n"+bridge.status()+"\n操作バー: "+(Settings.canDrawOverlays(this)?"許可済み":"許可が必要"));
        start.setText(Launches.prefs(this).getBoolean("enabled",false)?"外部デスクトップを停止":"外部デスクトップを開始");
        enable.setEnabled(!busy && bridge.ready());restore.setEnabled(!busy && bridge.ready() && Launches.prefs(this).contains("before_desktop"));
        detail.setText(diagnostics());
    }
    @Override public void onResume(){super.onResume();if(bridge!=null){bridge.connect();refresh();}}
    @Override public void onDisplayAdded(int id){refresh();}
    @Override public void onDisplayRemoved(int id){refresh();}
    @Override public void onDisplayChanged(int id){refresh();}
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key){refresh();}
    @Override public void onDestroy(){
        if(bridge!=null)bridge.remove(refreshListener);if(displays!=null)displays.unregisterDisplayListener(this);
        Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);super.onDestroy();
    }
}
