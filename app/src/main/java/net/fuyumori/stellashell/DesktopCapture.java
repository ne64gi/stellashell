package net.fuyumori.stellashell;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import java.io.FileOutputStream;

/** Shell-side logical-display capture. Never falls back to display 0 or captures secure layers. */
final class DesktopCapture {
    static String write(int id,ParcelFileDescriptor output)throws Exception {
        Class<?> capture=Class.forName("android.window.ScreenCapture");
        Object listener=capture.getMethod("createSyncCaptureListener").invoke(null);
        Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        Class.forName("android.view.IWindowManager").getMethod("captureDisplay",int.class,
            Class.forName("android.window.ScreenCapture$CaptureArgs"),Class.forName("android.window.ScreenCapture$ScreenCaptureListener"))
            .invoke(wm,id,null,listener);
        Object buffer=Class.forName("android.window.ScreenCapture$SynchronousScreenCaptureListener").getMethod("getBuffer").invoke(listener);
        if(buffer==null)throw new IllegalStateException("Display capture unavailable on this framework");
        Bitmap bitmap=(Bitmap)buffer.getClass().getMethod("asBitmap").invoke(buffer);
        if(bitmap==null)throw new IllegalStateException("Empty capture");
        try(FileOutputStream stream=new FileOutputStream(output.getFileDescriptor())){
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new java.io.IOException("PNG encoding failed");
            return "OK";
        }finally{bitmap.recycle();}
    }
}
