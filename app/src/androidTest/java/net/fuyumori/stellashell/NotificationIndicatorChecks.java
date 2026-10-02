package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.util.Collections;
import java.util.Locale;

/** Synthetic presence only: never connects to, posts, or reads OS/personal notifications. */
final class NotificationIndicatorChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    /** The existing notification fixture invokes this on the main thread. */
    static void run(Instrumentation test){
        Context base=test.getTargetContext();
        for(Locale locale:new Locale[]{Locale.ENGLISH,Locale.JAPANESE}){
            Configuration config=new Configuration(base.getResources().getConfiguration());config.setLocale(locale);
            Context context=new ContextThemeWrapper(base.createConfigurationContext(config),R.style.AppTheme);
            rendering(context);
        }
    }
    private static void rendering(Context context){
        int[] actions=new int[2];
        HubNavigation navigation=new HubNavigation(context,()->actions[0]++,()->actions[1]++);
        Button widgets=(Button)navigation.getChildAt(0);
        ViewGroup slot=(ViewGroup)navigation.getChildAt(1);
        Button notifications=(Button)slot.getChildAt(0);
        View dot=slot.getChildAt(1);
        String plain=context.getString(R.string.hub_notifications),present=context.getString(R.string.hub_notifications_present);
        check(dot.getVisibility()==View.GONE&&plain.contentEquals(notifications.getContentDescription()),"default presence/label");
        check(dot.getImportantForAccessibility()==View.IMPORTANT_FOR_ACCESSIBILITY_NO&&!dot.isFocusable()&&!dot.isClickable(),"dot creates a duplicate accessible control");
        navigation.setNotificationPresence(false,true,Collections.emptyList());
        check(dot.getVisibility()==View.GONE&&plain.contentEquals(notifications.getContentDescription()),"empty snapshot shows dot");
        navigation.setNotificationPresence(false,true,Collections.singletonList(new Object()));
        check(dot.getVisibility()==View.VISIBLE&&present.contentEquals(notifications.getContentDescription()),"present snapshot lacks dot/accessible presence label");
        check(!present.toLowerCase(Locale.ROOT).contains("unread")&&!present.contains("未読"),"presence label implies unread state");
        navigation.setNotificationPresence(false,true,Collections.nCopies(200,new Object()));
        check(dot.getVisibility()==View.VISIBLE&&present.contentEquals(notifications.getContentDescription()),"presence became a count");
        navigation.select(true);
        navigation.setNotificationPresence(true,true,Collections.singletonList(new Object()));
        check(dot.getVisibility()==View.GONE&&plain.contentEquals(notifications.getContentDescription()),"lock leaks presence");
        check(notifications.isSelected()&&!widgets.isSelected(),"presence update changed selected tab");
        navigation.setNotificationPresence(false,false,Collections.singletonList(new Object()));
        check(dot.getVisibility()==View.GONE&&plain.contentEquals(notifications.getContentDescription()),"missing listener/access leaks presence");
        navigation.setNotificationPresence(false,true,null);
        check(dot.getVisibility()==View.GONE,"missing snapshot shows dot");
        navigation.setNotificationPresence(false,true,Collections.singletonList(new Object()));
        navigation.setNotificationPresence(false,true,Collections.emptyList());
        check(dot.getVisibility()==View.GONE&&plain.contentEquals(notifications.getContentDescription()),"removed notification leaves stale dot/label");
        check(widgets.performClick()&&notifications.performClick()&&actions[0]==1&&actions[1]==1,"tab actions changed");
    }
}
