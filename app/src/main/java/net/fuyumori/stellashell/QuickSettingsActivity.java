package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;

/** Small desktop tray surface. Audio is device-wide; no silent network toggles. */
public final class QuickSettingsActivity extends Activity {
    private static WeakReference<QuickSettingsActivity> visible=new WeakReference<>(null);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView network,volumeLabel;
    private SeekBar volume;
    private AudioManager audio;
    private boolean dragging;
    private int displayId;
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,1500);}};
    static void open(Context c,int id){
        QuickSettingsActivity current=visible.get();
        if(current!=null&&!current.isFinishing()&&current.displayId==id){current.finish();return;}
        try{Displays.require(c,id);c.startActivity(new Intent(c,QuickSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle());}
        catch(RuntimeException e){Ui.message(c,e.getMessage());}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);visible=new WeakReference<>(this);displayId=getDisplay().getDisplayId();audio=getSystemService(AudioManager.class);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0x11101725);root.setOnClickListener(v->finish());
        LinearLayout panel=Ui.column(this);panel.setPadding(dp(20),dp(16),dp(20),dp(16));panel.setBackground(Ui.rounded(this,Ui.PANEL,20));panel.setElevation(dp(16));panel.setOnClickListener(v->{});
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(Math.min(dp(420),getResources().getDisplayMetrics().widthPixels-dp(24)),-2,Gravity.RIGHT|Gravity.BOTTOM);box.setMargins(dp(12),dp(12),dp(12),dp(68));root.addView(panel,box);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(Ui.text(this,getString(R.string.quick_settings),20,Ui.TEXT),new LinearLayout.LayoutParams(0,-2,1));
        Button close=Ui.button(this,"×",this::finish);close.setContentDescription(getString(R.string.ui_close));heading.addView(close,new LinearLayout.LayoutParams(dp(44),dp(44)));panel.addView(heading);
        network=Ui.text(this,"",15,Ui.TEXT);network.setPadding(0,dp(12),0,dp(12));panel.addView(network);
        panel.addView(Ui.button(this,getString(R.string.quick_wifi_settings),()->{
            try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());finish();}
            catch(RuntimeException e){Ui.message(this,e.getMessage());}
        }));
        volumeLabel=Ui.text(this,"",14,Ui.TEXT);volumeLabel.setPadding(0,dp(20),0,dp(8));panel.addView(volumeLabel);
        volume=new SeekBar(this);volume.setContentDescription(getString(R.string.quick_media_volume));volume.setMin(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC));volume.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));volume.setEnabled(!audio.isVolumeFixed());panel.addView(volume,new LinearLayout.LayoutParams(-1,dp(48)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar v){dragging=true;}
            public void onStopTrackingTouch(SeekBar v){dragging=false;update();}
            public void onProgressChanged(SeekBar v,int value,boolean user){if(user)try{audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);label(audio.getStreamVolume(AudioManager.STREAM_MUSIC));}catch(SecurityException e){Ui.message(QuickSettingsActivity.this,e.getMessage());}}
        });
        panel.addView(Ui.text(this,getString(R.string.quick_volume_note),12,Ui.MUTED));setContentView(root);update();
    }
    private void label(int value){volumeLabel.setText(getString(R.string.quick_media_volume)+"  "+Math.round(100f*value/Math.max(1,volume.getMax()))+"%");}
    private void update(){
        if(network==null)return;
        if(getSystemService(android.hardware.display.DisplayManager.class).getDisplay(displayId)==null||!Launches.prefs(this).getBoolean("enabled",false)){finish();return;}
        if(!dragging){int value=audio.getStreamVolume(AudioManager.STREAM_MUSIC);volume.setProgress(value);label(value);}
        try{
            ConnectivityManager cm=getSystemService(ConnectivityManager.class);boolean wifi=false;
            for(Network n:cm.getAllNetworks()){NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))wifi=true;}
            WifiManager wm=getApplicationContext().getSystemService(WifiManager.class);
            network.setText(getString(wifi?R.string.quick_wifi_connected:wm!=null&&wm.isWifiEnabled()?R.string.quick_wifi_disconnected:R.string.quick_wifi_off));
        }catch(RuntimeException e){network.setText(R.string.quick_wifi_unknown);}
    }
    private int dp(int n){return Ui.dp(this,n);}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);if(visible.get()==this)visible.clear();super.onDestroy();}
    @Override public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_ESCAPE&&e.getAction()==KeyEvent.ACTION_UP){finish();return true;}return super.dispatchKeyEvent(e);}
}
