package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic images only: no app launch, installed-app icons, settings writes or heap dumps. */
final class IconArtworkChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static final class Wide extends Drawable {
        @Override public void draw(Canvas c){android.graphics.Paint p=new android.graphics.Paint();p.setColor(Color.MAGENTA);c.drawRect(getBounds(),p);}
        @Override public int getIntrinsicWidth(){return 4000;}
        @Override public int getIntrinsicHeight(){return 2000;}
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }
    private static IconArtwork.Key key(Context c,SharedPreferences p,String component,int pixels){
        return new IconArtwork.Key(c,p,component,component,"","",0,pixels);
    }
    static void run(Instrumentation test){
        Context c=test.getTargetContext();SharedPreferences prefs=c.getSharedPreferences("instrumentation_icon_memory",0);
        IconArtwork.clear();
        try{
            Wide source=new Wide();source.setBounds(1,2,3,4);
            Bitmap wide=IconArtwork.render(source,2048);
            require(wide.getWidth()==256&&wide.getHeight()==128,"Intrinsic size escaped raster bound/aspect");
            require(source.getBounds().equals(new Rect(1,2,3,4)),"Raster changed source bounds");
            wide.recycle();

            Drawable adaptive=AppIcons.display(new AdaptiveIconDrawable(new ColorDrawable(Color.RED),new ColorDrawable(Color.TRANSPARENT)));
            Bitmap alpha=IconArtwork.render(adaptive,32);
            require(Color.alpha(alpha.getPixel(16,16))==0,"Adaptive background plate reintroduced");alpha.recycle();
            Bitmap pack=IconArtwork.render(new ColorDrawable(Color.RED),32);
            require(pack.getPixel(16,16)==Color.RED,"Pack artwork background stripped");pack.recycle();

            AtomicInteger decodes=new AtomicInteger();IconArtwork.Key key=key(c,prefs,"fixture/.Same",48);
            Drawable first=IconArtwork.resolve(c,key,48,()->{decodes.incrementAndGet();return new ColorDrawable(Color.BLUE);});
            Drawable second=IconArtwork.resolve(c,key,48,()->{throw new AssertionError("Cache hit decoded source");});
            require(decodes.get()==1&&first!=second,"Drawable instances shared across views");
            first.setAlpha(30);require(second.getAlpha()==255,"View alpha leaked to another view");
            Bitmap visible=((BitmapDrawable)first).getBitmap();
            for(int i=0;i<100;i++)IconArtwork.resolve(c,key(c,prefs,"fixture/."+i,256),256,()->new ColorDrawable(Color.GREEN));
            require(IconArtwork.cachedBytes()<=IconArtwork.MAX_BYTES&&IconArtwork.cachedEntries()<=IconArtwork.MAX_ENTRIES,"Cache exceeded byte/count budget");
            require(!visible.isRecycled()&&visible.getPixel(0,0)==Color.BLUE,"Eviction recycled pixels held by visible view");
            IconArtwork.clear();
            IconArtwork.resolve(c,key,48,()->{IconArtwork.clear();return new ColorDrawable(Color.YELLOW);});
            require(IconArtwork.cachedEntries()==0,"Pre-invalidation render repopulated cache");
        }finally{IconArtwork.clear();}
    }
}
