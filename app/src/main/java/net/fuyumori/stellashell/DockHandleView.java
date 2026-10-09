package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.layout.DockPlacement;
import net.fuyumori.stellashell.core.layout.EdgeDockReveal;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Configurable edge input, with event-time tap recognition and deliberate inward pulls. */
final class DockHandleView extends View {
    interface Listener {
        void onDrag(float progress);
        void onRelease(boolean open);
        default void onGestureEnd() {}
    }
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String edge = "right";
    private int travel = 1, pointer = -1;
    private float startX, startY;
    private EdgeDockReveal.Pull gesture;
    private EdgeDockReveal.Method method=EdgeDockReveal.Method.SWIPE;
    private EdgeDockReveal.Taps taps;
    private final ViewConfiguration touch;

    DockHandleView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        touch=ViewConfiguration.get(context);taps=newTaps();
        setFocusable(true);
        setContentDescription(context.getString(R.string.dock_handle_open));
        setOnClickListener(v -> listener.onRelease(true));
    }
    void configure(String selected, int distance) {
        configure(selected,distance,EdgeDockReveal.Method.SWIPE);
    }
    void configure(String selected, int distance, EdgeDockReveal.Method selectedMethod) {
        String next = DockPlacement.edge(selected);
        int nextTravel = Math.max(1, distance);
        EdgeDockReveal.Method nextMethod=selectedMethod==null?EdgeDockReveal.Method.SWIPE:selectedMethod;
        boolean changed=!edge.equals(next)||travel!=nextTravel||method!=nextMethod;
        if (changed) reset();
        if(method!=nextMethod){method=nextMethod;taps=newTaps();}
        edge = next; travel = nextTravel;
        if(changed){
            int label=method==EdgeDockReveal.Method.SINGLE_TAP?R.string.dock_handle_single_tap:method==EdgeDockReveal.Method.DOUBLE_TAP?R.string.dock_handle_double_tap:R.string.dock_handle_open;
            CharSequence description=getContext().getString(label);setContentDescription(description);setTooltipText(description);invalidate();
        }
    }
    private EdgeDockReveal.Taps newTaps(){return new EdgeDockReveal.Taps(method,touch.getScaledTouchSlop(),touch.getScaledDoubleTapSlop(),ViewConfiguration.getLongPressTimeout(),ViewConfiguration.getDoubleTapTimeout());}
    boolean tracking() { return pointer != -1; }
    void reset() {
        boolean tracked = tracking();
        boolean preview = gesture != null && gesture.active();
        gesture = null; pointer = -1;taps.cancel();
        if (preview) listener.onRelease(false);
        if (tracked) listener.onGestureEnd();
    }
    @Override public boolean performClick() { reset(); return super.performClick(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if(tracking())reset();pointer = event.getPointerId(0);
            startX = event.getRawX(); startY = event.getRawY();
            if(method==EdgeDockReveal.Method.SWIPE)gesture = new EdgeDockReveal.Pull(edge, Ui.dp(getContext(),16), travel);
            else taps.begin(startX,startY,event.getEventTime());
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            reset(); return true;
        }
        if (!tracking() || event.getPointerCount() != 1 || event.getPointerId(0) != pointer)
            return true;
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            if(method!=EdgeDockReveal.Method.SWIPE){
                taps.move(event.getRawX(),event.getRawY());
                if(action==MotionEvent.ACTION_UP){
                    boolean open=taps.finish(event.getRawX(),event.getRawY(),event.getEventTime());pointer=-1;
                    if(event.isFromSource(InputDevice.SOURCE_MOUSE)){
                        float dx=event.getRawX()-startX,dy=event.getRawY()-startY;
                        taps.cancel();if(dx*dx+dy*dy<=touch.getScaledTouchSlop()*touch.getScaledTouchSlop())performClick();
                    }else if(open)listener.onRelease(true);
                    listener.onGestureEnd();
                }
                return true;
            }
            EdgeDockReveal.Pull current = gesture;
            float fraction = current.update(event.getRawX()-startX, event.getRawY()-startY);
            if (fraction >= 0) listener.onDrag(fraction);
            // Relayout may cancel or replace this gesture during the callback.
            if (gesture != current) return true;
            if (action == MotionEvent.ACTION_UP) {
                boolean captured = gesture.active(), open = gesture.finish(false);
                gesture = null; pointer = -1;
                if (captured) listener.onRelease(open);
                else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) performClick();
                listener.onGestureEnd();
            }
        }
        return true;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor((Ui.TEXT&0xffffff)|0x66000000);
        float half = Math.min(Ui.dp(getContext(),1), Math.min(getWidth(),getHeight())/2f);
        float x = getWidth()/2f, y = getHeight()/2f;
        if (DockPlacement.vertical(edge))
            canvas.drawRoundRect(x-half,y-getHeight()/4f,x+half,y+getHeight()/4f,half,half,paint);
        else canvas.drawRoundRect(x-getWidth()/4f,y-half,x+getWidth()/4f,y+half,half,half,paint);
    }
}
