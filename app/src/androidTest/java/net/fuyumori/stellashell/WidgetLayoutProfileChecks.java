package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.fuyumori.stellashell.core.layout.WidgetLayoutProfiles;
import org.json.JSONArray;
import org.json.JSONObject;
import static net.fuyumori.stellashell.core.layout.WidgetLayoutProfiles.Profile.*;

/** Detached views + nonce store/host; configuration changes never alter device display/settings. */
final class WidgetLayoutProfileChecks {
    private static final int HOST=0x53574c;
    private WidgetLayoutProfileChecks(){}
    static void run(Instrumentation test)throws Exception{
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{verify(test.getTargetContext());}catch(Throwable error){failure[0]=error;}});
        if(failure[0] instanceof Exception)throw (Exception)failure[0];
        if(failure[0] instanceof Error)throw (Error)failure[0];
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static final class Screen extends Activity {
        private Resources fixtureResources;
        Screen(Context context,int orientation){attachBaseContext(context);orient(orientation);}
        void orient(int orientation){
            Configuration configuration=new Configuration(getBaseContext().getResources().getConfiguration());
            configuration.orientation=orientation;fixtureResources=getBaseContext().createConfigurationContext(configuration).getResources();
        }
        @Override public Resources getResources(){return fixtureResources==null?super.getResources():fixtureResources;}
        @Override public Display getDisplay(){return getBaseContext().getSystemService(DisplayManager.class).getDisplay(0);}
    }
    private static void verify(Context base)throws Exception{
        String store="widget_layout_profiles_"+System.nanoTime();SharedPreferences prefs=base.getSharedPreferences(store,0);
        Map<String,?> settingsBefore=Launches.prefs(base).getAll();
        Screen screen=new Screen(base,Configuration.ORIENTATION_PORTRAIT);FrameLayout canvas=new FrameLayout(screen);
        DesktopWidgets widgets=null;
        try{
            prefs.edit().putString("items",new JSONArray().put(new JSONObject().put("id",-1).put("kind","text").put("text","Shared fixture").put("x",12).put("y",24).put("w",180).put("h",120)).toString()).commit();
            canvas.layout(0,0,Ui.dp(screen,800),Ui.dp(screen,600));
            widgets=new DesktopWidgets(screen,canvas,store,HOST);widgets.setEditing(true);
            adjust(widgets,0,70);check(saved(prefs).getInt("x")==70,"portrait edit not saved");
            screen.orient(Configuration.ORIENTATION_LANDSCAPE);widgets.configurationChanged();
            check(geometry(widgets,"x")==12,"portrait edit leaked into landscape");
            adjust(widgets,0,120);adjust(widgets,2,400);
            screen.orient(Configuration.ORIENTATION_PORTRAIT);widgets.configurationChanged();
            check(geometry(widgets,"x")==70&&geometry(widgets,"w")==180,"portrait geometry not restored");
            // A cancelled active drag restores the old profile before the new geometry loads.
            WidgetEditFrame frame=(WidgetEditFrame)canvas.getChildAt(0);
            FrameLayout.LayoutParams bounds=(FrameLayout.LayoutParams)frame.getLayoutParams();
            frame.measure(android.view.View.MeasureSpec.makeMeasureSpec(bounds.width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(bounds.height,android.view.View.MeasureSpec.EXACTLY));
            frame.layout(bounds.leftMargin,bounds.topMargin,bounds.leftMargin+bounds.width,bounds.topMargin+bounds.height);
            event(frame,MotionEvent.ACTION_DOWN,30,30);event(frame,MotionEvent.ACTION_MOVE,130,90);
            screen.orient(Configuration.ORIENTATION_LANDSCAPE);widgets.configurationChanged();
            check(geometry(widgets,"x")==120&&geometry(widgets,"w")==400,"drag crossed profile boundary");
            WidgetLayoutProfiles layouts=profiles(prefs);
            check(layouts.snapshot(PHONE_PORTRAIT).get(-1).x==70,"rotation saved partial cancelled drag");
            check(layouts.snapshot(EXTERNAL).get(-1).x==12,"phone geometry overwrote external profile");
            check(saved(prefs).getInt("id")==-1&&saved(prefs).getString("text").equals("Shared fixture"),"rotation duplicated/changed catalog content");
            widgets.destroy();widgets=new DesktopWidgets(screen,canvas,store,HOST);
            check(geometry(widgets,"x")==120&&geometry(widgets,"w")==400,"landscape recreation lost geometry");
            String before=prefs.getString("layout_profiles_v1",null);widgets.configurationChanged();
            check(before.equals(prefs.getString("layout_profiles_v1",null)),"same-profile refresh rewrote layout storage");
            // Exercise the persistence owner with a shared-catalog removal, including its last item.
            entries(widgets).clear();invoke(widgets,"save");widgets.destroy();widgets=new DesktopWidgets(screen,canvas,store,HOST);
            check(entries(widgets).isEmpty()&&new JSONArray(prefs.getString("items","[]")).length()==0,"last item resurrected");
            for(WidgetLayoutProfiles.Profile profile:WidgetLayoutProfiles.Profile.values())check(profiles(prefs).snapshot(profile).isEmpty(),"deleted content remained in "+profile);
            check(new AppWidgetHost(screen,HOST).getAppWidgetIds().length==0,"geometry profiles allocated provider IDs");
            check(settingsBefore.equals(Launches.prefs(base).getAll()),"widget rotation rewrote global settings");
        }finally{if(widgets!=null)widgets.destroy();canvas.removeAllViews();prefs.edit().clear().commit();base.deleteSharedPreferences(store);}
    }
    private static WidgetLayoutProfiles profiles(SharedPreferences prefs)throws Exception{
        Map<Integer,WidgetLayoutProfiles.Geometry> catalog=new java.util.LinkedHashMap<>();JSONArray items=new JSONArray(prefs.getString("items","[]"));
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);catalog.put(item.getInt("id"),new WidgetLayoutProfiles.Geometry(item.optInt("x"),item.optInt("y"),item.optInt("w"),item.optInt("h"),item.optInt("mode"),item.optInt("baseW"),item.optInt("baseH")));}
        return new WidgetLayoutProfiles(prefs.getString("layout_profiles_v1",null),catalog);
    }
    private static JSONObject saved(SharedPreferences prefs)throws Exception{return new JSONArray(prefs.getString("items","[]")).getJSONObject(0);}
    private static List<?> entries(DesktopWidgets widgets)throws Exception{Field field=DesktopWidgets.class.getDeclaredField("entries");field.setAccessible(true);return (List<?>)field.get(widgets);}
    private static int geometry(DesktopWidgets widgets,String name)throws Exception{Object entry=entries(widgets).get(0);Field field=entry.getClass().getDeclaredField(name);field.setAccessible(true);return field.getInt(entry);}
    private static void adjust(DesktopWidgets widgets,int axis,int value)throws Exception{Object entry=entries(widgets).get(0);Method method=DesktopWidgets.class.getDeclaredMethod("adjust",entry.getClass(),int.class,int.class);method.setAccessible(true);method.invoke(widgets,entry,axis,value);}
    private static void invoke(DesktopWidgets widgets,String name)throws Exception{Method method=DesktopWidgets.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(widgets);}
    private static void event(WidgetEditFrame frame,int action,float x,float y){long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);try{frame.dispatchTouchEvent(event);}finally{event.recycle();}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
