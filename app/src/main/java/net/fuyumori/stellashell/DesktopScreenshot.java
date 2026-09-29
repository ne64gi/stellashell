package net.fuyumori.stellashell;

import android.content.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

final class DesktopScreenshot {
    private static boolean busy;
    static void take(Context context,int displayId){
        if(busy)return;
        Context c=context.getApplicationContext();busy=true;
        Bridge.get(c).call(service->{
            Displays.require(c,displayId);
            ContentResolver resolver=c.getContentResolver();
            ContentValues values=new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,"StellaShell_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS",java.util.Locale.ROOT).format(new java.util.Date())+".png");
            values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/StellaShell");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
            Uri uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new java.io.IOException("Cannot create screenshot");
            boolean saved=false;
            try{
                try(ParcelFileDescriptor fd=resolver.openFileDescriptor(uri,"w")){
                    if(fd==null)throw new java.io.IOException("Cannot open screenshot");
                    String result=service.captureDisplay(displayId,fd);
                    if(!"OK".equals(result))throw new java.io.IOException(result);
                }
                values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
                if(resolver.update(uri,values,null,null)!=1)throw new java.io.IOException("Cannot publish screenshot");
                saved=true;return "OK";
            }finally{if(!saved)resolver.delete(uri,null,null);}
        },(result,error)->{busy=false;Ui.message(context,error==null?context.getString(R.string.screenshot_saved):context.getString(R.string.screenshot_failed)+" "+error);});
    }
}
