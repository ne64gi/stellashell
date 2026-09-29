package net.fuyumori.stellashell;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.view.Display;

/** Routes RemoteViews IntentSender launches without replacing provider click actions. */
final class WidgetLaunchContext extends ContextWrapper {
    static final String PRIMARY="widget_launch_primary";
    WidgetLaunchContext(Context base){super(base);}
    @Override public void startIntentSender(IntentSender sender,Intent fill,int mask,int values,int extra,Bundle options)throws IntentSender.SendIntentException {
        boolean primary=Launches.prefs(this).getBoolean(PRIMARY,true);
        Bundle routed=options==null?new Bundle():new Bundle(options);
        Bundle defaults=ActivityOptions.makeBasic().toBundle();
        Bundle destination=ActivityOptions.makeBasic().setLaunchDisplayId(primary?Display.DEFAULT_DISPLAY:getDisplay().getDisplayId()).toBundle();
        // Merge only the display option, not default values that would overwrite provider flags.
        for(String key:new java.util.HashSet<>(destination.keySet()))
            if(java.util.Objects.equals(destination.get(key),defaults.get(key)))destination.remove(key);
        routed.putAll(destination);
        // ActivityOptions do not turn broadcast/service PendingIntents into activities.
        // Keep the original sender, fill-in intent, launch flags and BAL options intact.
        super.startIntentSender(sender,fill,mask,values,extra,routed);
    }
}
