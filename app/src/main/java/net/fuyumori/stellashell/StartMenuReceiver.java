package net.fuyumori.stellashell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit shell-only shortcut from the desktop session helper; never starts a session. */
public final class StartMenuReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent!=null&&StartMenuRequests.SHELL_TOGGLE.equals(intent.getAction()))StartMenuRequests.route(intent);
    }
}
