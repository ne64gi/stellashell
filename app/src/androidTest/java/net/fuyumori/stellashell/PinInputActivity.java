package net.fuyumori.stellashell;

/** Solid-color, touch-observable disposable window; no personal app content. */
public class PinInputActivity extends android.app.Activity {
    static final int FIRST=0xff2468ac, SECOND=0xffd08020, FIRST_TOUCHED=0xff20a050, SECOND_TOUCHED=0xff9040c0;
    @Override public void onCreate(android.os.Bundle state){
        super.onCreate(state);
        boolean second=this instanceof PinOtherActivity;
        android.view.View view=new android.view.View(this);
        view.setBackgroundColor(second?SECOND:FIRST);
        view.setOnTouchListener((v,event)->{
            if(event.getAction()==android.view.MotionEvent.ACTION_DOWN)v.setBackgroundColor(second?SECOND_TOUCHED:FIRST_TOUCHED);
            return true;
        });
        setContentView(view);
    }
}
