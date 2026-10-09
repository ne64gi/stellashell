package net.fuyumori.stellashell;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.view.accessibility.AccessibilityManager;

/** Register the default HOME's durable All Apps action, not a global key listener or input lease. */
final class LauncherSystemAction {
    static String register(Context context,PendingIntent pending)throws Exception {
        if(context==null||pending==null)throw new IllegalArgumentException("Start action unavailable");
        String own=DesktopBridgeService.class.getPackage().getName();
        if(!own.equals(pending.getCreatorPackage()))throw new IllegalArgumentException("Start action creator mismatch");
        ResolveInfo home=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME),0);
        if(home==null||home.activityInfo==null||!own.equals(home.activityInfo.packageName))return "OK: other HOME";
        Icon icon=Icon.createWithResource(own,context.getPackageManager().getApplicationInfo(own,0).icon);
        RemoteAction action=new RemoteAction(icon,"StellaShell","StellaShell",pending);
        AccessibilityManager manager=context.getSystemService(AccessibilityManager.class);
        if(manager==null)throw new IllegalStateException("Accessibility manager unavailable");
        AccessibilityManager.class.getMethod("registerSystemAction",RemoteAction.class,int.class)
                .invoke(manager,action,14); // GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS, used by Android's Meta policy.
        // PendingIntent belongs to HOME, remains valid across bridge death, and follows current
        // HOME if the role changes. Do not unregister a newer launcher's action on bridge teardown.
        return "OK";
    }
    private LauncherSystemAction(){}
}
