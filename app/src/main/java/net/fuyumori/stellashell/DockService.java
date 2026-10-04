package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.os.*;

/** Android foreground-service entry point; domain state belongs to its owners. */
public final class DockService extends Service {
    private ShellController controller;
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("desktop",getString(R.string.ui_external_desktop),NotificationManager.IMPORTANCE_LOW));
        startForeground(41,notification(getString(R.string.ui_waiting_for_a_display)));
        controller=new ShellController(this,new ShellController.Host(){
            public void status(int message){getSystemService(NotificationManager.class).notify(41,notification(getString(message)));}
            public void stop(){stopSelf();}
        });
    }
    private Notification notification(String message){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,SetupActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,DockService.class).setAction(ShellRuntime.STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent desktop=PendingIntent.getService(this,2,new Intent(this,DockService.class).setAction("net.fuyumori.stellashell.SHOW_DESKTOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"desktop").setSmallIcon(R.drawable.ic_desktop).setContentTitle("StellaShell").setContentText(message).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,getString(R.string.ui_back_to_desktop),desktop).build())
            .addAction(new Notification.Action.Builder(null,getString(R.string.ui_stop),stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){controller.start(intent);return ShellRuntime.enabled(this)?START_STICKY:START_NOT_STICKY;}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(controller!=null)controller.configurationChanged();}
    @Override public void onDestroy(){if(controller!=null){controller.close();controller=null;}super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
