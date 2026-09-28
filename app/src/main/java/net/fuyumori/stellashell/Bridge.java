package net.fuyumori.stellashell;

import android.content.*;
import android.content.pm.PackageManager;
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
    private boolean binding;
    private String error = "";
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IDesktopBridge.Stub.asInterface(binder); binding = false; error = ""; changed();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; binding = false; changed(); }
    };
    private Bridge(Context context) {
        this.context=context;
        args = new Shizuku.UserServiceArgs(new ComponentName(context, DesktopBridgeService.class))
                .daemon(false).processNameSuffix("desktop_bridge").debuggable(false).version(8);
        Shizuku.addBinderReceivedListenerSticky(this::connect);
        Shizuku.addBinderDeadListener(() -> { service = null; binding = false; changed(); });
        Shizuku.addRequestPermissionResultListener((code, result) -> { if (result == PackageManager.PERMISSION_GRANTED) connect(); changed(); });
    }
    public void observe(Runnable listener) { listeners.add(listener); }
    public void remove(Runnable listener) { listeners.remove(listener); }
    private void changed() { main.post(() -> { for (Runnable r : listeners) r.run(); }); }
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
    public void call(Work work, Reply reply) {
        worker.execute(() -> {
            String value = null, failure = null;
            try {
                IDesktopBridge current = service;
                if (current == null || !current.asBinder().isBinderAlive()) throw new IllegalStateException(status());
                value = work.run(current);
                if (value == null || value.startsWith("ERROR:")) throw new IllegalStateException(value);
            } catch (Exception e) { failure = e.getMessage() == null ? e.toString() : e.getMessage(); }
            String result = value, problem = failure;
            main.post(() -> reply.done(result, problem==null?null:ErrorText.localize(context,problem)));
        });
    }
}
