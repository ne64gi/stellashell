package net.fuyumori.stellashell;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.*;

/** Non-interactive 1px observer; metrics remain display-scoped. Never takes focus or consumes insets. */
final class WorkAreaObserver implements AutoCloseable {
    private final Context context;private final WindowManager windows;private final View probe;
    private final int display;private final Runnable changed;
    private WindowInsets last;private boolean closed;
    private final DisplayManager displays;
    private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){}
        public void onDisplayRemoved(int id){}
        public void onDisplayChanged(int id){if(id==display)refresh();}
    };
    WorkAreaObserver(Context context,int display,Runnable changed){
        this.context=context;this.display=display;this.changed=changed;windows=context.getSystemService(WindowManager.class);displays=context.getSystemService(DisplayManager.class);
        probe=new View(context){
            @Override protected void onConfigurationChanged(Configuration configuration){
                super.onConfigurationChanged(configuration);
                // Display notifications can precede this window context's rotation update.
                // Read again once the attached window has received its new configuration.
                refresh();
            }
        };probe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        probe.setOnApplyWindowInsetsListener((v,insets)->{
            refresh();
            return insets;
        });
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);p.alpha=0;p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("StellaShell insets observer");windows.addView(probe,p);probe.requestApplyInsets();
        displays.registerDisplayListener(displayListener,new Handler(Looper.getMainLooper()));
        refresh();probe.removeCallbacks(changed);probe.postDelayed(changed,120);
    }
    void refresh(){
        if(closed||!context.getDisplay().isValid())return;
        try{last=windows.getCurrentWindowMetrics().getWindowInsets();}catch(RuntimeException error){android.util.Log.w("StellaWorkArea","Metrics unavailable",error);return;}
        WorkArea next=WorkArea.read(context,last),before=WorkArea.get(context,display);
        WorkArea.put(display,next);
        String diagnostic="display="+display+" physical="+next.physical+" usable="+next.usable+" application="+next.application+" content="+next.content+" compact="+next.compact+" ime="+next.imeVisible;
        if(!diagnostic.equals(Launches.prefs(context).getString("work_area_diagnostics","")))Launches.prefs(context).edit().putString("work_area_diagnostics",diagnostic).apply();
        if(!next.same(before)){probe.removeCallbacks(changed);probe.postDelayed(changed,120);}
    }
    public void close(){if(closed)return;closed=true;displays.unregisterDisplayListener(displayListener);probe.removeCallbacks(changed);try{windows.removeViewImmediate(probe);}catch(RuntimeException ignored){}WorkArea.remove(display);}
}
