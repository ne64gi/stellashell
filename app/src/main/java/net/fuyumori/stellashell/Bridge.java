package net.fuyumori.stellashell;

import android.content.*;
import android.app.PendingIntent;
import android.os.*;
import java.util.concurrent.*;
import net.fuyumori.stellashell.platform.power.ShizukuConnection;

public final class Bridge {
    public interface Provider { Bridge bridge(); }
    public interface Work { String run(IDesktopBridge service) throws Exception; }
    public interface Reply { void done(String result, String error); }
    private static Bridge instance;
    public static synchronized Bridge get(Context context) {
        if(context instanceof Provider)return ((Provider)context).bridge();
        if(context.getApplicationContext() instanceof Provider)return ((Provider)context.getApplicationContext()).bridge();
        if (instance == null) instance = new Bridge(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private interface Transport {
        IDesktopBridge service();
        boolean ready();
        long epoch();
        boolean authorized();
        ShizukuConnection.Status status();
        String error();
        void wanted(boolean value);
        void connect();
        void request();
        void connectForCleanup();
        void disconnect();
        boolean binding();
        boolean hasConnection();
    }
    private static final class SdkTransport implements Transport {
        private final ShizukuConnection connection;
        SdkTransport(Context context,Runnable changed){connection=new ShizukuConnection(context,new ComponentName(context,DesktopBridgeService.class),changed);}
        public IDesktopBridge service(){return connection.service();}
        public boolean ready(){return connection.ready();}
        public long epoch(){return connection.epoch();}
        public boolean authorized(){return connection.authorized();}
        public ShizukuConnection.Status status(){return connection.status();}
        public String error(){return connection.error();}
        public void wanted(boolean value){connection.wanted(value);}
        public void connect(){connection.connect();}
        public void request(){connection.request();}
        public void connectForCleanup(){connection.connectForCleanup();}
        public void disconnect(){connection.disconnect();}
        public boolean binding(){return connection.binding();}
        public boolean hasConnection(){return connection.hasConnection();}
    }
    private static final class IsolatedTransport implements Transport {
        private volatile IDesktopBridge current;
        private volatile long epoch;
        IsolatedTransport(IDesktopBridge service){current=service;}
        public IDesktopBridge service(){IDesktopBridge value=current;return value!=null&&value.asBinder().isBinderAlive()?value:null;}
        public boolean ready(){return service()!=null;}
        public long epoch(){return epoch;}
        public boolean authorized(){return ready();}
        public ShizukuConnection.Status status(){return ready()?ShizukuConnection.Status.READY:ShizukuConnection.Status.WAITING;}
        public String error(){return "";}
        public void wanted(boolean value){}
        public void connect(){}
        public void request(){}
        public void connectForCleanup(){}
        public void disconnect(){current=null;epoch++;}
        public boolean binding(){return false;}
        public boolean hasConnection(){return current!=null;}
        void replace(IDesktopBridge service){epoch++;current=service;}
    }
    private final Transport transport;
    private final PowerMode power;
    private boolean hadPowerSession, releasing;
    private Reply releaseReply;
    private IDesktopBridge availableService;
    private IDesktopBridge startActionService;
    private boolean startActionReady;
    boolean startShortcutReady(){return ready()&&transport.service()==startActionService&&startActionReady;}
    private final BroadcastReceiver homeRoleChanged=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){startActionService=null;startActionReady=false;startShortcut();}
    };
    void startShortcut(){
        IDesktopBridge current=transport.service();
        if(!ready()||current==null||current==startActionService||!StartMenuRequests.defaultHome(context))return;
        startActionService=current;startActionReady=false;
        PendingIntent action=PendingIntent.getActivity(context,7341,new Intent(context,StartMenuActivity.class)
                .setAction(StartMenuRequests.LAUNCHER_TOGGLE).putExtra(StartMenuRequests.SYSTEM_REQUEST,true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        read(server->server.registerStartAction(action),(result,failure)->{
            if(startActionService!=current)return;
            startActionReady=failure==null&&"OK".equals(result);
            if(!startActionReady)startActionService=null;
            changed();
        });
    }
    private final IBinder mouseOwner=new Binder();
    private volatile int mouseDisplay=-1;
    private volatile boolean screenOff;
    public boolean screenOff(){return screenOff&&ready();}
    public void screenOff(boolean off,Reply reply){call(s->{String result=s.syncPrimaryScreen(mouseDisplay,off,mouseOwner);if(!result.equals(off?"off":"on"))throw new IllegalStateException(result);return result;},(result,error)->{if(error==null)screenOff=off&&mouseDisplay>0;reply.done(result,error);});}

    private String mouseStatus="",imeStatus="";
    private boolean observedEnabled;
    private final SharedPreferences.OnSharedPreferenceChangeListener imeChanged = this::imePreferenceChanged;
    private void imePreferenceChanged(SharedPreferences prefs, String key) {
        if (ready() && "hide_virtual_ime".equals(key)) call(s -> "OK", (result, failure) -> {});
    }
    private boolean shellHasExternalWorkspace(ShellSettings.Snapshot settings){
        return ShellRuntime.enabled(context)&&(!settings.primaryMode||TaskState.of(context).target(context)>0);
    }
    public void mouseDisplay(int displayId){
        int next=displayId>0?displayId:-1;
        if(mouseDisplay==next)return;
        mouseDisplay=next;if(next<0)screenOff=false;
        // A stop/display change must release input even if task/pin work is unavailable.
        call(s->{if(mouseDisplay<0)s.syncPrimaryScreen(-1,false,mouseOwner);return "OK";},(result,error)->{},false);
    }
    public void resetMouseRouting(Reply reply){
        mouseDisplay=-1;screenOff=false;
        call(s->{
            s.syncPrimaryScreen(-1,false,mouseOwner);
            String result=s.syncMouseRouting(-1,mouseOwner);
            if(!"inactive".equals(result))throw new IllegalStateException(result);
            return "OK";
        },reply,false);
    }
    private void transportChanged() {
        IDesktopBridge current=transport.service();
        if(current!=null)hadPowerSession=true;
        power.connected(power.requested()&&current!=null);
        if(!power.requested()) {
            screenOff=false;startActionService=null;startActionReady=false;
            if(power.transition()&&releaseReply!=null&&!transport.binding())releasePower(releaseReply);
        } else if(current!=null&&current!=availableService) {
            call(s->"OK",(result,failure)->{});startShortcut();
        }
        availableService=current;changed();
    }
    private Bridge(Context context){this(context,PowerMode.of(context),null);}
    static Bridge forService(Context context,IDesktopBridge service,PowerMode power){
        return new Bridge(context,power,new IsolatedTransport(service));
    }
    void replaceService(IDesktopBridge service){
        if(!(transport instanceof IsolatedTransport))throw new IllegalStateException("Not an isolated bridge");
        power.connected(false);((IsolatedTransport)transport).replace(service);
        availableService=service;power.connected(power.requested()&&transport.ready());changed();
    }
    void closeIsolated(){
        if(!(transport instanceof IsolatedTransport))throw new IllegalStateException("Not an isolated bridge");
        listeners.clear();worker.shutdownNow();
    }
    int serviceVersion(){return ShizukuConnection.SERVICE_VERSION;}
    private Bridge(Context context,PowerMode power,Transport isolated) {
        this.context=context;this.power=power;hadPowerSession=power.transition();
        transport=isolated==null?new SdkTransport(context,this::transportChanged):isolated;
        if(isolated!=null){
            availableService=transport.service();hadPowerSession|=availableService!=null;
            power.connected(power.requested()&&transport.ready());return;
        }
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(homeRoleChanged,new IntentFilter("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"),Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(homeRoleChanged,new IntentFilter("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"));
        transport.wanted(power.requested());
        ShellSettings.of(context).observe((changes, settings) -> {
            if (ready() && changes.contains(ShellSettings.Change.PRIMARY_MODE))
                call(s -> "OK", (result, failure) -> {});
        });
        observedEnabled = ShellRuntime.enabled(context);
        ShellRuntime.observeNavigation(() -> {
            boolean enabled = ShellRuntime.enabled(context);
            if (enabled == observedEnabled) return;
            observedEnabled = enabled;
            if (ready()) call(s -> "OK", (result, failure) -> {});
        });
        Launches.prefs(context).registerOnSharedPreferenceChangeListener(imeChanged);
    }
    public void observe(Runnable listener) { listeners.add(listener); }
    public void remove(Runnable listener) { listeners.remove(listener); }
    private void changed() { main.post(() -> { for (Runnable r : listeners) r.run(); }); }
    public boolean authorized() { return power.requested()&&!power.transition()&&transport.authorized(); }
    /** Permission UI may inspect capability even when the optional mode is OFF. */
    public boolean permissionGranted() { return transport.authorized(); }
    public boolean ready() { return power.requested()&&!power.transition()&&transport.ready(); }
    public String status() {
        if(!power.requested())return power.transition()?"Extensions cleanup is pending":"Extensions are off";
        switch(transport.status()) {
            case NOT_RUNNING:return context.getString(R.string.ui_shizuku_is_not_running_start_shizuku_for_full_window_management_t);
            case OLD_API:return context.getString(R.string.ui_shizuku_api_13_or_later_is_required);
            case PERMISSION:return context.getString(R.string.ui_shizuku_permission_is_required);
            case READY:return context.getString(R.string.ui_shizuku_connected);
            case WAITING:return context.getString(R.string.ui_waiting_for_shizuku);
            default:return transport.error().isEmpty()?context.getString(R.string.ui_reconnect_to_shizuku):transport.error();
        }
    }
    public void request() { if(power.requested()&&!power.transition())transport.request(); }
    public void connect() { if(power.requested()&&!power.transition())transport.connect(); }
    void powerChanged(boolean requested,Reply reply) {
        startActionService=null;startActionReady=false;screenOff=false;
        if(requested)availableService=null;
        releaseReply=null;
        changed();transport.wanted(requested);
        if(requested){reply.done("OK",null);return;}
        releasePower(reply);
    }
    /** Cleanup is intentionally outside execute(): it must never reacquire maintenance leases. */
    void retryReleasePower(Reply reply) {
        releaseReply=reply;
        hadPowerSession=true; // Persisted cleanup debt is unknown until the remote contract succeeds.
        if(transport.service()==null)transport.connectForCleanup();
        releasePower(reply);
    }
    void releasePower(Reply reply) {
        if(reply==null)reply=(result,failure)->{};
        releaseReply=reply;
        if(releasing)return;
        IDesktopBridge current=transport.service();
        if(current==null) {
            if(transport.binding())return;
            if(hadPowerSession||transport.hasConnection()){
                releaseReply=null;reply.done(null,"Extensions cleanup connection is unavailable");changed();return;
            }
            releaseReply=null;reply.done("OK",null);
            if(!power.requested()&&!power.transition())transport.disconnect();changed();return;
        }
        releasing=true;
        long releaseEpoch=transport.epoch();
        worker.execute(()->{
            String result=null,failure=null;
            try{
                result=current.releasePowerSession();
                if(!"OK".equals(result))throw new IllegalStateException(result);
            }catch(Exception error){failure=error.getMessage()==null?error.toString():error.getMessage();}
            String value=result,problem=failure;
            main.post(()->{
                releasing=false;
                Reply completion=releaseReply;releaseReply=null;
                String releaseProblem=transport.epoch()!=releaseEpoch||transport.service()!=current
                        ?"Extensions cleanup connection changed":problem;
                if(completion!=null)completion.done(value,releaseProblem==null?null:ErrorText.localize(context,releaseProblem));
                // The owner also has to durably save completion; keep the cleaned server for
                // idempotent retry if that write fails, rather than claiming the debt is gone.
                if(releaseProblem==null&&!power.requested()&&!power.transition()){hadPowerSession=false;transport.disconnect();}
                changed();
            });
        });
    }
    public void call(Work work, Reply reply) {call(work,reply,true);}
    /** Read model/event wiring does not enumerate input devices or mutate pin/IME leases. */
    void read(Work work, Reply reply) { execute(work, reply, false, false); }
    private void call(Work work,Reply reply,boolean syncPins) {
        execute(work,reply,true,syncPins);
    }
    private void execute(Work work,Reply reply,boolean maintenance,boolean syncPins) {
        long generation=power.generation();
        long serviceEpoch=transport.epoch();
        worker.execute(() -> {
            String value = null, failure = null;
            try {
                requirePower(generation,serviceEpoch);
                IDesktopBridge current = transport.service();
                if (current == null || !current.asBinder().isBinderAlive()) throw new IllegalStateException(status());
                ShellSettings.Snapshot settings=ShellSettings.of(context).snapshot();
                requirePower(generation,serviceEpoch);
                current.setPrimaryMode(settings.primaryMode&&ShellRuntime.enabled(context));
                if (maintenance) {
                requirePower(generation,serviceEpoch);
                try{current.syncExternalDisplayPolicy(ShellRuntime.enabled(context),mouseOwner);}
                catch(Exception ignored){/* Input cleanup remains independent of policy restoration. */}
                // Input restoration must not be gated by an unrelated pin cleanup failure.
                String routing;
                // The service retains this desired target and gates restoration/routing itself.
                requirePower(generation,serviceEpoch);
                try{routing=current.syncMouseRouting(shellHasExternalWorkspace(settings)?mouseDisplay:-1,mouseOwner);}
                catch(Exception e){routing="unavailable: "+e.getClass().getSimpleName();}
                if(!java.util.Objects.equals(mouseStatus,routing)){
                    mouseStatus=routing;String diagnostic=routing;
                    main.post(()->{if(power.permits(generation)&&transport.epoch()==serviceEpoch)Launches.prefs(context).edit().putString("mouse_diagnostics",diagnostic).apply();});
                }
                String ime;
                requirePower(generation,serviceEpoch);
                try{ime=current.syncVirtualKeyboard(shellHasExternalWorkspace(settings)?mouseDisplay:-1,
                        Launches.prefs(context).getBoolean("hide_virtual_ime",false),mouseOwner);}
                catch(Exception e){ime="unavailable: "+e.getClass().getSimpleName();}
                if(!java.util.Objects.equals(imeStatus,ime)){
                    imeStatus=ime;String diagnostic=ime;
                    main.post(()->{if(power.permits(generation)&&transport.epoch()==serviceEpoch)Launches.prefs(context).edit().putString("ime_diagnostics",diagnostic).apply();});
                }
                if(syncPins){
                    requirePower(generation,serviceEpoch);
                    String pins=current.syncWindowPins(ShellRuntime.enabled(context),mouseOwner);
                    if(pins.startsWith("ERROR:"))throw new IllegalStateException(pins);
                }
                }
                requirePower(generation,serviceEpoch);
                value = work.run(current);
                if (value == null || value.startsWith("ERROR:")) throw new IllegalStateException(value);
            } catch (Exception e) { failure = e.getMessage() == null ? e.toString() : e.getMessage(); }
            String result = value, problem = failure;
            main.post(() -> reply.done(result, !power.permits(generation)?status():transport.epoch()!=serviceEpoch?"Extensions connection changed":problem==null?null:ErrorText.localize(context,problem)));
        });
    }
    private void requirePower(long generation,long serviceEpoch) {
        if(!power.permits(generation))throw new IllegalStateException(status());
        if(transport.epoch()!=serviceEpoch)throw new IllegalStateException("Extensions connection changed");
    }
}
