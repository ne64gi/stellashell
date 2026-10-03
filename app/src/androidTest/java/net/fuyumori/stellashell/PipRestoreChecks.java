package net.fuyumori.stellashell;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Actual owned-display PiP -> fullscreen/focus/close, never targeting a personal app or PiP. */
final class PipRestoreChecks {
    private final Instrumentation test;private final Context context,receiverContext;
    private final String token=UUID.randomUUID().toString();private final ComponentName component;
    private volatile Intent entered,resumed,activityState,queryState;private boolean registered;private int fixtureId=-1;
    private long request;private final Deque<String> events=new ArrayDeque<>();
    private final BroadcastReceiver states=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        if(!token.equals(intent.getStringExtra(PipFixtureActivity.TOKEN)))return;
        activityState=new Intent(intent);
        // Queries are retained separately so polling cannot evict lifecycle evidence.
        if(!PipFixtureActivity.QUERY.equals(intent.getStringExtra("event")))
            synchronized(events){if(events.size()==64)events.removeFirst();events.addLast(describe(intent));}
        if(PipFixtureActivity.ENTER.equals(intent.getStringExtra("event")))entered=new Intent(intent);
        else if("resumed".equals(intent.getStringExtra("event")))resumed=new Intent(intent);
        else if(PipFixtureActivity.QUERY.equals(intent.getStringExtra("event")))queryState=new Intent(intent);
    }};
    PipRestoreChecks(Instrumentation test){
        this.test=test;context=test.getTargetContext();receiverContext=context.getApplicationContext();
        component=new ComponentName(test.getContext().getPackageName(),PipFixtureActivity.class.getName());
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void main(Runnable action){
        Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private String call(Bridge.Work work)throws Exception{
        CountDownLatch done=new CountDownLatch(1);String[] result=new String[2];
        main(()->Bridge.get(context).call(work,(value,error)->{result[0]=value;result[1]=error;done.countDown();}));
        check(done.await(15,TimeUnit.SECONDS),"Fixture Bridge call timed out");check(result[1]==null,"Fixture Bridge failed: "+result[1]);
        check(result[0]!=null&&!result[0].startsWith("ERROR:"),"Fixture Bridge rejected operation: "+result[0]);return result[0];
    }
    private void await(java.util.function.BooleanSupplier condition,String message)throws Exception{
        long until=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<until){if(condition.getAsBoolean())return;Thread.sleep(50);}
        throw new AssertionError(message);
    }
    private JSONArray rows(int display)throws Exception{return new JSONObject(call(s->display==0?s.phoneTaskSnapshot():s.taskSnapshot(display))).getJSONArray("tasks");}
    private boolean fixture(JSONObject task){return component.equals(ComponentName.unflattenFromString(task.optString("component","")));}
    private static final class Location {
        final int display;final JSONObject task;Location(int display,JSONObject task){this.display=display;this.task=task;}
    }
    private List<Location> locations()throws Exception{
        List<Location> result=new ArrayList<>();
        for(Display display:context.getSystemService(DisplayManager.class).getDisplays()){
            if(!display.isValid()||(display.getFlags()&Display.FLAG_PRIVATE)!=0)continue;
            JSONArray tasks=rows(display.getDisplayId());for(int i=0;i<tasks.length();i++){
                JSONObject task=tasks.getJSONObject(i);if(fixture(task))result.add(new Location(display.getDisplayId(),task));
            }
        }
        return result;
    }
    private void noOtherPip()throws Exception{
        for(Display display:context.getSystemService(DisplayManager.class).getDisplays()){
            if(!display.isValid()||(display.getFlags()&Display.FLAG_PRIVATE)!=0)continue;
            JSONArray tasks=rows(display.getDisplayId());for(int i=0;i<tasks.length();i++){
                JSONObject task=tasks.getJSONObject(i);
                check(task.getInt("mode")!=2||fixture(task),"Refusing to enter fixture PiP while another app already owns PiP");
            }
        }
    }
    private JSONObject awaitMode(int display,int mode)throws Exception{
        long until=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<until){
            List<Location> fixtures=locations();check(fixtures.size()<=1,"Fixture split into multiple task identities");
            if(!fixtures.isEmpty()){
                Location current=fixtures.get(0);
                check(current.display==display,"Owned fixture moved to another display; refusing further privileged task operations");
                check(current.task.getInt("id")==fixtureId,"PiP changed the owned fixture task ID; checked operations require the original identity");
                if(current.task.getInt("mode")==mode)return current.task;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Owned fixture did not reach window mode "+mode);
    }
    private void command(String command){
        command(command,-1);
    }
    private void command(String command,long request){
        receiverContext.sendBroadcast(new Intent(PipFixtureActivity.CONTROL).setPackage(component.getPackageName())
            .putExtra(PipFixtureActivity.TOKEN,token).putExtra(PipFixtureActivity.COMMAND,command).putExtra(PipFixtureActivity.REQUEST,request));
    }
    private void enter(int display)throws Exception{
        noOtherPip();entered=null;main(()->command(PipFixtureActivity.ENTER));
        await(()->entered!=null,"Owned fixture did not acknowledge enterPictureInPictureMode");
        check(entered.getBooleanExtra("accepted",false),"Fixture enterPictureInPictureMode rejected: "+entered.getStringExtra("error"));
        awaitMode(display,2);
        awaitActivityMode(display,true,"enter");
    }
    private static String describe(Intent state){
        if(state==null)return "no-state";
        StringBuilder text=new StringBuilder();
        for(String key:new String[]{"sequence","uptime","event","request","task_id","display_id","pip","resumed","lifecycle",
            "callback_known","callback_pip","focus","finishing","destroyed","attached","shown","width","height",
            "screen_width_dp","screen_height_dp","density_dpi","orientation","accepted","error"}){
            if(state.hasExtra(key)){if(text.length()>0)text.append(',');text.append(key).append('=').append(state.getExtras().get(key));}
        }
        return text.toString();
    }
    private String diagnostic(String operation)throws Exception{
        StringBuilder result=new StringBuilder("operation=").append(operation).append(" latest={").append(describe(activityState))
            .append("} query={").append(describe(queryState)).append("} ownedTasks=[");
        for(Location location:locations())result.append("{display=").append(location.display).append(",id=")
            .append(location.task.optInt("id",-1)).append(",mode=").append(location.task.optInt("mode",-1))
            .append(",bounds=[").append(location.task.optInt("left",-1)).append(',').append(location.task.optInt("top",-1))
            .append(',').append(location.task.optInt("right",-1)).append(',').append(location.task.optInt("bottom",-1))
            .append("],visible=").append(location.task.optBoolean("visible")).append(",focused=")
            .append(location.task.optBoolean("focused")).append("}");
        result.append("] callbacks=");synchronized(events){result.append(events);}
        return result.toString();
    }
    private void awaitRestored(int display,String operation)throws Exception{
        awaitActivityMode(display,false,operation);
    }
    private void awaitActivityMode(int display,boolean pip,String operation)throws Exception{
        // A fresh nonce-scoped query proves the Activity's current state, rather
        // than accepting a delayed pre-PiP resume/enter acknowledgement. Entry
        // acceptance and Task mode2 precede Shell's config-at-end callback; do
        // not race an exit against that unfinished system PiP transition.
        long until=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<until){
            long current=++request;main(()->command(PipFixtureActivity.QUERY,current));
            long replyUntil=Math.min(until,SystemClock.uptimeMillis()+250);
            while(SystemClock.uptimeMillis()<replyUntil){
                Intent state=queryState;
                if(state!=null&&state.getLongExtra(PipFixtureActivity.REQUEST,-1)==current){
                    check(state.getIntExtra("display_id",-1)==display&&state.getIntExtra("task_id",-1)==fixtureId,
                        "Owned Activity query changed identity; "+describe(state));
                    if(pip?state.getBooleanExtra("pip",false)&&state.getBooleanExtra("callback_known",false)
                            &&state.getBooleanExtra("callback_pip",false)
                        :!state.getBooleanExtra("pip",true)&&state.getBooleanExtra("resumed",false)
                            &&state.getBooleanExtra("callback_known",false)&&!state.getBooleanExtra("callback_pip",true))return;
                    break;
                }
                Thread.sleep(25);
            }
            Thread.sleep(100);
        }
        String details;
        try{details=diagnostic(operation);}catch(Exception error){details="diagnostic unavailable: "+error.getClass().getSimpleName()+" latest={"+describe(activityState)+"}";}
        throw new AssertionError((pip?"Owned fixture task entered PiP but Activity did not receive the system PiP callback"
            :"Owned fixture mode changed but Activity did not resume outside PiP")+"; "+details);
    }
    // These nonce-scoped replies deliberately cross package boundaries. Older
    // API 30–32 receivers are exported by default and lack the new flag semantics.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStates(IntentFilter filter){
        if(Build.VERSION.SDK_INT>=33)receiverContext.registerReceiver(states,filter,Context.RECEIVER_EXPORTED);
        else receiverContext.registerReceiver(states,filter);
    }
    private void launch(int display)throws Exception{
        // The app UID lacks permission to launch another UID on a virtual
        // display. Use shell only for our fixed, nonce-owning test Activity.
        // No fallback display, package discovery, or production launch path.
        String command="am start --display "+display+" -f 0x18000000 -n "+component.flattenToString()
                +" --es "+PipFixtureActivity.TOKEN+" "+token+" --es "+PipFixtureActivity.REPLY_PACKAGE+" "+context.getPackageName();
        try(ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand(command);
            java.io.InputStream stream=new java.io.FileInputStream(fd.getFileDescriptor())){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
            while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);
            String output=bytes.toString("UTF-8");check(!output.contains("Error")&&!output.contains("Exception"),"Owned Activity launch rejected: "+output);
        }
    }
    void run(int ownedDisplay,boolean restorePip)throws Exception{
        run(ownedDisplay,restorePip,false);
    }
    /** Caller identifies the existing selected external; this does not create,
     * resize, transfer or release it. Only our new nonce-owning Activity is acted on. */
    void runOnSelectedExternal(int selectedDisplay,boolean restorePip)throws Exception{
        run(selectedDisplay,restorePip,true);
    }
    private void run(int ownedDisplay,boolean restorePip,boolean selectedExternal)throws Exception{
        Display display=context.getSystemService(DisplayManager.class).getDisplay(ownedDisplay);
        check(ownedDisplay>0&&display!=null&&display.isValid()&&(display.getFlags()&Display.FLAG_PRIVATE)==0,
            selectedExternal?"Requires caller-identified existing selected public external display":"Requires caller-owned non-primary public display");
        if(restorePip)check(!context.getSystemService(KeyguardManager.class).isDeviceLocked(),"Requires normal unlocked device state");
        SharedPreferences prefs=Launches.prefs(context);Map<String,Object> protectedPrefs=new HashMap<>();
        for(String key:new String[]{"enabled","primary_mode","active_display","preferred_display","workspace_display","workspace_auto"})
            protectedPrefs.put(key,prefs.contains(key)?prefs.getAll().get(key):null);
        int selected=prefs.getInt("active_display",-1);
        check(DockService.running()&&prefs.getBoolean("enabled",false)&&selected>=0
            &&(selectedExternal?selected==ownedDisplay:selected!=ownedDisplay)
            &&prefs.getInt("active_display",-1)==selected,"Requires stable existing workspace; fixture never changes it");
        if(!selectedExternal)check(prefs.contains("workspace_auto")&&!prefs.getBoolean("workspace_auto",true),
            "Caller must suspend automatic handoff before creating the owned display");
        boolean[] bridgeReady={false};main(()->bridgeReady[0]=Bridge.get(context).ready()&&Bridge.get(context).authorized());
        check(bridgeReady[0],"Requires already-ready authorized Bridge; fixture does not bind or request access");
        if(restorePip)noOtherPip();check(locations().isEmpty(),"Refusing to reuse an existing PiP fixture task");
        try{
            main(()->{
                IntentFilter filter=new IntentFilter(PipFixtureActivity.STATE);
                registerStates(filter);
                registered=true;
            });
            launch(ownedDisplay);
            await(()->resumed!=null,"Owned PiP fixture did not resume");
            check(resumed.getIntExtra("display_id",-1)==ownedDisplay,"Fixture initially launched on another display");
            fixtureId=resumed.getIntExtra("task_id",-1);check(fixtureId>=0,"Fixture has no task ID");awaitMode(ownedDisplay,1);
            int id=fixtureId;String identity=component.flattenToString();
            if(restorePip)enter(ownedDisplay);
            String wrongIdentity=new ComponentName(component.getPackageName(),PinInputActivity.class.getName()).flattenToString();
            for(String action:restorePip?new String[]{"fullscreen","close"}:new String[]{"close"}){
                String rejection=call(s->"IDENTITY-CHECK:"+s.checkedTaskOperation(ownedDisplay,id,wrongIdentity,action,0,0,0,0));
                check(rejection.startsWith("IDENTITY-CHECK:ERROR:"),"Checked "+action+" accepted another component for the owned PiP task ID");
                awaitMode(ownedDisplay,restorePip?2:1);
            }
            if(restorePip){
            activityState=null;call(s->s.checkedTaskOperation(ownedDisplay,id,identity,"fullscreen",0,0,0,0));awaitMode(ownedDisplay,1);awaitRestored(ownedDisplay,"fullscreen");
            enter(ownedDisplay);
            activityState=null;call(s->s.checkedTaskOperation(ownedDisplay,id,identity,"focus",0,0,0,0));awaitMode(ownedDisplay,1);awaitRestored(ownedDisplay,"focus");
            }
            call(s->s.checkedTaskOperation(ownedDisplay,id,identity,"close",0,0,0,0));
            long until=SystemClock.uptimeMillis()+10000;while(!locations().isEmpty()&&SystemClock.uptimeMillis()<until)Thread.sleep(100);
            check(locations().isEmpty(),"Checked close left the owned PiP fixture task alive");
        }finally{
            try{
                // Also works after an OEM reparent/ID change: only our nonce-owning
                // Activity can consume this finish command; no Display-0 bridge operation.
                if(registered){main(()->command(PipFixtureActivity.FINISH));
                    long until=SystemClock.uptimeMillis()+5000;while(!locations().isEmpty()&&SystemClock.uptimeMillis()<until)Thread.sleep(100);
                    check(locations().isEmpty(),"Owned PiP fixture cleanup did not finish its task");}
            }finally{
                if(registered){main(()->receiverContext.unregisterReceiver(states));registered=false;}
                for(Map.Entry<String,Object> expected:protectedPrefs.entrySet()){
                    Object actual=prefs.contains(expected.getKey())?prefs.getAll().get(expected.getKey()):null;
                    check(Objects.equals(expected.getValue(),actual),"PiP fixture changed protected preference "+expected.getKey());
                }
            }
        }
    }
}
