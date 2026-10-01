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
                .putExtra("display",getDisplay().getDisplayId()));
        new Handler(Looper.getMainLooper()).postDelayed(this::finishAndRemoveTask,300);
    }
}
