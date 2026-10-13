package net.fuyumori.stellashell;

import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import java.util.*;
import java.util.function.Function;

/** Visible-surface subscription. Only package counts are retained, never notification content. */
final class NotificationApps implements AutoCloseable {
    private final Context context;
    private final Runnable changed;
    private Map<String,Integer> counts=Collections.emptyMap();
    private boolean started,screenOff;
    private final Runnable refresh=()->refresh(false);
    private final BroadcastReceiver screen=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent event){
            screenOff=Intent.ACTION_SCREEN_OFF.equals(event.getAction());refresh(false);
        }
    };
    NotificationApps(Context context,Runnable changed){this.context=context;this.changed=changed;}
    void start(){
        if(started)return;started=true;
        PowerManager power=context.getSystemService(PowerManager.class);screenOff=power!=null&&!power.isInteractive();
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(screen,filter,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(screen,filter);
        ShellNotifications.observe(refresh);refresh(true);
    }
    private void refresh(boolean force){
        if(!started)return;
        Map<String,Integer> next=screenOff||ShellNotifications.locked(context)?Collections.emptyMap():ShellNotifications.counts(context);
        if(screenOff||ShellNotifications.locked(context)||!ShellNotifications.ready(context))next=Collections.emptyMap();
        if(force||!counts.equals(next)){counts=next;changed.run();}
    }
    static Map<String,Integer> collect(List<StatusBarNotification> snapshot,UserHandle user){
        Map<String,Integer> out=new HashMap<>();
        for(NotificationGroups.Group group:NotificationGroups.collect(snapshot))if(user.equals(group.user))out.put(group.packageName,group.items.size());
        return Collections.unmodifiableMap(out);
    }
    int count(String componentOrPackage){
        ComponentName name=ComponentName.unflattenFromString(componentOrPackage);
        return counts.getOrDefault(name==null?componentOrPackage:name.getPackageName(),0);
    }
    <T> List<T> prioritize(List<T> source,Function<T,String> identity){
        List<T> result=new ArrayList<>(source);
        result.sort((a,b)->Boolean.compare(count(identity.apply(b))>0,count(identity.apply(a))>0));
        return result;
    }
    CharSequence label(CharSequence label,String identity){int n=count(identity);return n==0?label:label+", "+context.getResources().getQuantityString(R.plurals.app_notification_count,n,n);}
    @Override public void close(){
        if(!started)return;started=false;ShellNotifications.unobserve(refresh);context.unregisterReceiver(screen);counts=Collections.emptyMap();
    }
}
