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
import java.util.List;

/** Clock popover. Widgets have a distinct host/storage from the desktop surface. */
public final class HubActivity extends Activity implements DisplayManager.DisplayListener,SharedPreferences.OnSharedPreferenceChangeListener {
    private static WeakReference<HubActivity> visible=new WeakReference<>(null);
    private int displayId;
    private DesktopWidgets widgets;
    private FrameLayout widgetCanvas;
    private LinearLayout widgetPage,notificationPage,notificationRows;
    private Button widgetsTab,notificationsTab,edit;
    private boolean notifications;
    private DisplayManager displays;
    private final Runnable refresh=()->{if(notificationRows!=null)renderNotifications();};
    private final BroadcastReceiver lockChanges=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refresh.run();}};
    static void open(Context context,int displayId){
        try{
            Displays.require(context,displayId);
            HubActivity current=visible.get();
            if(current!=null&&!current.isFinishing()&&current.displayId==displayId&&current.hasWindowFocus()){current.finish();return;}
            context.startActivity(new Intent(context,HubActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        }catch(RuntimeException e){Launches.problem(context,e.getMessage());}
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);displayId=getDisplay()==null?-1:getDisplay().getDisplayId();
        try{Displays.require(this,displayId);if(!Launches.prefs(this).getBoolean("enabled",false)){finish();return;}}
        catch(RuntimeException e){finish();return;}
        visible=new WeakReference<>(this);
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,new Handler(Looper.getMainLooper()));
        Launches.prefs(this).registerOnSharedPreferenceChangeListener(this);
        FrameLayout backdrop=new FrameLayout(this);backdrop.setBackgroundColor(0x22101725);backdrop.setOnClickListener(v->finish());
        LinearLayout panel=Ui.column(this);panel.setPadding(dp(16),dp(16),dp(16),dp(16));panel.setBackground(Ui.rounded(this,Ui.BG,22));panel.setElevation(dp(18));panel.setOnClickListener(v->{});
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(Math.min(dp(720),getResources().getDisplayMetrics().widthPixels-dp(24)),-1,Gravity.RIGHT|Gravity.TOP);
        box.setMargins(dp(12),dp(16),dp(12),dp(76));backdrop.addView(panel,box);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Ui.text(this,getString(R.string.hub_title),22,Ui.TEXT);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=Ui.button(this,"×",this::finish);close.setContentDescription(getString(R.string.ui_close));heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(44)));panel.addView(heading);
        LinearLayout tabs=new LinearLayout(this);
        widgetsTab=Ui.button(this,getString(R.string.hub_widgets),()->showTab(false));notificationsTab=Ui.button(this,getString(R.string.hub_notifications),()->showTab(true));
        tabs.addView(widgetsTab,new LinearLayout.LayoutParams(0,dp(48),1));tabs.addView(notificationsTab,new LinearLayout.LayoutParams(0,dp(48),1));panel.addView(tabs);
        FrameLayout pages=new FrameLayout(this);panel.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        widgetPage=Ui.column(this);pages.addView(widgetPage,new FrameLayout.LayoutParams(-1,-1));
        TextView note=Ui.text(this,getString(R.string.hub_widgets_note),13,Ui.MUTED);note.setPadding(0,dp(12),0,dp(8));widgetPage.addView(note);
        LinearLayout actions=new LinearLayout(this);
        actions.addView(Ui.button(this,getString(R.string.ui_add_widget),()->widgets.choose()),new LinearLayout.LayoutParams(0,dp(48),1));
        edit=Ui.button(this,getString(R.string.ui_edit_widgets),()->{widgets.setEditing(!widgets.isEditing());updateEdit();});actions.addView(edit,new LinearLayout.LayoutParams(0,dp(48),1));widgetPage.addView(actions);
        widgetCanvas=new FrameLayout(this);widgetCanvas.setClipChildren(true);widgetPage.addView(widgetCanvas,new LinearLayout.LayoutParams(-1,0,1));widgets=new DesktopWidgets(this,widgetCanvas,true);
        notificationPage=Ui.column(this);pages.addView(notificationPage,new FrameLayout.LayoutParams(-1,-1));
        Button access=Ui.button(this,getString(R.string.hub_access),()->{
            try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());}
            catch(RuntimeException e){Launches.problem(this,e.getMessage());}
        });notificationPage.addView(access);
        ScrollView scroll=new ScrollView(this);notificationRows=Ui.column(this);scroll.addView(notificationRows);notificationPage.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(backdrop);getWindow().setLayout(-1,-1);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);filter.addAction(Intent.ACTION_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(lockChanges,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(lockChanges,filter);
        showTab(state!=null&&state.getBoolean("notifications"));
    }
    private int dp(int value){return Ui.dp(this,value);}
    private void updateEdit(){if(edit!=null&&widgets!=null)edit.setText(widgets.isEditing()?R.string.ui_finish_editing_widgets:R.string.ui_edit_widgets);}
    private void showTab(boolean value){notifications=value;widgetPage.setVisibility(value?View.GONE:View.VISIBLE);notificationPage.setVisibility(value?View.VISIBLE:View.GONE);widgetsTab.setTextColor(value?Ui.MUTED:Ui.ACCENT);notificationsTab.setTextColor(value?Ui.ACCENT:Ui.MUTED);if(value)renderNotifications();}
    private void renderNotifications(){
        notificationRows.removeAllViews();
        if(ShellNotifications.locked(this)){Ui.note(notificationRows,getString(R.string.hub_locked));return;}
        if(!ShellNotifications.ready()){Ui.note(notificationRows,getString(R.string.hub_access_note));return;}
        List<StatusBarNotification> items=ShellNotifications.current(this);
        if(items.isEmpty()){Ui.note(notificationRows,getString(R.string.hub_no_notifications));return;}
        for(StatusBarNotification item:items){
            Notification n=item.getNotification();LinearLayout card=Ui.column(this);card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setBackground(Ui.rounded(this,Ui.PANEL,14));
            String app=item.getPackageName();try{app=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(app,0)).toString();}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
            LinearLayout top=new LinearLayout(this);TextView source=Ui.text(this,app,12,Ui.ACCENT);top.addView(source,new LinearLayout.LayoutParams(0,-2,1));
            if(item.isClearable()){Button dismiss=Ui.button(this,"×",()->{if(!ShellNotifications.dismiss(this,item.getKey()))Ui.message(this,getString(R.string.hub_notification_gone));});dismiss.setContentDescription(getString(R.string.hub_dismiss));top.addView(dismiss,new LinearLayout.LayoutParams(dp(48),dp(40)));}card.addView(top);
            CharSequence title=n.extras.getCharSequence(Notification.EXTRA_TITLE),body=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if(body==null)body=n.extras.getCharSequence(Notification.EXTRA_TEXT);
            TextView heading=Ui.text(this,title==null?app:title.toString(),16,Ui.TEXT);heading.setMaxLines(2);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(heading);
            if(body!=null){TextView text=Ui.text(this,body.toString(),14,Ui.MUTED);text.setMaxLines(5);text.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(text);}
            if(n.contentIntent!=null){card.setOnClickListener(v->openNotification(item));card.setFocusable(true);}
            LinearLayout.LayoutParams margins=new LinearLayout.LayoutParams(-1,-2);margins.topMargin=dp(10);notificationRows.addView(card,margins);
        }
    }
    private void openNotification(StatusBarNotification item){
        if(ShellNotifications.locked(this)||!ShellNotifications.ready())return;
        // Re-read so removed or updated PendingIntents are not executed from stale cards.
        for(StatusBarNotification current:ShellNotifications.current(this))if(current.getKey().equals(item.getKey())){
            PendingIntent intent=current.getNotification().contentIntent;if(intent==null)return;
            try{
                ActivityOptions options=ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
                if(Build.VERSION.SDK_INT>=34)options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                intent.send(this,0,null,null,null,null,options.toBundle());finish();
            }catch(PendingIntent.CanceledException|RuntimeException e){Ui.message(this,getString(R.string.hub_notification_gone));renderNotifications();}return;
        }
        renderNotifications();
    }
    @Override protected void onStart(){super.onStart();if(widgets!=null)widgets.start();ShellNotifications.observe(refresh);}
    @Override protected void onResume(){super.onResume();if(notificationRows!=null)renderNotifications();updateEdit();}
    @Override protected void onStop(){ShellNotifications.unobserve(refresh);if(widgets!=null)widgets.stop();super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("notifications",notifications);super.onSaveInstanceState(state);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(widgets!=null){widgets.result(request,result);updateEdit();}}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getKeyCode()==KeyEvent.KEYCODE_ESCAPE&&event.getAction()==KeyEvent.ACTION_UP){finish();return true;}return super.dispatchKeyEvent(event);}
    @Override public void onDisplayRemoved(int id){if(id==displayId)finish();}
    @Override public void onDisplayAdded(int id){}
    @Override public void onDisplayChanged(int id){}
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){if("enabled".equals(key)&&!prefs.getBoolean("enabled",false))finish();}
    @Override public void onDestroy(){
        if(visible.get()==this)visible.clear();ShellNotifications.unobserve(refresh);
        if(widgets!=null)widgets.destroy();if(displays!=null)displays.unregisterDisplayListener(this);
        Launches.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        try{unregisterReceiver(lockChanges);}catch(IllegalArgumentException ignored){}super.onDestroy();
    }
}
