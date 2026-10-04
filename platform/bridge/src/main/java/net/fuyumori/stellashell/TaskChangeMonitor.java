package net.fuyumori.stellashell;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Shell-UID framework event adapter. Registered only while a live client is interested. */
final class TaskChangeMonitor implements AutoCloseable {
    private static final String DESCRIPTOR = "android.app.ITaskStackListener";
    private final Map<IBinder, Client> clients = new HashMap<>();
    private Object manager, frameworkListener;
    private Method register, unregister, transactionName;
    private boolean registered;

    private void prepare() throws ReflectiveOperationException {
        if (manager != null) return;
        Class<?> api = Class.forName("android.app.IActivityTaskManager");
        Class<?> listener = Class.forName(DESCRIPTOR);
        Class<?> stub = Class.forName(DESCRIPTOR + "$Stub");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "activity_task");
        Object service = Class.forName("android.app.IActivityTaskManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method names = stub.getMethod("getDefaultTransactionName", int.class);
        Method add = api.getMethod("registerTaskStackListener", listener);
        Method remove = api.getMethod("unregisterTaskStackListener", listener);
        Binder events = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(DESCRIPTOR);
                    return true;
                }
                String name;
                try { name = (String) transactionName.invoke(null, code); }
                catch (ReflectiveOperationException failure) { return false; }
                if (name == null) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(DESCRIPTOR);
                // The payload belongs to the framework. We need only invalidation;
                // never decode thumbnails, components or OEM-dependent TaskInfo.
                notifyClients();
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
        Object adapter = stub.getMethod("asInterface", IBinder.class).invoke(null, events);
        transactionName = names;
        register = add;
        unregister = remove;
        frameworkListener = adapter;
        manager = service;
    }

    synchronized String observe(ITaskChangeListener listener) {
        if (listener == null || !listener.asBinder().isBinderAlive()) return "ERROR: Missing task observer";
        IBinder binder = listener.asBinder();
        if (clients.containsKey(binder)) return "OK";
        try {
            prepare();
            Client client = new Client(listener);
            binder.linkToDeath(client, 0);
            clients.put(binder, client);
            try {
                if (!registered) {
                    register.invoke(manager, frameworkListener);
                    registered = true;
                }
            } catch (ReflectiveOperationException failure) {
                remove(listener);
                throw failure;
            }
            return "OK";
        } catch (Exception failure) { return "ERROR: " + TaskBackend.reason(failure); }
    }

    synchronized void remove(ITaskChangeListener listener) {
        if (listener == null) return;
        Client client = clients.remove(listener.asBinder());
        if (client != null) client.listener.asBinder().unlinkToDeath(client, 0);
        if (clients.isEmpty() && registered) {
            try { unregister.invoke(manager, frameworkListener); registered = false; }
            catch (ReflectiveOperationException ignored) {
                // Retain registration identity so an explicit close can retry.
                // No timer, task query or client callback remains.
            }
        }
    }

    private void notifyClients() {
        ArrayList<Client> targets;
        synchronized (this) { targets = new ArrayList<>(clients.values()); }
        for (Client client : targets) {
            try { client.listener.onChanged(); }
            catch (RemoteException failure) { remove(client.listener); }
        }
    }

    @Override public synchronized void close() {
        for (Client client : new ArrayList<>(clients.values())) remove(client.listener);
        if (registered) {
            try { unregister.invoke(manager, frameworkListener); registered = false; }
            catch (ReflectiveOperationException ignored) {}
        }
    }

    private final class Client implements IBinder.DeathRecipient {
        final ITaskChangeListener listener;
        Client(ITaskChangeListener listener) { this.listener = listener; }
        @Override public void binderDied() { remove(listener); }
    }
}
