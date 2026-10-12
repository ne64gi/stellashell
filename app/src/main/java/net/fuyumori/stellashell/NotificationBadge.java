package net.fuyumori.stellashell;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.view.View;

/** Decorative count. The owning actionable View provides the accessible label. */
final class NotificationBadge {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int count;
    boolean count(int count){int next=Math.max(0,count);if(this.count==next)return false;this.count=next;return true;}
    void draw(Canvas canvas,View view){
        if(count==0)return;
        float density=view.getResources().getDisplayMetrics().density;
        String text=count>99?"99+":Integer.toString(count);
        paint.setTextSize(10*density);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float height=16*density,width=Math.max(height,paint.measureText(text)+8*density);
        float right=Math.min(view.getWidth(),view.getWidth()-view.getPaddingRight()+4*density);
        float top=Math.max(0,view.getPaddingTop()-4*density);
        paint.setColor(0xffd33f5b);canvas.drawRoundRect(right-width,top,right,top+height,height/2,height/2,paint);
        paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text,right-width/2,top+height/2-(paint.ascent()+paint.descent())/2,paint);
    }
}
