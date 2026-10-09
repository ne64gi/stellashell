package net.fuyumori.stellashell;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.widget.TextView;

/** Disposable normal-launch target; no personal app or screen is inspected. */
public final class HomeLaunchProbeActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView label=new TextView(this);label.setText("StellaShell HOME launch check");setContentView(label);
        sendBroadcast(new Intent("net.fuyumori.stellashell.TEST_HOME_LAUNCHED").setPackage("net.fuyumori.stellashell")
                .putExtra("display",getDisplay().getDisplayId()).putExtra("multi_window",isInMultiWindowMode())
                .putExtra("nonce",getIntent().getStringExtra("nonce")));
        new Handler(Looper.getMainLooper()).postDelayed(this::finishAndRemoveTask,300);
    }
    @Override public void onDestroy(){
        String nonce=getIntent().getStringExtra("nonce");
        if(nonce!=null)sendBroadcast(new Intent("net.fuyumori.stellashell.TEST_HOME_CLOSED")
                .setPackage("net.fuyumori.stellashell").putExtra("nonce",nonce));
        super.onDestroy();
    }
}
