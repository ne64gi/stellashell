package net.fuyumori.stellashell;

import android.app.*;
import android.content.Intent;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** Synthetic input through the actual attached tutorial/ScrollView, never global input injection. */
final class DockGestureTutorialChecks {
    static void run(Instrumentation test) throws Exception {
        Activity activity=test.startActivitySync(new Intent(test.getTargetContext(),SidebarSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AlertDialog[] dialog={null};View[] practice={null};CountDownLatch drawn=new CountDownLatch(1);
        try{
            test.runOnMainSync(()->{
                dialog[0]=DockGestureTutorial.show(activity,()->{throw new AssertionError("Practice saved user settings");});
                View root=dialog[0].getWindow().getDecorView();practice[0]=find(root);
                root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
                    public boolean onPreDraw(){root.getViewTreeObserver().removeOnPreDrawListener(this);drawn.countDown();return true;}
                });
            });
            check(drawn.await(5,TimeUnit.SECONDS),"Tutorial did not draw");test.waitForIdleSync();
            Throwable[] failure={null};
            test.runOnMainSync(()->{try{
                View p=practice[0],root=dialog[0].getWindow().getDecorView();
                check(p!=null&&p.isAttachedToWindow()&&p.getWidth()>0,"Practice not attached");
                float d=p.getResources().getDisplayMetrics().density;
                int[] a=new int[2],b=new int[2];p.getLocationOnScreen(a);root.getLocationOnScreen(b);
                float x=a[0]-b[0]+p.getWidth()/2f,y=a[1]-b[1]+p.getHeight()-12*d;
                check(y<root.getHeight(),"Practice start clipped");
                TextView status=(TextView)field(p,"status");
                for(int sign:new int[]{-1,1}){
                    long t=SystemClock.uptimeMillis();
                    event(root,t,t,MotionEvent.ACTION_DOWN,x,y);
                    check(status.getText().toString().equals(p.getContext().getString(R.string.dock_gesture_up)),"No immediate touch feedback");
                    event(root,t,t+300,MotionEvent.ACTION_MOVE,x+sign*4*d,y-30*d);
                    event(root,t,t+500,MotionEvent.ACTION_MOVE,x+sign*8*d,y-55*d);
                    event(root,t,t+650,MotionEvent.ACTION_MOVE,x+sign*20*d,y-75*d);
                    event(root,t,t+850,MotionEvent.ACTION_MOVE,x+sign*42*d,y-80*d);
                    event(root,t,t+1100,MotionEvent.ACTION_UP,x+sign*64*d,y-80*d);
                    check((Boolean)field(p,"accepted"),"Rounded L rejected through tutorial hierarchy");
                    check(status.getText().toString().equals(p.getContext().getString(R.string.dock_gesture_ok)),"Success feedback missing");
                }
                long t=SystemClock.uptimeMillis();event(root,t,t,MotionEvent.ACTION_DOWN,x,y);
                event(root,t,t+200,MotionEvent.ACTION_MOVE,x,y-50*d);event(root,t,t+400,MotionEvent.ACTION_UP,x,y-100*d);
                check(!(Boolean)field(p,"accepted"),"Straight scrolling opened Dock");
                t=SystemClock.uptimeMillis();event(root,t,t,MotionEvent.ACTION_DOWN,x,y);
                event(root,t,t+200,MotionEvent.ACTION_MOVE,x,y-50*d);event(root,t,t+300,MotionEvent.ACTION_CANCEL,x,y-50*d);
                check(!(Boolean)field(p,"touching"),"Canceled touch marker survived");
            }catch(Throwable e){failure[0]=e;}});
            if(failure[0]!=null)throw new AssertionError(failure[0]);
        }finally{test.runOnMainSync(()->{if(dialog[0]!=null)dialog[0].dismiss();activity.finish();});}
    }
    private static View find(View root){
        if(root.getClass().getName().equals(DockGestureTutorial.class.getName()+"$Practice"))return root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View v=find(group.getChildAt(i));if(v!=null)return v;}}
        return null;
    }
    private static Object field(Object owner,String name)throws Exception{
        java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static void event(View root,long down,long time,int action,float x,float y){
        MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);try{check(root.dispatchTouchEvent(e),"Tutorial did not consume touch");}finally{e.recycle();}
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
}
