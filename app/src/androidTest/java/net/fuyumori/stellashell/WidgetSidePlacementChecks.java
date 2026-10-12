package net.fuyumori.stellashell;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Isolated layout/store: same widget IDs and content, opposite edge, reversible editing. */
final class WidgetSidePlacementChecks {
    static void run(Context context)throws Exception{
        String store="widget_side_test_"+System.nanoTime();
        SharedPreferences prefs=context.getSharedPreferences(store,0);
        WidgetHubChecks.Screen screen=new WidgetHubChecks.Screen(context,Configuration.ORIENTATION_PORTRAIT);
        FrameLayout canvas=new FrameLayout(screen);DesktopWidgets widgets=null;
        try{
            JSONArray initial=new JSONArray().put(new JSONObject().put("id",-1).put("kind","text").put("text","LEFT → RIGHT").put("x",72).put("y",480).put("w",240).put("h",100))
                    .put(new JSONObject().put("id",-2).put("kind","text").put("text","Full width").put("x",0).put("y",20).put("w",360).put("h",80));
            prefs.edit().putString("items",initial.toString()).commit();layout(canvas,360,720);
            widgets=new DesktopWidgets(screen,canvas,store,0x535751);widgets.enablePages(()->{});
            Map<String,?> before=new HashMap<>(prefs.getAll());
            Object entry=((List<?>)field(widgets,"entries")).get(0);ViewGroup frame=(ViewGroup)field(entry,"frame");
            View wide=(View)field(((List<?>)field(widgets,"entries")).get(1),"frame");
            for(int pass=0;pass<4;pass++)for(boolean right:new boolean[]{false,true}){
                widgets.hubSide(right);widgets.reservedControls(reservation(screen,right));layout(canvas,360,720);
                check(frame.getLeft()==Ui.dp(screen,right?48:72),"opposite-edge widget position");
                check(right?frame.getRight()<=Ui.dp(screen,304):frame.getLeft()>=Ui.dp(screen,56),"widget overlaps source rail");
                check(wide.getLeft()==0&&wide.getWidth()==canvas.getWidth(),"full-width upper widget moved");
                check(frame.getScaleX()==1&&frame.getChildAt(0).getScaleX()==1&&((TextView)frame.getChildAt(0)).getText().toString().equals("LEFT → RIGHT"),"provider content mirrored");
            }
            check(before.equals(prefs.getAll()),"opening sides rewrote saved layout");
            widgets.setEditing(true);
            Method adjust=DesktopWidgets.class.getDeclaredMethod("adjust",entry.getClass(),int.class,int.class);adjust.setAccessible(true);
            Method geometry=DesktopWidgets.class.getDeclaredMethod("geometry",entry.getClass(),int.class);geometry.setAccessible(true);
            adjust.invoke(widgets,entry,0,24);layout(canvas,360,720);
            check(frame.getLeft()==Ui.dp(screen,24)&&(Integer)field(entry,"x")==96&&(Integer)geometry.invoke(widgets,entry,0)==24,"numeric X uses saved rather than visible position");
            adjust.invoke(widgets,entry,2,220);layout(canvas,360,720);
            check(frame.getLeft()==Ui.dp(screen,24)&&(Integer)field(entry,"x")==116,"numeric resize moved visible left edge");
            WidgetEditFrame.Actions actions=(WidgetEditFrame.Actions)field(frame,"actions");
            actions.begin(false);actions.move(Ui.dp(screen,20),0);actions.end(false);layout(canvas,360,720);
            check(frame.getLeft()==Ui.dp(screen,44)&&(Integer)field(entry,"x")==96,"mirrored drag direction reversed");
            actions.begin(true);actions.move(Ui.dp(screen,10),0);actions.end(true);layout(canvas,360,720);
            check(frame.getLeft()==Ui.dp(screen,44)&&(Integer)field(entry,"w")==220,"cancelled resize not restored");
            actions.begin(true);actions.move(Ui.dp(screen,10),0);actions.end(false);layout(canvas,360,720);
            check(frame.getLeft()==Ui.dp(screen,44)&&(Integer)field(entry,"w")==230&&(Integer)field(entry,"x")==86,"resize anchor/conversion");
            String saved=prefs.getString("items","");
            event(frame,MotionEvent.ACTION_DOWN,Ui.dp(screen,10),Ui.dp(screen,10));event(frame,MotionEvent.ACTION_MOVE,Ui.dp(screen,30),Ui.dp(screen,10));
            widgets.hubSide(false);widgets.reservedControls(reservation(screen,false));layout(canvas,360,720);
            check((Integer)field(entry,"x")==86&&frame.getLeft()==Ui.dp(screen,86)&&saved.equals(prefs.getString("items","")),"side change saved a partial drag");
            widgets.finishEditing();widgets.destroy();canvas.removeAllViews();
            widgets=new DesktopWidgets(screen,canvas,store,0x535751);widgets.enablePages(()->{});widgets.hubSide(true);layout(canvas,360,720);
            entry=((List<?>)field(widgets,"entries")).get(0);frame=(ViewGroup)field(entry,"frame");
            check(frame.getLeft()==Ui.dp(screen,44)&&(Integer)field(entry,"id")==-1,"reopen lost canonical position/ID");
            screen.orient(Configuration.ORIENTATION_LANDSCAPE);widgets.configurationChanged();layout(canvas,720,360);
            screen.orient(Configuration.ORIENTATION_PORTRAIT);widgets.configurationChanged();layout(canvas,360,720);
            entry=((List<?>)field(widgets,"entries")).get(0);frame=(ViewGroup)field(entry,"frame");
            check(frame.getLeft()==Ui.dp(screen,44)&&(Integer)field(entry,"w")==230,"rotation geometry: left="+frame.getLeft()+" target="+Ui.dp(screen,44)+" width="+field(entry,"w")+" x="+field(entry,"x")+" margin="+((FrameLayout.LayoutParams)frame.getLayoutParams()).leftMargin+" canvas="+canvas.getWidth());
        }finally{if(widgets!=null)widgets.destroy();canvas.removeAllViews();context.deleteSharedPreferences(store);}
    }
    private static Rect reservation(Context c,boolean right){return new Rect(Ui.dp(c,right?304:0),Ui.dp(c,440),Ui.dp(c,right?360:56),Ui.dp(c,700));}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    // Canvas bounds changes relayout widget children after the first pass, just as a real
    // ViewRoot schedules a follow-up traversal. This detached fixture supplies that pass.
    private static void layout(View view,int w,int h){int width=Ui.dp(view.getContext(),w),height=Ui.dp(view.getContext(),h);for(int pass=0;pass<2;pass++){view.forceLayout();view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}}
    private static void event(View view,int action,int x,int y){long t=android.os.SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(t,t,action,x,y,0);view.dispatchTouchEvent(e);e.recycle();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
