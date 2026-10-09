package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.layout.WallpaperPlacement;

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
    private volatile int generation;
    private final boolean phone;
    private String key(String name){return WorkspaceProfile.key(activity,name);}
    private void matrix(ImageView image,boolean fit,float x,float y){
        android.graphics.drawable.Drawable d=image.getDrawable();if(d==null)return;
        float[] values=WallpaperPlacement.transform(d.getIntrinsicWidth(),d.getIntrinsicHeight(),image.getWidth(),image.getHeight(),fit,x,y);
        android.graphics.Matrix m=new android.graphics.Matrix();m.setScale(values[0],values[0]);m.postTranslate(values[1],values[2]);image.setImageMatrix(m);
    }
    private void place(){matrix(view,Launches.prefs(activity).getBoolean(key("wallpaper_fit"),false),Launches.prefs(activity).getFloat(key("wallpaper_x"),.5f),Launches.prefs(activity).getFloat(key("wallpaper_y"),.5f));}

    DesktopWallpaper(Activity activity){
        this.activity=activity;view=new ImageView(activity);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        WorkspaceProfile.initialize(activity);phone=WorkspaceProfile.phone(activity);
        file=new AtomicFile(new File(activity.getFilesDir(),phone?"phone-wallpaper.png":"desktop-wallpaper.png"));
        view.setScaleType(ImageView.ScaleType.MATRIX);view.setPadding(0,0,0,0);
        view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->place());
        // Serialize one-time image migration before loads/imports; never replace an existing Phone image.
        if(phone)io.execute(()->{
            if(Launches.prefs(activity).getBoolean("phone_wallpaper_seeded",false))return;
            FileOutputStream out=null;
            try{
                if(!file.getBaseFile().exists()){
                    AtomicFile desktop=new AtomicFile(new File(activity.getFilesDir(),"desktop-wallpaper.png"));
                    if(desktop.getBaseFile().exists())try(java.io.InputStream in=desktop.openRead()){
                        out=file.startWrite();byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);file.finishWrite(out);out=null;
                    }
                }
                Launches.prefs(activity).edit().putBoolean("phone_wallpaper_seeded",true).apply();
            }catch(Exception e){if(out!=null)file.failWrite(out);}
        });
    }
    void choose(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{DesktopBackdrop.startActivityForResult(activity,intent,PICK);}catch(RuntimeException e){Ui.message(activity,activity.getString(R.string.ui_could_not_choose_an_image)+e.getMessage());}
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
                main.post(()->{Launches.prefs(activity).edit().putBoolean(key("wallpaper_image"),true).apply();if(!closed)reload();});
            }catch(Exception e){if(output!=null)file.failWrite(output);main.post(()->{if(!closed)Ui.message(activity,activity.getString(R.string.ui_could_not_read_the_image_your_previous_wallpaper_is_unchanged));});}
            finally{if(bitmap!=null)bitmap.recycle();}
        });return true;
    }
    void reload(){
        if(closed)return;
        final int ticket=++generation;
        place();
        if(!Launches.prefs(activity).getBoolean(key("wallpaper_image"),false)){view.setImageDrawable(null);view.setVisibility(View.GONE);return;}
        io.execute(()->{
            if(closed||ticket!=generation)return;
            try{
                // Recover any interrupted atomic write before decoding the private file.
                try(java.io.FileInputStream ignored=file.openRead()){}
                if(closed||ticket!=generation)return;
                Bitmap bitmap=decode(ImageDecoder.createSource(file.getBaseFile()));
                if(closed||ticket!=generation){bitmap.recycle();return;}
                main.post(()->{if(closed||ticket!=generation){bitmap.recycle();return;}view.setImageBitmap(bitmap);view.setVisibility(View.VISIBLE);place();});
            }catch(Exception e){if(!closed&&ticket==generation)main.post(()->{if(!closed&&ticket==generation){view.setImageDrawable(null);view.setVisibility(View.GONE);Ui.message(activity,activity.getString(R.string.ui_could_not_read_the_saved_wallpaper_choose_an_image_again));}});}
        });
    }
    void position(){
        if(view.getDrawable()==null){choose();return;}
        android.widget.LinearLayout form=Ui.column(activity);int pad=Ui.dp(activity,16);form.setPadding(pad,pad,pad,pad);
        android.graphics.Point screen=new android.graphics.Point();activity.getDisplay().getRealSize(screen);
        ImageView preview=new ImageView(activity){@Override protected void onMeasure(int w,int h){
            int width=MeasureSpec.getSize(w),height=Math.min(Ui.dp(activity,320),Math.max(1,width*screen.y/Math.max(1,screen.x)));
            int fittedWidth=Math.min(width,Math.max(1,height*screen.x/Math.max(1,screen.y)));setMeasuredDimension(fittedWidth,height);
        }};
        preview.setImageDrawable(view.getDrawable().getConstantState()==null?view.getDrawable():view.getDrawable().getConstantState().newDrawable());
        preview.setScaleType(ImageView.ScaleType.MATRIX);preview.setBackgroundColor(Ui.BG);
        android.widget.LinearLayout.LayoutParams size=new android.widget.LinearLayout.LayoutParams(-1,Ui.dp(activity,320));size.gravity=android.view.Gravity.CENTER_HORIZONTAL;form.addView(preview,size);
        float[] focal={WallpaperPlacement.clamp(Launches.prefs(activity).getFloat(key("wallpaper_x"),.5f)),WallpaperPlacement.clamp(Launches.prefs(activity).getFloat(key("wallpaper_y"),.5f))};
        boolean[] fit={Launches.prefs(activity).getBoolean(key("wallpaper_fit"),false)};
        android.widget.CheckBox mode=new android.widget.CheckBox(activity);mode.setText(R.string.ui_fit_entire_image_with_margins);mode.setTextColor(Ui.TEXT);mode.setChecked(fit[0]);form.addView(mode);
        android.widget.SeekBar[] sliders={new android.widget.SeekBar(activity),new android.widget.SeekBar(activity)};
        Runnable render=()->matrix(preview,fit[0],focal[0],focal[1]);
        for(int i=0;i<2;i++){
            final int axis=i;sliders[i].setMax(100);sliders[i].setProgress(Math.round(focal[i]*100));sliders[i].setContentDescription(activity.getString(i==0?R.string.wallpaper_horizontal:R.string.wallpaper_vertical));
            form.addView(Ui.text(activity,activity.getString(i==0?R.string.wallpaper_horizontal:R.string.wallpaper_vertical),12,Ui.MUTED));form.addView(sliders[i]);
            sliders[i].setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(android.widget.SeekBar b,int value,boolean user){if(user){focal[axis]=value/100f;render.run();}}public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){}});
        }
        mode.setOnCheckedChangeListener((b,value)->{fit[0]=value;for(android.widget.SeekBar slider:sliders)slider.setEnabled(!value);render.run();});
        for(android.widget.SeekBar slider:sliders)slider.setEnabled(!fit[0]);
        float[] drag=new float[4];preview.setContentDescription(activity.getString(R.string.wallpaper_drag));
        preview.setOnTouchListener((v,event)->{
            if(fit[0])return false;
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){v.getParent().requestDisallowInterceptTouchEvent(true);drag[0]=event.getX();drag[1]=event.getY();drag[2]=focal[0];drag[3]=focal[1];return true;}
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE){
                android.graphics.drawable.Drawable d=preview.getDrawable();float[] m=WallpaperPlacement.transform(d.getIntrinsicWidth(),d.getIntrinsicHeight(),preview.getWidth(),preview.getHeight(),false,focal[0],focal[1]);
                focal[0]=WallpaperPlacement.clamp(drag[2]-(event.getX()-drag[0])/(d.getIntrinsicWidth()*m[0]));focal[1]=WallpaperPlacement.clamp(drag[3]-(event.getY()-drag[1])/(d.getIntrinsicHeight()*m[0]));
                for(int i=0;i<2;i++)sliders[i].setProgress(Math.round(focal[i]*100));render.run();return true;
            }
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP){preview.performClick();return true;}
            return true;
        });
        preview.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->render.run());Ui.note(form,activity.getString(R.string.wallpaper_drag));
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(form);
        DesktopBackdrop.showDialog(activity,new android.app.AlertDialog.Builder(activity).setTitle(R.string.wallpaper_position).setView(scroll).setNegativeButton(R.string.ui_cancel,null).setNeutralButton(R.string.ui_center,(d,w)->{Launches.prefs(activity).edit().putFloat(key("wallpaper_x"),.5f).putFloat(key("wallpaper_y"),.5f).apply();reload();}).setPositiveButton(R.string.ui_save,(d,w)->{
            Launches.prefs(activity).edit().putBoolean(key("wallpaper_fit"),fit[0]).putFloat(key("wallpaper_x"),focal[0]).putFloat(key("wallpaper_y"),focal[1]).apply();reload();
        }).create());
    }
    void destroy(){closed=true;generation++;io.shutdown();}
}
