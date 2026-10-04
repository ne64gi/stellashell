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
    void run(int externalDisplay)throws Exception{
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
            main(()->{
                // An already-resumed HOME must not restart the service while this
                // fixture is waiting for the previous service's onDestroy.
                prefs.edit().putBoolean("phone_sidebar",false).commit();
                ShellRuntime.stop(context,false);
            });
            awaitState("Previous ShellRuntime did not stop",()->ShellRuntime.running()?"runtime still running":null);
            // Even stale enabled=true after a restart must permit normal HOME with no bridge.
            main(()->{
                try{serviceField.set(bridge,null);bindingField.setBoolean(bridge,true);}catch(Exception e){throw new RuntimeException(e);}
                prefs.edit().putBoolean("enabled",true).putBoolean("primary_mode",true).putBoolean("phone_window_management",false).putBoolean("phone_sidebar",true).putBoolean("phone_taskbar",false).putInt("preferred_display",0).putBoolean("workspace_auto",false).remove("workspace_display").commit();
            });
            // startActivitySync must create an instance, not deliver onNewIntent to a
            // HOME that was already open before instrumentation began.
            Intent homeIntent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(homeComponent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            boolean candidate=false;
            for(android.content.pm.ResolveInfo info:context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0))
                if(info.activityInfo!=null&&info.activityInfo.name.equals(HomeActivity.class.getName()))candidate=true;
            require(candidate,"Always-enabled HOME not discoverable after desktop stop");
            System.out.println("HOME check: launching");home=(HomeActivity)test.startActivitySync(homeIntent);System.out.println("HOME check: launched");HomeActivity shown=home;test.waitForIdleSync();
            ShellRuntime.HomeVisibilityLease homeLease=ShellFixtureAccess.homeVisibility(shown);
            require(homeLease!=null,"HOME did not acquire its visibility lease");
            require(!home.isFinishing(),"HOME finished without bridge");require(!bridge.ready(),"Disconnect fixture was not active");
            main(()->shown.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            require(Launches.basicHome(home,0),"Unavailable bridge did not select basic HOME");
            awaitPhoneSidebar();
            System.out.println("HOME check: sidebar attached");
            checkStartRequestCache();
            PhoneSidebar sidebar=ShellFixtureAccess.sidebar();
            require(sidebar!=null,"Phone navigation owner did not attach a Sidebar");
            @SuppressWarnings("unchecked") java.util.List<View> handles=sidebar.handles;
            checkOverlayTaskPopup(handles.get(0));
            main(()->{
                prefs.edit().putBoolean("phone_sidebar_over_apps",false).putInt("sidebar_height",0).commit();
                homeLease.visible(true);
                require(handles.size()==2&&handles.get(0).getVisibility()==View.VISIBLE,"HOME-only sidebar absent on HOME");
            });test.waitForIdleSync();
            require(!containsClock((View)read(sidebar,"panel")),"Main sidebar still contains a clock");
            prefs.edit().putBoolean("phone_window_management",true).commit();
            require(Launches.basicHome(shown,0),"Legacy preference still changes ordinary launch to floating");
            int top=((android.view.WindowManager.LayoutParams)handles.get(0).getLayoutParams()).y;
            main(()->prefs.edit().putInt("sidebar_height",100).commit());test.waitForIdleSync();
            require(((android.view.WindowManager.LayoutParams)handles.get(0).getLayoutParams()).y>top,"Sidebar height did not move");
            main(()->{
                homeLease.visible(false);
                require(handles.get(0).getVisibility()==View.GONE,"HOME-only sidebar remained over app");
                prefs.edit().putBoolean("phone_sidebar_over_apps",true).commit();
            });test.waitForIdleSync();
            main(()->{
                require(handles.get(0).getVisibility()==View.VISIBLE,"Over-app toggle did not restore sidebar");
                homeLease.visible(true);
            });
            main(shown::openApps);drawer=(AppMenu)field(HomeActivity.class,"apps").get(home);
            AppMenu opened=drawer;long until=SystemClock.uptimeMillis()+10000;
            while(SystemClock.uptimeMillis()<until&&!field(AppMenu.class,"loaded").getBoolean(drawer))Thread.sleep(100);
            require(field(AppMenu.class,"loaded").getBoolean(drawer),"App drawer did not load without bridge");
            main(()->require(opened.isOpen()&&((LinearLayout)read(opened,"content")).getChildCount()>0,"Activity-owned drawer is empty"));
            main(()->{((EditText)read(opened,"search")).setText("zzzz-no-such-launcher-fixture");require(((EditText)read(opened,"search")).getText().toString().equals("zzzz-no-such-launcher-fixture"),"Search input lost");opened.close();});
            checkGroupPlacement(shown);
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
                require(ShellRuntime.running(),"Sidebar stopped when the app launched");
                Thread.sleep(500);
            }finally{context.unregisterReceiver(receiver);}
            System.out.println("HOME check: launch paths passed");
            if(externalDisplay>0){
                main(()->{
                    prefs.edit().putBoolean("primary_mode",false).putInt("workspace_display",0).commit();
                    ShellRuntime.start(shown,externalDisplay,false);
                });
                long deadline=SystemClock.uptimeMillis()+5000;
                while(prefs.getInt("active_display",-1)!=externalDisplay&&SystemClock.uptimeMillis()<deadline)Thread.sleep(100);
                test.waitForIdleSync();
                require(prefs.getInt("active_display",-1)==externalDisplay,"External fixture did not start");
                main(()->{
                    require(ShellRuntime.phoneNavigationReady(),"External handoff removed main sidebar");
                    require(((View)read(shown,"fallback")).getVisibility()==View.GONE,"Duplicate HOME handles visible");
                    require(sidebar==ShellFixtureAccess.sidebar(),"Main sidebar recreated during handoff");
                    require(ShellFixtureAccess.phone().toggleStart(),"Phone Start request was not routed alongside external Desktop");
                    require(sidebar.menu.isOpen(),"Main Start unavailable alongside external Desktop");sidebar.menu.close();
                });
            }
            System.out.println("HOME check: external sidebar passed");
            checkWidgetSwipe(sidebar,false,false);checkWidgetSwipe(sidebar,true,false);checkWidgetSwipe(sidebar,true,true);
            checkPanelTracking();
            main(()->ShellRuntime.stop(shown,false));test.waitForIdleSync();
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
                for(String key:new String[]{"enabled","primary_mode","workspace_display","active_display","preferred_display","compact_workspace","workspace_auto","phone_sidebar","phone_taskbar","phone_sidebar_over_apps","sidebar_height","phone_window_management","recent","last_error"}){
                    Object old=before.get(key);if(old instanceof Boolean)edit.putBoolean(key,(Boolean)old);else if(old instanceof Integer)edit.putInt(key,(Integer)old);else if(old instanceof String)edit.putString(key,(String)old);else edit.remove(key);
                }edit.commit();
                context.getPackageManager().setComponentEnabledSetting(legacy,legacyState,PackageManager.DONT_KILL_APP);
            });
        }
    }
    private void checkGroupPlacement(HomeActivity home)throws Exception{
        String name="Stella group check "+UUID.randomUUID();String[] token={null};AppMenu[] folder={null};
        try{
            main(()->{AppOrganization.addGroup(home,name);token[0]=GroupEntries.reference(home,name);StartPins.toggle(home,token[0]);Launches.toggleDesktop(home,token[0]);home.openApps();});
            AppMenu start=(AppMenu)read(home,"apps");long until=SystemClock.uptimeMillis()+10000;
            while(SystemClock.uptimeMillis()<until&&!field(AppMenu.class,"loaded").getBoolean(start))Thread.sleep(100);
            main(()->{require(textCount((View)read(start,"content"),name)==2,"Group missing from pinned and all apps sections");start.close();});
            Object shortcuts=field(DesktopActivity.class,"shortcuts").get(home);
            main(()->{
                @SuppressWarnings("unchecked") Map<String,View> icons=(Map<String,View>)read(shortcuts,"icons");
                View icon=icons.get(token[0]);require(icon!=null,"Group desktop shortcut not rendered");icon.performClick();folder[0]=(AppMenu)read(shortcuts,"groupMenu");
            });
            until=SystemClock.uptimeMillis()+10000;
            while(SystemClock.uptimeMillis()<until&&!field(AppMenu.class,"loaded").getBoolean(folder[0]))Thread.sleep(100);
            main(()->{
                require(folder[0].isOpen()&&name.equals(read(folder[0],"openGroup")),"Shortcut did not open its group");
                require(((View)read(folder[0],"main")).getVisibility()==View.GONE,"Shortcut exposed full Start instead of folder");
                require(textCount((View)read(folder[0],"folderLayer"),name)==1,"Folder title missing");folder[0].back();require(!folder[0].isOpen(),"Folder Back did not close standalone panel");
            });
        }finally{
            main(()->{if(folder[0]!=null)folder[0].close();((AppMenu)read(home,"apps")).close();AppOrganization.renameGroup(home,name,"");});
        }
    }
    private static int textCount(View view,String text){
        int count=view instanceof TextView&&text.contentEquals(((TextView)view).getText())?1:0;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)count+=textCount(group.getChildAt(i),text);}return count;
    }
    private static boolean containsClock(View view){
        if(view instanceof TextClock)return true;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(containsClock(group.getChildAt(i)))return true;}
        return false;
    }
    private void checkWidgetSwipe(PhoneSidebar sidebar,boolean right,boolean cancelled)throws Exception{
        android.app.Instrumentation.ActivityMonitor monitor=test.addMonitor(HubActivity.class.getName(),null,false);
        HubActivity hub=null;
        try{
            main(()->{
                sidebar.show(right);View panel=(View)read(sidebar,"panel");long now=SystemClock.uptimeMillis();
                android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,0,100,100,0);panel.dispatchTouchEvent(down);down.recycle();
                android.view.MotionEvent move=android.view.MotionEvent.obtain(now,now+100,2,right?100-Ui.dp(context,64):100+Ui.dp(context,64),100,0);panel.dispatchTouchEvent(move);move.recycle();
            });
            hub=(HubActivity)test.waitForMonitorWithTimeout(monitor,5000);require(hub!=null,"Inward sidebar swipe did not open widget panel");
            HubActivity opened=hub;test.waitForIdleSync();
            main(()->{
                View panel=((View)read(opened,"panelSettings")).getParent() instanceof android.view.ViewGroup?(View)((View)read(opened,"panelSettings")).getParent().getParent():null;
                FrameLayout.LayoutParams box=(FrameLayout.LayoutParams)panel.getLayoutParams();
                require((box.gravity&android.view.Gravity.HORIZONTAL_GRAVITY_MASK)==(right?android.view.Gravity.RIGHT:android.view.Gravity.LEFT),"Widget panel opened on wrong side");
                require(opened.getDisplay().getDisplayId()==0,"Sidebar widget panel left the main display");
                require(panel.getTranslationX()!=0,"Pull did not follow held gesture");
                int[] position=new int[2];panel.getLocationOnScreen(position);View rail=(View)read(sidebar,"panel");android.view.WindowManager.LayoutParams railBox=(android.view.WindowManager.LayoutParams)rail.getLayoutParams();
                int leading=position[0]+(right?0:panel.getWidth());require(Math.abs((railBox.x+(right?Ui.dp(context,76):0))-leading)<=2,"Sidebar and widget panel detached during pull");
                long now=SystemClock.uptimeMillis();android.view.MotionEvent up=android.view.MotionEvent.obtain(now-100,now,cancelled?3:1,right?100-Ui.dp(context,64):100+Ui.dp(context,64),100,0);rail.dispatchTouchEvent(up);up.recycle();
            });
            Thread.sleep(250);main(()->{require(cancelled?opened.isFinishing():((View)read(opened,"panel")).getTranslationX()==0,"Pull did not settle/cancel");require(!Boolean.TRUE.equals(read(sidebar,"shown")),"Pull left sidebar visible");});
        }finally{HubActivity closing=hub;if(closing!=null)main(closing::finish);test.removeMonitor(monitor);test.waitForIdleSync();}
    }
    private void checkPanelTracking()throws Exception{
        Instrumentation.ActivityMonitor monitor=test.addMonitor(HubActivity.class.getName(),null,false);HubActivity hub=null;
        try{
            main(()->HubActivity.open(context,0));
            hub=(HubActivity)test.waitForMonitorWithTimeout(monitor,5000);require(hub!=null,"Normal Hub did not open");
            test.waitForIdleSync();Thread.sleep(300);HubActivity opened=hub;
            main(()->{
                View panel=(View)read(opened,"panel");assertPanelBounds(panel);
                panel.setTranslationX(73);panel.invalidate();
            });
            Thread.sleep(100);test.waitForIdleSync();
            main(()->assertPanelBounds((View)read(opened,"panel")));
        }finally{HubActivity closing=hub;if(closing!=null)main(closing::finish);test.removeMonitor(monitor);test.waitForIdleSync();}
    }
    private void assertPanelBounds(View panel){
        int[] xy=new int[2];panel.getLocationOnScreen(xy);
        android.graphics.Rect expected=new android.graphics.Rect(xy[0],xy[1],xy[0]+panel.getWidth(),xy[1]+panel.getHeight());
        require(expected.equals(ShellPanels.bounds(0)),"Caption blocker did not follow translated Hub: "+ShellPanels.bounds(0)+" expected "+expected);
    }
    private static Object read(Object target,String name){try{return field(target.getClass(),name).get(target);}catch(Exception e){throw new RuntimeException(e);}}
    private void checkOverlayTaskPopup(View anchor)throws Exception{
        PhoneTaskMenu[] popup={null};String[] selected={null};int[] callbacks={0};
        String floating=anchor.getContext().getString(R.string.phone_sidebar_float_task);
        String closing=anchor.getContext().getString(R.string.phone_sidebar_close_task);
        View[] closeLabel={null},popupWindow={null};
        try{
            main(()->{
                require(anchor.isShown()&&anchor.isAttachedToWindow(),"Overlay task popup anchor unavailable");
                popup[0]=new PhoneTaskMenu(anchor.getContext());
                // Capture the action only. This popup must never issue a real task command.
                popup[0].show(anchor,action->{selected[0]=action;callbacks[0]++;});
            });
            test.waitForIdleSync();
            awaitState("Real overlay task popup labels did not appear",()->{
                for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews()){
                    View floatLabel=shownText(window,floating),close=shownText(window,closing);
                    if(floatLabel!=null&&close!=null){closeLabel[0]=close;popupWindow[0]=window;return null;}
                }
                return "both fixed task actions not shown in one window";
            });
            main(()->{
                View row=closeLabel[0];android.view.ViewParent parent=row.getParent();
                while(parent instanceof View&&!(parent instanceof ListView)){row=(View)parent;parent=row.getParent();}
                require(parent instanceof ListView,"Overlay popup action is not in a visible ListView");
                ListView list=(ListView)parent;int position=list.getPositionForView(row);
                require(list.isShown()&&row.isShown()&&position!=AdapterView.INVALID_POSITION,"Overlay popup action detached before selection");
                require(list.performItemClick(row,position,list.getAdapter().getItemId(position)),"Overlay popup item click not delivered");
                require(callbacks[0]==1&&"close".equals(selected[0]),"Overlay popup selection lost action identity");
                require(!popup[0].showing(),"Overlay popup remained open after selection");
            });
            test.waitForIdleSync();
            awaitState("Real overlay task popup did not close",()->popupWindow[0].isAttachedToWindow()&&popupWindow[0].isShown()?"popup window still shown":null);
            System.out.println("HOME check: real overlay task popup passed (captured action only)");
        }finally{
            main(()->{if(popup[0]!=null)popup[0].close();});
            test.waitForIdleSync();
        }
    }
    private static View shownText(View view,String text){
        if(!view.isShown())return null;
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText())&&view.getWidth()>0&&view.getHeight()>0)return view;
        if(view instanceof android.view.ViewGroup){
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){View found=shownText(group.getChildAt(i),text);if(found!=null)return found;}
        }
        return null;
    }
    private void awaitState(String failure,java.util.function.Supplier<String> probe)throws Exception{
        long until=SystemClock.uptimeMillis()+5000;String[] state={"not checked"};
        do{
            // All lifecycle/View state belongs to the main thread. running=true
            // only proves onCreate began, not that onStartCommand/attach finished.
            main(()->state[0]=probe.get());
            if(state[0]==null)return;
            Thread.sleep(100);
        }while(SystemClock.uptimeMillis()<until);
        require(false,failure+": "+state[0]);
    }
    private void awaitPhoneSidebar()throws Exception{
        awaitState("Phone edge overlays did not become ready",()->{
            if(!ShellRuntime.running())return "shell runtime not running";
            if(!ShellRuntime.phoneNavigationReady())return "navigation not ready";
            PhoneSidebar sidebar=ShellFixtureAccess.sidebar();
            if(sidebar==null)return "sidebar owner unavailable";
            View panel=(View)read(sidebar,"panel");
            if(!panel.isAttachedToWindow())return "sidebar panel not attached";
            if(panel.getDisplay()==null||panel.getDisplay().getDisplayId()!=0)return "sidebar panel not on display 0";
            for(int i=0;i<sidebar.handles.size();i++){
                View handle=sidebar.handles.get(i);
                if(!handle.isAttachedToWindow())return "edge handle "+i+" not attached";
                if(handle.getWidth()<=0||handle.getHeight()<=0)return "edge handle "+i+" not laid out";
                if(handle.getDisplay()==null||handle.getDisplay().getDisplayId()!=0)return "edge handle "+i+" not on display 0";
            }
            return null;
        });
    }
    private void checkStartRequestCache(){
        // Intercept command dispatch, leaving the real running output untouched.
        // A pending/repeated start must not erase the service's published cache.
        SharedPreferences fixture=context.getSharedPreferences("home_start_cache_fixture",0);
        ShellSettings settings=ShellSettings.isolated(fixture);
        int[] requests={0};
        Context capture=new RequestCaptureContext(context,fixture,settings,requests);
        try{
            main(()->{
                fixture.edit().clear().putBoolean("enabled",true).putInt("active_display",7).putInt("preferred_display",7).commit();
                ShellRuntime.start(capture,0,false);
                require(fixture.getInt("active_display",-1)==7,"Pending start erased actual output cache");
                ShellRuntime.start(capture,0,false);
                require(fixture.getInt("active_display",-1)==7,"Repeated start erased actual output cache");
                require(fixture.getInt("preferred_display",-1)==0&&requests[0]==2,"Start request was not dispatched");
            });
        }finally{
            settings.close();
            main(()->fixture.edit().clear().commit());
        }
    }

    private static final class RequestCaptureContext extends ContextWrapper implements ShellSettings.Provider {
        private final SharedPreferences preferences;
        private final ShellSettings settings;
        private final int[] requests;
        RequestCaptureContext(Context base,SharedPreferences preferences,ShellSettings settings,int[] requests){
            super(base);this.preferences=preferences;this.settings=settings;this.requests=requests;
        }
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
        @Override public ShellSettings shellSettings(){return settings;}
        @Override public ComponentName startForegroundService(Intent intent){requests[0]++;return intent.getComponent();}
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // API 30-32 branch intentionally receives only the disposable cross-package test probe; flags are used on 33+.
    private void registerProbeReceiver(BroadcastReceiver receiver,IntentFilter filter){
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver,filter);
    }
}
