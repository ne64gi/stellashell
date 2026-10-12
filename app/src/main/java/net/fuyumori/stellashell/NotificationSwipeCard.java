package net.fuyumori.stellashell;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import java.util.function.BooleanSupplier;

/** Per-card horizontal dismissal. Vertical motion stays with the containing ScrollView. */
@android.annotation.SuppressLint("ViewConstructor")
final class NotificationSwipeCard extends LinearLayout {
    private final BooleanSupplier clearable;
    private final Runnable dismiss;
    private final int slop;
    private float startX,startY;
    private boolean tracking,swiping,rejected,swallow,eligible;

    NotificationSwipeCard(Context context,BooleanSupplier clearable,Runnable dismiss){
        super(context);this.clearable=clearable;this.dismiss=dismiss;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();setOrientation(VERTICAL);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            resetVisual();tracking=true;eligible=clearable.getAsBoolean();swiping=false;rejected=false;swallow=false;
            startX=event.getRawX();startY=event.getRawY();
            // Decide the axis before ScrollView can steal a mostly-horizontal diagonal.
            if(tracking&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        }
        if(tracking&&eligible&&!clearable.getAsBoolean())cancelSwipe();
        if(tracking&&action==MotionEvent.ACTION_POINTER_DOWN){
            cancelChild(event);cancelSwipe();
        }
        if(tracking&&!rejected&&action==MotionEvent.ACTION_MOVE){
            float dx=event.getRawX()-startX,dy=event.getRawY()-startY;
            if(!swiping&&Math.abs(dy)>slop&&Math.abs(dy)>=Math.abs(dx)){
                rejected=true;releaseParent();
            }else if(!swiping&&Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.5f){
                swiping=true;cancelChild(event);
            }
            if(swiping){
                if(eligible){
                    setTranslationX(Math.max(-getWidth(),Math.min(getWidth(),dx)));
                    setAlpha(1f-Math.min(.65f,Math.abs(dx)/Math.max(1,getWidth())));
                }
                return true;
            }
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            boolean handled=swiping||swallow;
            float distance=Math.abs(event.getRawX()-startX);
            boolean commit=swiping&&eligible&&action==MotionEvent.ACTION_UP&&clearable.getAsBoolean()
                    &&distance>=Math.max(Ui.dp(getContext(),48),Math.min(Ui.dp(getContext(),120),getWidth()*.35f));
            tracking=false;swiping=false;swallow=false;releaseParent();
            if(handled){resetVisual();if(commit)dismiss.run();return true;}
        }
        if(swallow)return true;
        return super.dispatchTouchEvent(event)||tracking;
    }
    private void cancelChild(MotionEvent event){
        MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);cancel.recycle();
    }
    void cancelSwipe(){
        if(tracking){
            swallow=true;
            long now=android.os.SystemClock.uptimeMillis();
            MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);
            super.dispatchTouchEvent(event);event.recycle();
        }
        tracking=false;swiping=false;releaseParent();resetVisual();
    }
    private void releaseParent(){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    private void resetVisual(){setTranslationX(0);setAlpha(1);}
    @Override protected void onDetachedFromWindow(){cancelSwipe();super.onDetachedFromWindow();}
}
