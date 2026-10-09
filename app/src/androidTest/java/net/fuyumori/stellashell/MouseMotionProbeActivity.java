package net.fuyumori.stellashell;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Separate test-APK task: transparent, temporarily focused mouse diagnostic on a requested display.
 * SDK only; no overlay permission, production classes/settings, input injection/routing or other tasks.
 * Logcat tag StellaMouseProbe and an own-filesDir nonce receipt contain aggregate evidence only,
 * not a functional PASS. No authored output or receipt exists before the exact test UID guard.
 */
public final class MouseMotionProbeActivity extends Activity {
    public static final String EXPECTED_DISPLAY="expected_display";
    public static final String PROBE_NONCE="probe_nonce";
    private static final String TAG="StellaMouseProbe",TEST_PACKAGE="net.fuyumori.stellashell.test",PRODUCT_PACKAGE="net.fuyumori.stellashell";
    private static final int MEASUREMENT_MS=10000,HARD_LIMIT_MS=12000,MAX_RECEIPT_LINES=8;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopping=new AtomicBoolean(),destroyed=new AtomicBoolean();
    private DisplayManager displays;
    private View probe;
    private int displayId=-1,width,height,displayFlags,testUid=-1,productUid=-1;
    // Public identity comparison only, kept in memory and never printed/persisted.
    private String displayName;
    private volatile boolean uidVerified;
    private boolean registered,measuring,finishRemoveReturned;
    private volatile File receipt;
    private int receiptLines;
    private long genericEvents,hoverEvents,moveEvents,touchEvents,samples;
    private boolean observedXY,xyChanged,allXYZero=true,relativeNonzero,invalidXY;
    private float previousX,previousY;
    private volatile String stage="nonce_guard";
    private String nonce="unvalidated";

