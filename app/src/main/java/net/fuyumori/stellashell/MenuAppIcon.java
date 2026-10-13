package net.fuyumori.stellashell;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Rect;
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
class MenuAppIcon extends ImageView {
    private static final Handler main=new Handler(Looper.getMainLooper());
    // Main-thread-only weak demand: detached/collected Views never own queued work.
    private static final Set<MenuAppIcon> waiting=Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64)){
        @Override protected void afterExecute(Runnable task,Throwable failure){main.post(MenuAppIcon::retryWaiting);}
    };
    static {worker.allowCoreThreadTimeOut(true);}
    interface Loader { Drawable load(Context context,String component,Drawable original,int targetPx); }
    private static final Loader artwork=AppIcons::forApp;
    private final String component;
    private final Loader loader;
    private Drawable original;
    private Future<?> pending;
    private long generation;
    private boolean loaded,demand=true;
    private final Rect visibleBounds=new Rect();
    private final NotificationBadge badge=new NotificationBadge();
    void notificationCount(int count){if(badge.count(count))invalidate();}

    MenuAppIcon(Context context,String component,Drawable original){
        this(context,component,original,artwork);
    }
    MenuAppIcon(Context context,String component,Drawable original,Loader loader){
        super(context);this.component=component;this.original=original;this.loader=loader;
        setImageResource(android.R.drawable.sym_def_app_icon);
    }
    boolean artworkVisible(){return isAttachedToWindow()&&isShown()&&getLocalVisibleRect(visibleBounds);}
    void setArtworkDemand(boolean visible){
        if(demand==visible)return;
        demand=visible;
        if(visible)invalidate();else releaseArtwork();
    }
    private int artworkSize(){return Math.max(1,Math.min(256,Math.max(getWidth()-getPaddingLeft()-getPaddingRight(),getHeight()-getPaddingTop()-getPaddingBottom())));}
    private Context artworkContext(){
        Context application=getContext().getApplicationContext();
        if(application==null)return null;
        Context display=getDisplay()==null?application:application.createDisplayContext(getDisplay());
        return display.createConfigurationContext(new Configuration(getResources().getConfiguration()));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);badge.draw(canvas,this);
        if(loaded||pending!=null||!demand||!artworkVisible())return;
        Context context=artworkContext();
        if(context==null)return;
        long request=++generation;WeakReference<MenuAppIcon> target=new WeakReference<>(this);
        String identity=component;Drawable source=original;Loader resolve=loader;int targetPx=artworkSize();
        try{pending=worker.submit(()->{
            Drawable icon;
            try{icon=resolve.load(context,identity,source,targetPx);}
            catch(RuntimeException failure){icon=context.getDrawable(android.R.drawable.sym_def_app_icon);}
            Drawable result=icon;
            main.post(()->{
                MenuAppIcon view=target.get();
                if(view!=null)view.applyArtwork(request,result);
            });
            // Completion retains only the rendered result, never the source drawable.
        });original=null;
        }catch(RejectedExecutionException full){pending=null;waiting.add(this);}
    }
    void applyArtwork(long request,Drawable result){
        if(generation!=request)return;
        pending=null;
        if(!demand||!artworkVisible()){releaseArtwork();return;}
        original=null;loaded=true;setImageDrawable(result);
    }
    private static void retryWaiting(){
        for(MenuAppIcon view:new java.util.ArrayList<>(waiting)){
            waiting.remove(view);
            if(view!=null&&view.demand&&view.artworkVisible()&&!view.loaded&&view.pending==null)view.invalidate();
        }
    }
    private void releaseArtwork(){
        generation++;
        waiting.remove(this);
        if(pending!=null){pending.cancel(false);if(pending instanceof Runnable)worker.remove((Runnable)pending);}
        pending=null;original=null;loaded=false;setImageDrawable(null);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();demand=true;invalidate();}
    @Override protected void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);releaseArtwork();if(demand)invalidate();}
    @Override protected void onDetachedFromWindow(){
        demand=false;releaseArtwork();super.onDetachedFromWindow();retryWaiting();
    }
}
