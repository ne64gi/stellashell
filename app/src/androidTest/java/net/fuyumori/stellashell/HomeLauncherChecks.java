package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.*;

/** No default-role mutation, no Shizuku server shutdown. Disconnect only this test process. */
final class HomeLauncherChecks {
    private final Instrumentation test;private final Context context;
    HomeLauncherChecks(Instrumentation test){this.test=test;context=test.getTargetContext();}
    private void main(Runnable r){test.runOnMainSync(r);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Field field(Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}
    void run()throws Exception{
        SharedPreferences prefs=Launches.prefs(context);Map<String,?> before=prefs.getAll();
        boolean defaultBefore=HomeRegistration.selected(context);
        ComponentName legacy=new ComponentName(context,DesktopActivity.class),homeComponent=new ComponentName(context,HomeActivity.class);
        int legacyState=context.getPackageManager().getComponentEnabledSetting(legacy);
        Bridge bridge=Bridge.get(context);Field serviceField=field(Bridge.class,"service"),bindingField=field(Bridge.class,"binding");
        main(bridge::connect);
        long bindUntil=SystemClock.uptimeMillis()+15000;
        while(!bridge.ready()&&SystemClock.uptimeMillis()<bindUntil)Thread.sleep(100);
        test.waitForIdleSync();
        Object service=serviceField.get(bridge);boolean binding=bindingField.getBoolean(bridge);
        HomeActivity home=null;HomeAppsDialog drawer=null;
        try{
            main(()->DockService.stop(context,false));test.waitForIdleSync();
            // Even stale enabled=true after a restart must permit normal HOME with no bridge.
            main(()->{
                try{serviceField.set(bridge,null);bindingField.setBoolean(bridge,true);}catch(Exception e){throw new RuntimeException(e);}
                prefs.edit().putBoolean("enabled",true).putBoolean("primary_mode",true).remove("workspace_display").commit();
            });
            Intent homeIntent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(homeComponent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean candidate=false;
            for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0))
                if(info.activityInfo!=null&&info.activityInfo.name.equals(HomeActivity.class.getName()))candidate=true;
            require(candidate,"Always-enabled HOME not discoverable after desktop stop");
            home=(HomeActivity)test.startActivitySync(homeIntent);HomeActivity shown=home;test.waitForIdleSync();
            require(!home.isFinishing(),"HOME finished without bridge");require(!bridge.ready(),"Disconnect fixture was not active");
            require(Launches.basicHome(home,0),"Unavailable bridge did not select basic HOME");
            main(shown::openApps);drawer=(HomeAppsDialog)field(HomeActivity.class,"apps").get(home);
            HomeAppsDialog opened=drawer;long until=SystemClock.uptimeMillis()+10000;
            while(SystemClock.uptimeMillis()<until&&!field(HomeAppsDialog.class,"loaded").getBoolean(drawer))Thread.sleep(100);
            require(field(HomeAppsDialog.class,"loaded").getBoolean(drawer),"App drawer did not load without bridge");
            main(()->require(opened.isShowing()&&((LinearLayout)read(opened,"rows")).getChildCount()>0,"Activity-owned drawer is empty"));
            main(()->{((EditText)read(opened,"search")).setText("zzzz-no-such-launcher-fixture");require(((LinearLayout)read(opened,"rows")).getChildCount()==0,"Search failed");opened.dismiss();});
            // Intercept only the Android launch, checking that no bridge/profile/overlay path is used.
            Intent[] sent={null};Bundle[] options={null};
            Context capture=new ContextWrapper(home){@Override public void startActivity(Intent intent,Bundle bundle){sent[0]=intent;options[0]=bundle;}};
            main(()->Launches.normalApp(capture,"net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishPrimaryActivity"));
            require(sent[0]!=null&&sent[0].hasCategory(Intent.CATEGORY_LAUNCHER),"Normal app intent missing");
            require(options[0]!=null,"Normal launch omitted Activity options");
            java.util.concurrent.CountDownLatch launched=new java.util.concurrent.CountDownLatch(1);int[] launchDisplay={-1};
            BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){launchDisplay[0]=intent.getIntExtra("display",-1);launched.countDown();}};
            IntentFilter filter=new IntentFilter("net.fuyumori.stellashell.TEST_HOME_LAUNCHED");
            registerProbeReceiver(receiver,filter);
            try{
                main(()->Launches.app(shown,"net.fuyumori.stellashell.test/net.fuyumori.stellashell.HomeLaunchProbeActivity",0));
                require(launched.await(5,java.util.concurrent.TimeUnit.SECONDS)&&launchDisplay[0]==0,"Basic HOME could not launch an actual app on display 0");
                Thread.sleep(500);
            }finally{context.unregisterReceiver(receiver);}
            main(()->DockService.stop(shown,false));test.waitForIdleSync();
            require(!home.isFinishing()&&!home.isDestroyed(),"Extension stop destroyed HOME");
            require(context.getPackageManager().getActivityInfo(homeComponent,0).enabled,"Extension stop disabled HOME");
            main(shown::openApps);require(((HomeAppsDialog)field(HomeActivity.class,"apps").get(home)).isShowing(),"Apps inaccessible after stop");
            require(defaultBefore==HomeRegistration.selected(context),"Test changed default HOME");
        }finally{
            HomeActivity closing=home;HomeAppsDialog closingDrawer=drawer;
            main(()->{
                if(closingDrawer!=null)closingDrawer.dismiss();if(closing!=null)closing.finish();
                try{serviceField.set(bridge,service);bindingField.setBoolean(bridge,binding);}catch(Exception e){throw new RuntimeException(e);}
                SharedPreferences.Editor edit=prefs.edit();
                for(String key:new String[]{"enabled","primary_mode","workspace_display","active_display","recent","last_error"}){
                    Object old=before.get(key);if(old instanceof Boolean)edit.putBoolean(key,(Boolean)old);else if(old instanceof Integer)edit.putInt(key,(Integer)old);else if(old instanceof String)edit.putString(key,(String)old);else edit.remove(key);
                }edit.commit();
                context.getPackageManager().setComponentEnabledSetting(legacy,legacyState,PackageManager.DONT_KILL_APP);
            });
        }
    }
    private static Object read(Object target,String name){try{return field(target.getClass(),name).get(target);}catch(Exception e){throw new RuntimeException(e);}}
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // API 30-32 branch intentionally receives only the disposable cross-package test probe; flags are used on 33+.
    private void registerProbeReceiver(BroadcastReceiver receiver,IntentFilter filter){
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver,filter);
    }
}
