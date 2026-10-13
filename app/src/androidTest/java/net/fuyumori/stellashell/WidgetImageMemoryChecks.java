package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Isolated PNGs and synthetic demand: no Activity/window, widget IDs, provider or saved layouts. */
final class WidgetImageMemoryChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void main(Instrumentation test,Runnable work){
        Throwable[] failure={null};test.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static Object field(Object target,String name){
        try{Field field=WidgetImageView.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static Bitmap displayed(WidgetImageView view){return view.getDrawable()==null?null:((BitmapDrawable)view.getDrawable()).getBitmap();}
    private static final class Request {
        final File file;final int width,height;final Bitmap bitmap;
        boolean fail;
        final CountDownLatch proceed=new CountDownLatch(1);
        Request(File file,int width,int height){this.file=file;this.width=width;this.height=height;bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);}
    }
    private static final class Loader implements WidgetImageView.Loader {
        final ArrayBlockingQueue<Request> requests=new ArrayBlockingQueue<>(8);
        final ConcurrentLinkedQueue<Request> submitted=new ConcurrentLinkedQueue<>();
        public Bitmap decode(File file,int width,int height){
            Request request=new Request(file,width,height);submitted.add(request);
            check(requests.offer(request),"Unexpected image request backlog");
            try{check(request.proceed.await(5,TimeUnit.SECONDS),"Image request was not released");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            if(request.fail){request.bitmap.recycle();return null;}
            return request.bitmap;
        }
        Request request()throws InterruptedException{
            Request request=requests.poll(5,TimeUnit.SECONDS);check(request!=null,"Visible image did not decode");return request;
        }
    }
    private static final class View extends WidgetImageView {
        boolean attached=true;
        View(Context context,File file,Loader loader){super(context,file,loader);}
        @Override boolean imageVisible(){return attached;}
        void detach(){attached=false;onDetachedFromWindow();}
        void attach(){attached=true;onAttachedToWindow();}
    }
    private static void decodedBytes(File file)throws Exception{
        Bitmap source=Bitmap.createBitmap(1600,800,Bitmap.Config.ARGB_8888);source.eraseColor(0xff197d63);
        source.setPixel(0,0,0);int originalBytes=source.getAllocationByteCount();
        try(FileOutputStream out=new FileOutputStream(file)){check(source.compress(Bitmap.CompressFormat.PNG,100,out),"PNG fixture did not save");}
        finally{source.recycle();}
        Bitmap small=WidgetImages.decodeForDisplay(file,400,150);
        try{
            check(small.getWidth()==300&&small.getHeight()==150,"Decode did not preserve FIT_CENTER aspect/bounds");
            check(small.getAllocationByteCount()<=300*150*4&&small.getAllocationByteCount()<originalBytes/10,
                    "Display image retained source-sized allocation");
        }finally{small.recycle();}
        Bitmap full=WidgetImages.decodeForDisplay(file,4000,4000);
        try{check(full.getWidth()==1600&&full.getHeight()==800,"Display decode upscaled source pixels");}
        finally{full.recycle();}
        android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(),bounds);
        check(bounds.outWidth==1600&&bounds.outHeight==800,"Display decode changed saved image resolution");
    }
    static void run(Instrumentation test)throws Exception{
        File file=File.createTempFile("widget-image-memory-",".png",test.getTargetContext().getCacheDir());
        Loader loader=new Loader();View[] view={null};Future<?>[] pending={null};long[] generation={0};
        try{
            decodedBytes(file);
            main(test,()->{
                view[0]=new View(test.getTargetContext(),file,loader);view[0].setContentDescription("Synthetic custom image");
                view[0].setPageVisible(false);view[0].layout(0,0,100,80);view[0].setOwnerActive(true);view[0].decodeNow();
                check(field(view[0],"pending")==null&&loader.requests.isEmpty(),"Hidden initial page decoded an image");
                view[0].setPageVisible(true);view[0].decodeNow();pending[0]=(Future<?>)field(view[0],"pending");
                generation[0]=(long)field(view[0],"generation");
            });
            Request first=loader.request();check(first.file.equals(file)&&first.width==100&&first.height==80,"Decode ignored actual display pixels");
            first.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(displayed(view[0])==first.bitmap,"Visible image failed to apply");
                view[0].setPageVisible(false);check(view[0].getDrawable()==null,"Hidden page retained its image");
                check(!first.bitmap.isRecycled(),"Already displayed image was unsafely recycled");
                Bitmap stale=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
                view[0].applyImage(generation[0],100,80,stale);
                check(stale.isRecycled()&&view[0].getDrawable()==null,"Hidden late result was retained");
                view[0].setPageVisible(true);view[0].decodeNow();pending[0]=(Future<?>)field(view[0],"pending");
            });
            Request second=loader.request();second.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(displayed(view[0])==second.bitmap,"Page replay did not reload its private image");
                view[0].setOwnerActive(false);view[0].decodeNow();
                check(view[0].getDrawable()==null&&field(view[0],"pending")==null,"Stopped owner retained/decoded image");
                view[0].setOwnerActive(true);view[0].decodeNow();pending[0]=(Future<?>)field(view[0],"pending");
            });
            Request cancelled=loader.request();
            main(test,()->{
                generation[0]=(long)field(view[0],"generation");view[0].detach();
                check(pending[0].isCancelled()&&field(view[0],"pending")==null&&view[0].getDrawable()==null,
                        "Detach failed to cancel/drop pending image work");
                view[0].decodeNow();check(loader.requests.isEmpty(),"Detached image submitted work");
                Bitmap stale=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);view[0].applyImage(generation[0],100,80,stale);
                check(stale.isRecycled(),"Cancelled late image was not discarded");
            });
            cancelled.proceed.countDown();
            main(test,()->{view[0].attach();view[0].decodeNow();pending[0]=(Future<?>)field(view[0],"pending");});
            Request attached=loader.request();attached.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(displayed(view[0])==attached.bitmap,"Reattach failed to reload");
                view[0].onConfigurationChanged(new Configuration(view[0].getResources().getConfiguration()));
                check(view[0].getDrawable()==null,"Configuration retained old decoded image");
                view[0].decodeNow();pending[0]=(Future<?>)field(view[0],"pending");
            });
            Request configured=loader.request();configured.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(displayed(view[0])==configured.bitmap,"Configuration did not reload current image");
                view[0].layout(0,0,110,85);view[0].decodeNow();
                check(displayed(view[0])==configured.bitmap,"Resize removed the old artwork before replacement");
                pending[0]=(Future<?>)field(view[0],"pending");
            });
            Request resizing=loader.request();
            main(test,()->{
                ThreadPoolExecutor worker=(ThreadPoolExecutor)field(null,"worker");
                for(int i=0;i<40;i++){
                    view[0].layout(0,0,120+i,90+i);view[0].decodeNow();
                    check(displayed(view[0])==configured.bitmap,"Fast resize blanked the displayed image");
                    check(worker.getQueue().size()<=1,"Fast resize queued obsolete image decodes");
                }
                check(pending[0].isCancelled(),"Resize did not cancel old decode");
                pending[0]=(Future<?>)field(view[0],"pending");
                check(worker.getQueue().remainingCapacity()+worker.getQueue().size()==16,"Image queue is unbounded");
            });
            resizing.proceed.countDown();
            Request latest=loader.request();check(latest.width==159&&latest.height==129,"Fast resize lost the latest dimensions");
            main(test,()->check(resizing.bitmap.isRecycled(),"Superseded resize result retained pixel memory"));
            latest.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(displayed(view[0])==latest.bitmap&&"Synthetic custom image".contentEquals(view[0].getContentDescription()),
                        "Latest resize image/accessibility failed");
                check(latest.bitmap.getWidth()==159&&latest.bitmap.getHeight()==129,"Latest resize applied wrong bitmap dimensions");
                view[0].layout(0,0,159,129);view[0].decodeNow();
                check(field(view[0],"pending")==null&&loader.requests.isEmpty(),"Unchanged bounds decoded the image again");
                view[0].layout(0,0,200,120);view[0].decodeNow();
                check(displayed(view[0])==latest.bitmap,"Pending resize discarded current artwork");
                pending[0]=(Future<?>)field(view[0],"pending");
            });
            Request failed=loader.request();failed.fail=true;failed.proceed.countDown();pending[0].get(5,TimeUnit.SECONDS);
            main(test,()->{
                check(displayed(view[0])==latest.bitmap,"Failed resize discarded previous artwork");
                for(int i=0;i<5;i++)view[0].decodeNow();
                check(field(view[0],"pending")==null&&loader.requests.isEmpty(),"Failed bounds retried on every draw");
                view[0].layout(0,0,159,129);view[0].decodeNow();
                check(displayed(view[0])==latest.bitmap&&field(view[0],"pending")==null,
                        "Returning to already loaded bounds decoded again");
                view[0].close();view[0].decodeNow();
                check(view[0].getDrawable()==null&&field(view[0],"pending")==null,"Closed image retained or restarted decode");
            });
        }finally{
            for(Request request:loader.submitted)request.proceed.countDown();
            main(test,()->{if(view[0]!=null)view[0].close();});
            for(Request request:loader.submitted)if(!request.bitmap.isRecycled())request.bitmap.recycle();
            file.delete();
        }
    }
}
