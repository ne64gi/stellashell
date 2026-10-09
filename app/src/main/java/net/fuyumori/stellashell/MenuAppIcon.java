package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Only drawn Start tiles request artwork; hidden/offscreen rows do not decode icons. */
final class MenuAppIcon extends ImageView {
    private static final Handler main=new Handler(Looper.getMainLooper());
    // Main-thread-only weak demand: detached/collected Views never own queued work.
    private static final Set<MenuAppIcon> waiting=Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64)){
        @Override protected void afterExecute(Runnable task,Throwable failure){main.post(MenuAppIcon::retryWaiting);}
    };
    static {worker.allowCoreThreadTimeOut(true);}
    private final String component;
    private final Drawable original;
    private Future<?> pending;
    private long generation;
    private boolean loaded;

    MenuAppIcon(Context context,String component,Drawable original){
        super(context);this.component=component;this.original=original;
        setImageResource(android.R.drawable.sym_def_app_icon);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(loaded||pending!=null||!isAttachedToWindow())return;
        Context context=getContext().getApplicationContext();
        if(context==null)return;
        long request=++generation;WeakReference<MenuAppIcon> target=new WeakReference<>(this);
        String identity=component;Drawable source=original;
        try{pending=worker.submit(()->{
            Drawable icon;
            try{icon=AppIcons.forApp(context,identity,source);}
            catch(RuntimeException failure){icon=context.getDrawable(android.R.drawable.sym_def_app_icon);}
            Drawable result=icon;
            main.post(()->{
                MenuAppIcon view=target.get();
                if(view==null||view.generation!=request||!view.isAttachedToWindow())return;
                view.pending=null;view.loaded=true;view.setImageDrawable(result);
            });
        });}catch(RejectedExecutionException full){pending=null;waiting.add(this);}
    }
    private static void retryWaiting(){
        for(MenuAppIcon view:new java.util.ArrayList<>(waiting)){
            waiting.remove(view);
            if(view!=null&&view.isAttachedToWindow()&&!view.loaded&&view.pending==null)view.invalidate();
        }
    }
    @Override protected void onDetachedFromWindow(){
        generation++;
        waiting.remove(this);
        if(pending!=null){pending.cancel(false);if(pending instanceof Runnable)worker.remove((Runnable)pending);}
        pending=null;super.onDetachedFromWindow();retryWaiting();
    }
}
