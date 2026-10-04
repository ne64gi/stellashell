package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.display.HomeMode;
import net.fuyumori.stellashell.core.display.HomeOutputRecovery;

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
    private final Runnable bridgeChanged=this::refreshHome;
    private final Runnable navigationChanged=this::updateFallback;
    @Override protected boolean homeSurface(){return true;}
    static boolean extensions(Context c){
        return !WorkspaceProfile.standard(c,0)&&HomeMode.extensions(ShellRuntime.enabled(c),
                Displays.primary(c)&&TaskState.of(c).target(c)==0,Bridge.get(c).ready(),Settings.canDrawOverlays(c));
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(root==null)return;
        // No permanent HOME toolbar: the existing Edge Sidebar owns navigation.
        // When the overlay is absent locally, HOME-owned edge handles open the same Start menu.
        fallback=new FrameLayout(this);
        root.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        for(int side=0;side<2;side++){
            final boolean right=side==1;
            View handle=new View(this){
                private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(android.graphics.Canvas canvas){
                    paint.setColor((Ui.TEXT&0xffffff)|0x66000000);float half=Ui.dp(getContext(),1),length=Ui.dp(getContext(),16);
                    canvas.drawRoundRect(getWidth()/2f-half,getHeight()/2f-length,getWidth()/2f+half,getHeight()/2f+length,half,half,paint);
                }
                @Override public boolean performClick(){super.performClick();return true;}
            };
            handle.setContentDescription(getString(R.string.edge_dock_open));handle.setFocusable(true);handle.setOnClickListener(v->openApps());
            handle.setOnTouchListener(new View.OnTouchListener(){float x,y;boolean opened;
                public boolean onTouch(View v,MotionEvent e){
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();opened=false;return true;}
                    float dx=e.getRawX()-x,dy=e.getRawY()-y;
                    if(!opened&&e.getActionMasked()==MotionEvent.ACTION_MOVE&&(right?-dx:dx)>Ui.dp(HomeActivity.this,16)&&Math.abs(dx)>Math.abs(dy)){opened=true;openApps();}
                    if(!opened&&e.getActionMasked()==MotionEvent.ACTION_UP&&Math.abs(dx)<Ui.dp(HomeActivity.this,8)&&Math.abs(dy)<Ui.dp(HomeActivity.this,8))v.performClick();
                    return true;
                }
            });
            fallback.addView(handle,new FrameLayout.LayoutParams(Ui.dp(this,16),Ui.dp(this,64),Gravity.TOP|Gravity.LEFT));
        }
        ImageButton homeApps=new ImageButton(this);homeApps.setImageResource(R.mipmap.ic_launcher);homeApps.setBackground(Ui.toolbarBackground(this,12));
        homeApps.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8));homeApps.setContentDescription(getString(R.string.ui_app_menu));homeApps.setOnClickListener(v->openApps());
        fallback.addView(homeApps,new FrameLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48),Gravity.TOP|Gravity.LEFT));
        fallback.setOnApplyWindowInsetsListener((v,insets)->{positionHomeEdges(insets);return insets;});
        fallback.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->positionHomeEdges(getWindow().getDecorView().getRootWindowInsets()));
        Bridge.get(this).observe(bridgeChanged);ShellRuntime.observeNavigation(navigationChanged);homeSettingsSubscription=ShellSettings.of(this).observe((changes,snapshot)->refreshHome());updateFallback();
    }
    void openApps(){if(apps==null)apps=new AppMenu(this,getWindowManager(),0);if(!apps.isOpen())apps.open(menuLoader);}
    private void positionHomeEdges(WindowInsets insets){
        if(fallback==null||insets==null)return;
        WorkArea area=WorkArea.read(this,insets);ShellSettings.Dock config=ShellSettings.of(this).snapshot().phoneDock;int percent=config.triggerPercent;
        boolean enabled=config.enabled;String side=config.phoneSide.storedValue();
        for(int i=0;i<2;i++){
            View child=fallback.getChildAt(i);FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)child.getLayoutParams();
            child.setVisibility(enabled&&!(i==0?"right":"left").equals(side)?View.VISIBLE:View.GONE);
            int x=i==0?Math.max(area.usable.left,area.gestureLeft)+Ui.dp(this,8):Math.min(area.usable.right,area.physical.right-area.gestureRight)-Ui.dp(this,24);
            int y=area.usable.top+Math.round(Math.max(0,area.usable.height()-p.height)*percent/100f);
            if(p.leftMargin!=x||p.topMargin!=y){p.leftMargin=x;p.topMargin=y;child.setLayoutParams(p);}
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
        super.onNewIntent(intent);if(apps!=null)apps.close();returnPending=true;refreshHome();
    }
    @Override protected void onStart(){super.onStart();if(homeVisibility==null)homeVisibility=ShellRuntime.attachHomeSurface();homeVisibility.visible(true);}
    @Override protected void onStop(){if(apps!=null)apps.close();if(homeVisibility!=null)homeVisibility.visible(false);super.onStop();}
    @Override protected void onResume(){super.onResume();resumed=true;Bridge.get(this).connect();refreshHome();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)refreshHome();}
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){super.onSharedPreferenceChanged(prefs,key);if("phone_window_management".equals(key))refreshHome();}
    @Override public void onBackPressed(){if(apps!=null&&apps.isOpen())apps.close();else super.onBackPressed();}
    @Override public void onDestroy(){ShellRuntime.unobserveNavigation(navigationChanged);if(homeVisibility!=null)homeVisibility.close();if(homeSettingsSubscription!=null)try{homeSettingsSubscription.close();}catch(Exception e){Launches.problem(this,e.getMessage());}Bridge.get(this).remove(bridgeChanged);if(apps!=null)apps.close();menuLoader.shutdownNow();super.onDestroy();}
}
