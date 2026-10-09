package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import net.fuyumori.stellashell.core.layout.CornerDockGesture;

/** An owned practice surface, using the same recognizer as the optional global gesture. */
final class DockGestureTutorial {
    private DockGestureTutorial() {}
    static AlertDialog show(Activity activity,Runnable use){
        LinearLayout column=Ui.column(activity);int padding=Ui.dp(activity,20);column.setPadding(padding,padding,padding,padding);
        Ui.note(column,activity.getString(R.string.dock_gesture_instruction));
        TextView status=DashboardUi.text(activity,activity.getString(R.string.dock_gesture_practice),14,Ui.TEXT);
        status.setMinHeight(Ui.dp(activity,48));status.setMaxLines(2);column.addView(status);
        Practice practice=new Practice(activity,status);
        int height=Math.min(220,Math.max(144,activity.getResources().getConfiguration().screenHeightDp-240));
        column.addView(practice,new LinearLayout.LayoutParams(-1,Ui.dp(activity,height)));
        Ui.note(column,activity.getString(R.string.dock_gesture_overlap));
        Ui.note(column,activity.getString(R.string.home_recovery_note));
        ScrollView scroll=new ScrollView(activity);scroll.addView(column);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.dock_gesture_title).setView(scroll)
                .setPositiveButton(R.string.dock_gesture_use,(choice,which)->use.run()).setNegativeButton(R.string.ui_cancel,null).show();
        practice.post(()->practice.requestRectangleOnScreen(new Rect(0,0,practice.getWidth(),practice.getHeight()),true));
        return dialog;
    }
    private static final class Practice extends View {
        final CornerDockGesture gesture=new CornerDockGesture();
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextView status;
        final float density;
        boolean accepted;
        boolean touching;
        float fingerX,fingerY;
        Practice(Activity activity,TextView status){
            super(activity);this.status=status;density=getResources().getDisplayMetrics().density;
            setContentDescription(activity.getString(R.string.dock_gesture_practice));setBackground(Ui.rounded(activity,(Ui.TEXT&0xffffff)|0x0a000000,12));
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float center=getWidth()/2f,top=getHeight()*.38f,bottom=getHeight()*.73f,side=56*density;
            paint.setColor(accepted?Ui.ACCENT:Ui.MUTED);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*density);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            Path path=new Path();path.moveTo(center,bottom);path.lineTo(center,top);path.lineTo(center+side,top);
            path.moveTo(center-7*density,top+20*density);path.lineTo(center,top+12*density);path.lineTo(center+7*density,top+20*density);
            path.moveTo(center+side-9*density,top-9*density);path.lineTo(center+side,top);path.lineTo(center+side-9*density,top+9*density);
            path.moveTo(center,top);path.lineTo(center-side,top);path.moveTo(center-side+9*density,top-9*density);path.lineTo(center-side,top);path.lineTo(center-side+9*density,top+9*density);canvas.drawPath(path,paint);
            paint.setStyle(Paint.Style.FILL);canvas.drawCircle(center,bottom,5*density,paint);
            if(touching){paint.setColor(Ui.ACCENT);canvas.drawCircle(fingerX,fingerY,8*density,paint);}
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked();float x=event.getX()/density,y=event.getY()/density;
            fingerX=event.getX();fingerY=event.getY();
            if(action==MotionEvent.ACTION_DOWN){getParent().requestDisallowInterceptTouchEvent(true);accepted=false;touching=true;gesture.begin(x,y,event.getEventTime(),getWidth()/density,getHeight()/density);feedback();invalidate();return true;}
            if(event.getPointerCount()!=1||action==MotionEvent.ACTION_CANCEL){gesture.cancel();touching=false;feedback();invalidate();getParent().requestDisallowInterceptTouchEvent(false);return true;}
            for(int i=0;i<event.getHistorySize();i++)gesture.move(event.getHistoricalX(i)/density,event.getHistoricalY(i)/density,event.getHistoricalEventTime(i));
            if(action==MotionEvent.ACTION_UP){
                CornerDockGesture.Result result=gesture.finish(x,y,event.getEventTime());accepted=result!=null;
                touching=false;status.setText(result==null?R.string.dock_gesture_retry:R.string.dock_gesture_ok);
                getParent().requestDisallowInterceptTouchEvent(false);performClick();invalidate();
            }else if(action==MotionEvent.ACTION_MOVE){gesture.move(x,y,event.getEventTime());feedback();invalidate();}
            return true;
        }
        private void feedback(){
            switch(gesture.progress()){
                case UP:status.setText(R.string.dock_gesture_up);break;
                case TURN:status.setText(R.string.dock_gesture_turn);break;
                case READY:status.setText(R.string.dock_gesture_release);break;
                default:status.setText(R.string.dock_gesture_retry);
            }
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
