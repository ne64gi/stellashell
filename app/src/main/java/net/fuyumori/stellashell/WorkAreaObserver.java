package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.*;

/** Non-interactive 1px observer; metrics remain display-scoped. Never takes focus or consumes insets. */
final class WorkAreaObserver implements AutoCloseable {
    private final Context context;private final WindowManager windows;private final View probe;
    private final int display;private final Runnable changed;
    private WindowInsets last;private boolean closed;
    private final Runnable sample=new Runnable(){public void run(){
        if(closed||!context.getDisplay().isValid())return;
        try{last=windows.getCurrentWindowMetrics().getWindowInsets();refresh();}catch(RuntimeException error){android.util.Log.w("StellaWorkArea","Metrics unavailable",error);}
        if(!closed)probe.postDelayed(this,750);
    }};
    WorkAreaObserver(Context context,int display,Runnable changed){
        this.context=context;this.display=display;this.changed=changed;windows=context.getSystemService(WindowManager.class);
        probe=new View(context);probe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        probe.setOnApplyWindowInsetsListener((v,insets)->{
            if(!closed&&context.getDisplay().isValid())try{last=windows.getCurrentWindowMetrics().getWindowInsets();refresh();}catch(RuntimeException error){android.util.Log.w("StellaWorkArea","Insets unavailable",error);}
            return insets;
        });
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);p.alpha=0;p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("StellaShell insets observer");windows.addView(probe,p);probe.requestApplyInsets();
        last=windows.getCurrentWindowMetrics().getWindowInsets();refresh();probe.removeCallbacks(changed);probe.postDelayed(changed,120);probe.postDelayed(sample,750);
    }
    void refresh(){
        if(last==null||closed)return;
        WorkArea next=WorkArea.read(context,last),before=WorkArea.get(context,display);
        WorkArea.put(display,next);
        String diagnostic="display="+display+" physical="+next.physical+" usable="+next.usable+" application="+next.application+" content="+next.content+" compact="+next.compact+" ime="+next.imeVisible;
        if(!diagnostic.equals(Launches.prefs(context).getString("work_area_diagnostics","")))Launches.prefs(context).edit().putString("work_area_diagnostics",diagnostic).apply();
        if(!next.same(before)){probe.removeCallbacks(changed);probe.postDelayed(changed,120);}
    }
    public void close(){closed=true;probe.removeCallbacks(sample);probe.removeCallbacks(changed);try{windows.removeViewImmediate(probe);}catch(RuntimeException ignored){}WorkArea.remove(display);}
}
