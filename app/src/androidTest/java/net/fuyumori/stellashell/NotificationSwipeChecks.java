package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Dispatches real MotionEvents through a ScrollView using synthetic, never-posted notifications. */
final class NotificationSwipeChecks {
    static void run(Instrumentation test){
        Context c=test.getTargetContext();
        ScrollView scroll=new ScrollView(c);LinearLayout rows=Ui.column(c);scroll.addView(rows);
        List<String> opened=new ArrayList<>(),dismissed=new ArrayList<>();
        NotificationList list=new NotificationList(rows,n->opened.add(n.getKey()),n->dismissed.add(n.getKey()));
        StatusBarNotification first=item(c,1,false,true),second=item(c,2,false,false);
        List<StatusBarNotification> snapshot=new ArrayList<>();snapshot.add(first);snapshot.add(second);
        for(int i=3;i<12;i++)snapshot.add(item(c,i,false,false));
        list.render(snapshot);layout(scroll,c);
        NotificationSwipeCard card=card(rows);
        int[] delivered={0,0};
        card.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_UP)delivered[0]++;if(e.getActionMasked()==MotionEvent.ACTION_CANCEL)delivered[1]++;return false;});
        // Normal tap opens, including the PendingIntent-less card remaining swipeable.
        // This detached fixture cannot run View's posted click until attached: check dispatch
        // and the real click callback separately, instead of pretending a queued click ran.
        gesture(card,c,30,30,30,30,MotionEvent.ACTION_UP);check(delivered[0]==1&&dismissed.isEmpty(),"tap not forwarded");
        check(card.performClick()&&opened.size()==1,"tap callback missing");
        gesture(card,c,30,30,180,35,MotionEvent.ACTION_UP);check(dismissed.equals(Collections.singletonList(first.getKey()))&&opened.size()==1&&delivered[0]==1&&delivered[1]>0,"right swipe opened or dismissed wrong card");
        gesture(card,c,210,30,40,34,MotionEvent.ACTION_UP);check(dismissed.size()==2,"left swipe failed");
        gesture(card,c,30,30,45,30,MotionEvent.ACTION_UP);check(dismissed.size()==2&&opened.size()==1,"short drag dismissed or opened");
        gesture(card,c,30,30,180,30,MotionEvent.ACTION_CANCEL);check(dismissed.size()==2&&card.getTranslationX()==0&&card.getAlpha()==1,"cancel left shifted card");
        // Once vertical scrolling wins, a later horizontal movement must not change that decision.
        event(card,c,MotionEvent.ACTION_DOWN,30,20);event(card,c,MotionEvent.ACTION_MOVE,32,65);event(card,c,MotionEvent.ACTION_MOVE,220,70);event(card,c,MotionEvent.ACTION_UP,220,70);
        check(dismissed.size()==2,"vertical gesture switched to dismissal");
        // Start on a real row and feed its parent: ScrollView must actually scroll.
        int y=card.getTop()+((View)card.getParent()).getTop()+((View)card.getParent().getParent()).getTop()+35;
        event(scroll,c,MotionEvent.ACTION_DOWN,30, y/ c.getResources().getDisplayMetrics().density);
        event(scroll,c,MotionEvent.ACTION_MOVE,32,y/c.getResources().getDisplayMetrics().density-35);
        event(scroll,c,MotionEvent.ACTION_MOVE,33,y/c.getResources().getDisplayMetrics().density-90);
        event(scroll,c,MotionEvent.ACTION_MOVE,33,y/c.getResources().getDisplayMetrics().density-120);
        event(scroll,c,MotionEvent.ACTION_UP,33,y/c.getResources().getDisplayMetrics().density-120);
        check(scroll.getScrollY()>0&&dismissed.size()==2,"parent vertical scrolling blocked");scroll.scrollTo(0,0);
        // A live update making the same key persistent cancels the pending swipe.
        event(card,c,MotionEvent.ACTION_DOWN,30,30);event(card,c,MotionEvent.ACTION_MOVE,180,30);
        snapshot.set(0,item(c,1,true,true));list.render(snapshot);layout(scroll,c);
        event(card,c,MotionEvent.ACTION_UP,220,30);check(dismissed.size()==2&&card.getTranslationX()==0,"newly non-clearable notification dismissed");
        gesture(card,c,30,30,220,30,MotionEvent.ACTION_UP);check(dismissed.size()==2,"persistent notification dismissed");
        snapshot.set(0,first);list.render(snapshot);layout(scroll,c);
        event(card,c,MotionEvent.ACTION_DOWN,30,30);event(card,c,MotionEvent.ACTION_MOVE,160,30);
        event(card,c,MotionEvent.ACTION_POINTER_DOWN,180,30);event(card,c,MotionEvent.ACTION_UP,220,30);
        check(dismissed.size()==2&&card.getTranslationX()==0,"multi-touch dismissed");
        event(card,c,MotionEvent.ACTION_DOWN,30,30);event(card,c,MotionEvent.ACTION_MOVE,160,30);
        list.clear();event(card,c,MotionEvent.ACTION_UP,220,30);
        check(dismissed.size()==2&&card.getTranslationX()==0&&rows.getChildCount()==0,"cleared renderer kept gesture/callback");
        list.render(Collections.singletonList(second));layout(scroll,c);card=card(rows);
        gesture(card,c,30,30,190,30,MotionEvent.ACTION_UP);check(dismissed.size()==3&&dismissed.get(2).equals(second.getKey()),"non-actionable notification not swipeable");
        // Existing close action remains a separate explicit button.
        Button close=findButton(card);check(close!=null&&close.performClick()&&dismissed.size()==4,"close button broken");
        list.clear();
    }
    private static StatusBarNotification item(Context c,int id,boolean persistent,boolean actionable){
        Notification.Builder builder=new Notification.Builder(c,"synthetic-only").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Synthetic "+id).setContentText("Fixture content").setOngoing(persistent);
        if(actionable)builder.setContentIntent(PendingIntent.getActivity(c,998,new Intent(c,SetupActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        int uid=10000;return new StatusBarNotification("fixture."+id,"fixture."+id,id,null,uid,0,0,builder.build(),UserHandle.getUserHandleForUid(uid),1000-id);
    }
    private static NotificationSwipeCard card(LinearLayout rows){return (NotificationSwipeCard)((ViewGroup)((ViewGroup)rows.getChildAt(0)).getChildAt(1)).getChildAt(0);}
    private static Button findButton(View v){if(v instanceof Button)return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=findButton(((ViewGroup)v).getChildAt(i));if(b!=null)return b;}return null;}
    private static void layout(View view,Context c){int w=Ui.dp(c,320),h=Ui.dp(c,300);view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);view.getViewTreeObserver().dispatchOnGlobalLayout();}
    private static long down;
    private static void event(View target,Context c,int action,float x,float y){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)down=now;MotionEvent event=MotionEvent.obtain(down,now,action,x*c.getResources().getDisplayMetrics().density,y*c.getResources().getDisplayMetrics().density,0);target.dispatchTouchEvent(event);event.recycle();}
    private static void gesture(View target,Context c,int x,int y,int endX,int endY,int end){event(target,c,MotionEvent.ACTION_DOWN,x,y);event(target,c,MotionEvent.ACTION_MOVE,endX,endY);event(target,c,end,endX,endY);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
