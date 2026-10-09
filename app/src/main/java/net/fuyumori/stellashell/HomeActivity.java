package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.display.HomeMode;
import net.fuyumori.stellashell.core.display.HomeOutputRecovery;
import net.fuyumori.stellashell.core.layout.EdgeDockReveal;
import net.fuyumori.stellashell.core.navigation.HomeRecovery;

import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Always-available primary HOME. Desktop/bridge shutdown must never disable this Activity. */
public final class HomeActivity extends DesktopActivity {
    private AppMenu apps;
    private FrameLayout fallback;
    private final java.util.concurrent.ExecutorService menuLoader=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean resumed,returnPending=true;
    private ShellRuntime.HomeVisibilityLease homeVisibility;
    private AutoCloseable homeSettingsSubscription;
    private final HomeOutputRecovery homeRecovery=new HomeOutputRecovery();
    private final HomeRecovery recoveryPresses=new HomeRecovery();
    private final Runnable bridgeChanged=this::refreshHome;
    private final Runnable navigationChanged=this::updateFallback;
    @Override protected boolean homeSurface(){return true;}
    static boolean extensions(Context c){
        return !WorkspaceProfile.standard(c,0)&&HomeMode.extensions(ShellRuntime.enabled(c),
                Displays.primary(c)&&TaskState.of(c).target(c)==0,Bridge.get(c).ready(),Settings.canDrawOverlays(c));
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(root==null)return;
        if(state!=null)returnPending=false; // Restoring a surface is not an explicit HOME action.
        // No permanent HOME toolbar: the existing Edge Sidebar owns navigation.
        // When the overlay is absent locally, HOME-owned edge handles open the same Start menu.
        fallback=new FrameLayout(this);
        root.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        for(int side=0;side<2;side++){
            DockHandleView handle=new DockHandleView(this,new DockHandleView.Listener(){
                public void onDrag(float progress){}
                public void onRelease(boolean open){if(open)openApps();}
            });
            handle.setOnClickListener(v->openApps());
            fallback.addView(handle,new FrameLayout.LayoutParams(Ui.dp(this,16),Ui.dp(this,64),Gravity.TOP|Gravity.LEFT));
        }
        ImageButton homeApps=new ImageButton(this);homeApps.setImageDrawable(AppIcons.stella(this));homeApps.setBackground(Ui.toolbarBackground(this,12));
        homeApps.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8));homeApps.setContentDescription(getString(R.string.ui_app_menu));homeApps.setOnClickListener(v->openApps());
        fallback.addView(homeApps,new FrameLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48),Gravity.TOP|Gravity.LEFT));
        fallback.setOnApplyWindowInsetsListener((v,insets)->{positionHomeEdges(insets);return insets;});
        fallback.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->positionHomeEdges(getWindow().getDecorView().getRootWindowInsets()));
        Bridge.get(this).observe(bridgeChanged);ShellRuntime.observeNavigation(navigationChanged);homeSettingsSubscription=ShellSettings.of(this).observe((changes,snapshot)->refreshHome());updateFallback();
        if(!startRequest(getIntent())&&state==null)recoveryHome(getIntent());
    }
    void openApps(){if(apps==null)apps=new AppMenu(this,getWindowManager(),0);if(!apps.isOpen())apps.open(menuLoader);}
    private void positionHomeEdges(WindowInsets insets){
        if(fallback==null||insets==null)return;
        WorkArea area=WorkArea.read(this,insets);ShellSettings.Dock config=ShellSettings.of(this).snapshot().phoneDock;int percent=config.triggerPercent;
        boolean enabled=config.enabled;String side=config.phoneSide.storedValue();
        boolean landscape=EdgeDockReveal.landscape(area.physical.width(),area.physical.height());
        ShellSettings.DockTrigger trigger=ShellSettings.of(this).snapshot().phoneLandscapeDock;
        android.graphics.Rect safe=new android.graphics.Rect(area.usable);
        safe.left=Math.min(safe.right-1,Math.max(safe.left,area.gestureLeft));
        safe.right=Math.max(safe.left+1,Math.min(safe.right,area.physical.right-area.gestureRight));
        for(int i=0;i<2;i++){
            DockHandleView child=(DockHandleView)fallback.getChildAt(i);FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)child.getLayoutParams();
            String edge=landscape?trigger.edge.storedValue():i==0?"left":"right";
            boolean visible=enabled&&(landscape?i==0:!(i==0?"right":"left").equals(side));
            child.setVisibility(visible?View.VISIBLE:View.GONE);
            int[] rect=EdgeDockReveal.handle(safe.left,safe.top,safe.right,safe.bottom,edge,
                    landscape?trigger.positionPercent:percent,Ui.dp(this,EdgeDockReveal.handleThickness(config.openMethod)),Ui.dp(this,64),Ui.dp(this,8));
            child.configure(edge,Ui.dp(this,76),config.openMethod);
            if(p.leftMargin!=rect[0]||p.topMargin!=rect[1]||p.width!=rect[2]-rect[0]||p.height!=rect[3]-rect[1]){
                child.reset();
                p.leftMargin=rect[0];p.topMargin=rect[1];p.width=rect[2]-rect[0];p.height=rect[3]-rect[1];child.setLayoutParams(p);
            }
        }
        View appsButton=fallback.getChildAt(2);appsButton.setVisibility(enabled?View.GONE:View.VISIBLE);
        FrameLayout.LayoutParams appParams=(FrameLayout.LayoutParams)appsButton.getLayoutParams();
        appParams.leftMargin=area.usable.left+Math.max(0,(area.usable.width()-appParams.width)/2);appParams.topMargin=Math.max(area.usable.top,area.usable.bottom-appParams.height-Ui.dp(this,8));appsButton.setLayoutParams(appParams);
    }
    private void updateFallback(){
        if(fallback==null||isFinishing()||isDestroyed())return;
        // HOME must stay navigable even when the only Shell is on another display,
        // still starting, or failed to attach. Preference targets are not proof of UI.
        fallback.setVisibility(Settings.canDrawOverlays(this)&&ShellRuntime.phoneNavigationReady()?View.GONE:View.VISIBLE);
        positionHomeEdges(getWindow().getDecorView().getRootWindowInsets());
    }
    private void refreshHome(){
        if(fallback==null||isFinishing()||isDestroyed())return;
        // apply() delivers these preference callbacks synchronously on the main
        // thread. Do not start a second refresh before this request is dispatched.
        homeRecovery.refresh(()->{
            boolean extended=extensions(this);
            updateFallback();
            if(!resumed)return;
            if(WorkspaceProfile.standard(this,0)&&ShellSettings.of(this).snapshot().phoneDock.enabled&&Settings.canDrawOverlays(this)&&!ShellRuntime.running()) {
                int preferred=HomeOutputRecovery.target(TaskState.of(this).savedTarget(this),ShellSettings.of(this).snapshot().preferredDisplayId,Displays.ids(this));
                boolean external=preferred>0;
                if(!external){ShellSettings.of(this).setPrimaryMode(true);TaskState.of(this).initializeTarget(this,0);}
                ShellSettings.of(this).initializeWorkspaceAuto();
                ShellRuntime.start(this,external?preferred:0,false);
            } else if(extended&&!ShellRuntime.running())ShellRuntime.start(this,0,false);
            if(returnPending&&hasWindowFocus()&&extended){returnPending=false;ShellPanels.dismiss(0);Launches.returnedHome(this);}
        });
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);if(startRequest(intent))return;
        if(recoveryHome(intent))return;
        if(apps!=null)apps.close();returnPending=true;refreshHome();
    }
    private boolean recoveryHome(Intent intent){
        if(!HomeRecoveryEntry.isPress(intent)){recoveryPresses.reset();return false;}
        if(!recoveryPresses.press(android.os.SystemClock.uptimeMillis()))return false;
        returnPending=false;if(apps!=null)apps.close();ShellPanels.dismiss(0);
        startActivity(new Intent(this,SetupActivity.class).putExtra(HomeRecoveryEntry.SETTINGS_PAGE,true),
                android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        return true;
    }
    private boolean startRequest(Intent intent){
        if(intent==null||StartMenuRequests.operation(intent.getAction())==StartMenuRequests.Operation.NONE)return false;
        recoveryPresses.reset();
        Intent request=new Intent(intent);
        // Activity recreation must not replay a previously consumed toggle.
        setIntent(new Intent(intent).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        returnPending=false;
        root.post(()->{
            if(isFinishing()||isDestroyed()||StartMenuRequests.route(request))return;
            int target=StartMenuRequests.target(request.getIntExtra(StartMenuRequests.DISPLAY,-1),ShellRuntime.selectedDisplay());
            if(target!=0)return; // Never silently redirect a disconnected external request to the phone.
            if(StartMenuRequests.operation(request.getAction())==StartMenuRequests.Operation.TOGGLE&&apps!=null&&apps.isOpen())apps.close();
            else openApps();
        });
        return true;
    }
    @Override protected void onStart(){super.onStart();if(homeVisibility==null)homeVisibility=ShellRuntime.attachHomeSurface();homeVisibility.visible(true);}
    @Override protected void onStop(){if(apps!=null)apps.close();if(homeVisibility!=null)homeVisibility.visible(false);super.onStop();}
    @Override protected void onResume(){super.onResume();resumed=true;Bridge.get(this).connect();Bridge.get(this).startShortcut();refreshHome();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)refreshHome();}
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){super.onSharedPreferenceChanged(prefs,key);if("phone_window_management".equals(key))refreshHome();}
    @Override public void onBackPressed(){if(apps!=null&&apps.isOpen())apps.close();else super.onBackPressed();}
    @Override public void onDestroy(){ShellRuntime.unobserveNavigation(navigationChanged);if(homeVisibility!=null)homeVisibility.close();if(homeSettingsSubscription!=null)try{homeSettingsSubscription.close();}catch(Exception e){Launches.problem(this,e.getMessage());}Bridge.get(this).remove(bridgeChanged);if(apps!=null)apps.close();menuLoader.shutdownNow();super.onDestroy();}
}
