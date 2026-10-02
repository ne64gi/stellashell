package net.fuyumori.stellashell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit shell-only shortcut from the desktop session helper; never starts a session. */
public final class StartMenuReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if((context.getPackageName()+".TOGGLE_START").equals(intent.getAction()))DockService.toggleStart(-1);
    }
}
