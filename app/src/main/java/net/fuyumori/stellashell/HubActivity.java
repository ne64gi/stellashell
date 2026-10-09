package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.*;
import java.lang.ref.WeakReference;

/** Clock popover. Widgets have a distinct host/storage from the desktop surface. */
public final class HubActivity extends Activity implements DisplayManager.DisplayListener {
    private static WeakReference<HubActivity> visible=new WeakReference<>(null);
    private int displayId;
    private LinearLayout panel;
    private FrameLayout backdrop;
    private SidebarDrag sidebarDrag;
    private android.animation.ValueAnimator pullAnimation;
    private float pullProgress;
    private static SidebarDrag pendingDrag;
    private static long dragSequence;
    static final class SidebarDrag {
        final long token=++dragSequence;
        final boolean right;
        final java.util.function.Consumer<Float> leading;
        final Runnable done;
        final float threshold;
        final Float origin;
        float distance;
        boolean ended,commit,finished;
        SidebarDrag(Context c,boolean right,float distance,Float origin,java.util.function.Consumer<Float> leading,Runnable done){this.right=right;this.distance=distance;this.origin=origin;this.leading=leading;this.done=done;threshold=Ui.dp(c,32);}
        void update(float distance){if(finished||ended)return;this.distance=distance;HubActivity current=visible.get();if(current!=null&&current.sidebarDrag==this)current.renderPull();}
        void release(boolean cancel){if(finished||ended)return;ended=true;commit=!cancel&&distance>=threshold;HubActivity current=visible.get();if(current!=null&&current.sidebarDrag==this)current.renderPull();}
        void complete(){if(finished)return;finished=true;if(pendingDrag==this)pendingDrag=null;done.run();}
    }
    static SidebarDrag beginPull(Context c,boolean right,float distance,java.util.function.Consumer<Float> leading,Runnable done){
        return beginPull(c,right,distance,null,leading,done);
    }
    static SidebarDrag beginPull(Context c,boolean right,float distance,Float origin,java.util.function.Consumer<Float> leading,Runnable done){
        if(pendingDrag!=null)pendingDrag.complete();HubActivity current=visible.get();if(current!=null)current.finish();
        SidebarDrag drag=new SidebarDrag(c,right,distance,origin,leading,done);pendingDrag=drag;
        // The panel owns its pull animation; a task enter animation would add a second axis.
        try{c.startActivity(new Intent(c,HubActivity.class).putExtra("from_right",right).putExtra("sidebar_pull",drag.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION),ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());}
        catch(RuntimeException error){drag.complete();Launches.problem(c,error.getMessage());}
        return drag;
    }
    private DesktopWidgets widgets;
    private FrameLayout widgetCanvas;
    private LinearLayout widgetPage,notificationPage,notificationRows;
    private NotificationList notificationList;
    private Button panelSettings;
    private HubNavigation navigation;
    private boolean notifications;
    private boolean screenOff;
    private boolean started;
    private static final int NOTIFICATIONS_LIVE=0,NOTIFICATIONS_LOCKED=1,NOTIFICATIONS_UNAVAILABLE=2;
    private int notificationState=-1;
    private DisplayManager displays;
    private boolean observingNavigation;
    private final Runnable navigationChanged=()->{if(!ShellRuntime.enabled(this))finish();};
    private final Runnable refresh=()->{if(notificationRows!=null)renderNotifications();};
    private final BroadcastReceiver lockChanges=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String action=i.getAction();
        if(Intent.ACTION_TIME_TICK.equals(action)||Intent.ACTION_TIME_CHANGED.equals(action)||Intent.ACTION_TIMEZONE_CHANGED.equals(action)){
            if(started&&notifications&&notificationState==NOTIFICATIONS_LIVE){
                if(screenOff||ShellNotifications.locked(HubActivity.this)||!ShellNotifications.ready(HubActivity.this))renderNotifications();
                else notificationList.refreshTimes();
            }
            return;
        }
        if(Intent.ACTION_SCREEN_OFF.equals(i.getAction()))screenOff=true;
        else if(Intent.ACTION_SCREEN_ON.equals(i.getAction())||Intent.ACTION_USER_PRESENT.equals(i.getAction()))screenOff=false;
        syncWidgets();
        refresh.run();
    }};
    static void open(Context context,int displayId){open(context,displayId,true);}
    static void open(Context context,int displayId,boolean fromRight){
        try{
            if(displayId!=0)Displays.require(context,displayId);
            HubActivity current=visible.get();
            if(current!=null&&!current.isFinishing()&&current.displayId==displayId&&current.hasWindowFocus()){current.finish();return;}
            context.startActivity(new Intent(context,HubActivity.class).putExtra("from_right",fromRight).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION),ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        }catch(RuntimeException e){Launches.problem(context,e.getMessage());}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);displayId=getDisplay()==null?-1:getDisplay().getDisplayId();
        try{if(displayId!=0)Displays.require(this,displayId);if(!ShellRuntime.enabled(this)){finish();return;}}
        catch(RuntimeException e){finish();return;}
        visible=new WeakReference<>(this);
        if(pendingDrag!=null&&getIntent().getLongExtra("sidebar_pull",-1)==pendingDrag.token){sidebarDrag=pendingDrag;getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);}
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));
        ShellRuntime.observeNavigation(navigationChanged);observingNavigation=true;
        if(!ShellRuntime.enabled(this)){finish();return;}
        backdrop=new FrameLayout(this);backdrop.setBackgroundColor(0x22101725);backdrop.setOnClickListener(v->finish());
        panel=Ui.column(this);panel.setPadding(dp(16),dp(16),dp(16),dp(16));panel.setBackground(Appearance.surface(this,22));panel.setElevation(dp(18));panel.setOnClickListener(v->{});
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(Math.min(dp(720),getResources().getDisplayMetrics().widthPixels-dp(24)),-1,(getIntent().getBooleanExtra("from_right",true)?Gravity.RIGHT:Gravity.LEFT)|Gravity.TOP);
        box.setMargins(dp(12),dp(16),dp(12),dp(displayId==0?16:76));backdrop.addView(panel,box);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Ui.text(this,getString(R.string.hub_title),22,Ui.TEXT);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        panelSettings=Ui.button(this,"⚙",this::settingsMenu);panelSettings.setContentDescription(getString(R.string.hub_widget_settings));heading.addView(panelSettings,new LinearLayout.LayoutParams(dp(48),dp(44)));
        Button close=Ui.button(this,"×",this::finish);close.setContentDescription(getString(R.string.ui_close));heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(44)));panel.addView(heading);
        FrameLayout pages=new FrameLayout(this);panel.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        widgetPage=Ui.column(this);pages.addView(widgetPage,new FrameLayout.LayoutParams(-1,-1));
        widgetCanvas=new FrameLayout(this);widgetCanvas.setClipChildren(true);widgetPage.addView(widgetCanvas,new LinearLayout.LayoutParams(-1,0,1));widgets=new DesktopWidgets(this,widgetCanvas,true);widgets.onEditingChanged(this::updateEdit);
        notificationPage=Ui.column(this);pages.addView(notificationPage,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);notificationRows=Ui.column(this);scroll.addView(notificationRows);notificationPage.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        notificationList=new NotificationList(notificationRows,this::openNotification,item->{if(!ShellNotifications.dismiss(this,item.getKey()))Ui.message(this,getString(R.string.hub_notification_gone));});
        if(state!=null)notificationList.restoreExpandedGroups(state.getStringArrayList("expanded_notification_groups"));
        navigation=new HubNavigation(this,()->showTab(false),()->showTab(true));
        FrameLayout footer=new FrameLayout(this);
        FrameLayout.LayoutParams navBox=new FrameLayout.LayoutParams(Math.min(dp(400),getResources().getDisplayMetrics().widthPixels-dp(56)),dp(56),Gravity.CENTER);
        footer.addView(navigation,navBox);
        LinearLayout.LayoutParams footerBox=new LinearLayout.LayoutParams(-1,dp(56));footerBox.topMargin=dp(10);panel.addView(footer,footerBox);
        setContentView(backdrop);
        panel.post(()->{
            if(sidebarDrag!=null)renderPull();
            else{panel.setTranslationX(getIntent().getBooleanExtra("from_right",true)?panel.getWidth():-panel.getWidth());panel.animate().translationX(0).setDuration(180).start();}
        });ShellPanels.activate(displayId,this,this::finish);ShellPanels.track(displayId,this,panel);getWindow().setLayout(-1,-1);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(displayId>0)getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_TIME_TICK);filter.addAction(Intent.ACTION_TIME_CHANGED);filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(lockChanges,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(lockChanges,filter);
        showTab(state!=null&&state.getBoolean("notifications"));
    }
    private int dp(int value){return Ui.dp(this,value);}
    private float pullRange(){
        if(sidebarDrag.origin==null)return panel.getWidth()+dp(12);
        int[] location=new int[2];panel.getLocationOnScreen(location);
        float restingLeading=location[0]-panel.getTranslationX()+(sidebarDrag.right?0:panel.getWidth());
        return Math.max(1,sidebarDrag.right?sidebarDrag.origin-restingLeading:restingLeading-sidebarDrag.origin);
    }
    private void applyPull(float progress){
        pullProgress=progress;float range=pullRange();
        panel.setTranslationX((sidebarDrag.right?1:-1)*range*(1-progress));
        backdrop.setBackgroundColor((Math.round(34*progress)<<24)|0x101725);
        int[] location=new int[2];panel.getLocationOnScreen(location);
        sidebarDrag.leading.accept((float)(location[0]+(sidebarDrag.right?0:panel.getWidth())));
        ShellPanels.bounds(displayId,this,new android.graphics.Rect(location[0],location[1],location[0]+panel.getWidth(),location[1]+panel.getHeight()));
    }
    private void renderPull(){
        if(sidebarDrag==null||panel.getWidth()==0||isFinishing()||pullAnimation!=null)return;
        applyPull(Math.min(1,sidebarDrag.distance/pullRange()));
        if(!sidebarDrag.ended)return;
        float target=sidebarDrag.commit?1:0;
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){applyPull(target);endPull();return;}
        pullAnimation=android.animation.ValueAnimator.ofFloat(pullProgress,target);pullAnimation.setDuration(180);
        pullAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());pullAnimation.addUpdateListener(animation->applyPull((float)animation.getAnimatedValue()));
        pullAnimation.addListener(new android.animation.AnimatorListenerAdapter(){boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation){cancelled=true;}
            @Override public void onAnimationEnd(android.animation.Animator animation){pullAnimation=null;if(!cancelled)endPull();}
        });pullAnimation.start();
    }
    private void endPull(){sidebarDrag.complete();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);if(!sidebarDrag.commit)finish();}

    private void settingsMenu(){
        PopupMenu menu=new PopupMenu(this,panelSettings);
        if(notifications){
            menu.getMenu().add(R.string.hub_access).setOnMenuItemClickListener(item->{
                try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());}
                catch(RuntimeException e){Launches.problem(this,e.getMessage());}
                return true;
            });
            menu.show();return;
        }
        if(widgets==null)return;
        menu.getMenu().add(R.string.ui_add_widget).setOnMenuItemClickListener(item->{widgets.choose();return true;});
        menu.getMenu().add(R.string.widget_add_text).setOnMenuItemClickListener(item->{widgets.addText();return true;});
        menu.getMenu().add(R.string.widget_add_image).setOnMenuItemClickListener(item->{widgets.addImage();return true;});
        menu.getMenu().add(widgets.isEditing()?R.string.ui_finish_editing_widgets:R.string.ui_edit_widgets).setOnMenuItemClickListener(item->{widgets.setEditing(!widgets.isEditing());updateEdit();return true;});
        menu.getMenu().add(R.string.widget_launch_primary).setCheckable(true).setChecked(Launches.prefs(this).getBoolean(WidgetLaunchContext.PRIMARY,true)).setOnMenuItemClickListener(item->{Launches.prefs(this).edit().putBoolean(WidgetLaunchContext.PRIMARY,!item.isChecked()).apply();return true;});
        menu.show();
    }
    private void updateEdit(){if(panelSettings!=null&&widgets!=null){
        boolean editing=!notifications&&widgets.isEditing();
        panelSettings.setText(editing?"⚙ •":"⚙");
        String description=getString(notifications?R.string.hub_access:editing?R.string.ui_finish_editing_widgets:R.string.hub_widget_settings);
        panelSettings.setContentDescription(description);panelSettings.setTooltipText(description);
    }}
    private void showTab(boolean value){if(value&&widgets!=null)widgets.finishEditing();notifications=value;syncWidgets();updateEdit();widgetPage.setVisibility(value?View.GONE:View.VISIBLE);notificationPage.setVisibility(value?View.VISIBLE:View.GONE);navigation.select(value);renderNotifications();}
    private boolean widgetsListening;
    private void syncWidgets(){
        boolean wanted=started&&!notifications&&!screenOff;
        if(widgets==null||wanted==widgetsListening)return;
        widgetsListening=wanted;
        if(wanted)widgets.start();else widgets.stop();
    }
    private void renderNotifications(){
        if(!started){notificationList.clear();notificationState=-1;navigation.setNotificationPresence(false,false,null);return;}
        boolean locked=screenOff||ShellNotifications.locked(this),available=ShellNotifications.ready(this);
        java.util.List<StatusBarNotification> snapshot=locked||!available?java.util.Collections.emptyList():ShellNotifications.current(this);
        // Recheck after snapshot acquisition so a newly lost lock/access guard also clears old cards.
        locked=locked||ShellNotifications.locked(this);available=available&&ShellNotifications.ready(this);
        navigation.setNotificationPresence(locked,available,snapshot);
        int state=locked?NOTIFICATIONS_LOCKED:available?NOTIFICATIONS_LIVE:NOTIFICATIONS_UNAVAILABLE;
        if(state!=notificationState){
            // Only privacy/notice transitions discard rows; live updates keep the renderer and its anchors.
            notificationList.clear();notificationState=state;
            if(state!=NOTIFICATIONS_LIVE)Ui.note(notificationRows,getString(state==NOTIFICATIONS_LOCKED?R.string.hub_locked:R.string.hub_access_note));
        }
        // A hidden notification page need not rebuild when providers post/update while widgets are in use.
        if(notifications&&state==NOTIFICATIONS_LIVE)notificationList.render(snapshot);
    }
    private void openNotification(StatusBarNotification item){
        if(screenOff||ShellNotifications.locked(this)||!ShellNotifications.ready(this))return;
        // Re-read so removed or updated PendingIntents are not executed from stale cards.
        for(StatusBarNotification current:ShellNotifications.current(this))if(current.getKey().equals(item.getKey())){
            if(screenOff||ShellNotifications.locked(this)||!ShellNotifications.ready(this)){renderNotifications();return;}
            PendingIntent intent=current.getNotification().contentIntent;if(intent==null)return;
            try{
                // Notification destinations belong on the phone, not over the external shell.
                // Keep the original PendingIntent, including provider flags and actions.
                ActivityOptions options=ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY);
                if(Build.VERSION.SDK_INT>=34)options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                intent.send(this,0,null,null,null,null,options.toBundle());
                if(displayId!=Display.DEFAULT_DISPLAY)Ui.message(this,getString(R.string.opening_on_phone));
                finish();
            }catch(PendingIntent.CanceledException|RuntimeException e){Ui.message(this,getString(R.string.hub_notification_gone));renderNotifications();}return;
        }
        renderNotifications();
    }
    @Override protected void onStart(){super.onStart();started=true;PowerManager power=getSystemService(PowerManager.class);screenOff=power!=null&&!power.isInteractive();syncWidgets();ShellNotifications.observe(refresh);}
    @Override protected void onResume(){super.onResume();if(!isFinishing())ShellPanels.activate(displayId,this,this::finish);if(notificationRows!=null)renderNotifications();updateEdit();}
    @Override protected void onStop(){started=false;syncWidgets();ShellNotifications.unobserve(refresh);if(notificationList!=null){notificationList.clear();notificationState=-1;}if(navigation!=null)navigation.setNotificationPresence(false,false,null);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("notifications",notifications);if(notificationList!=null)state.putStringArrayList("expanded_notification_groups",notificationList.expandedGroups());super.onSaveInstanceState(state);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(widgets!=null){widgets.result(request,result,data);updateEdit();}}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getKeyCode()==KeyEvent.KEYCODE_ESCAPE&&event.getAction()==KeyEvent.ACTION_UP){if(widgets==null||!widgets.finishEditing())finish();return true;}return super.dispatchKeyEvent(event);}
    @Override public void onBackPressed(){if(widgets==null||!widgets.finishEditing())super.onBackPressed();}
    @Override public void onDisplayRemoved(int id){if(id==displayId)finish();}
    @Override public void onDisplayAdded(int id){}
    @Override public void onDisplayChanged(int id){}
    @Override public void onDestroy(){
        if(pullAnimation!=null)pullAnimation.cancel();if(sidebarDrag!=null)sidebarDrag.complete();
        ShellPanels.release(displayId,this);
        if(observingNavigation){ShellRuntime.unobserveNavigation(navigationChanged);observingNavigation=false;}
        if(visible.get()==this)visible.clear();ShellNotifications.unobserve(refresh);
        if(notificationList!=null)notificationList.clear();
        if(widgets!=null)widgets.destroy();if(displays!=null)displays.unregisterDisplayListener(this);
        try{unregisterReceiver(lockChanges);}catch(IllegalArgumentException ignored){}super.onDestroy();
    }
}
