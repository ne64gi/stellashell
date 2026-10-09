package net.fuyumori.stellashell;

import android.content.*;
import android.content.pm.PackageManager;
import android.app.PendingIntent;
import android.os.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public final class Bridge {
    public interface Work { String run(IDesktopBridge service) throws Exception; }
    public interface Reply { void done(String result, String error); }
    private static Bridge instance;
    public static synchronized Bridge get(Context context) {
        if (instance == null) instance = new Bridge(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Shizuku.UserServiceArgs args;
    private volatile IDesktopBridge service;
    private IDesktopBridge startActionService;
    private boolean startActionReady;
    boolean startShortcutReady(){return ready()&&service==startActionService&&startActionReady;}
    private final BroadcastReceiver homeRoleChanged=new BroadcastReceiver(){
        @Override public void onReceive(Context ignored,Intent intent){startActionService=null;startActionReady=false;startShortcut();}
    };
    void startShortcut(){
        IDesktopBridge current=service;
        if(current==null||current==startActionService||!StartMenuRequests.defaultHome(context))return;
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
    private boolean binding;
    private String error = "";
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IDesktopBridge.Stub.asInterface(binder); binding = false; error = "";
            call(s -> "OK", (result, failure) -> {});startShortcut(); changed();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; screenOff=false; binding = false; changed(); }
    };
    private Bridge(Context context) {
        this.context=context;
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(homeRoleChanged,new IntentFilter("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"),Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(homeRoleChanged,new IntentFilter("android.intent.action.ACTION_PREFERRED_ACTIVITY_CHANGED"));
        args = new Shizuku.UserServiceArgs(new ComponentName(context, DesktopBridgeService.class))
                .daemon(false).processNameSuffix("desktop_bridge").debuggable(false).version(38);
        Shizuku.addBinderReceivedListenerSticky(this::connect);
        Shizuku.addBinderDeadListener(() -> { service = null; screenOff=false; binding = false; changed(); });
        Shizuku.addRequestPermissionResultListener((code, result) -> { if (result == PackageManager.PERMISSION_GRANTED) connect(); changed(); });
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
    public boolean authorized() {
        try{return Shizuku.pingBinder()&&Shizuku.getVersion()>=13&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}
        catch(RuntimeException e){return false;}
    }
    public boolean ready() { return service != null && service.asBinder().isBinderAlive(); }
    public String status() {
        if (!Shizuku.pingBinder()) return context.getString(R.string.ui_shizuku_is_not_running_start_shizuku_for_full_window_management_t);
        try {
            if (Shizuku.getVersion() < 13) return context.getString(R.string.ui_shizuku_api_13_or_later_is_required);
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return context.getString(R.string.ui_shizuku_permission_is_required);
        } catch (RuntimeException e) { return context.getString(R.string.ui_reconnect_to_shizuku); }
        return ready() ? context.getString(R.string.ui_shizuku_connected) : error.isEmpty() ? context.getString(R.string.ui_waiting_for_shizuku) : error;
    }
    public void request() {
        if (!Shizuku.pingBinder()) { changed(); return; }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect();
            else Shizuku.requestPermission(401);
        } catch (RuntimeException e) { error = e.getMessage(); changed(); }
    }
    public void connect() {
        if (binding || ready() || !Shizuku.pingBinder()) return;
        try {
            if (Shizuku.getVersion() < 13 || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            binding = true; Shizuku.bindUserService(args, connection);
            main.postDelayed(() -> { if (binding && !ready()) { binding = false; error = context.getString(R.string.ui_retry_the_connection); changed(); } }, 6000);
        } catch (RuntimeException e) { binding = false; error = e.getMessage(); changed(); }
    }
    public void call(Work work, Reply reply) {call(work,reply,true);}
    /** Read model/event wiring does not enumerate input devices or mutate pin/IME leases. */
    void read(Work work, Reply reply) { execute(work, reply, false, false); }
    private void call(Work work,Reply reply,boolean syncPins) {
        execute(work,reply,true,syncPins);
    }
    private void execute(Work work,Reply reply,boolean maintenance,boolean syncPins) {
        worker.execute(() -> {
            String value = null, failure = null;
            try {
                IDesktopBridge current = service;
                if (current == null || !current.asBinder().isBinderAlive()) throw new IllegalStateException(status());
                ShellSettings.Snapshot settings=ShellSettings.of(context).snapshot();
                current.setPrimaryMode(settings.primaryMode&&ShellRuntime.enabled(context));
                if (maintenance) {
                try{current.syncExternalDisplayPolicy(ShellRuntime.enabled(context),mouseOwner);}
                catch(Exception ignored){/* Input cleanup remains independent of policy restoration. */}
                // Input restoration must not be gated by an unrelated pin cleanup failure.
                String routing;
                // The service retains this desired target and gates restoration/routing itself.
                try{routing=current.syncMouseRouting(shellHasExternalWorkspace(settings)?mouseDisplay:-1,mouseOwner);}
                catch(Exception e){routing="unavailable: "+e.getClass().getSimpleName();}
                if(!java.util.Objects.equals(mouseStatus,routing)){
                    mouseStatus=routing;String diagnostic=routing;
                    main.post(()->Launches.prefs(context).edit().putString("mouse_diagnostics",diagnostic).apply());
                }
                String ime;
                try{ime=current.syncVirtualKeyboard(shellHasExternalWorkspace(settings)?mouseDisplay:-1,
                        Launches.prefs(context).getBoolean("hide_virtual_ime",false),mouseOwner);}
                catch(Exception e){ime="unavailable: "+e.getClass().getSimpleName();}
                if(!java.util.Objects.equals(imeStatus,ime)){
                    imeStatus=ime;String diagnostic=ime;
                    main.post(()->Launches.prefs(context).edit().putString("ime_diagnostics",diagnostic).apply());
                }
                if(syncPins){
                    String pins=current.syncWindowPins(ShellRuntime.enabled(context),mouseOwner);
                    if(pins.startsWith("ERROR:"))throw new IllegalStateException(pins);
                }
                }
                value = work.run(current);
                if (value == null || value.startsWith("ERROR:")) throw new IllegalStateException(value);
            } catch (Exception e) { failure = e.getMessage() == null ? e.toString() : e.getMessage(); }
            String result = value, problem = failure;
            main.post(() -> reply.done(result, problem==null?null:ErrorText.localize(context,problem)));
        });
    }
}
