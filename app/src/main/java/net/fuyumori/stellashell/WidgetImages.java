package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;
import java.util.UUID;

/** Bounded private copies: no persistent URI grant or dependency on the source file. */
final class WidgetImages {
    static File file(Context c,String name){
        if(!name.matches("[a-f0-9-]{36}\\.png"))return null;
        return new File(new File(c.getFilesDir(),"widget-images"),name);
    }
    static String copy(Context c,Uri uri)throws IOException{
        Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(),uri),(decoder,info,source)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,1600f/Math.max(w,h));
            decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        String name=UUID.randomUUID()+".png";File out=file(c,name);
        try{
            if(!out.getParentFile().isDirectory()&&!out.getParentFile().mkdirs())throw new IOException("Image directory unavailable");
            try(OutputStream stream=new FileOutputStream(out)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("Image could not be saved");}
            return name;
        }catch(IOException|RuntimeException e){out.delete();throw e;}finally{bitmap.recycle();}
    }
    /** Display decode only; keep the private saved image at its existing import resolution. */
    static Bitmap decodeForDisplay(File file,int width,int height)throws IOException{
        if(width<=0||height<=0)throw new IllegalArgumentException("Image bounds must be positive");
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),(decoder,info,source)->{
            int sourceWidth=info.getSize().getWidth(),sourceHeight=info.getSize().getHeight();
            double scale=Math.min(1d,Math.min((double)width/sourceWidth,(double)height/sourceHeight));
            decoder.setTargetSize(Math.max(1,(int)Math.floor(sourceWidth*scale)),Math.max(1,(int)Math.floor(sourceHeight*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }
    static void remove(Context c,String name){File f=file(c,name);if(f!=null)f.delete();}
}
