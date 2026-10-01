package net.fuyumori.stellashell;

import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;

/** Observes a hold without stealing taps or scrolling from the provider. Editing owns the gesture. */
final class WidgetEditFrame extends FrameLayout {
    interface Actions {
        boolean editing();
        void select();
        boolean canResize();
        void begin(boolean resize);
        void move(float dx,float dy);
        void end(boolean cancel);
    }
    private final Actions actions;
    private final int slop;
    private float downX,downY;
    private boolean holding,dragging,blocked;
    private MotionEvent last;
    private final Runnable hold=this::held;
    private void held(){
        if(!holding||last==null)return;
        MotionEvent cancel=MotionEvent.obtain(last);cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);cancel.recycle();holding=false;
        performLongClick();dragging=true;actions.begin(false);
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
    }
    WidgetEditFrame(Context context,Actions actions){
        super(context);this.actions=actions;slop=ViewConfiguration.get(context).getScaledTouchSlop();
        setOnLongClickListener(v->{actions.select();performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);return true;});
        setOnContextClickListener(v->performLongClick());
    }
    private void clearHold(){holding=false;removeCallbacks(hold);if(last!=null){last.recycle();last=null;}}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            clearHold();blocked=false;downX=event.getRawX();downY=event.getRawY();
            if(actions.editing()){dragging=true;actions.select();actions.begin(actions.canResize()&&event.getX()>=getWidth()-Math.min(Ui.dp(getContext(),48),getWidth()/2)&&event.getY()>=getHeight()-Math.min(Ui.dp(getContext(),48),getHeight()/2));if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}
            holding=true;last=MotionEvent.obtain(event);postDelayed(hold,ViewConfiguration.getLongPressTimeout());
        }
        if(event.getPointerCount()>1){clearHold();if(dragging){actions.end(true);dragging=false;blocked=true;}}
        if(blocked){if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)blocked=false;return true;}
        if(dragging){
            if(action==MotionEvent.ACTION_MOVE)actions.move(event.getRawX()-downX,event.getRawY()-downY);
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){actions.end(action==MotionEvent.ACTION_CANCEL);dragging=false;clearHold();}
            return true;
        }
        if(action==MotionEvent.ACTION_MOVE&&Math.hypot(event.getRawX()-downX,event.getRawY()-downY)>slop)clearHold();
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)clearHold();
        // Claim the sequence even when a provider's empty region does not consume DOWN.
        super.dispatchTouchEvent(event);return true;
    }
    @Override protected void dispatchDraw(android.graphics.Canvas canvas){
        super.dispatchDraw(canvas);
        if(!actions.editing()||!actions.canResize())return;
        android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float unit=getResources().getDisplayMetrics().density,right=getWidth(),bottom=getHeight();
        paint.setColor(Ui.PANEL);canvas.drawCircle(right-14*unit,bottom-14*unit,14*unit,paint);
        paint.setColor(Ui.ACCENT);paint.setStrokeWidth(2*unit);paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        for(int i=0;i<3;i++){float length=(6+i*5)*unit;canvas.drawLine(right-6*unit-length,bottom-6*unit,right-6*unit,bottom-6*unit-length,paint);}
    }
    // The frame owns hold timing; View's second long-press timer must not fire after a scroll.
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
    @Override public boolean performClick(){return super.performClick();}
    void cancelEditingGesture(){clearHold();if(dragging){actions.end(true);dragging=false;}blocked=true;}
    @Override protected void onDetachedFromWindow(){clearHold();if(dragging){actions.end(true);dragging=false;}super.onDetachedFromWindow();}
}
