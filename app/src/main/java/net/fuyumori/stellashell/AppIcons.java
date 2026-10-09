package net.fuyumori.stellashell;

import android.graphics.*;
import android.graphics.drawable.*;

/** Preserve foreground alpha, without the Android adaptive-icon background plate. */
final class AppIcons {
    static Drawable forApp(android.content.Context c,String component,Drawable original){return IconTheme.resolve(c,component,original);}
    static Drawable stella(android.content.Context c){return display(c.getDrawable(R.mipmap.ic_launcher));}
    static Drawable display(Drawable source){
        if(source instanceof AdaptiveIconDrawable){
            Drawable foreground=((AdaptiveIconDrawable)source).getForeground();
            if(foreground!=null)return new Foreground(copy(foreground));
        }
        return copy(source);
    }
    private static Drawable copy(Drawable source){Drawable.ConstantState state=source.getConstantState();return (state==null?source:state.newDrawable()).mutate();}
    private static final class Foreground extends Drawable {
        private final Drawable foreground;
        Foreground(Drawable foreground){this.foreground=foreground;}
        @Override public void draw(Canvas canvas){
            Rect b=getBounds();int save=canvas.save();canvas.clipRect(b);
            float inset=AdaptiveIconDrawable.getExtraInsetFraction();int x=Math.round(b.width()*inset),y=Math.round(b.height()*inset);
            foreground.setBounds(b.left-x,b.top-y,b.right+x,b.bottom+y);foreground.draw(canvas);canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha){foreground.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){foreground.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        @Override public int getIntrinsicWidth(){return foreground.getIntrinsicWidth();}
        @Override public int getIntrinsicHeight(){return foreground.getIntrinsicHeight();}
    }
}