    private final DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){}
        public void onDisplayRemoved(int id){if(id==displayId)stop("display_removed");}
        public void onDisplayChanged(int id){if(id==displayId&&!sameDisplay())stop("display_identity_or_geometry_changed");}
    };

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{
            String candidate=getIntent().getStringExtra(PROBE_NONCE);
            require(candidate!=null&&candidate.matches("[0-9a-f]{32}"),"invalid_probe_nonce");
            nonce=candidate;stage="uid_guard";
            ApplicationInfo product=getPackageManager().getApplicationInfo(PRODUCT_PACKAGE,0);
            testUid=getApplicationInfo().uid;productUid=product.uid;
            require(TEST_PACKAGE.equals(getPackageName())&&Process.myUid()==testUid&&testUid!=productUid,"test_uid_guard_failed");
            uidVerified=true;startWatchdog();
            require(saved==null,"recreated_probe_rejected");
            stage="receipt_init";
            File freshReceipt=new File(getFilesDir(),"mouse-probe-"+nonce+".txt");
            // A nonce identifies one launch. Never replace or extend a previous launch's evidence.
            require(freshReceipt.createNewFile(),"receipt_nonce_already_exists");receipt=freshReceipt;
            stage="display_guard";displayId=getIntent().getIntExtra(EXPECTED_DISPLAY,-1);
            require(displayId>0,"requires_positive_expected_display");
            Display actual=getDisplay();displays=getSystemService(DisplayManager.class);
            Display current=displays.getDisplay(displayId);
            require(actual!=null&&actual.getDisplayId()==displayId&&current!=null&&current.isValid()
                &&(current.getFlags()&Display.FLAG_PRIVATE)==0,"requested_public_display_context_mismatch");
            displayName=current.getName();displayFlags=current.getFlags();Point size=new Point();current.getRealSize(size);width=size.x;height=size.y;
            require(width>0&&height>0,"native_display_geometry_unavailable");
            stage="window_setup";
            Window window=getWindow();window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setFormat(PixelFormat.TRANSLUCENT);
            window.setDecorFitsSystemWindows(false);window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_FULLSCREEN);
            WindowManager.LayoutParams params=window.getAttributes();params.dimAmount=0;params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;params.setTitle("StellaShell mouse diagnostic");window.setAttributes(params);
            probe=new View(this);probe.setBackground(null);probe.setWillNotDraw(true);
            probe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            probe.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->beginIfReady());
            setContentView(probe);
            stage="display_listener";displays.registerDisplayListener(listener,main);registered=true;
            stage="await_full_display_layout";probe.post(this::beginIfReady);
        }catch(Throwable error){stop(error instanceof ProbeFailure?((ProbeFailure)error).reason:"framework_failure_"+error.getClass().getSimpleName());}
    }
    private void startWatchdog(){
        Thread watchdog=new Thread(()->{
            try{Thread.sleep(HARD_LIMIT_MS);}catch(InterruptedException ignored){return;}
            if(!destroyed.get()&&uidVerified&&Process.myUid()==testUid&&testUid!=productUid){
                emit(true,"FAIL: hard_watchdog_timeout; stage="+stage+"; own_test_process_exit=true; normal_task_cleanup_unconfirmed");
                // The manifest isolates this Activity in :mouse_probe; never terminate the product UID.
                System.exit(1);
            }
        },"mouse-activity-watchdog");watchdog.setDaemon(true);watchdog.start();
    }
    private boolean sameDisplay(){
        if(displays==null||displayId<=0)return false;
        Display actual=getDisplay(),current=displays.getDisplay(displayId);
        if(actual==null||actual.getDisplayId()!=displayId||current==null||!current.isValid()
            ||current.getFlags()!=displayFlags||!displayName.equals(current.getName()))return false;
        Point size=new Point();current.getRealSize(size);return size.x==width&&size.y==height;
    }
    private void beginIfReady(){
        if(stopping.get()||probe==null||probe.getWidth()==0||probe.getHeight()==0)return;
        if(!sameDisplay()){stop("display_changed_before_or_during_layout");return;}
        View decor=getWindow().getDecorView();
        if(decor.getWidth()==0||decor.getHeight()==0)return;
        if(probe.getWidth()!=width||probe.getHeight()!=height||decor.getWidth()!=width||decor.getHeight()!=height){stop("activity_window_not_full_display");return;}
        if(!measuring){
            measuring=true;stage="measuring";
            main.postDelayed(()->stop(sameDisplay()?null:"display_changed_at_measurement_end"),MEASUREMENT_MS);
            emit(false,"READY: own_test_uid_verified=true; transparent_normal_activity=true; temporary_focus=true; measurement_ms="+MEASUREMENT_MS+"; hard_limit_ms="+HARD_LIMIT_MS);
        }
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(mouse(event)){record(event,true);return true;}return super.dispatchGenericMotionEvent(event);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(mouse(event)){record(event,false);return true;}return super.dispatchTouchEvent(event);
    }
    private static boolean mouse(MotionEvent event){return event.isFromSource(InputDevice.SOURCE_MOUSE)||event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE);}
    private void record(MotionEvent event,boolean generic){
        if(!uidVerified||!measuring||stopping.get())return;
        if(!sameDisplay()){stop("display_changed_during_motion");return;}
        if(generic)genericEvents++;else touchEvents++;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_HOVER_ENTER||action==MotionEvent.ACTION_HOVER_MOVE||action==MotionEvent.ACTION_HOVER_EXIT)hoverEvents++;
        if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_HOVER_MOVE)moveEvents++;
        for(int index=Math.max(0,event.getHistorySize()-32);index<event.getHistorySize();index++){
            sample(event.getHistoricalX(index),event.getHistoricalY(index),event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X,index),event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y,index));
        }
        sample(event.getX(),event.getY(),event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y));
    }
    private void sample(float x,float y,float relativeX,float relativeY){
        samples++;
        if(Float.isFinite(x)&&Float.isFinite(y)){
            if(observedXY&&(x!=previousX||y!=previousY))xyChanged=true;
            allXYZero&=x==0&&y==0;previousX=x;previousY=y;observedXY=true;
        }else{invalidXY=true;allXYZero=false;}
        relativeNonzero|=Float.isFinite(relativeX)&&relativeX!=0||Float.isFinite(relativeY)&&relativeY!=0;
    }
    private void stop(String failure){
        if(!stopping.compareAndSet(false,true))return;
        String failureStage=stage;stage="cleanup";main.removeCallbacksAndMessages(null);
        try{if(registered){displays.unregisterDisplayListener(listener);registered=false;}}
        catch(Throwable error){if(failure==null)failure="display_listener_cleanup_failed";}
        try{if(uidVerified){finishAndRemoveTask();finishRemoveReturned=true;}else finish();}
        catch(Throwable error){if(failure==null)failure="own_activity_task_cleanup_failed";}
        if(failure!=null)emit(true,"FAIL: "+failure+"; stage="+failureStage+"; finish_and_remove_task_returned="+finishRemoveReturned);
        else if(uidVerified)emit(false,"standaloneResult: measurement_completed; functional_pass_not_claimed=true; generic_mouse_events="+genericEvents
            +"; hover_events="+hoverEvents+"; move_events="+moveEvents+"; touch_mouse_events="+touchEvents+"; samples="+samples
            +"; xy_observed="+observedXY+"; xy_changed="+xyChanged+"; all_xy_zero="+(observedXY&&allXYZero)+"; relative_axis_nonzero="+relativeNonzero
            +"; invalid_xy="+invalidXY+"; finish_and_remove_task_returned="+finishRemoveReturned);
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);if(uidVerified&&!stopping.get()&&!sameDisplay())stop("configuration_changed_geometry");}
    @Override protected void onStop(){super.onStop();if(uidVerified&&!stopping.get())stop("activity_stopped_before_measurement_end");}
    @Override protected void onDestroy(){
        if(uidVerified&&!stopping.get())stop("activity_destroyed_before_measurement_end");
        destroyed.set(true);main.removeCallbacksAndMessages(null);super.onDestroy();
        if(uidVerified)emit(false,"CLEANUP: own_activity_destroyed=true; finish_and_remove_task_returned="+finishRemoveReturned);
    }
    private synchronized void emit(boolean error,String body){
        if(!uidVerified||Process.myUid()!=testUid||testUid==productUid||!TEST_PACKAGE.equals(getPackageName()))return;
        if(receiptLines>=MAX_RECEIPT_LINES)return;
        receiptLines++;
        String line=prefix()+body;
        Log.println(error?Log.ERROR:Log.INFO,TAG,line);
        if(receipt==null)return;
        try(FileOutputStream output=new FileOutputStream(receipt,true)){
            output.write((line+"\n").getBytes(StandardCharsets.UTF_8));output.getFD().sync();
        }catch(Exception ignored){
            // Fixed diagnostic only; never print an exception message, path or private stack.
            Log.e(TAG,prefix()+"FAIL: receipt_append_failed; receipt_persistence_unconfirmed=true");
        }
    }
    private String prefix(){return "nonce="+nonce+"; ";}
    private static void require(boolean ok,String reason){if(!ok)throw new ProbeFailure(reason);}
    private static final class ProbeFailure extends RuntimeException {final String reason;ProbeFailure(String reason){this.reason=reason;}}
}
