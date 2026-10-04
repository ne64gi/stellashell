package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.search.SearchEngine;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import java.util.UUID;

/** UI destinations must not inherit the selected workspace's display-0 prohibition. */
final class WebSearchRoutingChecks {
    static void run(Instrumentation test,boolean realBrowser)throws Exception {
        Context actual=test.getTargetContext();String file="web_search_routing_"+UUID.randomUUID();
        ShellSettings settings=ShellSettings.isolated(actual,file);
        CaptureContext context=new CaptureContext(actual,settings);
        Throwable[] failure={null};
        try{
            test.runOnMainSync(()->{
                VirtualDisplay privateDisplay=null;
                try{
                    settings.setPrimaryMode(false);
                    SearchSettingsActivity.open(context,0);
                    check(context.attempts==1&&context.intent.getComponent().getClassName().equals(SearchSettingsActivity.class.getName()),
                            "Search settings rejected the phone when external workspace mode was selected");
                    check(context.options.getInt("android.activity.launchDisplayId",-1)==0,"Phone settings lost display 0");
                    check(WebSearchLauncher.open(context,0,SearchEngine.GOOGLE,"StellaShell & 日本語"),
                            "Web search rejected the phone when external workspace mode was selected");
                    check(context.attempts==2&&Intent.ACTION_VIEW.equals(context.intent.getAction())
                            &&context.options.getInt("android.activity.launchDisplayId",-1)==0,"Phone URL was not routed to display 0");
                    check(context.intent.getDataString().equals(SearchEngine.GOOGLE.url("StellaShell & 日本語")),"Phone URL encoding changed");
                    // The task/workspace safety boundary itself must remain strict.
                    boolean rejected=false;
                    try{Displays.require(context,0);}catch(IllegalArgumentException expected){rejected=true;}
                    check(rejected,"The existing workspace display-0 prohibition was weakened");
                    settings.setPrimaryMode(true);
                    check(WebSearchLauncher.open(context,0,SearchEngine.BING,"StellaShell"),"Phone search failed in primary mode");
                    int before=context.attempts;
                    check(!WebSearchLauncher.open(context,-1,SearchEngine.GOOGLE,"StellaShell"),"Missing display fell back to phone");
                    SearchSettingsActivity.open(context,Integer.MAX_VALUE);
                    check(context.attempts==before,"Disconnected settings display fell back to phone");
                    privateDisplay=actual.getSystemService(DisplayManager.class).createVirtualDisplay(
                            "StellaShell private search routing "+UUID.randomUUID(),320,240,160,null,0);
                    check(privateDisplay!=null,"Could not create a disposable private display");
                    int privateId=privateDisplay.getDisplay().getDisplayId();
                    check(!WebSearchLauncher.open(context,privateId,SearchEngine.GOOGLE,"StellaShell"),"Search accepted a private display");
                    SearchSettingsActivity.open(context,privateId);
                    check(context.attempts==before,"Private display was redirected to phone");
                }catch(Throwable error){failure[0]=error;}
                finally{if(privateDisplay!=null)privateDisplay.release();}
            });
            if(failure[0]!=null)throw new AssertionError(failure[0]);
            if(realBrowser){
                Instrumentation.ActivityMonitor monitor=test.addMonitor(SearchSettingsActivity.class.getName(),null,false);
                android.app.Activity activity=null;
                try{
                    test.runOnMainSync(()->SearchSettingsActivity.open(actual,0));
                    activity=test.waitForMonitorWithTimeout(monitor,5000);
                    check(activity!=null&&activity.getDisplay().getDisplayId()==0,"Real phone settings did not open on display 0");
                }finally{
                    if(activity!=null){android.app.Activity opened=activity;test.runOnMainSync(opened::finish);}
                    test.removeMonitor(monitor);
                }
                test.runOnMainSync(()->{
                    try{check(WebSearchLauncher.open(actual,0,SearchEngine.GOOGLE,"StellaShell"),"Real browser rejected phone search");}
                    catch(Throwable error){failure[0]=error;}
                });
                if(failure[0]!=null)throw new AssertionError(failure[0]);
            }
        }finally{settings.close();test.runOnMainSync(()->actual.deleteSharedPreferences(file));}
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static final class CaptureContext extends ContextWrapper implements ShellSettings.Provider {
        final ShellSettings settings;int attempts;Intent intent;Bundle options;
        CaptureContext(Context actual,ShellSettings settings){super(actual);this.settings=settings;}
        @Override public Context getApplicationContext(){return this;}
        @Override public ShellSettings shellSettings(){return settings;}
        @Override public void startActivity(Intent intent,Bundle options){attempts++;this.intent=new Intent(intent);this.options=new Bundle(options);}
    }
}
