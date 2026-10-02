package net.fuyumori.stellashell;

import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;

/** Normal input belongs to the provider; only explicit editing owns a drag. */
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
    private float downX,downY;
    private boolean dragging,blocked;
    WidgetEditFrame(Context context,Actions actions){super(context);this.actions=actions;}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            blocked=false;downX=event.getRawX();downY=event.getRawY();
            if(actions.editing()){dragging=true;actions.select();actions.begin(actions.canResize()&&event.getX()>=getWidth()-Math.min(Ui.dp(getContext(),48),getWidth()/2)&&event.getY()>=getHeight()-Math.min(Ui.dp(getContext(),48),getHeight()/2));if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}

        }
        if(event.getPointerCount()>1){if(dragging){actions.end(true);dragging=false;blocked=true;}}
        if(blocked){if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)blocked=false;return true;}
        if(dragging){
            if(action==MotionEvent.ACTION_MOVE)actions.move(event.getRawX()-downX,event.getRawY()-downY);
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){actions.end(action==MotionEvent.ACTION_CANCEL);dragging=false;}
            return true;
        }
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
    // Empty provider regions do not install a launcher long-press handler.
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
    @Override public boolean performClick(){return super.performClick();}
    void cancelEditingGesture(){if(dragging){actions.end(true);dragging=false;}blocked=true;}
    @Override protected void onDetachedFromWindow(){if(dragging){actions.end(true);dragging=false;}super.onDetachedFromWindow();}
}
