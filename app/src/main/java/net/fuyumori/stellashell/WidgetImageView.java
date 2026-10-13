package net.fuyumori.stellashell;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Private custom images are decoded only at their visible display size; no bitmap cache. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic only: requires an owned private image file.
class WidgetImageView extends ImageView {
    interface Loader { Bitmap decode(File file,int width,int height)throws IOException; }
    private static final Loader images=WidgetImages::decodeForDisplay;
    private static final Handler main=new Handler(Looper.getMainLooper());
    // Weak, main-thread demand; completed jobs, not a timer, retry a full queue.
    private static final Set<WidgetImageView> waiting=Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16)){
        @Override protected void afterExecute(Runnable task,Throwable failure){main.post(WidgetImageView::retryWaiting);}
    };
    static {worker.allowCoreThreadTimeOut(true);}
    private final File file;
    private final Loader loader;
    private final Runnable decodeFrame=this::decodeNow;
    private Future<?> pending;
    private long generation;
    private boolean pageVisible=true,ownerActive,closed,frameQueued;
    private int failedWidth,failedHeight,loadedWidth,loadedHeight;

    WidgetImageView(Context context,File file){this(context,file,images);}
    WidgetImageView(Context context,File file,Loader loader){
        super(context);this.file=file;this.loader=loader;setScaleType(ScaleType.FIT_CENTER);
    }
    boolean imageVisible(){return isAttachedToWindow()&&isShown();}
    private boolean wanted(){return !closed&&ownerActive&&pageVisible&&imageVisible();}
    void setOwnerActive(boolean active){
        if(ownerActive==active)return;ownerActive=active;
        if(active)scheduleDecode();else releaseImage();
    }
    void setPageVisible(boolean visible){
        if(pageVisible==visible)return;pageVisible=visible;
        if(visible)scheduleDecode();else releaseImage();
    }
    private void scheduleDecode(){
        if(!wanted()||frameQueued||pending!=null||getWidth()<=0||getHeight()<=0)return;
        int width=getWidth()-getPaddingLeft()-getPaddingRight(),height=getHeight()-getPaddingTop()-getPaddingBottom();
        if(!needsDecode(width,height))return;
        frameQueued=true;postOnAnimation(decodeFrame);
    }
    private boolean needsDecode(int width,int height){
        return width>0&&height>0&&!(failedWidth==width&&failedHeight==height)
                &&(getDrawable()==null||loadedWidth!=width||loadedHeight!=height);
    }
    void decodeNow(){
        removeCallbacks(decodeFrame);frameQueued=false;
        if(!wanted()||pending!=null)return;
        int width=getWidth()-getPaddingLeft()-getPaddingRight(),height=getHeight()-getPaddingTop()-getPaddingBottom();
        if(!needsDecode(width,height))return;
        long request=++generation;
        File source=file;Loader decode=loader;WeakReference<WidgetImageView> target=new WeakReference<>(this);
        try{pending=worker.submit(()->{
            Bitmap bitmap=null;
            try{bitmap=decode.decode(source,width,height);}catch(IOException|RuntimeException failure){/* Retry on new bounds or visible lifecycle, not every draw. */}
            Bitmap result=bitmap;
            main.post(()->{
                WidgetImageView view=target.get();
                if(view==null){discard(result);return;}
                view.applyImage(request,width,height,result);
            });
        });}catch(RejectedExecutionException full){pending=null;waiting.add(this);}
    }
    void applyImage(long request,int width,int height,Bitmap bitmap){
        if(generation!=request||!wanted()){
            discard(bitmap);
            if(generation==request){pending=null;releaseImage();}
            return;
        }
        pending=null;
        if(bitmap==null){failedWidth=width;failedHeight=height;return;}
        loadedWidth=width;loadedHeight=height;failedWidth=failedHeight=0;
        setImageBitmap(bitmap);
    }
    private static void discard(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    private void cancelDecode(){
        generation++;
        waiting.remove(this);removeCallbacks(decodeFrame);frameQueued=false;
        if(pending!=null){pending.cancel(false);if(pending instanceof Runnable)worker.remove((Runnable)pending);}
        pending=null;failedWidth=failedHeight=0;
    }
    private void releaseImage(){
        cancelDecode();loadedWidth=loadedHeight=0;
        // A previously drawn bitmap may still be used by RenderThread. Drop ownership;
        // only stale results that were never displayed are explicitly recycled.
        setImageDrawable(null);
    }
    void close(){closed=true;releaseImage();}
    private static void retryWaiting(){
        for(WidgetImageView view:new java.util.ArrayList<>(waiting)){
            waiting.remove(view);if(view!=null)view.scheduleDecode();
        }
    }
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
        super.onSizeChanged(width,height,oldWidth,oldHeight);
        // Preserve the current artwork while its FIT_CENTER matrix follows a drag.
        // Only a successful decode for the latest bounds replaces it.
        cancelDecode();scheduleDecode();
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);scheduleDecode();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();scheduleDecode();}
    @Override public void onVisibilityAggregated(boolean visible){super.onVisibilityAggregated(visible);if(visible)scheduleDecode();else releaseImage();}
    @Override protected void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);releaseImage();scheduleDecode();}
    @Override protected void onDetachedFromWindow(){releaseImage();super.onDetachedFromWindow();}
}
