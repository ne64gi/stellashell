package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.layout.EdgeDockReveal.Method;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Detached production handle + synthetic input only; no windows, tasks or preferences. */
final class DockRevealChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}

    /** Safe for dispatchers on either thread; all View/input work is performed on main. */
    static void run(Instrumentation test){
        if(Looper.myLooper()==Looper.getMainLooper()){verify(test.getTargetContext());return;}
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{verify(test.getTargetContext());}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("Dock reveal fixture failed",failure[0]);
    }

    private static void verify(Context base){
        Context context=new ContextThemeWrapper(base,R.style.AppTheme);
        int travel=Math.max(300,(int)Math.ceil(300*context.getResources().getDisplayMetrics().density));
        for(String edge:new String[]{"left","right","top","bottom"}){
            drag(context,edge,travel,.75f,true);
            drag(context,edge,travel,.5f,true);
            drag(context,edge,travel,.25f,false);
            drag(context,edge,travel,1.25f,true);
            rejected(context,edge,travel,0,0,"stationary tap");
            rejected(context,edge,travel,1,1,"light touch");
            rejected(context,edge,travel,-.75f*travel,0,"outward swipe");
            rejected(context,edge,travel,.75f*travel,.75f*travel,"equal diagonal");
            rejected(context,edge,travel,.5f*travel,.75f*travel,"perpendicular-dominant diagonal");
            reverse(context,edge,travel);
            lateCross(context,edge,travel);
            cancel(context,edge,travel);
            secondPointer(context,edge,travel);
            reset(context,edge,travel);
            rendering(context,edge,travel);
        }
        reconfigure(context,travel);
        callbackReset(context,travel);
        for(Method method:Method.values())explicitClick(context,travel,method);
        for(String edge:new String[]{"left","right","top","bottom"})tapMethods(context,edge,travel);
    }

    private static final class Calls implements DockHandleView.Listener {
        final List<Float> drags=new ArrayList<>();
        final List<Boolean> releases=new ArrayList<>();
        int clicks,ends;
        @Override public void onDrag(float progress){
            check(!Float.isNaN(progress)&&!Float.isInfinite(progress)&&progress>=0&&progress<=1,
                "Handle supplied invalid preview progress");
            drags.add(progress);
        }
        @Override public void onRelease(boolean open){releases.add(open);}
        @Override public void onGestureEnd(){ends++;}
        void clear(){drags.clear();releases.clear();clicks=0;ends=0;}
        void noOpen(String operation){check(!releases.contains(Boolean.TRUE),operation+": unexpectedly opened Dock");check(clicks==0,operation+": synthesized a click");}
        void released(boolean open,String operation){
            check(releases.size()==1&&releases.get(0)==open,operation+": expected one release("+open+"), got "+releases);
            check(clicks==0,operation+": drag synthesized a click");
        }
    }

    private static final class Fixture {
        final Calls calls=new Calls();
        final DockHandleView view;
        final String edge;
        final int travel;
        final float origin;
        long downTime,eventTime;
        Fixture(Context context,String edge,int travel){
            this.edge=edge;this.travel=travel;origin=travel*2f;
            view=new DockHandleView(context,calls);
            view.setOnClickListener(v->calls.clicks++);
            view.setContentDescription("Synthetic Dock reveal fixture");
            view.configure(edge,travel);
            int size=travel*4;
            view.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));
            view.layout(0,0,size,size);
            calls.clear();
            check(!view.isAttachedToWindow(),"Dock reveal fixture unexpectedly attached to a window");
        }
        Fixture(Context context,String edge,int travel,Method method){this(context,edge,travel);view.configure(edge,travel,method);calls.clear();}
        float x(float inward,float perpendicular){
            if("left".equals(edge))return origin+inward;
            if("right".equals(edge))return origin-inward;
            return origin+perpendicular;
        }
        float y(float inward,float perpendicular){
            if("top".equals(edge))return origin+inward;
            if("bottom".equals(edge))return origin-inward;
            return origin+perpendicular;
        }
        void event(int action,float inward,float perpendicular){
            event(action,inward,perpendicular,InputDevice.SOURCE_TOUCHSCREEN);
        }
        void event(int action,float inward,float perpendicular,int source){
            if(action==MotionEvent.ACTION_DOWN){downTime=eventTime==0?SystemClock.uptimeMillis():eventTime+16;eventTime=downTime;}
            else eventTime+=16;
            MotionEvent event=MotionEvent.obtain(downTime,eventTime,action,x(inward,perpendicular),y(inward,perpendicular),0);
            event.setSource(source);
            try{view.dispatchTouchEvent(event);}finally{event.recycle();}
        }
        void begin(){event(MotionEvent.ACTION_DOWN,0,0);}
        void tap(float inward,float perpendicular,int duration){
            event(MotionEvent.ACTION_DOWN,inward,perpendicular);
            eventTime=downTime+duration-16;event(MotionEvent.ACTION_UP,inward,perpendicular);
        }
        void preview(float fraction){
            event(MotionEvent.ACTION_MOVE,travel*fraction,0);
            check(view.tracking(),edge+": deliberate inward drag was not tracked");
            check(!calls.drags.isEmpty()&&calls.drags.get(calls.drags.size()-1)>0,edge+": inward drag did not preview");
            check(calls.releases.isEmpty()&&calls.clicks==0,edge+": preview committed or clicked before release");
        }
        void ended(String operation){check(!view.tracking(),edge+": "+operation+" left handle tracking");check(calls.ends==1,edge+": "+operation+" did not complete exactly once");}
        void pointerDown(float inward){
            MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[2];
            for(int i=0;i<2;i++){
                properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i]=new MotionEvent.PointerCoords();coordinates[i].x=x(inward,i*20);coordinates[i].y=y(inward,i*20);
                coordinates[i].pressure=1;coordinates[i].size=1;
            }
            eventTime+=16;
            MotionEvent event=MotionEvent.obtain(downTime,eventTime,
                MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coordinates,
                0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
            try{view.dispatchTouchEvent(event);}finally{event.recycle();}
        }
    }

    private static void drag(Context context,String edge,int travel,float fraction,boolean open){
        Fixture fixture=new Fixture(context,edge,travel);fixture.begin();fixture.preview(fraction);
        float progress=fixture.calls.drags.get(fixture.calls.drags.size()-1);
        check(Math.abs(progress-Math.min(1,fraction))<.001f,edge+": preview did not reflect inward distance/travel");
        fixture.event(MotionEvent.ACTION_UP,travel*fraction,0);
        fixture.calls.released(open,edge+" inward "+fraction);fixture.ended("release");
    }

    private static void rejected(Context context,String edge,int travel,float inward,float perpendicular,String operation){
        Fixture fixture=new Fixture(context,edge,travel);fixture.begin();
        fixture.event(MotionEvent.ACTION_MOVE,inward,perpendicular);
        check(fixture.calls.drags.isEmpty(),edge+" "+operation+": unexpected preview");
        fixture.event(MotionEvent.ACTION_UP,inward,perpendicular);
        check(fixture.calls.drags.isEmpty()&&fixture.calls.releases.isEmpty(),edge+" "+operation+": rejected touch invoked gesture callbacks");
        fixture.calls.noOpen(edge+" "+operation);fixture.ended(operation);
    }

    private static void reverse(Context context,String edge,int travel){
        Fixture fixture=new Fixture(context,edge,travel);fixture.begin();fixture.preview(.75f);
        fixture.event(MotionEvent.ACTION_MOVE,-travel*.25f,0);
        check(fixture.calls.drags.get(fixture.calls.drags.size()-1)==0,edge+": reverse did not close preview");
        fixture.event(MotionEvent.ACTION_UP,-travel*.25f,0);
        fixture.calls.released(false,edge+" reverse");fixture.ended("reverse");
    }

    private static void lateCross(Context context,String edge,int travel){
        Fixture fixture=new Fixture(context,edge,travel);fixture.begin();fixture.preview(.75f);
        fixture.event(MotionEvent.ACTION_MOVE,travel*.75f,travel);
        fixture.event(MotionEvent.ACTION_UP,travel*.75f,travel);
        fixture.calls.released(false,edge+" late perpendicular drift");fixture.ended("late perpendicular drift");
    }

    private static void callbackReset(Context context,int travel){
        Calls calls=new Calls();DockHandleView[] view={null};
        view[0]=new DockHandleView(context,new DockHandleView.Listener(){
            public void onDrag(float progress){calls.onDrag(progress);view[0].configure("bottom",travel);}
            public void onRelease(boolean open){calls.onRelease(open);}
            public void onGestureEnd(){calls.onGestureEnd();}
        });view[0].configure("left",travel);
        long start=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){
            MotionEvent event=MotionEvent.obtain(start,start+(action==MotionEvent.ACTION_DOWN?0:16),action,action==MotionEvent.ACTION_DOWN?0:travel*.75f,0,0);
            try{view[0].onTouchEvent(event);}finally{event.recycle();}
        }
        check(!view[0].tracking()&&calls.ends==1,"Relayout during release retained or completed the gesture twice");
        calls.released(false,"Relayout during release");
    }

    private static void cancel(Context context,String edge,int travel){
        Fixture fixture=new Fixture(context,edge,travel);fixture.begin();fixture.preview(.75f);
        fixture.event(MotionEvent.ACTION_CANCEL,travel*.75f,0);
        fixture.calls.released(false,edge+" cancel");fixture.ended("cancel");
        int previews=fixture.calls.drags.size(),releases=fixture.calls.releases.size();
        fixture.event(MotionEvent.ACTION_MOVE,travel,0);fixture.event(MotionEvent.ACTION_UP,travel,0);
        check(fixture.calls.drags.size()==previews&&fixture.calls.releases.size()==releases,edge+": trailing canceled input restarted gesture");
        fixture.calls.noOpen(edge+" canceled trailing input");
    }

    private static void secondPointer(Context context,String edge,int travel){
        Fixture fixture=new Fixture(context,edge,travel);fixture.begin();fixture.preview(.75f);
        fixture.pointerDown(travel*.75f);
        fixture.calls.released(false,edge+" second pointer");fixture.ended("second pointer");
        fixture.event(MotionEvent.ACTION_UP,travel,0);fixture.calls.noOpen(edge+" multi-touch remainder");
        check(fixture.calls.releases.size()==1,edge+": multi-touch remainder released twice");
    }

    private static void reset(Context context,String edge,int travel){
        Fixture fixture=new Fixture(context,edge,travel);fixture.begin();fixture.preview(.75f);
        fixture.view.reset();fixture.ended("reset");fixture.calls.noOpen(edge+" reset");
        fixture.calls.released(false,edge+" reset captured preview");
        int previews=fixture.calls.drags.size(),releases=fixture.calls.releases.size();
        fixture.event(MotionEvent.ACTION_MOVE,travel,0);fixture.event(MotionEvent.ACTION_UP,travel,0);
        check(fixture.calls.drags.size()==previews&&fixture.calls.releases.size()==releases,edge+": reset accepted stale gesture remainder");
        fixture.calls.clear();fixture.begin();fixture.preview(.75f);fixture.event(MotionEvent.ACTION_UP,travel*.75f,0);
        fixture.calls.released(true,edge+" fresh drag after reset");fixture.ended("fresh release");
        fixture.calls.clear();fixture.begin();fixture.view.reset();fixture.ended("reset before capture");
        check(fixture.calls.drags.isEmpty()&&fixture.calls.releases.isEmpty()&&fixture.calls.clicks==0,
            edge+": reset before capture produced navigation callbacks");
    }

    private static void reconfigure(Context context,int travel){
        Fixture fixture=new Fixture(context,"left",travel);fixture.begin();fixture.preview(.75f);
        fixture.view.configure("bottom",travel*2);
        fixture.ended("edge/travel reconfigure");fixture.calls.noOpen("Reconfigure");
        fixture.calls.released(false,"Reconfigure captured preview");
        int previews=fixture.calls.drags.size(),releases=fixture.calls.releases.size();
        fixture.event(MotionEvent.ACTION_MOVE,travel,0);fixture.event(MotionEvent.ACTION_UP,travel,0);
        check(fixture.calls.drags.size()==previews&&fixture.calls.releases.size()==releases,"Reconfigure accepted old-edge gesture remainder");
        fixture.calls.clear();
        // Keep the same production View: new bottom edge moves upward, with its new travel.
        long start=SystemClock.uptimeMillis();
        int index=0;
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){
            float y=fixture.origin-(action==MotionEvent.ACTION_DOWN?0:travel*.75f);
            MotionEvent event=MotionEvent.obtain(start,start+16*index++,action,fixture.origin,y,0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try{fixture.view.dispatchTouchEvent(event);}finally{event.recycle();}
        }
        check(!fixture.calls.drags.isEmpty(),"Reconfigured bottom edge did not preview upward input");
        fixture.calls.released(false,"Reconfigured travel below halfway");fixture.ended("reconfigured release");
        Fixture unchanged=new Fixture(context,"left",travel);unchanged.begin();unchanged.preview(.75f);
        unchanged.view.configure("left",travel);
        check(unchanged.view.tracking()&&unchanged.calls.releases.isEmpty(),"Unchanged configure canceled active preview");
        unchanged.event(MotionEvent.ACTION_UP,travel*.75f,0);
        unchanged.calls.released(true,"Unchanged configure release");unchanged.ended("unchanged configure release");
    }

    private static void explicitClick(Context context,int travel,Method method){
        Fixture fixture=new Fixture(context,"left",travel,method);
        check(fixture.view.performClick()&&fixture.calls.clicks==1,"Explicit Dock handle click was not accessible");
        check(fixture.view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null)&&fixture.calls.clicks==2,
            "Accessibility ACTION_CLICK did not reach the explicit click listener");
        fixture.event(MotionEvent.ACTION_DOWN,0,0,InputDevice.SOURCE_MOUSE);
        fixture.event(MotionEvent.ACTION_UP,0,0,InputDevice.SOURCE_MOUSE);
        check(fixture.calls.clicks==3,"Explicit mouse tap did not reach the click listener");
        check(fixture.view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER))
            &&fixture.view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER))
            &&fixture.calls.clicks==4,"Explicit keyboard activation did not reach the click listener");
        check(fixture.calls.drags.isEmpty()&&fixture.calls.releases.isEmpty(),"Explicit click synthesized drag/release callbacks");
        fixture.ended("explicit click");
    }

    private static void tapMethods(Context context,String edge,int travel){
        ViewConfiguration touch=ViewConfiguration.get(context);int hold=ViewConfiguration.getLongPressTimeout(),timeout=ViewConfiguration.getDoubleTapTimeout();
        for(Method method:new Method[]{Method.SINGLE_TAP,Method.DOUBLE_TAP}){
            Fixture fixture=new Fixture(context,edge,travel,method);
            int description=method==Method.SINGLE_TAP?R.string.dock_handle_single_tap:R.string.dock_handle_double_tap;
            check(context.getString(description).contentEquals(fixture.view.getContentDescription())
                &&context.getString(description).contentEquals(fixture.view.getTooltipText()),method+": method-specific affordance label missing");
            fixture.event(MotionEvent.ACTION_DOWN,0,0);fixture.event(MotionEvent.ACTION_MOVE,1,0);
            check(fixture.calls.drags.isEmpty()&&fixture.calls.releases.isEmpty(),method+": tap DOWN/MOVE created preview or early open");
            fixture.event(MotionEvent.ACTION_UP,1,0);
            if(method==Method.SINGLE_TAP)fixture.calls.released(true,edge+" single tap");
            else{
                check(fixture.calls.releases.isEmpty(),edge+": first double-tap UP opened");
                fixture.view.configure(edge,travel,method); // Ordinary relayout must retain a first-tap candidate.
                fixture.tap(1,0,16);fixture.calls.released(true,edge+" double tap");
            }
            check(fixture.calls.drags.isEmpty()&&!fixture.view.tracking()&&fixture.calls.ends==(method==Method.SINGLE_TAP?1:2),method+": valid tap retained preview/tracking or completed twice");
            Fixture scroll=new Fixture(context,edge,travel,method);scroll.begin();
            scroll.event(MotionEvent.ACTION_MOVE,touch.getScaledTouchSlop()*2f+1,0);
            scroll.event(MotionEvent.ACTION_UP,0,0);
            check(scroll.calls.drags.isEmpty()&&scroll.calls.releases.isEmpty()&&scroll.calls.clicks==0,method+": scroll/reverse became a tap/preview");
            for(int duration:new int[]{hold,hold+1}){
                Fixture longPress=new Fixture(context,edge,travel,method);longPress.tap(0,0,duration);
                check(longPress.calls.releases.isEmpty()&&longPress.calls.drags.isEmpty()&&!longPress.view.tracking(),method+": long press opened at "+duration);
            }
            for(String terminal:new String[]{"cancel","pointer","reset","configure"}){
                Fixture rejected=new Fixture(context,edge,travel,method);rejected.begin();
                if("cancel".equals(terminal))rejected.event(MotionEvent.ACTION_CANCEL,0,0);
                else if("pointer".equals(terminal))rejected.pointerDown(0);
                else if("reset".equals(terminal))rejected.view.reset();
                else rejected.view.configure(edge,travel+1,method);
                rejected.event(MotionEvent.ACTION_UP,0,0);
                check(rejected.calls.releases.isEmpty()&&rejected.calls.drags.isEmpty()&&rejected.calls.ends==1&&!rejected.view.tracking(),
                    method+": "+terminal+" accepted stale tap UP or completed twice");
            }
        }
        Fixture boundary=new Fixture(context,edge,travel,Method.DOUBLE_TAP);boundary.tap(0,0,16);
        boundary.eventTime+=timeout-16;boundary.tap(0,0,hold-1);
        boundary.calls.released(true,edge+" second DOWN at double timeout/UP within press timeout");
        Fixture late=new Fixture(context,edge,travel,Method.DOUBLE_TAP);late.tap(0,0,16);late.eventTime+=timeout+1;late.tap(0,0,16);
        check(late.calls.releases.isEmpty()&&late.calls.drags.isEmpty(),edge+": late second tap opened");
        late.tap(0,0,16);late.calls.released(true,edge+" fresh double after expired first");
        Fixture distant=new Fixture(context,edge,travel,Method.DOUBLE_TAP);distant.tap(0,0,16);
        float far=touch.getScaledDoubleTapSlop()*2f+1;distant.tap(far,0,16);
        check(distant.calls.releases.isEmpty(),edge+": far-away second tap opened");
        distant.tap(far,0,16);distant.calls.released(true,edge+" local fresh double after distant tap");
        for(String clear:new String[]{"cancel","pointer","reset","edge","travel","method","scroll","hold"}){
            Fixture candidate=new Fixture(context,edge,travel,Method.DOUBLE_TAP);candidate.tap(0,0,16);
            switch(clear){
                case "cancel":candidate.begin();candidate.event(MotionEvent.ACTION_CANCEL,0,0);break;
                case "pointer":candidate.begin();candidate.pointerDown(0);candidate.event(MotionEvent.ACTION_UP,0,0);break;
                case "reset":candidate.view.reset();break;
                case "edge":candidate.view.configure("left".equals(edge)?"right":"left",travel,Method.DOUBLE_TAP);candidate.view.configure(edge,travel,Method.DOUBLE_TAP);break;
                case "travel":candidate.view.configure(edge,travel+1,Method.DOUBLE_TAP);candidate.view.configure(edge,travel,Method.DOUBLE_TAP);break;
                case "method":candidate.view.configure(edge,travel,Method.SINGLE_TAP);candidate.view.configure(edge,travel,Method.DOUBLE_TAP);break;
                case "scroll":candidate.begin();candidate.event(MotionEvent.ACTION_MOVE,touch.getScaledTouchSlop()*2f+1,0);candidate.event(MotionEvent.ACTION_UP,0,0);break;
                case "hold":candidate.tap(0,0,hold);break;
            }
            candidate.tap(0,0,16);
            check(candidate.calls.releases.isEmpty()&&candidate.calls.drags.isEmpty()&&candidate.calls.clicks==0&&!candidate.view.tracking(),
                edge+": "+clear+" failed to clear pending double-tap candidate");
            candidate.tap(0,0,16);candidate.calls.released(true,edge+" fresh double after "+clear);
        }
    }

    private static void rendering(Context context,String edge,int travel){
        Fixture fixture=new Fixture(context,edge,travel);
        fixture.view.layout(0,0,80,80);
        Bitmap bitmap=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);
        try{
            fixture.view.draw(new Canvas(bitmap));
            int left=80,top=80,right=-1,bottom=-1;
            for(int y=0;y<80;y++)for(int x=0;x<80;x++)if(Color.alpha(bitmap.getPixel(x,y))>0){
                left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
            }
            check(right>=left&&bottom>=top,edge+": handle affordance was not drawn");
            int width=right-left+1,height=bottom-top+1;
            boolean vertical="left".equals(edge)||"right".equals(edge);
            check(vertical?height>width*2:width>height*2,edge+": handle stroke used the wrong orientation");
        }finally{bitmap.recycle();}
        check(fixture.calls.drags.isEmpty()&&fixture.calls.releases.isEmpty()&&fixture.calls.clicks==0,
            edge+": rendering invoked navigation callbacks");
    }
}
