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
    static void remove(Context c,String name){File f=file(c,name);if(f!=null)f.delete();}
}
