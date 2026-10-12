package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Canvas;
import android.widget.ImageButton;

final class BadgedAppButton extends ImageButton {
    private final NotificationBadge badge=new NotificationBadge();
    BadgedAppButton(Context context){super(context);}
    void notificationCount(int count){if(badge.count(count))invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);badge.draw(canvas,this);}
}
