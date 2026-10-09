package net.fuyumori.stellashell;

import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Display;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Own-desktop focusability only. Never reorders or modifies an application window. */
final class DesktopBackdropLease {
    private static final ComponentName DESKTOP = new ComponentName("net.fuyumori.stellashell", "net.fuyumori.stellashell.DesktopActivity");
    private final Context context;
    private FrameworkTaskAccess access;
    private Object organizer, organizerManager;
    private Class<?> transaction, token;
    private Method tokenBinder, focusable, apply, unique, allRoots;
    private java.lang.reflect.Field childIds;
    private final Map<IBinder, Client> clients = new HashMap<>();
    private final Map<IBinder, State> states = new HashMap<>();
    private HandlerThread retryThread;
    private Handler retryHandler;
    private final class Client {
        final IBinder owner; final State state;
        final IBinder.DeathRecipient death;
        Client(IBinder owner, State state) { this.owner=owner; this.state=state; death=()->release(owner); }
    }
    private final class State {
        final FrameworkTaskAccess.Entry task; final IBinder binder; final String displayIdentity;
        Boolean applied; boolean dirty; int failures; Runnable retry;
        State(FrameworkTaskAccess.Entry task, IBinder binder, String identity) { this.task=task; this.binder=binder; displayIdentity=identity; }
    }
    DesktopBackdropLease(Context context) { this.context=context; }
    private void initialize() throws Exception {
        if (access!=null) return;
        Class<?> api=Class.forName("android.app.IActivityTaskManager");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"activity_task");
        Object manager=Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        organizerManager=manager; organizer=api.getMethod("getWindowOrganizerController").invoke(manager);
        transaction=Class.forName("android.window.WindowContainerTransaction"); token=Class.forName("android.window.WindowContainerToken");
        tokenBinder=token.getMethod("asBinder"); focusable=transaction.getMethod("setFocusable",token,boolean.class);
        allRoots=api.getMethod("getAllRootTaskInfos");
        childIds=Class.forName("android.app.ActivityTaskManager$RootTaskInfo").getField("childTaskIds");
        apply=Class.forName("android.window.IWindowOrganizerController").getMethod("applyTransaction",transaction);
        unique=Display.class.getMethod("getUniqueId"); access=new FrameworkTaskAccess(api,manager);
    }
    private String identity(int id) throws Exception {
        Display display=context.getSystemService(DisplayManager.class).getDisplay(id);
        if (display==null || !display.isValid() || (display.getFlags()&Display.FLAG_PRIVATE)!=0) throw new IllegalStateException("Desktop display disconnected");
        return (String)unique.invoke(display);
    }
    private FrameworkTaskAccess.Entry require(int display, int id) throws Exception {
        identity(display);
        for (FrameworkTaskAccess.Entry task:access.query(display)) if (task.id==id) {
            if (task.displayId!=display || task.userId!=android.os.Process.myUid()/100000 || task.activityType!=1
                    || task.windowMode!=1 || !DESKTOP.equals(task.component) || task.token==null
                    || task.topComponent!=null && !DESKTOP.equals(task.topComponent))
                throw new IllegalArgumentException("Not the fullscreen Stella desktop");
            return task;
        }
        throw new IllegalArgumentException("Desktop task disappeared");
    }
    /** Restoration follows only the exact live owned token, even after mode/display migration. */
    private FrameworkTaskAccess.Entry current(State state) throws Exception {
        for (FrameworkTaskAccess.Entry task:access.query(-1)) if (task.id==state.task.id) {
            return task.userId==state.task.userId && DESKTOP.equals(task.component) && task.token!=null
                    && state.binder.equals(tokenBinder.invoke(task.token)) ? task : null;
        }
        // A capped running-task snapshot cannot prove disappearance. Check all root IDs/children.
        for (Object root:(java.util.List<?>)allRoots.invoke(organizerManager)) {
            if (((android.app.TaskInfo)root).taskId==state.task.id)
                throw new IllegalStateException("Owned desktop is outside the current snapshot");
            for (int id:(int[])childIds.get(root)) if (id==state.task.id)
                throw new IllegalStateException("Owned desktop is outside the current snapshot");
        }
        return null;
    }
    private boolean eligible(State state, FrameworkTaskAccess.Entry task) throws Exception {
        return task.displayId==state.task.displayId && task.activityType==1 && task.windowMode==1
                && (task.topComponent==null || DESKTOP.equals(task.topComponent))
                && state.displayIdentity.equals(identity(task.displayId));
    }
    synchronized String sync(int display, int id, boolean enabled, IBinder owner) throws Exception {
        if (owner==null) throw new IllegalArgumentException("Desktop owner missing");
        if (!enabled) { releaseChecked(owner); return "OK: released"; }
        if (!owner.isBinderAlive()) throw new IllegalStateException("Desktop owner disconnected");
        initialize(); FrameworkTaskAccess.Entry task=require(display,id); IBinder key=(IBinder)tokenBinder.invoke(task.token);
        Client client=clients.get(owner);
        if (client!=null && !client.state.binder.equals(key)) { releaseChecked(owner); client=null; }
        State state=states.get(key);
        if (state!=null && (current(state)==null || state.task.displayId!=display
                || !state.displayIdentity.equals(identity(display)))) {
            // Retire the former scope before enrolling the same owned token on its new display.
            update(state); forget(state); state=null; client=null;
        }
        if (state==null) state=new State(task,key,identity(display));
        if (client==null) {
            client=new Client(owner,state); owner.linkToDeath(client.death,0); clients.put(owner,client);
        }
        states.put(key,state);
        cancelRetry(state);
        try { update(state); } catch (Exception error) { retry(state); throw error; }
        return "OK: protected";
    }
    private boolean occupied(State state) { for (Client c:clients.values()) if (c.state==state) return true; return false; }
    private void update(State state) throws Exception {
        FrameworkTaskAccess.Entry task=current(state);
        if (task==null) { forget(state); return; }
        boolean active=occupied(state), valid=false;
        if (active) try { valid=eligible(state,task); } catch (IllegalStateException disconnected) { /* restore exact live token */ }
        boolean wanted=!active || !valid;
        if (active && valid && !state.dirty && Boolean.FALSE.equals(state.applied)) return;
        Object change=transaction.getConstructor().newInstance(); focusable.invoke(change,task.token,wanted);
        // No task reorder: interactive UI lives in its own disposable Activity task.
        state.dirty=true; apply.invoke(organizer,change);
        state.applied=wanted; state.dirty=false;
        state.failures=0; cancelRetry(state); if (!active || !valid) forget(state);
    }
    private synchronized void release(IBinder owner) {
        try { releaseChecked(owner); } catch (Exception error) { /* exact state remains as restoration debt */ }
    }
    private void releaseChecked(IBinder owner) throws Exception {
        Client client=clients.remove(owner); if (client==null) return;
        client.owner.unlinkToDeath(client.death,0);
        try { update(client.state); } catch (Exception error) { retry(client.state); throw error; }
    }
    private void retry(State state) {
        if (state.retry!=null) return;
        if (retryThread==null) { retryThread=new HandlerThread("StellaBackdropCleanup"); retryThread.start(); retryHandler=new Handler(retryThread.getLooper()); }
        long delay=Math.min(30000L,1000L<<Math.min(state.failures++,5));
        state.retry=()->{ synchronized(DesktopBackdropLease.this) {
            state.retry=null; if (states.get(state.binder)!=state) return;
            try { update(state); } catch (Exception error) { retry(state); }
        }};
        retryHandler.postDelayed(state.retry,delay);
    }
    private void cancelRetry(State state) { if (state.retry!=null && retryHandler!=null) retryHandler.removeCallbacks(state.retry); state.retry=null; }
    private void forget(State state) {
        cancelRetry(state); states.remove(state.binder);
        for (Client client:new ArrayList<>(clients.values())) if (client.state==state) {
            clients.remove(client.owner); client.owner.unlinkToDeath(client.death,0);
        }
        if (states.isEmpty() && retryThread!=null) { retryThread.quitSafely(); retryThread=null; retryHandler=null; }
    }
    synchronized void close() { for (IBinder owner:new ArrayList<>(clients.keySet())) release(owner); }
}
