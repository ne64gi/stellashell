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
        HomeActivity home=null;AppMenu drawer=null;
        try{
            main(()->DockService.stop(context,false));test.waitForIdleSync();
            // Even stale enabled=true after a restart must permit normal HOME with no bridge.
            main(()->{
                try{serviceField.set(bridge,null);bindingField.setBoolean(bridge,true);}catch(Exception e){throw new RuntimeException(e);}
                prefs.edit().putBoolean("enabled",true).putBoolean("primary_mode",true).putBoolean("phone_window_management",false).putBoolean("phone_sidebar",true).remove("workspace_display").commit();
            });
            Intent homeIntent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(homeComponent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean candidate=false;
            for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0))
                if(info.activityInfo!=null&&info.activityInfo.name.equals(HomeActivity.class.getName()))candidate=true;
            require(candidate,"Always-enabled HOME not discoverable after desktop stop");
            home=(HomeActivity)test.startActivitySync(homeIntent);HomeActivity shown=home;test.waitForIdleSync();
            require(!home.isFinishing(),"HOME finished without bridge");require(!bridge.ready(),"Disconnect fixture was not active");
            require(Launches.basicHome(home,0),"Unavailable bridge did not select basic HOME");
            long sidebarUntil=SystemClock.uptimeMillis()+5000;
            while(!DockService.running()&&SystemClock.uptimeMillis()<sidebarUntil)Thread.sleep(100);
            require(DockService.running(),"Phone sidebar did not start without Shizuku");
            Thread.sleep(350);
            try(android.os.ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand("dumpsys window windows");java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){
                String windows=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                require(windows.contains("StellaShell edge"),"Phone edge overlays absent");
            }
            Object dockInstance=field(DockService.class,"instance").get(null);
            @SuppressWarnings("unchecked") java.util.List<View> handles=(java.util.List<View>)field(DockService.class,"edgeHandles").get(dockInstance);
            main(()->{
                prefs.edit().putBoolean("phone_sidebar_over_apps",false).putInt("sidebar_height",0).commit();
                DockService.homeVisible(true);
                require(handles.size()==2&&handles.get(0).getVisibility()==View.VISIBLE,"HOME-only sidebar absent on HOME");
            });test.waitForIdleSync();
            int top=((android.view.WindowManager.LayoutParams)handles.get(0).getLayoutParams()).y;
            main(()->prefs.edit().putInt("sidebar_height",100).commit());test.waitForIdleSync();
            require(((android.view.WindowManager.LayoutParams)handles.get(0).getLayoutParams()).y>top,"Sidebar height did not move");
            main(()->{
                DockService.homeVisible(false);
                require(handles.get(0).getVisibility()==View.GONE,"HOME-only sidebar remained over app");
                prefs.edit().putBoolean("phone_sidebar_over_apps",true).commit();
            });test.waitForIdleSync();
            main(()->{
                require(handles.get(0).getVisibility()==View.VISIBLE,"Over-app toggle did not restore sidebar");
                DockService.homeVisible(true);
            });
            main(shown::openApps);drawer=(AppMenu)field(HomeActivity.class,"apps").get(home);
            AppMenu opened=drawer;long until=SystemClock.uptimeMillis()+10000;
            while(SystemClock.uptimeMillis()<until&&!field(AppMenu.class,"loaded").getBoolean(drawer))Thread.sleep(100);
            require(field(AppMenu.class,"loaded").getBoolean(drawer),"App drawer did not load without bridge");
            main(()->require(opened.isOpen()&&((LinearLayout)read(opened,"content")).getChildCount()>0,"Activity-owned drawer is empty"));
            main(()->{((EditText)read(opened,"search")).setText("zzzz-no-such-launcher-fixture");require(((EditText)read(opened,"search")).getText().toString().equals("zzzz-no-such-launcher-fixture"),"Search input lost");opened.close();});
            // Intercept only the Android launch, checking that no bridge/profile/overlay path is used.
            Intent[] sent={null};Bundle[] options={null};
            Context capture=new ContextWrapper(home){@Override public void startActivity(Intent intent,Bundle bundle){sent[0]=intent;options[0]=bundle;}};
            main(()->Launches.normalApp(capture,"net.fuyumori.stellashell.test/net.fuyumori.stellashell.PolishPrimaryActivity"));
            require(sent[0]!=null&&sent[0].hasCategory(Intent.CATEGORY_LAUNCHER),"Normal app intent missing");
            require(options[0]!=null,"Normal launch omitted Activity options");
            java.util.concurrent.CountDownLatch launched=new java.util.concurrent.CountDownLatch(1);int[] launchDisplay={-1};boolean[] multiWindow={true};
            BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){launchDisplay[0]=intent.getIntExtra("display",-1);multiWindow[0]=intent.getBooleanExtra("multi_window",true);launched.countDown();}};
            IntentFilter filter=new IntentFilter("net.fuyumori.stellashell.TEST_HOME_LAUNCHED");
            registerProbeReceiver(receiver,filter);
            try{
                main(()->Launches.app(shown,"net.fuyumori.stellashell.test/net.fuyumori.stellashell.HomeLaunchProbeActivity",0));
                require(launched.await(5,java.util.concurrent.TimeUnit.SECONDS)&&launchDisplay[0]==0,"Basic HOME could not launch an actual app on display 0");
                require(!multiWindow[0],"Normal phone app was forced into a window");
                require(DockService.running(),"Sidebar stopped when the app launched");
                Thread.sleep(500);
            }finally{context.unregisterReceiver(receiver);}
            main(()->DockService.stop(shown,false));test.waitForIdleSync();
            require(!home.isFinishing()&&!home.isDestroyed(),"Extension stop destroyed HOME");
            require(context.getPackageManager().getActivityInfo(homeComponent,0).enabled,"Extension stop disabled HOME");
            main(shown::openApps);require(((AppMenu)field(HomeActivity.class,"apps").get(home)).isOpen(),"Apps inaccessible after stop");
            require(defaultBefore==HomeRegistration.selected(context),"Test changed default HOME");
        }finally{
            HomeActivity closing=home;AppMenu closingDrawer=drawer;
            main(()->{
                if(closingDrawer!=null)closingDrawer.close();if(closing!=null)closing.finish();
                try{serviceField.set(bridge,service);bindingField.setBoolean(bridge,binding);}catch(Exception e){throw new RuntimeException(e);}
                SharedPreferences.Editor edit=prefs.edit();
                for(String key:new String[]{"enabled","primary_mode","workspace_display","active_display","preferred_display","compact_workspace","workspace_auto","phone_sidebar","phone_sidebar_over_apps","sidebar_height","phone_window_management","recent","last_error"}){
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
