package net.fuyumori.stellashell;

import android.app.*;
import android.appwidget.AppWidgetHost;
import android.content.*;
import android.content.res.*;
import android.hardware.display.DisplayManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Isolated stores and synthetic notifications; never posts/reads private notification content. */
final class WidgetHubChecks {
    private static final int HOST=0x535750;
    static void run(Instrumentation test)throws Exception{
        Throwable[] failure={null};
        test.runOnMainSync(()->{try{
            Context context=test.getTargetContext();
            state(context);notifications(context);rail(context);reservation(context);widgets(context,360,720);widgets(context,1000,600);WidgetSidePlacementChecks.run(context);
        }catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError("Widget Hub checks",failure[0]);
    }
    static void attached(Instrumentation test)throws Exception{
        Context c=test.getTargetContext();check(!ShellNotifications.locked(c),"Unlock is required for attached Hub verification");
        for(boolean right:new boolean[]{false,true}){
            HubActivity hub=(HubActivity)test.startActivitySync(new Intent(c,HubActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("from_right",right).putExtra("sidebar_edge",right?"right":"left"));
            Throwable[] failure={null};
            try{
                showAndLayout(test,hub,false);
                test.runOnMainSync(()->{try{
                    View rail=(View)field(hub,"controlRail");check(rail!=null,"Phone Hub used desktop footer when primary_mode is enabled");
                    ViewGroup body=(ViewGroup)rail.getParent();
                    View controls=((ViewGroup)rail).getChildAt(0);
                    check(right?controls.getRight()==rail.getWidth():controls.getLeft()==0,"Controls do not follow source side");
                    View canvas=(View)field(hub,"widgetCanvas");
                    check(canvas.getWidth()==body.getWidth(),"Full-height gutter still reserved");
                    check((Boolean)field(field(hub,"widgets"),"hubFromRight")==right,"Widgets did not follow the Hub opening side");
                    View navigation=(View)field(hub,"navigation"),settings=(View)field(hub,"panelSettings");
                    check(settings.getParent()==navigation.getParent(),"Settings left in heading");
                    check(((ViewGroup)settings.getParent()).getChildCount()==3,"Missing settings or close control in rail");
                    int[] point=new int[2];navigation.getLocationOnScreen(point);
                    float center=(point[1]+navigation.getHeight()/2f)/hub.getResources().getDisplayMetrics().heightPixels;
                    check(center>=.64f&&center<=.80f,"Tabs are not at lower thumb height: "+center);
                }catch(Throwable error){failure[0]=error;}});
                if(failure[0]!=null)throw new AssertionError("Attached Hub",failure[0]);
                showAndLayout(test,hub,true);
                test.runOnMainSync(()->{try{
                    ViewGroup rail=(ViewGroup)field(hub,"controlRail");View tools=rail.getChildAt(0);
                    ViewGroup page=(ViewGroup)field(hub,"notificationPage");View scroll=page.getChildAt(0);
                    check(right?scroll.getRight()<=tools.getLeft():scroll.getLeft()>=tools.getRight(),"notification cards overlap controls: right="+right+" scroll="+scroll.getLeft()+","+scroll.getRight()+" tools="+tools.getLeft()+","+tools.getRight()+" padding="+page.getPaddingLeft()+","+page.getPaddingRight()+" page="+page.getWidth()+","+page.getHeight()+" visibility="+page.getVisibility()+" finishing="+hub.isFinishing());
                    check((right?page.getPaddingRight():page.getPaddingLeft())==HubControlRail.controlsWidth(hub)+Ui.dp(hub,8),"notification gutter is not fixed width");
                }catch(Throwable error){failure[0]=error;}});
                if(failure[0]!=null)throw new AssertionError("Attached notification gutter",failure[0]);
            }finally{test.runOnMainSync(hub::finish);test.waitForIdleSync();}
        }
    }
    private static void showAndLayout(Instrumentation test,HubActivity hub,boolean notifications)throws Exception{
        java.util.concurrent.CountDownLatch drawn=new java.util.concurrent.CountDownLatch(1);
        test.runOnMainSync(()->{try{
            View root=hub.getWindow().getDecorView();
            View page=(View)field(hub,notifications?"notificationPage":"widgetPage");
            View target=notifications?((ViewGroup)page).getChildAt(0):(View)field(hub,"widgetCanvas");
            root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(page.getVisibility()==View.VISIBLE&&target.getWidth()>0&&target.getHeight()>0){root.getViewTreeObserver().removeOnPreDrawListener(this);drawn.countDown();}return true;}});
            Method show=HubActivity.class.getDeclaredMethod("showTab",boolean.class);show.setAccessible(true);show.invoke(hub,notifications);root.requestLayout();
        }catch(Exception e){throw new AssertionError(e);}});
        boolean ready=drawn.await(5,java.util.concurrent.TimeUnit.SECONDS);
        if(!ready){String[] state={""};test.runOnMainSync(()->{try{View page=(View)field(hub,notifications?"notificationPage":"widgetPage");View target=notifications?((ViewGroup)page).getChildAt(0):(View)field(hub,"widgetCanvas");state[0]=" notifications="+notifications+" page="+page.getWidth()+","+page.getHeight()+" visible="+page.getVisibility()+" target="+target.getWidth()+","+target.getHeight()+" attached="+target.isAttachedToWindow()+" focus="+hub.hasWindowFocus()+" finishing="+hub.isFinishing();}catch(Exception e){state[0]=e.getClass().getSimpleName();}});throw new AssertionError("Hub layout did not complete"+state[0]);}
        test.waitForIdleSync();
    }
    private static void state(Context c){
        String store="hub_state_test_"+System.nanoTime();SharedPreferences p=c.getSharedPreferences(store,0);
        try{
            HubState phone=new HubState(p,true),desktop=new HubState(p,false);
            check(!phone.notifications()&&!desktop.notifications(),"default tab");phone.select(true);
            check(new HubState(p,true).notifications()&&!desktop.notifications(),"phone tab must persist independently");
            desktop.select(true);phone.select(false);check(!phone.notifications()&&new HubState(p,false).notifications(),"desktop tab lost");
        }finally{c.deleteSharedPreferences(store);}
    }
    private static StatusBarNotification notification(Context c,String pkg,int id,int user,boolean summary){
        Notification n=new Notification.Builder(c,"synthetic-only").setSmallIcon(android.R.drawable.ic_dialog_info).setGroup("test").setGroupSummary(summary).setNumber(999).build();
        int uid=10000+user*100000;
        return new StatusBarNotification(pkg,pkg,id,null,uid,0,0,n,UserHandle.getUserHandleForUid(uid),id);
    }
    private static void notifications(Context c)throws Exception{
        List<StatusBarNotification> items=Arrays.asList(notification(c,"a",1,0,true),notification(c,"a",2,0,false),notification(c,"a",3,0,false),notification(c,"b",4,0,true),notification(c,"a",5,10,false));
        Map<String,Integer> counts=NotificationApps.collect(items,UserHandle.getUserHandleForUid(10000));
        check(counts.size()==2&&counts.get("a")==2&&counts.get("b")==1,"summary double counting or cross-user count");
        NotificationApps feed=new NotificationApps(c,()->{});
        Field field=NotificationApps.class.getDeclaredField("counts");field.setAccessible(true);field.set(feed,counts);
        List<String> original=Arrays.asList("x","b","y","a");
        check(feed.prioritize(original,item->item).equals(Arrays.asList("b","a","x","y")),"notification partition must be stable");
        check(original.equals(Arrays.asList("x","b","y","a")),"saved order mutated");check(feed.count("a/.Alias")==2,"alias count");
        feed.start();feed.close();feed.close();check(feed.count("a")==0,"closed feed retained counts");
    }
    private static void rail(Context c){
        for(int height:new int[]{720,320,240}){
            LinearLayout tools=Ui.column(c);HubNavigation tabs=new HubNavigation(c,()->{},()->{},true);tools.addView(tabs);
            tools.addView(new Button(c),new LinearLayout.LayoutParams(-1,Ui.dp(c,48)));tools.addView(new Button(c),new LinearLayout.LayoutParams(-1,Ui.dp(c,48)));
            View pages=new Button(c);HubControlRail rail=new HubControlRail(c,tools,pages);
            layout(rail,c,360,height);
            check(tools.getWidth()==Ui.dp(c,56),"controls expanded across canvas");
            check(tools.getBottom()<=rail.getHeight()&&tools.getTop()>=0,"controls outside rail");
            if(height==720){float center=(tools.getTop()+tabs.getTop()+tabs.getHeight()/2f)/rail.getHeight();check(center>.66f&&center<.76f,"thumb position");}
            if(pages.getVisibility()==View.VISIBLE)check(pages.getBottom()<=tools.getTop(),"page controls overlap tools");
            rail.widgetPage(false);layout(rail,c,360,height);check(pages.getVisibility()==View.GONE,"page controls visible on notifications");
        }
    }
    private static void reservation(Context c)throws Exception{
        FrameLayout body=new FrameLayout(c);Button under=new Button(c);int[] taps={0};under.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_UP)taps[0]++;return true;});body.addView(under,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout tools=Ui.column(c);HubNavigation tabs=new HubNavigation(c,()->{},()->{},true);tools.addView(tabs);tools.addView(new Button(c),new LinearLayout.LayoutParams(-1,Ui.dp(c,48)));tools.addView(new Button(c),new LinearLayout.LayoutParams(-1,Ui.dp(c,48)));
        HubControlRail rail=new HubControlRail(c,tools,new Button(c));body.addView(rail,new FrameLayout.LayoutParams(-1,-1));
        android.graphics.Rect reserved=new android.graphics.Rect();rail.onReservation(reserved::set);
        for(boolean right:new boolean[]{false,true}){
            rail.fromRight(right);layout(body,c,360,720);
            check(reserved.width()==Ui.dp(c,56)&&reserved.height()<=Ui.dp(c,250)&&reserved.top>body.getHeight()/2,"reservation is not compact/lower");
            check(right?reserved.right==body.getWidth():reserved.left==0,"reservation side");
            float x=right?Ui.dp(c,340):Ui.dp(c,20);tap(body,x,Ui.dp(c,30));
        }
        check(taps[0]==2,"upper canvas blocked by transparent rail");
        rail.fromRight(false);layout(body,c,360,720);
        tap(body,Ui.dp(c,20),tools.getTop()+Ui.dp(c,24));check(taps[0]==2,"controls passed through to widget");
        String store="hub_obstacle_test_"+System.nanoTime();Screen screen=new Screen(c,Configuration.ORIENTATION_PORTRAIT);SharedPreferences p=c.getSharedPreferences(store,0);DesktopWidgets widgets=null;
        try{
            p.edit().putString("items",new JSONArray().put(new JSONObject().put("id",-1).put("kind","text").put("text","Synthetic").put("x",0).put("y",480).put("w",180).put("h",90)).toString()).commit();
            FrameLayout canvas=new FrameLayout(screen);layout(canvas,screen,360,720);widgets=new DesktopWidgets(screen,canvas,store,HOST);widgets.enablePages(()->{});
            String original=p.getString("items","");widgets.reservedControls(new android.graphics.Rect(0,Ui.dp(c,450),Ui.dp(c,56),Ui.dp(c,700)));widgets.setEditing(true);layout(canvas,screen,360,720);
            check(original.equals(p.getString("items","")),"reservation relocated saved widgets");
            check(((View)field(widgets,"overlapHint")).getVisibility()==View.VISIBLE,"legacy overlap warning absent");
            Object entry=((List<?>)field(widgets,"entries")).get(0);Method adjust=DesktopWidgets.class.getDeclaredMethod("adjust",entry.getClass(),int.class,int.class);adjust.setAccessible(true);
            adjust.invoke(widgets,entry,0,70);check(((View)field(widgets,"overlapHint")).getVisibility()==View.GONE,"cannot escape reserved area");
            adjust.invoke(widgets,entry,0,0);Field x=entry.getClass().getDeclaredField("x");x.setAccessible(true);check(x.getInt(entry)==70,"new intrusion accepted");
            adjust.invoke(widgets,entry,1,20);adjust.invoke(widgets,entry,0,0);check(x.getInt(entry)==0,"upper edge incorrectly reserved");
        }finally{if(widgets!=null)widgets.destroy();c.deleteSharedPreferences(store);}
    }
    private static void tap(View view,float x,float y){long t=android.os.SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(t,t,action,x,y,0);view.dispatchTouchEvent(event);event.recycle();}}
    static final class Screen extends Activity {
        private Resources resources;
        Screen(Context context,int orientation){attachBaseContext(context);orient(orientation);}
        void orient(int orientation){Configuration c=new Configuration(getBaseContext().getResources().getConfiguration());c.orientation=orientation;resources=getBaseContext().createConfigurationContext(c).getResources();}
        @Override public Resources getResources(){return resources==null?super.getResources():resources;}
        @Override public Display getDisplay(){return getBaseContext().getSystemService(DisplayManager.class).getDisplay(0);}
    }
    private static void widgets(Context c,int width,int height)throws Exception{
        String store="widget_hub_test_"+System.nanoTime();SharedPreferences p=c.getSharedPreferences(store,0);Screen screen=new Screen(c,Configuration.ORIENTATION_PORTRAIT);
        FrameLayout canvas=new FrameLayout(screen);DesktopWidgets widgets=null;
        try{
            JSONArray initial=new JSONArray().put(new JSONObject().put("id",-1).put("kind","text").put("text","Synthetic text").put("x",12).put("y",20).put("w",180).put("h",90));
            p.edit().putString("items",initial.toString()).commit();layout(canvas,screen,width,height);
            widgets=new DesktopWidgets(screen,canvas,store,HOST);widgets.enablePages(()->{});
            check(widgets.pageCount()==1&&canvas.getChildAt(0).getVisibility()==View.VISIBLE,"legacy migration");
            widgets.addPage();check(widgets.currentPage()==1&&canvas.getChildAt(0).getVisibility()==View.GONE,"new page not empty");
            widgets.moveWidgetToPage(-1,1);check(canvas.getChildAt(0).getVisibility()==View.VISIBLE,"moved widget hidden");
            widgets.setEditing(true);layout(canvas,screen,width,height);
            View editor=(View)field(widgets,"editor");check(editor.getLayoutParams().height==ViewGroup.LayoutParams.WRAP_CONTENT,"inspector fills canvas");
            check(editor.getHeight()<Ui.dp(screen,200),"compact inspector too tall");
            check(!contains(editor,R.string.widget_edit_x,screen)&&!contains(editor,R.string.widget_item_background,screen),"numeric/detail controls leaked into inspector");
            Object entry=((List<?>)field(widgets,"entries")).get(0);
            Method content=DesktopWidgets.class.getDeclaredMethod("detailContent",entry.getClass(),int.class);content.setAccessible(true);
            View appearance=(View)content.invoke(widgets,entry,1);check(contains(appearance,R.string.widget_edit_text,screen)&&!has(appearance,RadioButton.class),"text details show widget-only controls");
            View management=(View)content.invoke(widgets,entry,2);check(contains(management,R.string.ui_remove_widget,screen)&&!contains(management,R.string.widget_add_image,screen),"management mixes global creation");
            widgets.finishEditing();String items=p.getString("items","");
            screen.orient(Configuration.ORIENTATION_LANDSCAPE);widgets.configurationChanged();check(widgets.pageCount()==1&&widgets.currentPage()==0,"portrait pages leaked into landscape");
            screen.orient(Configuration.ORIENTATION_PORTRAIT);widgets.configurationChanged();check(widgets.pageCount()==2&&widgets.currentPage()==1,"portrait page lost on rotation");
            widgets.destroy();canvas.removeAllViews();widgets=new DesktopWidgets(screen,canvas,store,HOST);widgets.enablePages(()->{});
            check(widgets.currentPage()==1&&widgets.pageCount()==2,"page lost on recreation");
            JSONObject saved=new JSONArray(items).getJSONObject(0);check(saved.getInt("id")==-1&&saved.getInt("x")==12&&saved.getInt("w")==180,"paging changed identity/geometry");
            check(new AppWidgetHost(screen,HOST).getAppWidgetIds().length==0,"paging allocated widget IDs");
        }finally{if(widgets!=null)widgets.destroy();canvas.removeAllViews();c.deleteSharedPreferences(store);}
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static boolean contains(View view,int label,Context c){if(view instanceof TextView&&((TextView)view).getText().toString().equals(c.getString(label)))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),label,c))return true;return false;}
    private static boolean has(View view,Class<?> type){if(type.isInstance(view))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(has(((ViewGroup)view).getChildAt(i),type))return true;return false;}
    private static void layout(View view,Context c,int w,int h){view.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(c,w),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Ui.dp(c,h),View.MeasureSpec.EXACTLY));view.layout(0,0,Ui.dp(c,w),Ui.dp(c,h));}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
