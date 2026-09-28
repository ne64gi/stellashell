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
        args = new Shizuku.UserServiceArgs(new ComponentName(context, DesktopBridgeService.class))
                .daemon(false).processNameSuffix("desktop_bridge").debuggable(false).version(6);
        Shizuku.addBinderReceivedListenerSticky(this::connect);
        Shizuku.addBinderDeadListener(() -> { service = null; binding = false; changed(); });
        Shizuku.addRequestPermissionResultListener((code, result) -> { if (result == PackageManager.PERMISSION_GRANTED) connect(); changed(); });
    }
    public void observe(Runnable listener) { listeners.add(listener); }
    public void remove(Runnable listener) { listeners.remove(listener); }
    private void changed() { main.post(() -> { for (Runnable r : listeners) r.run(); }); }
    public boolean ready() { return service != null && service.asBinder().isBinderAlive(); }
    public String status() {
        if (!Shizuku.pingBinder()) return "Shizuku を起動してください";
        try {
            if (Shizuku.getVersion() < 13) return "Shizuku API 13 以降が必要です";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "Shizuku の利用許可が必要です";
        } catch (RuntimeException e) { return "Shizuku に再接続してください"; }
        return ready() ? "Shizuku 接続済み" : error.isEmpty() ? "Shizuku 接続待ち" : error;
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
            main.postDelayed(() -> { if (binding && !ready()) { binding = false; error = "接続を再試行してください"; changed(); } }, 6000);
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
            main.post(() -> reply.done(result, problem));
        });
    }
}
