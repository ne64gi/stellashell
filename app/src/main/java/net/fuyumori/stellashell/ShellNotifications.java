package net.fuyumori.stellashell;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

/** Live OS notifications only: no notification database, network, or content logging. */
public final class ShellNotifications extends NotificationListenerService {
    private static volatile ShellNotifications connected;
    private static final Set<Runnable> observers=new CopyOnWriteArraySet<>();
    private static final Handler main=new Handler(Looper.getMainLooper());
    static void observe(Runnable observer){observers.add(observer);}
    static void unobserve(Runnable observer){observers.remove(observer);}
    private static void changed(){main.post(()->{for(Runnable observer:observers)observer.run();});}
    static boolean ready(){return connected!=null;}
    static boolean locked(Context c){KeyguardManager keyguard=c.getSystemService(KeyguardManager.class);return keyguard!=null&&keyguard.isDeviceLocked();}
    static List<StatusBarNotification> current(Context context){
        ShellNotifications service=connected;
        if(service==null||locked(context))return Collections.emptyList();
        try{
            StatusBarNotification[] active=service.getActiveNotifications();
            List<StatusBarNotification> result=new ArrayList<>();
            if(active!=null)for(StatusBarNotification item:active)
                if(!item.getPackageName().equals(context.getPackageName()))result.add(item);
            result.sort((a,b)->Long.compare(b.getPostTime(),a.getPostTime()));return result;
        }catch(SecurityException|IllegalStateException e){return Collections.emptyList();}
    }
    static boolean dismiss(Context context,String key){
        ShellNotifications service=connected;if(service==null||locked(context))return false;
        try{for(StatusBarNotification item:current(context))if(item.getKey().equals(key)&&item.isClearable()){service.cancelNotification(key);return true;}}
        catch(SecurityException|IllegalStateException ignored){}return false;
    }
    @Override public void onListenerConnected(){connected=this;changed();}
    @Override public void onListenerDisconnected(){if(connected==this)connected=null;changed();}
    @Override public void onNotificationPosted(StatusBarNotification item){changed();}
    @Override public void onNotificationRemoved(StatusBarNotification item){changed();}
    @Override public void onDestroy(){if(connected==this)connected=null;changed();super.onDestroy();}
}
