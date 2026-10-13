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
import net.fuyumori.stellashell.core.layout.HubPullAxis;

/** Clock popover. Widgets have a distinct host/storage from the desktop surface. */
public final class HubActivity extends Activity implements DisplayManager.DisplayListener {
    private static WeakReference<HubActivity> visible=new WeakReference<>(null);
    private int displayId;
    private LinearLayout panel;
    private FrameLayout backdrop;
    private SidebarDrag sidebarDrag;
    private android.animation.ValueAnimator pullAnimation;
    private float pullProgress;
    private int pullDisplayWidth,pullDisplayHeight,pullDisplayRotation;
    private static SidebarDrag pendingDrag;
    private static long dragSequence;
    static final class SidebarDrag {
        final long token=++dragSequence;
        final boolean right;
        final String edge;
        final HubPullAxis axis;
        final java.util.function.Consumer<Float> leading;
        final Runnable done;
        final float threshold;
        final Float origin;
        float distance;
        boolean ended,commit,finished;
        SidebarDrag(Context c,boolean right,float distance,Float origin,java.util.function.Consumer<Float> leading,Runnable done){this(c,right?"right":"left",distance,origin,leading,done);}
        SidebarDrag(Context c,String edge,float distance,Float origin,java.util.function.Consumer<Float> leading,Runnable done){axis=new HubPullAxis(edge);this.edge=axis.edge;right="right".equals(this.edge);this.distance=distance;this.origin=origin;this.leading=leading;this.done=done;threshold=Ui.dp(c,32);}
        void update(float distance){if(finished||ended)return;this.distance=distance;HubActivity current=visible.get();if(current!=null&&current.sidebarDrag==this)current.renderPull();}
        void release(boolean cancel){if(finished||ended)return;ended=true;commit=!cancel&&Float.isFinite(distance)&&distance>=threshold;HubActivity current=visible.get();if(current!=null&&current.sidebarDrag==this)current.renderPull();}
        /** Geometry/owner invalidation overrides an already-ended commit, without waiting for its animation. */
        void cancel(){if(finished)return;ended=true;commit=false;HubActivity current=visible.get();if(current!=null&&current.sidebarDrag==this)current.finish();else complete();}
        void complete(){if(finished)return;finished=true;if(pendingDrag==this)pendingDrag=null;done.run();}
    }
    static SidebarDrag beginPull(Context c,boolean right,float distance,java.util.function.Consumer<Float> leading,Runnable done){
        return beginPull(c,right,distance,null,leading,done);
    }
    static SidebarDrag beginPull(Context c,boolean right,float distance,Float origin,java.util.function.Consumer<Float> leading,Runnable done){
        return beginPull(c,right?"right":"left",distance,origin,leading,done);
    }
    static SidebarDrag beginPull(Context c,String edge,float distance,Float origin,java.util.function.Consumer<Float> leading,Runnable done){
        if(pendingDrag!=null)pendingDrag.cancel();HubActivity current=visible.get();if(current!=null)current.finish();
        SidebarDrag drag=new SidebarDrag(c,edge,distance,origin,leading,done);pendingDrag=drag;
        // The panel owns its pull animation; a task enter animation would add a second axis.
        try{c.startActivity(new Intent(c,HubActivity.class).putExtra("from_right",drag.right).putExtra("sidebar_edge",drag.edge).putExtra("sidebar_pull",drag.token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION),ActivityOptions.makeCustomAnimation(c,0,0).setLaunchDisplayId(0).toBundle());}
        catch(RuntimeException error){drag.complete();Launches.problem(c,error.getMessage());}
        return drag;
    }
    private DesktopWidgets widgets;
    private FrameLayout widgetCanvas;
    private LinearLayout widgetPage,notificationPage,notificationRows;
    private NotificationList notificationList;
    private Button panelSettings;
    private HubNavigation navigation;
    private HubPageNavigation pageNavigation;
    private HubState hubState;
    private HubControlRail controlRail;
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
        if(state!=null&&state.getBoolean("sidebar_pull_finished"))getIntent().removeExtra("sidebar_pull");
        long pullToken=getIntent().getLongExtra("sidebar_pull",-1);
        if(pullToken!=-1){
            // A rotation/replacement must not revive a gesture whose overlay has already released it.
            if(pendingDrag==null||pendingDrag.finished||pendingDrag.token!=pullToken){finish();return;}
            sidebarDrag=pendingDrag;capturePullDisplay();getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);overridePendingTransition(0,0);
        }
        try{if(displayId!=0)Displays.require(this,displayId);if(!ShellRuntime.enabled(this)){finish();return;}}
        catch(RuntimeException e){finish();return;}
        visible=new WeakReference<>(this);
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));
        ShellRuntime.observeNavigation(navigationChanged);observingNavigation=true;
        if(!ShellRuntime.enabled(this)){finish();return;}
        backdrop=new FrameLayout(this);backdrop.setBackgroundColor(0x22101725);backdrop.setOnClickListener(v->finish());
        panel=Ui.column(this);panel.setPadding(dp(16),dp(16),dp(16),dp(16));panel.setBackground(Appearance.surface(this,22));panel.setElevation(dp(18));panel.setOnClickListener(v->{});
        backdrop.addView(panel,panelBox());
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Ui.text(this,getString(R.string.hub_title),22,Ui.TEXT);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        panelSettings=Ui.button(this,"⚙",this::settingsMenu);panelSettings.setContentDescription(getString(R.string.hub_widget_settings));heading.addView(panelSettings,new LinearLayout.LayoutParams(dp(48),dp(44)));
        Button close=Ui.button(this,"×",this::finish);close.setContentDescription(getString(R.string.ui_close));heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(44)));panel.addView(heading);
        boolean phoneControls=WorkspaceProfile.standard(this,displayId);
        hubState=new HubState(this,phoneControls);
        FrameLayout body=new FrameLayout(this);
        panel.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout pages=new FrameLayout(this);body.addView(pages,new FrameLayout.LayoutParams(-1,-1));
        widgetPage=Ui.column(this);pages.addView(widgetPage,new FrameLayout.LayoutParams(-1,-1));
        widgetCanvas=new FrameLayout(this);widgetCanvas.setClipChildren(true);widgetPage.addView(widgetCanvas,new LinearLayout.LayoutParams(-1,0,1));widgets=new DesktopWidgets(this,widgetCanvas,true);widgets.onEditingChanged(this::updateEdit);
        notificationPage=Ui.column(this);pages.addView(notificationPage,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);notificationRows=Ui.column(this);scroll.addView(notificationRows);notificationPage.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        notificationList=new NotificationList(notificationRows,this::openNotification,item->{if(!ShellNotifications.dismiss(this,item.getKey()))Ui.message(this,getString(R.string.hub_notification_gone));});
        if(state!=null)notificationList.restoreExpandedGroups(state.getStringArrayList("expanded_notification_groups"));
        navigation=new HubNavigation(this,()->selectTab(false),()->selectTab(true),phoneControls);
        widgets.enablePages(()->{if(pageNavigation!=null)pageNavigation.refresh();});
        pageNavigation=new HubPageNavigation(this,widgets,phoneControls);
        if(phoneControls){
            heading.removeView(panelSettings);heading.removeView(close);
            LinearLayout tools=Ui.column(this);
            tools.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
            tools.addView(panelSettings,new LinearLayout.LayoutParams(-1,dp(48)));
            tools.addView(close,new LinearLayout.LayoutParams(-1,dp(48)));
            HubControlRail rail=new HubControlRail(this,tools,pageNavigation);controlRail=rail;
            String edge=getIntent().getStringExtra("sidebar_edge");
            boolean fromRight="right".equals(edge)||(!"left".equals(edge)&&getIntent().getBooleanExtra("from_right",true));
            widgets.hubSide(fromRight);
            rail.fromRight(fromRight);rail.onReservation(widgets::reservedControls);
            insetNotifications(fromRight);
            body.addView(rail,new FrameLayout.LayoutParams(-1,-1));
        }else{
            widgetPage.addView(pageNavigation,new LinearLayout.LayoutParams(-1,dp(48)));
            FrameLayout footer=new FrameLayout(this);
            FrameLayout.LayoutParams navBox=new FrameLayout.LayoutParams(Math.min(dp(400),getResources().getDisplayMetrics().widthPixels-dp(56)),dp(56),Gravity.CENTER);
            footer.addView(navigation,navBox);
            LinearLayout.LayoutParams footerBox=new LinearLayout.LayoutParams(-1,dp(56));footerBox.topMargin=dp(10);panel.addView(footer,footerBox);
        }
        setContentView(backdrop);
        backdrop.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            if(sidebarDrag!=null&&!sidebarDrag.finished&&oldRight>oldLeft&&oldBottom>oldTop&&
                    (right-left!=oldRight-oldLeft||bottom-top!=oldBottom-oldTop)){finish();return;}
            if(getIntent().hasExtra("sidebar_edge")){
                FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)panel.getLayoutParams();
                int width=Math.max(1,Math.min(dp(720),right-left-dp(24)));
                if(p.width!=width){p.width=width;panel.setLayoutParams(p);}
            }
        });
        panel.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{if(sidebarDrag!=null&&!sidebarDrag.finished)renderPull();});
        panel.post(()->{
            if(isFinishing())return;
            if(sidebarDrag!=null)renderPull();
            else if(getIntent().hasExtra("sidebar_edge")){panel.setTranslationX(0);panel.setTranslationY(0);}
            else{panel.setTranslationX(getIntent().getBooleanExtra("from_right",true)?panel.getWidth():-panel.getWidth());panel.animate().translationX(0).setDuration(180).start();}
        });ShellPanels.activate(displayId,this,this::finish);ShellPanels.track(displayId,this,panel);getWindow().setLayout(-1,-1);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(displayId>0)getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_TIME_TICK);filter.addAction(Intent.ACTION_TIME_CHANGED);filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(lockChanges,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(lockChanges,filter);
        showTab(state!=null?state.getBoolean("notifications"):hubState.notifications());
    }
    private int dp(int value){return Ui.dp(this,value);}
    private FrameLayout.LayoutParams panelBox(){
        int gravity=(getIntent().getBooleanExtra("from_right",true)?Gravity.RIGHT:Gravity.LEFT)|Gravity.TOP;
        String edge=getIntent().getStringExtra("sidebar_edge");
        if("top".equals(edge)||"bottom".equals(edge))gravity=Gravity.CENTER_HORIZONTAL|("bottom".equals(edge)?Gravity.BOTTOM:Gravity.TOP);
        int width=backdrop!=null&&backdrop.getWidth()>0?backdrop.getWidth():getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(Math.max(1,Math.min(dp(720),width-dp(24))),-1,gravity);
        box.setMargins(dp(12),dp(16),dp(12),dp(displayId==0?16:76));return box;
    }
    private void capturePullDisplay(){
        Display display=getDisplay();if(display==null)return;
        android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
        pullDisplayWidth=size.x;pullDisplayHeight=size.y;pullDisplayRotation=display.getRotation();
    }
    private float pullSize(){return sidebarDrag.axis.vertical?panel.getHeight():panel.getWidth();}
    private float pullRange(){
        int[] location=new int[2];panel.getLocationOnScreen(location);
        boolean vertical=sidebarDrag.axis.vertical;
        float restingStart=location[vertical?1:0]-(vertical?panel.getTranslationY():panel.getTranslationX());
        Float origin=sidebarDrag.origin;
        if(origin==null)origin=sidebarDrag.axis.reverse?(float)(vertical?pullDisplayHeight:pullDisplayWidth):0f;
        return sidebarDrag.axis.range(restingStart,pullSize(),dp(vertical?16:12),origin);
    }
    private void applyPull(float progress){
        pullProgress=progress;float range=pullRange();
        float translation=sidebarDrag.axis.translation(range,progress);
        panel.setTranslationX(sidebarDrag.axis.vertical?0:translation);panel.setTranslationY(sidebarDrag.axis.vertical?translation:0);
        backdrop.setBackgroundColor((Math.round(34*progress)<<24)|0x101725);
        int[] location=new int[2];panel.getLocationOnScreen(location);
        sidebarDrag.leading.accept(sidebarDrag.axis.leading(location[sidebarDrag.axis.vertical?1:0],pullSize()));
        ShellPanels.bounds(displayId,this,new android.graphics.Rect(location[0],location[1],location[0]+panel.getWidth(),location[1]+panel.getHeight()));
    }
    private void renderPull(){
        if(sidebarDrag==null||sidebarDrag.finished||pullSize()==0||isFinishing()||pullAnimation!=null)return;
        applyPull(sidebarDrag.axis.progress(sidebarDrag.distance,pullRange()));
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
    private void abandonPull(){
        if(pullAnimation!=null){pullAnimation.cancel();pullAnimation=null;}
        if(sidebarDrag!=null&&!sidebarDrag.finished){sidebarDrag.ended=true;sidebarDrag.commit=false;sidebarDrag.complete();}
    }
    @Override public void finish(){abandonPull();super.finish();if(getIntent()!=null&&getIntent().hasExtra("sidebar_pull"))overridePendingTransition(0,0);}
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        long token=intent.getLongExtra("sidebar_pull",-1);if(token==-1)return;
        if(pendingDrag==null||pendingDrag.finished||pendingDrag.token!=token)return;
        SidebarDrag replacement=pendingDrag;if(sidebarDrag==replacement)return;
        abandonPull();setIntent(intent);sidebarDrag=replacement;capturePullDisplay();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);overridePendingTransition(0,0);
        panel.animate().cancel();panel.setTranslationX(0);panel.setTranslationY(0);panel.setLayoutParams(panelBox());
        repositionControls();
        showTab(hubState.notifications());
        panel.post(this::renderPull);
    }

    private void repositionControls(){
        if(controlRail==null)return;
        String edge=getIntent().getStringExtra("sidebar_edge");
        boolean right="right".equals(edge)||(!"left".equals(edge)&&getIntent().getBooleanExtra("from_right",true));
        controlRail.fromRight(right);
        insetNotifications(right);
        widgets.hubSide(right);
    }
    private void insetNotifications(boolean fromRight){
        // Scrolling cards need a straight fixed-width gutter; the widget canvas does not.
        int space=HubControlRail.controlsWidth(this)+dp(8);
        notificationPage.setPadding(fromRight?0:space,0,fromRight?space:0,0);
    }

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
        menu.getMenu().add(R.string.widget_pages).setOnMenuItemClickListener(item->{pageNavigation.choose();return true;});
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
    private void selectTab(boolean value){hubState.select(value);showTab(value);}
    private void showTab(boolean value){if(value&&widgets!=null)widgets.finishEditing();notifications=value;syncWidgets();updateEdit();widgetPage.setVisibility(value?View.GONE:View.VISIBLE);notificationPage.setVisibility(value?View.VISIBLE:View.GONE);navigation.select(value);if(controlRail!=null)controlRail.widgetPage(!value);else if(pageNavigation!=null)pageNavigation.setVisibility(value?View.GONE:View.VISIBLE);renderNotifications();}
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
        java.util.List<StatusBarNotification> snapshot=locked||!available||!notifications?java.util.Collections.emptyList():ShellNotifications.current(this);
        // Recheck after snapshot acquisition so a newly lost lock/access guard also clears old cards.
        locked=locked||ShellNotifications.locked(this);available=available&&ShellNotifications.ready(this);
        navigation.setNotificationPresence(locked,available,notifications?!snapshot.isEmpty():ShellNotifications.hasNotifications(this));
        int state=locked?NOTIFICATIONS_LOCKED:available?NOTIFICATIONS_LIVE:NOTIFICATIONS_UNAVAILABLE;
        if(state!=notificationState){
            // Only privacy/notice transitions discard rows; live updates keep the renderer and its anchors.
            notificationList.clear();notificationState=state;
            if(state!=NOTIFICATIONS_LIVE)Ui.note(notificationRows,getString(state==NOTIFICATIONS_LOCKED?R.string.hub_locked:R.string.hub_access_note));
        }
        // Hidden tabs keep expansion identifiers, not old notification payloads or app resources.
        if(!notifications&&state==NOTIFICATIONS_LIVE)notificationList.hide();
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
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("notifications",notifications);state.putBoolean("sidebar_pull_finished",sidebarDrag!=null&&sidebarDrag.finished&&sidebarDrag.commit||!getIntent().hasExtra("sidebar_pull")&&getIntent().hasExtra("sidebar_edge"));if(notificationList!=null)state.putStringArrayList("expanded_notification_groups",notificationList.expandedGroups());super.onSaveInstanceState(state);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(widgets!=null){widgets.result(request,result,data);updateEdit();}}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getKeyCode()==KeyEvent.KEYCODE_ESCAPE&&event.getAction()==KeyEvent.ACTION_UP){if(widgets==null||!widgets.finishEditing())finish();return true;}return super.dispatchKeyEvent(event);}
    @Override public void onBackPressed(){if(widgets==null||!widgets.finishEditing())super.onBackPressed();}
    @Override public void onDisplayRemoved(int id){if(id==displayId)finish();}
    @Override public void onDisplayAdded(int id){}
    @Override public void onDisplayChanged(int id){
        if(id!=displayId||sidebarDrag==null||sidebarDrag.finished)return;
        Display display=getDisplay();if(display==null){finish();return;}
        android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
        if(size.x!=pullDisplayWidth||size.y!=pullDisplayHeight||display.getRotation()!=pullDisplayRotation)finish();
    }
    @Override public void onDestroy(){
        abandonPull();
        ShellPanels.release(displayId,this);
        if(observingNavigation){ShellRuntime.unobserveNavigation(navigationChanged);observingNavigation=false;}
        if(visible.get()==this)visible.clear();ShellNotifications.unobserve(refresh);
        if(notificationList!=null)notificationList.clear();
        if(widgets!=null)widgets.destroy();if(displays!=null)displays.unregisterDisplayListener(this);
        try{unregisterReceiver(lockChanges);}catch(IllegalArgumentException ignored){}super.onDestroy();
    }
}
