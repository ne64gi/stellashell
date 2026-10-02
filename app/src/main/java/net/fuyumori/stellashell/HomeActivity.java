package net.fuyumori.stellashell;

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
    private final HomeOutputRecovery homeRecovery=new HomeOutputRecovery();
    private final Runnable bridgeChanged=this::refreshHome;
    private final Runnable navigationChanged=this::updateFallback;
    @Override protected boolean homeSurface(){return true;}
    static boolean extensions(Context c){
        return !WorkspaceProfile.standard(c,0)&&HomeMode.extensions(Launches.prefs(c).getBoolean("enabled",false),
                Displays.primary(c)&&Workspace.target(c)==0,Bridge.get(c).ready(),Settings.canDrawOverlays(c));
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
        fallback.setOnApplyWindowInsetsListener((v,insets)->{positionHomeEdges(insets);return insets;});
        fallback.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->positionHomeEdges(getWindow().getDecorView().getRootWindowInsets()));
        Bridge.get(this).observe(bridgeChanged);DockService.observeNavigation(navigationChanged);updateFallback();
    }
    void openApps(){if(apps==null)apps=new AppMenu(this,getWindowManager(),0);if(!apps.isOpen())apps.open(menuLoader);}
    private void positionHomeEdges(WindowInsets insets){
        if(fallback==null||insets==null)return;
        WorkArea area=WorkArea.read(this,insets);int percent=Math.max(0,Math.min(100,Launches.prefs(this).getInt("sidebar_height",80)));
        for(int i=0;i<fallback.getChildCount();i++){
            View child=fallback.getChildAt(i);FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)child.getLayoutParams();
            int x=i==0?Math.max(area.usable.left,area.gestureLeft)+Ui.dp(this,8):Math.min(area.usable.right,area.physical.right-area.gestureRight)-Ui.dp(this,24);
            int y=area.usable.top+Math.round(Math.max(0,area.usable.height()-p.height)*percent/100f);
            if(p.leftMargin!=x||p.topMargin!=y){p.leftMargin=x;p.topMargin=y;child.setLayoutParams(p);}
        }
    }
    private void updateFallback(){
        if(fallback==null||isFinishing()||isDestroyed())return;
        // HOME must stay navigable even when the only Shell is on another display,
        // still starting, or failed to attach. Preference targets are not proof of UI.
        fallback.setVisibility(Settings.canDrawOverlays(this)&&DockService.phoneNavigationReady()?View.GONE:View.VISIBLE);
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
            if(WorkspaceProfile.standard(this,0)&&Launches.prefs(this).getBoolean("phone_sidebar",true)&&Settings.canDrawOverlays(this)&&!DockService.running()) {
                SharedPreferences prefs=Launches.prefs(this);
                int preferred=HomeOutputRecovery.target(prefs.getInt("workspace_display",-1),prefs.getInt("preferred_display",-1),Displays.ids(this));
                boolean external=preferred>0;
                if(!external)prefs.edit().putBoolean("primary_mode",true).putInt("workspace_display",0).apply();
                if(!prefs.contains("workspace_auto"))prefs.edit().putBoolean("workspace_auto",true).apply();
                DockService.start(this,external?preferred:0,false);
            } else if(extended&&!DockService.running())DockService.start(this,0,false);
            if(returnPending&&hasWindowFocus()&&extended){returnPending=false;ShellPanels.dismiss(0);Launches.returnedHome(this);}
        });
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);if(apps!=null)apps.close();returnPending=true;refreshHome();
    }
    @Override protected void onStart(){super.onStart();DockService.homeVisible(true);}
    @Override protected void onStop(){DockService.homeVisible(false);super.onStop();}
    @Override protected void onResume(){super.onResume();resumed=true;Bridge.get(this).connect();refreshHome();}
    @Override protected void onPause(){resumed=false;super.onPause();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)refreshHome();}
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){super.onSharedPreferenceChanged(prefs,key);if("sidebar_height".equals(key)||"enabled".equals(key)||"primary_mode".equals(key)||"workspace_display".equals(key)||"phone_sidebar".equals(key)||"phone_window_management".equals(key))refreshHome();}
    @Override public void onBackPressed(){if(apps!=null&&apps.isOpen())apps.close();else super.onBackPressed();}
    @Override public void onDestroy(){DockService.unobserveNavigation(navigationChanged);Bridge.get(this).remove(bridgeChanged);if(apps!=null)apps.close();menuLoader.shutdownNow();super.onDestroy();}
}
