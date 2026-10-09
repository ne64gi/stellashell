package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.fuyumori.stellashell.feature.launch.PublicLauncher;

/** No bridge calls or adopted permissions. Launches only the auto-closing test APK activity. */
final class ExternalAppLaunchChecks {
    private ExternalAppLaunchChecks(){}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void main(Instrumentation test,Runnable action){
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    static void run(Instrumentation test,int externalId)throws Exception{
        Context base=test.getTargetContext();String fixturePackage=test.getContext().getPackageName();
        String component=fixturePackage+"/net.fuyumori.stellashell.LauncherEntrySecond";
        String prefsName="external_app_check_"+UUID.randomUUID();String nonce=UUID.randomUUID().toString();
        Intent[] captured={null};Bundle[] options={null};boolean[] nativeLaunch={false};int[] submissions={0};
        class CapturedContext extends ContextThemeWrapper {
            CapturedContext(){super(base,Appearance.theme());}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefsName,mode);}
            @Override public void startActivity(Intent intent,Bundle bundle){
                captured[0]=new Intent(intent);options[0]=new Bundle(bundle);submissions[0]++;
                if(nativeLaunch[0])base.startActivity(new Intent(intent).putExtra("nonce",nonce),bundle);
            }
        }
        Context context=new CapturedContext();PublicLauncher launcher=new PublicLauncher(context);
        require(android.os.Process.myUid()!=2000,"Fixture must run as the application UID");
        require(base.checkSelfPermission("android.permission.MANAGE_ACTIVITY_TASKS")!=PackageManager.PERMISSION_GRANTED,
                "This check must not use privileged task-management identity");
        CountDownLatch launched=new CountDownLatch(1),closed=new CountDownLatch(1);
        AtomicReference<Intent> actual=new AtomicReference<>();
        BroadcastReceiver receiver=new BroadcastReceiver(){
            @Override public void onReceive(Context ignored,Intent intent){
                if(!nonce.equals(intent.getStringExtra("nonce")))return;
                if("net.fuyumori.stellashell.TEST_HOME_LAUNCHED".equals(intent.getAction())){actual.set(new Intent(intent));launched.countDown();}
                if("net.fuyumori.stellashell.TEST_HOME_CLOSED".equals(intent.getAction()))closed.countDown();
            }
        };
        IntentFilter filter=new IntentFilter("net.fuyumori.stellashell.TEST_HOME_LAUNCHED");
        filter.addAction("net.fuyumori.stellashell.TEST_HOME_CLOSED");
        registerFixtureReceiver(base,receiver,filter);
        try{
            for(int rejected:new int[]{-1,0,Integer.MAX_VALUE}){
                boolean failed=false;try{launcher.launchExternalFullscreen(component,rejected);}
                catch(IllegalArgumentException expected){failed=true;}
                require(failed&&submissions[0]==0,"Invalid external target fell back to the phone");
            }
            if(externalId<=0){
                require(Displays.available(context).isEmpty(),"Guard-only scope requires no external displays");
                main(test,()->{
                    Menu menu=new PopupMenu(context,new View(context)).getMenu();
                    ExternalAppLaunch.addToMenu(context,menu,component,()->{});
                    require(menu.findItem(ExternalAppLaunch.MENU_ID)==null,"Disconnected menu exposes an external launch");
                });
                return;
            }
            Display display=base.getSystemService(DisplayManager.class).getDisplay(externalId);
            require(display!=null&&display.isValid()&&(display.getFlags()&Display.FLAG_PRIVATE)==0,"Expected public external screen is absent");
            main(test,()->launcher.launchExternalFullscreen(component,externalId));
            require(captured[0]!=null&&captured[0].getComponent().equals(ComponentName.unflattenFromString(component)),"Explicit launcher alias was replaced");
            require(options[0].getInt("android.activity.launchDisplayId",-1)==externalId
                    &&options[0].getInt("android.activity.windowingMode",-1)==1,"External fullscreen request was lost");
            require((captured[0].getFlags()&(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                    ==(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),"Ordinary launcher flags were lost");
            main(test,()->launcher.launchExternalFullscreen(fixturePackage+"/net.fuyumori.stellashell.LauncherEntryPrivate",externalId));
            require(!captured[0].getComponent().getClassName().endsWith("LauncherEntryPrivate"),"Private Activity was launched instead of a public alias");
            main(test,()->{
                Menu menu=new PopupMenu(context,new View(context)).getMenu();int[] dismissed={0};boolean[] live={false};
                ExternalAppLaunch.addToMenu(context,menu,component,()->dismissed[0]++,()->live[0]);
                MenuItem entry=menu.findItem(ExternalAppLaunch.MENU_ID);
                require(entry!=null,"Connected external launch item is absent");
                // Every submenu child has its own click listener; test one-display direct action here.
                if(!entry.hasSubMenu()){
                    int before=submissions[0];menu.performIdentifierAction(ExternalAppLaunch.MENU_ID,0);
                    require(before==submissions[0]&&dismissed[0]==0,"Detached popup launched an app");
                    live[0]=true;menu.performIdentifierAction(ExternalAppLaunch.MENU_ID,0);
                    require(submissions[0]==before+1&&dismissed[0]==1,"Visible menu failed to submit one launch");
                    require(captured[0].getComponent().getClassName().equals(ExternalAppActivity.class.getName())
                            &&options[0].getInt("android.activity.launchDisplayId",-1)==0
                            &&options[0].getInt("android.activity.windowingMode",-1)==1
                            &&captured[0].getIntExtra(ExternalAppActivity.DISPLAY,-1)==externalId,
                            "Menu did not open the phone waiting surface with the explicit external target");
                    require(Launches.recents(context).isEmpty(),"Menu recorded an app before the waiting surface submitted it");
                }
            });
            nativeLaunch[0]=true;
            main(test,()->launcher.launchExternalFullscreen(component,externalId));
            require(launched.await(10,TimeUnit.SECONDS),"Unprivileged external fixture did not start");
            Intent result=actual.get();
            require(result.getIntExtra("display",-1)==externalId,"Android started the fixture on a different display");
            require(!result.getBooleanExtra("multi_window",true),"Android did not honor fullscreen");
            require(closed.await(10,TimeUnit.SECONDS),"Owned launch fixture did not close");
        }finally{
            base.unregisterReceiver(receiver);base.deleteSharedPreferences(prefsName);
        }
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // API 30-32: nonce-scoped, cross-package disposable fixture; API 33+ uses the explicit flag.
    private static void registerFixtureReceiver(Context context,BroadcastReceiver receiver,IntentFilter filter){
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver,filter);
    }
}
