package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

/** Device checks against real Android drawing and inverse input transforms; no desktop changes. */
public final class WidgetRenderInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            Throwable[] failure={null};
            runOnMainSync(()->{try{check(400,300,200,100);check(100,120,200,100);check(200,100,200,100);iconPreview();locales();}catch(Throwable error){failure[0]=error;}});
            if(failure[0]!=null)throw failure[0];
            wallpaperDecode();
            result.putString("stream","WidgetViewport: 3 rendering/input scenarios + bounded wallpaper decoding + locale checks passed\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream","FAILED: "+error+"\n");finish(0,result);}
    }
    private void check(int width,int height,int logicalWidth,int logicalHeight){
        WidgetViewport viewport=new WidgetViewport(getTargetContext());
        View child=new View(getTargetContext());child.setBackgroundColor(Color.CYAN);
        float[] touch={-1,-1};child.setOnTouchListener((v,event)->{touch[0]=event.getX();touch[1]=event.getY();return true;});
        viewport.addView(child);viewport.contentSize(logicalWidth,logicalHeight);
        viewport.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));viewport.layout(0,0,width,height);
        require(child.getWidth()==logicalWidth&&child.getHeight()==logicalHeight,"logical content was squeezed");
        float scale=WidgetGeometry.scale(width,height,logicalWidth,logicalHeight);
        require(child.getScaleX()==child.getScaleY(),"nonuniform scale");
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);viewport.draw(new Canvas(bitmap));
        require(bitmap.getPixel(width/2,height/2)==Color.CYAN,"content missing");
        if(height>logicalHeight*scale)require(bitmap.getPixel(width/2,0)==Color.TRANSPARENT,"letterbox was stretched");
        bitmap.recycle();
        float x=(width-logicalWidth*scale)/2f+logicalWidth*.75f*scale;
        float y=(height-logicalHeight*scale)/2f+logicalHeight*.25f*scale;
        MotionEvent event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,x,y,0);viewport.dispatchTouchEvent(event);event.recycle();
        require(Math.abs(touch[0]-logicalWidth*.75f)<.01f&&Math.abs(touch[1]-logicalHeight*.25f)<.01f,"input coordinate mismatch");
        event=MotionEvent.obtain(0,1,MotionEvent.ACTION_UP,x,y,0);viewport.dispatchTouchEvent(event);event.recycle();
    }
    private void locales(){
        android.content.res.Configuration configuration=new android.content.res.Configuration(getTargetContext().getResources().getConfiguration());
        configuration.setLocales(android.os.LocaleList.forLanguageTags("en"));
        android.content.Context en=getTargetContext().createConfigurationContext(configuration);
        configuration.setLocales(android.os.LocaleList.forLanguageTags("ja"));
        android.content.Context ja=getTargetContext().createConfigurationContext(configuration);
        configuration.setLocales(android.os.LocaleList.forLanguageTags("fr"));
        android.content.Context fallback=getTargetContext().createConfigurationContext(configuration);
        require("Wallpaper".equals(en.getString(R.string.ui_wallpaper)),"English locale");
        require("壁紙".equals(ja.getString(R.string.ui_wallpaper)),"Japanese locale");
        require("Wallpaper".equals(fallback.getString(R.string.ui_wallpaper)),"fallback locale");
        require("1 app".equals(en.getResources().getQuantityString(R.plurals.app_count,1,1)),"singular");
        require("2 apps".equals(en.getResources().getQuantityString(R.plurals.app_count,2,2)),"plural");
        require("Resize Clock".equals(en.getString(R.string.ui_resize,"Clock")),"English format");
        require("Clock のサイズを変更".equals(ja.getString(R.string.ui_resize,"Clock")),"Japanese format");
        require("外部ディスプレイが切断されています".equals(ErrorText.localize(ja,"ERROR: The external display is disconnected")),"backend error translation");
    }
    private void wallpaperDecode()throws Exception{
        java.io.File image=new java.io.File(getTargetContext().getCacheDir(),"wallpaper-test.png");
        try{
            Bitmap source=Bitmap.createBitmap(4096,128,Bitmap.Config.ARGB_8888);source.eraseColor(Color.MAGENTA);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(image)){source.compress(Bitmap.CompressFormat.PNG,100,out);}source.recycle();
            java.lang.reflect.Method decode=DesktopWallpaper.class.getDeclaredMethod("decode",android.graphics.ImageDecoder.Source.class);decode.setAccessible(true);
            Bitmap decoded=(Bitmap)decode.invoke(null,android.graphics.ImageDecoder.createSource(image));
            require(decoded.getWidth()==2048&&decoded.getHeight()==64,"wallpaper aspect or memory bound");decoded.recycle();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(image)){out.write(new byte[]{1,2,3});}
            boolean rejected=false;try{decode.invoke(null,android.graphics.ImageDecoder.createSource(image));}catch(java.lang.reflect.InvocationTargetException expected){rejected=true;}
            require(rejected,"invalid image was accepted");
        }finally{image.delete();}
    }
    private void iconPreview(){
        Bitmap bitmap=Bitmap.createBitmap(432,432,Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable icon=getTargetContext().getDrawable(net.fuyumori.stellashell.R.drawable.ic_desktop);
        icon.setBounds(0,0,432,432);icon.draw(new Canvas(bitmap));
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"icon-preview.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}catch(java.io.IOException e){throw new AssertionError(e);}finally{bitmap.recycle();}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
