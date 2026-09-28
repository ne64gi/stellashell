package net.fuyumori.stellashell;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.view.View;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-selected image, normalized off the UI thread into app-private storage. */
final class DesktopWallpaper {
    static final int PICK=7201;
    private final Activity activity;
    final ImageView view;
    private final AtomicFile file;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private int generation;
    DesktopWallpaper(Activity activity){
        this.activity=activity;view=new ImageView(activity);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        file=new AtomicFile(new File(activity.getFilesDir(),"desktop-wallpaper.png"));
    }
    void choose(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{activity.startActivityForResult(intent,PICK);}catch(RuntimeException e){Ui.message(activity,activity.getString(R.string.ui_could_not_choose_an_image)+e.getMessage());}
    }
    private static Bitmap decode(ImageDecoder.Source source)throws java.io.IOException{
        return ImageDecoder.decodeBitmap(source,(decoder,info,s)->{
            int width=info.getSize().getWidth(),height=info.getSize().getHeight();
            float scale=Math.min(1f,2048f/Math.max(width,height));
            decoder.setTargetSize(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }
    boolean result(int request,int result,Intent data){
        if(request!=PICK)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        android.net.Uri uri=data.getData();
        io.execute(()->{
            Bitmap bitmap=null;FileOutputStream output=null;
            try{
                bitmap=decode(ImageDecoder.createSource(activity.getContentResolver(),uri));
                output=file.startWrite();
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw new java.io.IOException(activity.getString(R.string.ui_could_not_save_the_image));
                file.finishWrite(output);output=null;
                main.post(()->{Launches.prefs(activity).edit().putBoolean("wallpaper_image",true).apply();if(!closed)reload();});
            }catch(Exception e){if(output!=null)file.failWrite(output);main.post(()->{if(!closed)Ui.message(activity,activity.getString(R.string.ui_could_not_read_the_image_your_previous_wallpaper_is_unchanged));});}
            finally{if(bitmap!=null)bitmap.recycle();}
        });return true;
    }
    void reload(){
        final int ticket=++generation;
        view.setScaleType(Launches.prefs(activity).getBoolean("wallpaper_fit",false)?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);
        if(!Launches.prefs(activity).getBoolean("wallpaper_image",false)){view.setImageDrawable(null);view.setVisibility(View.GONE);return;}
        io.execute(()->{
            try{
                // Recover any interrupted atomic write before decoding the private file.
                try(java.io.FileInputStream ignored=file.openRead()){}
                Bitmap bitmap=decode(ImageDecoder.createSource(file.getBaseFile()));
                main.post(()->{if(closed||ticket!=generation){bitmap.recycle();return;}view.setImageBitmap(bitmap);view.setVisibility(View.VISIBLE);});
            }catch(Exception e){main.post(()->{if(!closed&&ticket==generation){view.setImageDrawable(null);view.setVisibility(View.GONE);Ui.message(activity,activity.getString(R.string.ui_could_not_read_the_saved_wallpaper_choose_an_image_again));}});}
        });
    }
    void destroy(){closed=true;generation++;io.shutdown();}
}
