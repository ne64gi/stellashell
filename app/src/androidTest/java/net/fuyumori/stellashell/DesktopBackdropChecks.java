package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.*;
import net.fuyumori.stellashell.feature.launch.LaunchItems;

/** Actual normal DesktopActivity task focus, composition and scoped input on a disposable display. */
final class DesktopBackdropChecks {
    private static final int WIDTH=1400,HEIGHT=900,TRUSTED=1024;
    private static final int[] COLORS={0x147c62,0x98442d};
    private final Instrumentation test;private final Context actual,receiverContext;
    private final boolean requireCompact;private boolean compactPresentation;
    private final String nonce=UUID.randomUUID().toString();
    private final String[] tokens={nonce+"-a",nonce+"-b",nonce+"-sentinel",nonce+"-result"};
    private final ComponentName probe;
    private final Map<String,Intent> states=new ConcurrentHashMap<>();
    private final Map<String,Intent> queries=new ConcurrentHashMap<>();
    private final AtomicInteger backgroundClicks=new AtomicInteger();
    private VirtualDisplay display;private ImageReader reader;private HandlerThread frames;
    private Display fixtureDisplay;private DesktopActivity home;
    private MonitoredPopup popup;private View popupAnchor;private IBinder desktopWindowToken;private Dialog dialog;private android.widget.EditText input;
    private final int[] taskIds={-1,-1};private int sentinelTaskId=-1,resultTaskId=-1;
    private final int resultRequestCode=0x6a27;
    private final Map<String,Map<String,Object>> preferencesBefore=new HashMap<>();
    private ShellRuntime.Snapshot runtimeBefore;private String globalBefore;
    private int displayId=-1;private boolean registered;private long request;
    private volatile Frame latestFrame;
    private final BroadcastReceiver replies=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        String token=intent.getStringExtra(HomeWindowFixtureActivity.TOKEN);
        if(Arrays.asList(tokens).contains(token)&&nonce.equals(intent.getStringExtra(HomeWindowFixtureActivity.OWNER_NONCE))){
            states.put(token,new Intent(intent));
            if(intent.getLongExtra(HomeWindowFixtureActivity.REQUEST,-1)>=0)queries.put(token,new Intent(intent));
        }
    }};
    private static final class Frame {
        final long time;final int first,second;
        Frame(long time,int first,int second){this.time=time;this.first=first;this.second=second;}
    }
    private static final class MonitoredPopup extends android.widget.PopupMenu {
        volatile boolean shown;
        MonitoredPopup(Context context,View anchor){super(context,anchor);}
        @Override public void show(){super.show();shown=true;}
    }
    DesktopBackdropChecks(Instrumentation test,boolean requireCompact){
        this.test=test;actual=test.getTargetContext();receiverContext=actual.getApplicationContext();
        this.requireCompact=requireCompact;
        probe=new ComponentName(test.getContext().getPackageName(),HomeWindowFixtureActivity.class.getName());
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void main(Runnable action){
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private String call(Bridge.Work work)throws Exception{
        CountDownLatch done=new CountDownLatch(1);String[] answer=new String[2];
        main(()->Bridge.get(actual).read(work,(value,error)->{answer[0]=value;answer[1]=error;done.countDown();}));
        check(done.await(15,TimeUnit.SECONDS),"Backdrop fixture Bridge timeout");check(answer[1]==null,"Backdrop fixture Bridge failed: "+answer[1]);
        check(answer[0]!=null&&!answer[0].startsWith("ERROR:"),"Backdrop fixture operation rejected");return answer[0];
    }
    private void await(java.util.function.BooleanSupplier condition,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<until){if(condition.getAsBoolean())return;Thread.sleep(50);}
        throw new AssertionError(message);
    }
    private void requireOwnedDisplay(){
        Display current=actual.getSystemService(DisplayManager.class).getDisplay(displayId);
        check(displayId>0&&display!=null&&fixtureDisplay!=null&&current!=null&&current.isValid()
            &&current.getName().equals("StellaShell backdrop fixture "+nonce),"Owned backdrop display vanished; no main-display fallback");
    }
    /** Output stays in memory; only fixed owned-display launch/input commands are used. */
    private String shell(String command)throws Exception{
        try(ParcelFileDescriptor pipe=test.getUiAutomation().executeShellCommand(command);
            InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(pipe);
            ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1){check(output.size()+count<=8*1024*1024,"Unexpectedly large owned command output");output.write(buffer,0,count);}
            return output.toString("UTF-8");
        }
    }
    private JSONArray rows()throws Exception{
        requireOwnedDisplay();return new JSONObject(call(s->s.taskSnapshot(displayId))).getJSONArray("tasks");
    }
    private JSONObject task(int id)throws Exception{
        JSONArray rows=rows();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);
            if(row.getInt("id")==id){check(probe.flattenToString().equals(row.getString("component")),"Owned task identity changed");return row;}}
        throw new AssertionError("Owned freeform task disappeared");
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReplies(){
        if(Build.VERSION.SDK_INT>=33)receiverContext.registerReceiver(replies,new IntentFilter(HomeWindowFixtureActivity.STATE),Context.RECEIVER_EXPORTED);
        else receiverContext.registerReceiver(replies,new IntentFilter(HomeWindowFixtureActivity.STATE));
        registered=true;
    }
    private void command(String token,String action,long sequence){
        receiverContext.sendBroadcast(new Intent(HomeWindowFixtureActivity.CONTROL).setPackage(probe.getPackageName())
            .putExtra(HomeWindowFixtureActivity.TOKEN,token).putExtra(HomeWindowFixtureActivity.OWNER_NONCE,nonce).putExtra(HomeWindowFixtureActivity.COMMAND,action)
            .putExtra(HomeWindowFixtureActivity.REQUEST,sequence));
    }
    private Intent query(String token)throws Exception{
        long current=++request;main(()->command(token,"query",current));
        await(()->{Intent state=queries.get(token);return state!=null&&state.getLongExtra(HomeWindowFixtureActivity.REQUEST,-1)==current;},"Owned nonce Activity did not reply");
        Intent state=queries.get(token);
        check(nonce.equals(state.getStringExtra(HomeWindowFixtureActivity.OWNER_NONCE)),"Owned Activity reply was not bound to this fixture nonce");
        check(state.getIntExtra("display_id",-1)==displayId,"Owned Activity left the fixture display; no privileged fallback");return state;
    }
    private void launchFixture(String token,int color,int mode,String roleFlag)throws Exception{
        String role=roleFlag==null?"":" "+roleFlag;
        String output=shell("am start --user current --display "+displayId+" --windowingMode "+mode
                +" -a android.intent.action.MAIN -n "+probe.flattenToString()
                +" --es "+HomeWindowFixtureActivity.TOKEN+" "+token
                +" --es "+HomeWindowFixtureActivity.OWNER_NONCE+" "+nonce
                +" --es "+HomeWindowFixtureActivity.REPLY+" "+actual.getPackageName()
                +" --ei fixture_color "+color+role+" -f 0x18000000");
        check(!output.contains("Error")&&!output.contains("Exception"),"Nonce fixture launch rejected");
    }
    private void tap(int x,int y)throws Exception{
        requireAwake();requireOwnedDisplay();check(x>=0&&x<WIDTH&&y>=0&&y<HEIGHT,"Fixture tap is outside its display");
        String output=shell("input -d "+displayId+" tap "+x+" "+y);
        check(!output.contains("Error")&&!output.contains("Exception"),"Target-scoped fixture input rejected");
    }
    private void collectFrame(ImageReader images){
        try(Image image=images.acquireLatestImage()){
            if(image==null)return;Image.Plane plane=image.getPlanes()[0];ByteBuffer pixels=plane.getBuffer();
            int stride=plane.getPixelStride(),rowStride=plane.getRowStride(),first=0,second=0;
            for(int y=0;y<image.getHeight();y+=3)for(int x=0;x<image.getWidth();x+=3){
                int offset=y*rowStride+x*stride;if(offset+2>=pixels.limit())continue;
                int r=pixels.get(offset)&255,g=pixels.get(offset+1)&255,b=pixels.get(offset+2)&255;
                if(matches(COLORS[0],r,g,b))first++;if(matches(COLORS[1],r,g,b))second++;
            }
            latestFrame=new Frame(SystemClock.uptimeMillis(),first,second);
        }catch(IllegalStateException ignored){/* Reader may close while its last callback drains. */}
    }
    private static boolean matches(int color,int r,int g,int b){return Math.abs((color>>16&255)-r)<=3&&Math.abs((color>>8&255)-g)<=3&&Math.abs((color&255)-b)<=3;}
    private void awaitComposition(long after,String operation)throws Exception{
        await(()->{Frame frame=latestFrame;return frame!=null&&frame.time>=after&&frame.first>500&&frame.second>500;},
            operation+": both owned freeforms must actually be composited, not just reported visible");
    }
    private void assertUnchanged(JSONObject before,JSONObject after,String operation)throws Exception{
        for(String key:new String[]{"id","component","mode","left","top","right","bottom","alwaysOnTop","pinActive"})
            check(Objects.equals(before.opt(key),after.opt(key)),operation+": changed owned freeform "+key);
        check(after.getBoolean("visible")&&after.getInt("mode")==5,operation+": owned freeform was hidden or changed mode");
    }
    private void assertSentinel(JSONObject before,String operation)throws Exception{
        check(before!=null&&sentinelTaskId>=0,operation+": owned fullscreen sentinel was not captured");
        JSONObject after=task(sentinelTaskId);
        for(String key:new String[]{"id","component","mode","left","top","right","bottom","alwaysOnTop","pinActive"})
            check(Objects.equals(before.opt(key),after.opt(key)),operation+": changed owned fullscreen sentinel "+key);
        check(!after.getBoolean("visible"),operation+": opaque fullscreen sentinel became visible; Desktop may have moved below it");
    }
    private ActivityManager.RecentTaskInfo ownAppTask(int id){
        for(ActivityManager.AppTask task:actual.getSystemService(ActivityManager.class).getAppTasks()){
            ActivityManager.RecentTaskInfo info=task.getTaskInfo();if(info!=null&&info.taskId==id)return info;
        }
        return null;
    }
    private void requireAwake(){
        check(actual.getSystemService(PowerManager.class).isInteractive(),"Wake normally before backdrop/input fixture");
        KeyguardManager guard=actual.getSystemService(KeyguardManager.class);
        check(!guard.isDeviceLocked()&&!guard.isKeyguardLocked(),"Unlock normally before backdrop/input fixture");
    }
    private static Map<String,Object> persistentPreferences(Map<String,?> source){
        Map<String,Object> result=new HashMap<>();result.putAll(source);
        // Volatile diagnostics and icon-cache revision are not saved user settings.
        for(String key:new String[]{"mouse_diagnostics","ime_diagnostics","task_diagnostics","work_area_diagnostics","last_error","icons_revision"})result.remove(key);
        return result;
    }
    private void settleUi()throws Exception{
        CountDownLatch done=new CountDownLatch(1);
        main(()->Choreographer.getInstance().postFrameCallback(first->Choreographer.getInstance().postFrameCallback(second->done.countDown())));
        check(done.await(8,TimeUnit.SECONDS),"Owned UI frame barrier timed out");
    }
    private void bridgeBarrier()throws Exception{call(s->"OK");settleUi();}
    private void resumeExistingSession()throws Exception{
        CountDownLatch ready=new CountDownLatch(1);
        Runnable changed=()->{if(ShellRuntime.running()&&ShellRuntime.selectedDisplay()>=0)ready.countDown();};
        main(()->{
            ShellRuntime.observeNavigation(changed);
            if(!ShellRuntime.running())actual.startForegroundService(new Intent(actual,DockService.class).putExtra("show_home",false));
            changed.run();
        });
        try{check(ready.await(15,TimeUnit.SECONDS),"Existing enabled shell did not resume");bridgeBarrier();}
        finally{main(()->ShellRuntime.unobserveNavigation(changed));}
    }
    private void savePreferences(){
        for(String name:new String[]{"desktop","app_organization","desktop_widgets","shortcut_positions","phone_shortcut_positions","web_search","launch_profiles"})
            preferencesBefore.put(name,persistentPreferences(actual.getSharedPreferences(name,0).getAll()));
        runtimeBefore=ShellFixtureAccess.runtimeSnapshot();
    }
    private void assertPreserved()throws Exception{
        for(Map.Entry<String,Map<String,Object>> entry:preferencesBefore.entrySet())
            check(entry.getValue().equals(persistentPreferences(actual.getSharedPreferences(entry.getKey(),0).getAll())),"Backdrop fixture changed saved preferences: "+entry.getKey());
        ShellRuntime.Snapshot after=ShellFixtureAccess.runtimeSnapshot();
        check(runtimeBefore.running==after.running&&runtimeBefore.selectedDisplay==after.selectedDisplay
            &&runtimeBefore.phoneNavigationReady==after.phoneNavigationReady&&runtimeBefore.generation==after.generation,"Backdrop fixture changed production runtime owner/selection");
        check(globalBefore.equals(call(s->s.settingsSnapshot())),"Backdrop fixture changed global desktop/freeform settings");
    }
    private void requireSafeDesktopStorage()throws Exception{
        SharedPreferences widgets=actual.getSharedPreferences("desktop_widgets",0);
        check(widgets.getInt("pending",-1)<0,"Finish or cancel the existing pending widget normally; fixture never cancels it");
        JSONArray items=new JSONArray(widgets.getString("items","[]"));Set<Integer> retained=new HashSet<>();
        for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if("widget".equals(item.optString("kind","widget")))retained.add(item.getInt("id"));}
        android.appwidget.AppWidgetHost host=new android.appwidget.AppWidgetHost(actual,0x534f47);
        for(int id:host.getAppWidgetIds())check(retained.contains(id),"Production widget reconciliation would remove an allocation; fixture refuses to launch");
        // TargetContext can be display 0. Validate the external placement owner,
        // not Phone shortcuts, against the external coordinates checked below.
        List<String> desktopItems=new LaunchItems(Launches.prefs(actual)).desktop(LaunchItems.Profile.DESKTOP);
        for(String key:actual.getSharedPreferences("shortcut_positions",0).getAll().keySet())
            check(desktopItems.contains(key),"Production shortcut reconciliation would delete a saved position; fixture refuses to launch");
    }
    private void exerciseWindows(JSONObject[] before,JSONObject sentinelBefore,String operation)throws Exception{
        for(int i=0;i<2;i++)assertUnchanged(before[i],task(taskIds[i]),operation);
        assertSentinel(sentinelBefore,operation);
        long after=SystemClock.uptimeMillis();
        for(int i=0;i<2;i++){
            final int index=i;int clicks=query(tokens[i]).getIntExtra("clicks",0);tap(i==0?360:900,i==0?320:360);
            await(()->states.get(tokens[index]).getIntExtra("clicks",0)>clicks,operation+": owned freeform lost input");
            Intent state=query(tokens[i]);check(state.getBooleanExtra("shown",false)&&state.getBooleanExtra("attached",false)&&state.getBooleanExtra("multi_window",false),operation+": owned window was detached or hidden");
        }
        awaitComposition(after,operation);
        for(int i=0;i<2;i++)assertUnchanged(before[i],task(taskIds[i]),operation+" after input");
        assertSentinel(sentinelBefore,operation+" after input");
    }
    private void blankTap(JSONObject[] before,JSONObject sentinelBefore,String operation)throws Exception{
        requireAwake();int clicks=backgroundClicks.get();long after=SystemClock.uptimeMillis();tap(650,700);
        await(()->backgroundClicks.get()>clicks,operation+": actual desktop did not receive owned-display blank touch");
        bridgeBarrier();
        for(int i=0;i<2;i++)assertUnchanged(before[i],task(taskIds[i]),operation);
        assertSentinel(sentinelBefore,operation);awaitComposition(after,operation);exerciseWindows(before,sentinelBefore,operation);
    }
    private void popupDialogTransition(JSONObject sentinelBefore)throws Exception{
        requireOwnedDisplay();requireAwake();
        main(()->{
            check(home.backdrop!=null,"Actual DesktopActivity did not attach backdrop owner");
            popupAnchor=new View(home);android.widget.FrameLayout.LayoutParams bounds=new android.widget.FrameLayout.LayoutParams(40,40);
            bounds.leftMargin=620;bounds.topMargin=580;home.root.addView(popupAnchor,bounds);
            desktopWindowToken=popupAnchor.getWindowToken();popup=new MonitoredPopup(home,popupAnchor);
            popup.getMenu().add(0,1,0,"Owned backdrop input").setOnMenuItemClickListener(item->{
                input=new android.widget.EditText(home);input.setSingleLine(true);input.setShowSoftInputOnFocus(false);
                dialog=new AlertDialog.Builder(home).setTitle("Owned backdrop input").setView(input).setNegativeButton("Close",null).create();
                dialog.setOnShowListener(ignored->input.requestFocus());
                DesktopBackdrop.showDialog(home,dialog);return true;
            });
            home.backdrop.showPopup(popup);
        });
        await(()->{boolean[] hosted={false};main(()->hosted[0]=popupAnchor!=null&&popupAnchor.isAttachedToWindow()
                    &&popupAnchor.getWindowToken()!=null&&popupAnchor.getWindowToken()!=desktopWindowToken
                    &&popupAnchor.getWindowToken()==home.root.getWindowToken()&&popup!=null&&popup.shown);
            return hosted[0];},"PopupMenu did not show in its hosted interaction window");
        main(()->{check(popup.getMenu().performIdentifierAction(1,0),"Owned popup action was rejected");popup.dismiss();});
        bridgeBarrier();
        await(()->{boolean[] ready={false};main(()->ready[0]=dialog!=null&&dialog.isShowing()&&dialog.getWindow().getDecorView().hasWindowFocus()&&input.hasFocus());return ready[0];},"Popup dismissal dropped nested dialog input focus");
        main(()->check(dialog.getWindow().getDecorView().getDisplay().getDisplayId()==displayId,"Owned dialog left its display"));
        assertSentinel(sentinelBefore,"Popup/dialog visible");
        requireAwake();requireOwnedDisplay();
        String output=shell("input -d "+displayId+" text backdrop_owned_input");
        check(!output.contains("Error")&&!output.contains("Exception"),"Owned-display text input rejected");
        await(()->{boolean[] typed={false};main(()->typed[0]="backdrop_owned_input".contentEquals(input.getText()));return typed[0];},"Nested dialog did not receive actual owned-display EditText input");
        main(()->dialog.dismiss());bridgeBarrier();
        await(()->{boolean[] returned={false};main(()->returned[0]=popupAnchor!=null&&popupAnchor.isAttachedToWindow()
                    &&popupAnchor.getWindowToken()==desktopWindowToken&&home.root.getWindowToken()==desktopWindowToken);return returned[0];},
            "Popup/dialog interaction host did not restore the original Desktop window");
        assertSentinel(sentinelBefore,"Popup/dialog dismissed");
    }
    private void activityForResultTransition(JSONObject[] before,JSONObject sentinelBefore)throws Exception{
        requireOwnedDisplay();requireAwake();String token=tokens[3];
        Intent intent=new Intent().setComponent(probe)
                .putExtra(HomeWindowFixtureActivity.TOKEN,token)
                .putExtra(HomeWindowFixtureActivity.OWNER_NONCE,nonce)
                .putExtra(HomeWindowFixtureActivity.REPLY,actual.getPackageName())
                .putExtra("fixture_result",true).putExtra("fixture_color",0x374c68);
        main(()->DesktopBackdrop.startActivityForResult(home,intent,resultRequestCode));
        await(()->states.containsKey(token),"Owned activity-for-result fixture did not start");
        Intent opened=query(token);resultTaskId=opened.getIntExtra("task_id",-1);
        check(opened.getBooleanExtra("result_fixture",false)&&opened.getBooleanExtra("shown",false)
                &&opened.getBooleanExtra("attached",false)&&opened.getBooleanExtra("focus",false)
                &&!opened.getBooleanExtra("multi_window",true)&&opened.getIntExtra("window_width",0)==WIDTH
                &&opened.getIntExtra("window_height",0)==HEIGHT,"Result fixture did not own a focused fullscreen window on the fixture display");
        check(resultTaskId>=0,"Result fixture omitted task identity");
        // The nonce Activity reply above proves its display; RecentTaskInfo's displayId
        // is hidden from the public SDK. Cross-check its own root/base/top identity here.
        await(()->{ActivityManager.RecentTaskInfo info=ownAppTask(resultTaskId);return info!=null
                    &&new ComponentName(actual,DesktopInteractionActivity.class).equals(info.baseActivity)
                    &&probe.equals(info.topActivity);},"Activity-for-result was not hosted in the owned DesktopInteractionActivity task/display");
        assertSentinel(sentinelBefore,"Activity-for-result visible");
        int clicks=opened.getIntExtra("clicks",0);tap(560,420);
        await(()->{Intent state=states.get(token);return state!=null&&state.getIntExtra("clicks",0)>clicks;},
                "Owned activity-for-result fixture did not receive the scoped display tap");
        Intent tapped=states.get(token);
        check(tapped.getBooleanExtra("tap_focus",false)&&tapped.getIntExtra("tap_display_id",-1)==displayId
                &&tapped.getIntExtra("tap_task_id",-1)==resultTaskId,"Result fixture tap did not arrive with focus on its owned display/task");
        await(()->ownAppTask(resultTaskId)==null,
                "Activity-for-result result did not close its temporary host task");
        await(()->{boolean[] returned={false};main(()->returned[0]=home!=null&&!home.isFinishing()&&!home.isDestroyed()
                    &&popupAnchor!=null&&popupAnchor.getWindowToken()==desktopWindowToken&&home.root.getWindowToken()==desktopWindowToken);return returned[0];},
                "Activity-for-result flow did not restore the original Desktop window");
        assertSentinel(sentinelBefore,"Activity-for-result returned");
        for(int i=0;i<2;i++)assertUnchanged(before[i],task(taskIds[i]),"Activity-for-result returned");
        exerciseWindows(before,sentinelBefore,"Activity-for-result returned");
    }
    void run()throws Exception{
        requireAwake();
        SharedPreferences production=Launches.prefs(actual);
        check(production.getBoolean("enabled",false),"Existing enabled Shell session is required; fixture never enables it");
        check(production.getBoolean("phone_profile_initialized",false),"Initialize the existing workspace normally before this fixture");
        ComponentName desktop=new ComponentName(actual,DesktopActivity.class);
        check(actual.getPackageManager().getComponentEnabledSetting(desktop)==android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,"Existing DesktopActivity component must already be enabled");
        requireSafeDesktopStorage();
        main(()->{check(Bridge.get(actual).authorized(),"Existing authorized Bridge is required");Bridge.get(actual).connect();});
        await(()->Bridge.get(actual).ready(),"Existing authorized Bridge is unavailable");
        // Instrumentation restarts the process. Resume the user's enabled output
        // without ordinary HOME navigation or changing its selected display.
        resumeExistingSession();
        savePreferences();globalBefore=call(s->s.settingsSnapshot());
        boolean autoPresent=production.contains("workspace_auto"),auto=production.getBoolean("workspace_auto",false);
        Instrumentation.ActivityMonitor monitor=test.addMonitor(DesktopActivity.class.getName(),null,false);
        Throwable failure=null;JSONObject sentinelBefore=null;
        try{
            registerReplies();main(()->check(production.edit().putBoolean("workspace_auto",false).commit(),"Could not pause automatic handoff"));settleUi();
            frames=new HandlerThread("owned-backdrop-composition");frames.start();
            reader=ImageReader.newInstance(WIDTH,HEIGHT,PixelFormat.RGBA_8888,3);reader.setOnImageAvailableListener(this::collectFrame,new Handler(frames.getLooper()));
            UiAutomation automation=test.getUiAutomation();automation.adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY");
            try{display=actual.getSystemService(DisplayManager.class).createVirtualDisplay("StellaShell backdrop fixture "+nonce,WIDTH,HEIGHT,160,reader.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY|TRUSTED);}
            finally{automation.dropShellPermissionIdentity();}
            check(display!=null,"Owned trusted display could not be created");fixtureDisplay=display.getDisplay();displayId=fixtureDisplay.getDisplayId();requireOwnedDisplay();
            main(()->WorkArea.put(displayId,new WorkArea(new Rect(0,0,WIDTH,HEIGHT),new Rect(0,0,WIDTH,HEIGHT),false,32,60)));
            // Put an opaque, nonce-owned fullscreen task underneath the Desktop first.
            launchFixture(tokens[2],0x24405c,1,"--ez fixture_sentinel true");
            await(()->states.containsKey(tokens[2]),"Owned fullscreen sentinel did not start before Desktop");
            // The creation reply can precede focus/first layout. Wait for the
            // nonce Activity's window events before asserting full composition.
            try{await(()->{
                Intent state=states.get(tokens[2]);
                return state!=null&&state.getBooleanExtra("sentinel",false)&&state.getBooleanExtra("shown",false)
                    &&state.getBooleanExtra("attached",false)&&state.getBooleanExtra("focus",false)
                    &&!state.getBooleanExtra("multi_window",true)&&state.getIntExtra("window_width",0)==WIDTH
                    &&state.getIntExtra("window_height",0)==HEIGHT;
            },"Owned fullscreen sentinel did not finish focus/layout");}
            catch(AssertionError windowFailure){
                Intent state=states.get(tokens[2]);
                throw new AssertionError("Owned sentinel window: width="+state.getIntExtra("window_width",0)
                    +" height="+state.getIntExtra("window_height",0)+" focus="+state.getBooleanExtra("focus",false)
                    +" attached="+state.getBooleanExtra("attached",false)+" shown="+state.getBooleanExtra("shown",false)
                    +" multiWindow="+state.getBooleanExtra("multi_window",true),windowFailure);
            }
            Intent sentinelState=query(tokens[2]);sentinelTaskId=sentinelState.getIntExtra("task_id",-1);
            check(sentinelState.getBooleanExtra("sentinel",false)&&sentinelState.getBooleanExtra("shown",false)
                    &&sentinelState.getBooleanExtra("attached",false)&&sentinelState.getBooleanExtra("focus",false)
                    &&!sentinelState.getBooleanExtra("multi_window",true)
                    &&sentinelState.getIntExtra("window_width",0)==WIDTH&&sentinelState.getIntExtra("window_height",0)==HEIGHT,
                    "Sentinel was not an opaque fullscreen window on the owned display");
            JSONObject sentinelTop=task(sentinelTaskId);
            check(sentinelTop.getInt("mode")==1&&sentinelTop.getBoolean("visible"),
                    "Nonce fullscreen sentinel was not initially topmost and full-display");
            // Ordinary explicit component launch, never CATEGORY_HOME/SECONDARY_HOME or Launches.home.
            requireAwake();requireOwnedDisplay();
            main(()->actual.startActivity(new Intent(actual,DesktopActivity.class).putExtra(HomeWindowFixtureActivity.TOKEN,nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK),ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()));
            await(()->{Activity activity=monitor.getLastActivity();return activity!=null&&!activity.isFinishing();},"Actual standard DesktopActivity did not launch");
            home=(DesktopActivity)monitor.getLastActivity();
            check(nonce.equals(home.getIntent().getStringExtra(HomeWindowFixtureActivity.TOKEN))&&home.getDisplay()!=null&&home.getDisplay().getDisplayId()==displayId,"Desktop launch did not create the nonce-owned display Activity");
            main(()->{
                WorkArea area=WorkArea.read(home,home.getWindowManager().getCurrentWindowMetrics().getWindowInsets());
                compactPresentation=area.compact;
                check(!requireCompact||compactPresentation,"Compact regression requires actual Compact presentation; never change the user's setting");
                WorkArea.put(displayId,new WorkArea(new Rect(0,0,WIDTH,HEIGHT),new Rect(0,0,WIDTH,HEIGHT),compactPresentation,32,60));
                // Remove only this newly owned Activity's visible shortcut/widget views, so
                // blank taps cannot invoke any saved personal item. Owners stay alive.
                home.root.removeAllViews();
                View blank=new View(home);blank.setBackgroundColor(0xff102030);
                blank.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_UP){int clicks=backgroundClicks.incrementAndGet();view.setBackgroundColor((clicks&1)==0?0xff102030:0xff102031);view.performClick();}return true;});
                home.root.addView(blank,new android.widget.FrameLayout.LayoutParams(-1,-1));
                check(home.backdrop!=null,"Actual standard DesktopActivity has no backdrop owner");home.backdrop.refresh();
            });bridgeBarrier();
            sentinelBefore=task(sentinelTaskId);
            check(!sentinelBefore.getBoolean("visible"),"Desktop did not hide the opaque fullscreen sentinel launched below it");
            main(()->{
                View decor=home.getWindow().getDecorView();
                check(!home.isInMultiWindowMode()&&!home.isInPictureInPictureMode()&&decor.getWidth()==WIDTH&&decor.getHeight()==HEIGHT,
                    "Owned DesktopActivity must be actual standard fullscreen, not a freeform backdrop false-positive");
            });
            call(s->{s.setWorkArea(displayId,0,32,WIDTH,HEIGHT-60);return "OK";});
            JSONObject[] before=new JSONObject[2];
            for(int i=0;i<2;i++){
                requireAwake();requireOwnedDisplay();String token=tokens[i];int left=i==0?180:740,top=i==0?160:200;
                launchFixture(token,COLORS[i],5,null);
                await(()->states.containsKey(token),"Nonce freeform Activity did not acknowledge creation");
                Intent state=query(token);taskIds[i]=state.getIntExtra("task_id",-1);check(taskIds[i]>=0,"Owned Activity omitted task identity");
                JSONObject row=task(taskIds[i]);check(row.getInt("mode")==5,"Owned launch is not freeform; refusing to convert any task");
                int id=taskIds[i];call(s->s.checkedTaskOperation(displayId,id,probe.flattenToString(),"bounds",left,top,left+360,top+340));
                before[i]=task(taskIds[i]);check(before[i].getBoolean("visible"),"Owned initial freeform is not visible");
            }
            check(taskIds[0]!=taskIds[1],"Two nonce windows reused one task");
            exerciseWindows(before,sentinelBefore,"Initial windows");blankTap(before,sentinelBefore,"Blank desktop click");
            popupDialogTransition(sentinelBefore);
            exerciseWindows(before,sentinelBefore,"Popup/dialog dismissed");blankTap(before,sentinelBefore,"Blank click after dialog dismissal");
            activityForResultTransition(before,sentinelBefore);
            blankTap(before,sentinelBefore,"Blank click after activity-for-result return");
        }catch(Throwable error){failure=error;}
        finally{
            try{cleanup(monitor,production,autoPresent,auto);}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }
        if(failure!=null){if(failure instanceof Exception)throw (Exception)failure;throw new AssertionError(failure);}
        System.out.println("DESKTOP_BACKDROP PASS compact="+compactPresentation+" standardDesktop=true fullscreenSentinelHidden=true windows=2 blankTouch=true popupDialog=true editTextInput=true activityForResult=true visible=true composed=true input=true settingsRuntimePreserved=true");
    }
    private interface CleanupStep {void run()throws Exception;}
    private static void cleanupStep(Throwable[] failure,CleanupStep action){
        try{action.run();}catch(Throwable error){if(failure[0]==null)failure[0]=error;else failure[0].addSuppressed(error);}
    }
    private void cleanup(Instrumentation.ActivityMonitor monitor,SharedPreferences production,boolean autoPresent,boolean auto)throws Exception{
        Throwable[] failure={null};
        cleanupStep(failure,()->main(()->{if(popup!=null)popup.dismiss();}));
        cleanupStep(failure,()->main(()->{if(dialog!=null)dialog.dismiss();}));
        if(registered)for(String token:tokens)cleanupStep(failure,()->main(()->command(token,"finish",-1)));
        cleanupStep(failure,()->main(()->{
            Activity observed=home!=null?home:monitor.getLastActivity();
            if(observed instanceof DesktopActivity&&nonce.equals(observed.getIntent().getStringExtra(HomeWindowFixtureActivity.TOKEN))&&observed.getDisplay()!=null&&observed.getDisplay().getDisplayId()==displayId){
                home=(DesktopActivity)observed;if(home.backdrop!=null)home.backdrop.close();home.finishAndRemoveTask();
            }
        }));
        if(display!=null)cleanupStep(failure,()->{
            bridgeBarrier();
            if(home!=null)await(()->{boolean[] destroyed={false};main(()->destroyed[0]=home.isDestroyed());return destroyed[0];},"Owned desktop Activity did not destroy after finishAndRemoveTask");
            await(()->{try{
                JSONArray remaining=rows();
                for(int i=0;i<remaining.length();i++){int id=remaining.getJSONObject(i).getInt("id");
                    if(id==taskIds[0]||id==taskIds[1]||id==sentinelTaskId||(home!=null&&id==home.getTaskId()))return false;}
                return true;
            }catch(Exception error){throw new AssertionError(error);}},"Nonce-owned sentinel/window/result tasks did not clean up before releasing display");
        });
        cleanupStep(failure,()->await(()->ownAppTask(resultTaskId)==null,"Nonce-owned result host task did not clean up before releasing display"));
        cleanupStep(failure,()->{if(display!=null)display.release();});
        cleanupStep(failure,()->{if(displayId>0){main(()->WorkArea.remove(displayId));await(()->actual.getSystemService(DisplayManager.class).getDisplay(displayId)==null,"Owned display did not disappear after release");}});
        cleanupStep(failure,()->{if(reader!=null)reader.close();});
        cleanupStep(failure,()->{if(frames!=null){frames.quitSafely();frames.join(3000);check(!frames.isAlive(),"Owned frame collector did not stop");}});
        cleanupStep(failure,()->{if(registered){main(()->receiverContext.unregisterReceiver(replies));registered=false;}});
        cleanupStep(failure,()->test.removeMonitor(monitor));
        cleanupStep(failure,()->main(()->{
            SharedPreferences.Editor edit=production.edit();if(autoPresent)edit.putBoolean("workspace_auto",auto);else edit.remove("workspace_auto");
            check(edit.commit(),"Could not restore exact automatic-handoff preference");
        }));
        cleanupStep(failure,this::settleUi);
        cleanupStep(failure,this::assertPreserved);
        if(failure[0]!=null){if(failure[0] instanceof Exception)throw (Exception)failure[0];throw new AssertionError(failure[0]);}
    }
}
