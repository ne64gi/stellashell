package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Synthetic view demand and artwork only: no activity/window, packages, settings or OS notifications. */
final class MenuIconDemandChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void main(Instrumentation test,Runnable work){
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static Object field(MenuAppIcon icon,String name){
        try{Field field=MenuAppIcon.class.getDeclaredField(name);field.setAccessible(true);return field.get(icon);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static final class Request {
        final Context context;
        final Drawable original,result=new ColorDrawable(0xff126546);
        final int pixels;
        final CountDownLatch proceed=new CountDownLatch(1);
        Request(Context context,Drawable original,int pixels){this.context=context;this.original=original;this.pixels=pixels;}
    }
    private static final class Loader implements MenuAppIcon.Loader {
        final ArrayBlockingQueue<Request> requests=new ArrayBlockingQueue<>(8);
        final ConcurrentLinkedQueue<Request> submitted=new ConcurrentLinkedQueue<>();
        public Drawable load(Context context,String component,Drawable original,int targetPx){
            Request request=new Request(context,original,targetPx);
            submitted.add(request);
            check(requests.offer(request),"Unbounded icon demand");
            try{check(request.proceed.await(5,TimeUnit.SECONDS),"Icon test did not release request");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            return request.result;
        }
        Request request() throws InterruptedException {
            Request request=requests.poll(5,TimeUnit.SECONDS);
            check(request!=null,"Visible icon did not request artwork");return request;
        }
    }
    private static final class Icon extends MenuAppIcon {
        boolean attached=true,visible=true;
        Icon(Context context,Drawable original,Loader loader){super(context,"synthetic/synthetic.Icon",original,loader);}
        @Override boolean artworkVisible(){return attached&&visible;}
        void paint(Canvas canvas){onDraw(canvas);}
        void detach(){attached=false;onDetachedFromWindow();}
        void attach(){attached=true;onAttachedToWindow();}
    }
    static void run(Instrumentation test) throws Exception {
        Loader loader=new Loader();Icon[] icon={null};Future<?>[] pending={null};long[] generation={0};
        ColorDrawable original=new ColorDrawable(0xff354678);
        Bitmap bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        int[] clicks={0};
        try{
            main(test,()->{
                icon[0]=new Icon(test.getTargetContext(),original,loader);icon[0].layout(0,0,42,42);
                icon[0].setPadding(4,4,4,4);icon[0].setContentDescription("Synthetic app");
                icon[0].setOnClickListener(view->clicks[0]++);icon[0].notificationCount(7);
                icon[0].paint(canvas);pending[0]=(Future<?>)field(icon[0],"pending");
                generation[0]=(long)field(icon[0],"generation");
                check(field(icon[0],"original")==null,"Submitted request retained its original on the view");
            });
            Request first=loader.request();
            check(first.original==original,"Optional original was not passed to its first resolution");
            check(first.pixels==34,"Icon ignored actual padded pixel size");
            check(!(first.context instanceof android.app.Activity),"Worker retained an Activity context");
            check(first.context.getResources().getConfiguration().densityDpi==test.getTargetContext().getResources().getConfiguration().densityDpi,
                    "Worker context lost the view density");
            first.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(icon[0].getDrawable()==first.result&&field(icon[0],"original")==null,"Resolution retained source or lost artwork");
                icon[0].visible=false;icon[0].setArtworkDemand(false);
                check(icon[0].getDrawable()==null&&field(icon[0],"pending")==null,"Offscreen artwork/pending request retained");
                icon[0].applyArtwork(generation[0],new ColorDrawable(0xffabcdef));
                check(icon[0].getDrawable()==null,"Late offscreen result restored artwork");
                icon[0].paint(canvas);check(loader.requests.isEmpty(),"Offscreen icon submitted new work");
                icon[0].visible=true;icon[0].setArtworkDemand(true);icon[0].paint(canvas);
                pending[0]=(Future<?>)field(icon[0],"pending");generation[0]=(long)field(icon[0],"generation");
            });
            Request second=loader.request();check(second.original==null,"Reentry retained the original drawable");
            second.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(icon[0].getDrawable()==second.result,"Visible reentry failed to resolve artwork");
                icon[0].detach();check(icon[0].getDrawable()==null,"Detached view retained artwork");
                icon[0].applyArtwork(generation[0],first.result);check(icon[0].getDrawable()==null,"Stale detached result accepted");
                icon[0].attach();icon[0].layout(0,0,600,600);icon[0].paint(canvas);
                pending[0]=(Future<?>)field(icon[0],"pending");generation[0]=(long)field(icon[0],"generation");
            });
            Request third=loader.request();check(third.pixels==256,"Large view exceeded raster size cap");
            third.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(icon[0].getDrawable()==third.result,"Reattach failed to reload artwork");
                icon[0].applyArtwork(generation[0]-1,first.result);
                check(icon[0].getDrawable()==third.result,"Stale result overwrote reattached artwork");
                icon[0].onConfigurationChanged(new Configuration(icon[0].getResources().getConfiguration()));
                check(icon[0].getDrawable()==null,"Configuration change retained old raster artwork");
                icon[0].layout(0,0,42,42);icon[0].visible=false;icon[0].setArtworkDemand(false);
                bitmap.eraseColor(0);icon[0].paint(canvas);
                boolean badge=false;
                for(int y=0;y<42&&!badge;y++)for(int x=0;x<42;x++)if(bitmap.getPixel(x,y)==0xffd33f5b){badge=true;break;}
                check(badge,"Releasing artwork removed notification count rendering");
                check(icon[0].performClick()&&clicks[0]==1&&"Synthetic app".contentEquals(icon[0].getContentDescription()),
                        "Icon demand changed click or accessibility controls");
                icon[0].visible=true;icon[0].setArtworkDemand(true);icon[0].paint(canvas);
                pending[0]=(Future<?>)field(icon[0],"pending");generation[0]=(long)field(icon[0],"generation");
            });
            Request fourth=loader.request();
            main(test,()->{
                icon[0].detach();
                check(pending[0].isCancelled()&&field(icon[0],"pending")==null&&icon[0].getDrawable()==null,
                        "Detach retained or failed to cancel a running request");
                icon[0].applyArtwork(generation[0],fourth.result);
                check(icon[0].getDrawable()==null,"Cancelled request restored detached artwork");
            });
            fourth.proceed.countDown();
        }finally{
            for(Request request:loader.submitted)request.proceed.countDown();
            main(test,()->{if(icon[0]!=null)icon[0].detach();});bitmap.recycle();
        }
    }
}
