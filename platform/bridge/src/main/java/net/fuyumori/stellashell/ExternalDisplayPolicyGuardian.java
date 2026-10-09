package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Display;

import net.fuyumori.stellashell.core.settings.SettingTransaction;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.io.RandomAccessFile;

/** Shell-UID app_process guardian. It has no timer or periodic display/settings polling. */
@android.annotation.TargetApi(34) // run() rejects every other SDK before using the guardian APIs.
public final class ExternalDisplayPolicyGuardian {
    private static final String FORCE = "force_desktop_mode_on_external_displays";
    private static final String FREEFORM = "enable_freeform_support";
    private static final String MARKER_PATH = "/data/local/tmp/stellashell_external_display_policy.v1";
    private static final String LOCK_PATH = "/data/local/tmp/stellashell_external_display_policy.v1.lock";
    private static final long COMMAND_TIMEOUT_MS = 8_000;
    private static final int TYPE_EXTERNAL = 2;
    private static final Pattern SIZE_LINE = Pattern.compile("(?m)^(Physical|Override) size: ([0-9]{2,5})x([0-9]{2,5})\\s*$");

    private final BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final AtomicInteger requestedSelection = new AtomicInteger(-1);
    private final ExternalDisplayPolicy.LeaseState state = new ExternalDisplayPolicy.LeaseState();
    private final Object outputLock = new Object();
    private Context context;
    private DisplayManager displays;
    private Object windowManager;
    private RandomAccessFile lockFile;
    private FileChannel lockChannel;
    private FileLock fileLock;
    private Class<?> listenerInterface;
    private Object listenerProxy;
    private boolean listenerRegistered;
    private volatile boolean readerEof;
    private volatile boolean writerBlocked;
    private boolean lastBaselineReadback;
    private boolean ownerActive;
    private boolean orphanRecoveryFailed;
    private boolean orphanRecoveryPulsePending;
    private boolean pointerJournalPending;
    private boolean recoveredPointerOnly;
    private boolean recoveredPointerComplete;
    private boolean stopRequested;
    private String status = "SAFE:inactive";
    private int selectedDisplayId = -1;
    private int candidateDisplayId = -1;
    private long candidateEpoch = -1;
    private String candidateIdentity;
    private int observedRemovedDisplay = -1;
    private String lastRemovedIdentity;
    private final java.util.Map<Integer,String> observedIdentities = new java.util.HashMap<>();
    private final ConcurrentHashMap<Integer,AtomicLong> displayEpochs = new ConcurrentHashMap<>();

    private static final String JOURNAL_ARMING = "arming";
    private static final String JOURNAL_ARMED = "armed";
    private static final String JOURNAL_PULSE_PENDING = "pulse_pending";

    private static final class Journal {
        final String baseline;
        final String phase;
        Journal(String baseline,String phase){this.baseline=baseline;this.phase=phase;}
    }

    private interface Event {}
    private static final class Command implements Event {
        final long id;
        final String name;
        final String[] args;
        Command(long id, String name, String[] args) { this.id=id; this.name=name; this.args=args; }
    }
    private static final class DisplayEvent implements Event {
        final int kind, id;
        final long epoch;
        DisplayEvent(int kind, int id,long epoch) { this.kind=kind; this.id=id;this.epoch=epoch; }
    }
    private static final class Eof implements Event {}
    private static final int ADDED=1, REMOVED=2;

    private ExternalDisplayPolicyGuardian() {}

    public static void main(String[] args) {
        if (args == null || args.length != 1 || !"guardian".equals(args[0])) return;
        new ExternalDisplayPolicyGuardian().run();
    }

    private void run() {
        try {
            context = createSystemContext();
            if (Build.VERSION.SDK_INT != 34 || !"SOG06".equals(Build.MODEL) || Process.myUid() != 2000) {
                status = "SAFE:unsupported";
                emit("READY", status);
                readCommandsWithoutServices();
                return;
            }
            displays = context.getSystemService(DisplayManager.class);
            if (displays == null) throw new IllegalStateException("DisplayManager unavailable");
            windowManager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            if(!acquireLifetimeLock()) {
                status="UNSAFE:guardian-locked";
                emit("READY",status);
                return;
            }
            if (!recoverOrphan()) {
                orphanRecoveryFailed = true;
                status = "UNSAFE:orphan-recovery";
            }
            startBinderPool();
            registerWindowListener();
            startCommandReader();
            emit("READY", status);
            eventLoop();
        } catch (Throwable failure) {
            emit("READY", "UNSAFE:startup");
        } finally {
            unregisterWindowListener();
            try { if(fileLock!=null&&fileLock.isValid())fileLock.release(); } catch(Throwable ignored) {}
            try { if(lockChannel!=null)lockChannel.close(); } catch(Throwable ignored) {}
            try { if(lockFile!=null)lockFile.close(); } catch(Throwable ignored) {}
        }
    }

    private void readCommandsWithoutServices() {
        startCommandReader();
        try {
            while (true) {
                Event event=queue.take();
                if (event instanceof Command) reply(((Command)event).id, "SAFE:unsupported");
                else if (event instanceof Eof) return;
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private boolean acquireLifetimeLock() throws Exception {
        lockFile=new RandomAccessFile(LOCK_PATH,"rw");
        lockChannel=lockFile.getChannel();
        try{fileLock=lockChannel.tryLock();}
        catch(OverlappingFileLockException alreadyLocked){fileLock=null;}
        return fileLock!=null;
    }

    private static int staticIntField(Class<?> type,String name)throws Exception {
        Field field;
        try{field=type.getDeclaredField(name);}catch(NoSuchFieldException absent){field=type.getField(name);}
        field.setAccessible(true);return field.getInt(null);
    }

    private static String staticStringField(Class<?> type,String name)throws Exception {
        Field field;
        try{field=type.getDeclaredField(name);}catch(NoSuchFieldException absent){field=type.getField(name);}
        field.setAccessible(true);Object value=field.get(null);
        if(!(value instanceof String))throw new IllegalStateException("Invalid listener descriptor");
        return (String)value;
    }

    private void registerWindowListener() throws Exception {
        Class<?> stubClass=Class.forName("android.view.IDisplayWindowListener$Stub");
        listenerInterface=Class.forName("android.view.IDisplayWindowListener");
        String descriptor=staticStringField(stubClass,"DESCRIPTOR");
        int addCode=staticIntField(stubClass,"TRANSACTION_onDisplayAdded");
        int removeCode=staticIntField(stubClass,"TRANSACTION_onDisplayRemoved");
        Map<Integer,String> transactions=new HashMap<>();
        for(Field field:stubClass.getDeclaredFields()){
            if(!field.getName().startsWith("TRANSACTION_")||field.getType()!=int.class
                    ||!java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
            field.setAccessible(true);
            transactions.put(field.getInt(null),field.getName().substring("TRANSACTION_".length()));
        }
        if(!transactions.containsKey(addCode)||!transactions.containsKey(removeCode))throw new NoSuchFieldException("Display listener transaction");
        DisplayWindowListenerBinder binder=new DisplayWindowListenerBinder(descriptor,addCode,removeCode,transactions);
        java.lang.reflect.InvocationHandler handler=(proxy,method,args)->{
            String name=method.getName();
            if("asBinder".equals(name))return binder;
            if("hashCode".equals(name))return System.identityHashCode(proxy);
            if("equals".equals(name))return args!=null&&args.length==1&&proxy==args[0];
            if("toString".equals(name))return "ExternalDisplayPolicyWindowListener";
            return null;
        };
        listenerProxy=java.lang.reflect.Proxy.newProxyInstance(listenerInterface.getClassLoader(),new Class<?>[]{listenerInterface},handler);
        binder.attachInterface((IInterface)listenerProxy,descriptor);
        Method register=findMethod(windowManager.getClass(),"registerDisplayWindowListener",listenerInterface);
        if(register==null)throw new NoSuchMethodException("registerDisplayWindowListener");
        register.setAccessible(true);
        register.invoke(windowManager,listenerProxy);
        listenerRegistered=true;
        for (Display display : displays.getDisplays()) {
            if (display != null && display.isValid() && display.getDisplayId() > 0
                    && (display.getFlags() & Display.FLAG_PRIVATE) == 0) {
                try { observedIdentities.put(display.getDisplayId(), uniqueId(display)); } catch (Throwable ignored) {}
            }
        }
    }

    private void unregisterWindowListener() {
        if(!listenerRegistered||windowManager==null||listenerProxy==null||listenerInterface==null)return;
        try {
            Method unregister=findMethod(windowManager.getClass(),"unregisterDisplayWindowListener",listenerInterface);
            if(unregister!=null){unregister.setAccessible(true);unregister.invoke(windowManager,listenerProxy);}
        } catch(Throwable ignored) {}
        listenerRegistered=false;
    }

    private final class DisplayWindowListenerBinder extends Binder {
        private final String descriptor;
        private final int addCode,removeCode;
        private final Map<Integer,String> transactions;
        DisplayWindowListenerBinder(String descriptor,int addCode,int removeCode,Map<Integer,String> transactions){
            this.descriptor=descriptor;this.addCode=addCode;this.removeCode=removeCode;this.transactions=transactions;
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(code==IBinder.INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(descriptor);return true;}
            String callback=transactions.get(code);
            if(callback==null)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(descriptor);
            if(code==addCode)postDisplayEvent(ADDED,data.readInt());
            else if(code==removeCode)postDisplayEvent(REMOVED,data.readInt());
            return true;
        }
    }

    private void postDisplayEvent(int kind,int id) {
        long epoch=displayEpochs.computeIfAbsent(id,ignored->new AtomicLong()).incrementAndGet();
        queue.offer(new DisplayEvent(kind,id,epoch));
    }

    private long displayEpoch(int id) {
        AtomicLong epoch=displayEpochs.get(id);
        return epoch==null?0:epoch.get();
    }

    private void startCommandReader() {
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    String[] fields=line.split("\\t", 4);
                    if(fields.length<2)continue;
                    long id;
                    try{id=Long.parseLong(fields[0]);}catch(NumberFormatException ignored){continue;}
                    String name=fields[1];
                    String tail=fields.length>2?fields[2]:"";
                    if(fields.length>3)tail+="\t"+fields[3];
                    String[] args=tail.isEmpty()?new String[0]:tail.split("\\t",-1);
                    if("SELECT".equals(name)&&args.length==1){
                        try{requestedSelection.set(Integer.parseInt(args[0]));}catch(NumberFormatException ignored){requestedSelection.set(-1);}
                    }
                    queue.offer(new Command(id,name,args));
                }
            } catch (Throwable ignored) {
            } finally {
                readerEof=true;
                queue.offer(new Eof());
            }
        }, "external-display-policy-command-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void eventLoop() {
        try {
            while (!stopRequested) {
                Event event=queue.take();
                if(event instanceof Command)handle((Command)event);
                else if(event instanceof DisplayEvent)handleDisplay((DisplayEvent)event);
                else if(event instanceof Eof){releaseOnExit();return;}
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            releaseOnExit();
        } catch (Throwable failure) {
            publish("UNSAFE:guardian-loop");
            releaseOnExit();
        }
    }

    private void handle(Command command) {
        try {
            if (orphanRecoveryFailed) {
                if("STOP".equals(command.name)) { reply(command.id,"UNSAFE:orphan-recovery"); stopRequested=true; }
                else if("SNAPSHOT".equals(command.name)) reply(command.id,"ERROR: orphan recovery required");
                else reply(command.id,"UNSAFE:orphan-recovery");
                return;
            }
            switch(command.name) {
                case "ENABLE": reply(command.id, enable()); break;
                case "SELECT": reply(command.id, select(command.args)); break;
                case "SNAPSHOT": reply(command.id, snapshotSettings()); break;
                case "APPLY": reply(command.id, applySettings(command.args)); break;
                case "STOP": {
                    String result=stop();
                    reply(command.id,result);
                    stopRequested=true;
                    break;
                }
                default: reply(command.id,"ERROR: invalid command");
            }
        } catch(Throwable failure) {
            publish("UNSAFE:command");
            reply(command.id,"UNSAFE:command");
        }
    }

    private String enable() throws Exception {
        ownerActive=true;
        List<Display> publicDisplays=publicNonDefaultDisplays();
        rememberIdentities(publicDisplays);
        String force=readSetting(FORCE);
        if(!"1".equals(readSetting(FREEFORM))) {
            ExternalDisplayPolicy.LeaseState.Action release=state.disable();
            if(release==ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE) {
                boolean restored=restoreOwnedBaseline(false);
                ExternalDisplayPolicy.LeaseState.Action finished=state.disableFinished(!writerBlocked,restored);
                if(!restored||finished==ExternalDisplayPolicy.LeaseState.Action.UNSAFE) {
                    publish("UNSAFE:restore-pending");
                    return status;
                }
            }
            publish("SAFE:freeform-disabled");
            return status;
        }
        ExternalDisplayPolicy.LeaseState.Action action=state.enable(true,true,true,force,publicDisplays.size());
        if(action==ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO)armZero();
        else attachRecoveryCandidate(publicDisplays);
        publish(statusForState());
        return status;
    }

    private String select(String[] args) throws Exception {
        if(args.length!=1)return "ERROR: invalid target";
        int target;
        try{target=Integer.parseInt(args[0]);}catch(NumberFormatException failure){return "ERROR: invalid target";}
        selectedDisplayId=target>0?target:-1;
        ExternalDisplayPolicy.LeaseState.Action action=state.select(selectedDisplayId);
        if(action==ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE)runSelectedPulse();
        publish(statusForState());
        return status;
    }

    private void handleDisplay(DisplayEvent event) throws Exception {
        if(event.kind==ADDED) {
            if(event.epoch!=displayEpoch(event.id))return;
            if(!ownerActive)return;
            List<Display> all=publicNonDefaultDisplays();
            rememberIdentities(all);
            if(state.phase()!=ExternalDisplayPolicy.LeaseState.Phase.ARMED||all.isEmpty())return;
            Display eventDisplay=displayForId(event.id);
            if(eventDisplay==null)return;
            boolean eventIsPublic=false;
            for(Display display:all)if(display.getDisplayId()==event.id){eventIsPublic=true;break;}
            if(!eventIsPublic)return;
            String identity=null;
            boolean physical=false;
            int physicalCount=0;
            for(Display d:all)if(isType2Physical(d))physicalCount++;
            if(eventDisplay!=null) {
                try{identity=uniqueId(eventDisplay);}catch(Throwable ignored){}
                physical=isType2Physical(eventDisplay);
            }
            ExternalDisplayPolicy.LeaseState.Action action=state.onAdded(event.id,identity,physicalCount,all.size(),physical);
            if(action==ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE) {
                candidateDisplayId=event.id;
                candidateEpoch=event.epoch;
                candidateIdentity=identity;
                publish("UNSAFE:restoring-settings");
                boolean exact=identity!=null&&sameLiveGeneration(event.id,identity,event.epoch);
                boolean restored=restoreOwnedBaseline(exact);
                exact=exact&&sameLiveGeneration(event.id,identity,event.epoch);
                boolean navAvailable=false,navFalse=false;
                if(exact)try{navFalse=!nativeHasNavigationBar(event.id);navAvailable=true;}catch(Throwable ignored){}
                ExternalDisplayPolicy.LeaseState.Action after=state.restoreFinished(restored,lastBaselineReadback,exact,navAvailable,navFalse);
                if(after==ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE)runSelectedPulse();
                publish(statusForState());
            }
            return;
        }
        if(event.kind==REMOVED) {
            List<Display> remaining=publicNonDefaultDisplays();
            String removed=observedIdentities.remove(event.id);
            if(removed==null&&event.id==observedRemovedDisplay)removed=lastRemovedIdentity;
            observedRemovedDisplay=event.id;lastRemovedIdentity=removed;
            boolean selectionRemoved=selectedDisplayId==event.id||remaining.isEmpty();
            if(selectionRemoved) {
                selectedDisplayId=-1;
                requestedSelection.set(-1);
                state.select(-1);
            }
            if(candidateDisplayId==event.id&&candidateEpoch!=event.epoch) {
                candidateDisplayId=-1;
                candidateEpoch=-1;
                candidateIdentity=null;
            }
            if(!ownerActive)return;
            ExternalDisplayPolicy.LeaseState.Action action=state.onRemoved(event.id,removed,remaining.size());
            if(action==ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO)armZero();
            else if(remaining.isEmpty()&&state.phase()==ExternalDisplayPolicy.LeaseState.Phase.WATCHING) {
                String force=readSetting(FORCE);
                ExternalDisplayPolicy.LeaseState.Action rearm=state.enable(true,true,true,force,0);
                if(rearm==ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO)armZero();
            }
            if(selectionRemoved)publishClearSelection(statusForState());else publish(statusForState());
        }
    }

    private void armZero() throws Exception {
        if(!ownerActive||writerBlocked)return;
        String baseline=state.baseline();
        if(!"1".equals(baseline)){publish("SAFE:watching");return;}
        if(!"1".equals(readSetting(FREEFORM))) {
            state.armSkipped(readSetting(FORCE));
            publish("SAFE:freeform-disabled");
            return;
        }
        String current=readSetting(FORCE);
        if(!"1".equals(current)) {
            state.armSkipped(current);
            publish(statusForState());
            return;
        }
        if(!publicNonDefaultDisplays().isEmpty()) {
            state.armSkipped(current);
            publish("SAFE:watching");
            return;
        }
        if(!writeMarker(baseline,JOURNAL_ARMING))throw new java.io.IOException("Could not persist reconnect policy journal");
        orphanRecoveryPulsePending=false;
        recoveredPointerOnly=false;
        recoveredPointerComplete=false;
        CommandResult result=writeSetting(FORCE,"0");
        boolean readback=false;
        if(result.quiesced&&result.ok)readback="0".equals(readSetting(FORCE));
        state.armFinished(result.quiesced,readback);
        if(readback)writeMarker(baseline,JOURNAL_ARMED);
        if(!result.quiesced)writerBlocked=true;
        if(!readback) {
            boolean restored=restoreOwnedBaseline();
            if(restored) {
                state.disableFinished(true,true);
                state.armSkipped(readSetting(FORCE));
            }
        }
        publish(statusForState());
    }

    private String stop() throws Exception {
        ownerActive=false;
        ExternalDisplayPolicy.LeaseState.Action action=state.disable();
        if(action==ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE) {
            boolean restored=restoreOwnedBaseline(false);
            ExternalDisplayPolicy.LeaseState.Action finished=state.disableFinished(!writerBlocked,restored);
            if(finished==ExternalDisplayPolicy.LeaseState.Action.UNSAFE||!restored) {
                publish("UNSAFE:restore-pending");return status;
            }
        }
        if(writerBlocked) { publish("UNSAFE:writer-unconfirmed");return status; }
        if(!clearMarker()) { publish("UNSAFE:journal-clear");return status; }
        orphanRecoveryPulsePending=false;
        recoveredPointerOnly=false;
        recoveredPointerComplete=false;
        publish("SAFE:inactive");
        return status;
    }

    private void releaseOnExit() {
        ownerActive=false;
        boolean safe=!writerBlocked&&!orphanRecoveryFailed;
        try {
            ExternalDisplayPolicy.LeaseState.Action action=state.disable();
            if(action==ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE&&safe) {
                boolean restored=restoreOwnedBaseline(true);
                state.disableFinished(true,restored);
                if(!restored)safe=false;
            }
            if(!writerBlocked&&!pointerJournalPending&&!clearMarker())safe=false;
        } catch(Throwable ignored) { safe=false; }
        publish(safe?"SAFE:inactive":"UNSAFE:restore-pending");
    }

    private String snapshotSettings() throws Exception {
        String force=readSetting(FORCE);
        boolean owned=state.ownsZero()&&"0".equals(force);
        String visible=ExternalDisplayPolicy.LeaseState.visibleDesktopValue(force,owned,state.baseline());
        return "SNAPSHOT\t"+visible+"\t"+readSetting(FREEFORM);
    }

    private String applySettings(String[] args) throws Exception {
        if(args.length!=2||!validSetting(args[0])||!validSetting(args[1]))return "ERROR: invalid settings";
        if(writerBlocked)return "ERROR: settings writer has not quiesced";
        ExternalDisplayPolicy.LeaseState.Action release=state.disable();
        if(release==ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE) {
            boolean restored=restoreOwnedBaseline();
            if(!restored){publish("UNSAFE:restore-pending");return "ERROR: could not restore owned settings";}
            state.disableFinished(true,true);
        }
        if(!clearMarker())return "ERROR: could not clear reconnect policy journal";
        orphanRecoveryPulsePending=false;
        recoveredPointerOnly=false;
        recoveredPointerComplete=false;
        String failure=null;
        try {
            SettingTransaction.apply(new SettingTransaction.Store() {
                @Override public String read(String key) throws Exception { return readSetting(key); }
                @Override public void write(String key,String value) throws Exception {
                    CommandResult result=writeSetting(key,value);
                    if(!result.quiesced||!result.ok)throw new java.io.IOException("Could not verify setting " + key);
                }
            },args[0],args[1]);
        } catch(Throwable error) { failure=error.getMessage()==null?"settings update failed":error.getMessage(); }
        if(failure!=null) {
            // SettingTransaction rolls back synchronously. Keep the watcher live and do not mask failure.
            if(ownerActive)resumeAfterSettings();
            return "ERROR: "+failure;
        }
        if(ownerActive)resumeAfterSettings();
        return "OK";
    }

    private void resumeAfterSettings() throws Exception {
        String force=readSetting(FORCE);
        List<Display> current=publicNonDefaultDisplays();
        rememberIdentities(current);
        if(!"1".equals(readSetting(FREEFORM))) {
            ExternalDisplayPolicy.LeaseState.Action release=state.disable();
            if(release==ExternalDisplayPolicy.LeaseState.Action.RESTORE_BASELINE) {
                boolean restored=restoreOwnedBaseline(false);
                ExternalDisplayPolicy.LeaseState.Action finished=state.disableFinished(!writerBlocked,restored);
                if(!restored||finished==ExternalDisplayPolicy.LeaseState.Action.UNSAFE) {
                    publish("UNSAFE:restore-pending");
                    return;
                }
            }
            publish("SAFE:freeform-disabled");
            return;
        }
        ExternalDisplayPolicy.LeaseState.Action action=state.enable(true,true,true,force,current.size());
        if(action==ExternalDisplayPolicy.LeaseState.Action.WRITE_ZERO)armZero();
        else {
            attachRecoveryCandidate(current);
            publish(statusForState());
        }
    }

    private void attachRecoveryCandidate(List<Display> publicDisplays) throws Exception {
        if(!orphanRecoveryPulsePending||state.phase()!=ExternalDisplayPolicy.LeaseState.Phase.WATCHING
                ||publicDisplays.size()!=1||!isType2Physical(publicDisplays.get(0)))return;
        Display display=publicDisplays.get(0);
        String identity=uniqueId(display);
        state.recoveredTarget(display.getDisplayId(),identity);
        candidateDisplayId=display.getDisplayId();
        candidateEpoch=displayEpoch(candidateDisplayId);
        candidateIdentity=identity;
        recoveredPointerOnly=true;
        recoveredPointerComplete=false;
        observedIdentities.put(candidateDisplayId,identity);
    }

    private boolean restoreOwnedBaseline() throws Exception {
        return restoreOwnedBaseline(false);
    }

    private boolean restoreOwnedBaseline(boolean retainPulseJournal) throws Exception {
        lastBaselineReadback=false;
        if(writerBlocked)return false;
        String baseline=state.baseline();
        if(baseline==null)return true;
        if(retainPulseJournal&&!writeMarker(baseline,JOURNAL_PULSE_PENDING))return false;
        String current=readSetting(FORCE);
        if("0".equals(current)) {
            CommandResult write=writeSetting(FORCE,baseline);
            if(!write.quiesced||!write.ok){if(!write.quiesced)writerBlocked=true;return false;}
            current=readSetting(FORCE);
        }
        if(!baseline.equals(current)) {
            // Another writer changed the setting away from our temporary zero. Preserve it.
            if(!"0".equals(current)) {
                clearMarker();
                lastBaselineReadback=false;
                return true;
            }
            lastBaselineReadback=false;
            return false;
        }
        lastBaselineReadback=retainPulseJournal||clearMarker();
        return lastBaselineReadback;
    }

    private void handleSelectedPulse() throws Exception {
        ExternalDisplayPolicy.LeaseState.Action action=state.select(selectedDisplayId);
        if(action==ExternalDisplayPolicy.LeaseState.Action.PULSE_ONCE)runSelectedPulse();
    }

    private void runSelectedPulse() throws Exception {
        int id=candidateDisplayId;
        String identity=candidateIdentity;
        long epoch=candidateEpoch;
        boolean recoveryPulse=recoveredPointerOnly;
        if(id<=0||identity==null||epoch<0||state.candidateDisplayId()!=id
                ||requestedSelection.get()!=id||selectedDisplayId!=id||!sameCandidate(id,identity,epoch)) {
            state.pulseFinished(false,false,false);publish(statusForState());return;
        }
        boolean navAvailable=false,navFalse=false;
        try{navFalse=!nativeHasNavigationBar(id);navAvailable=true;}catch(Throwable ignored){}
        SizeSnapshot original=null;
        boolean pulseApplied=false;
        boolean attemptedMutation=false;
        boolean geometryRestored=true;
        try {
            original=readSizeSnapshot(id,identity,epoch);
            if(original!=null&&requestedSelection.get()==id&&sameCandidate(id,identity,epoch)) {
                int width=original.width-1;
                if(width>=320) {
                    attemptedMutation=true;
                    CommandResult temporary=setSize(id,identity,epoch,width+"x"+original.height,true);
                    if(!temporary.quiesced)writerBlocked=true;
                    pulseApplied=temporary.quiesced&&temporary.ok;
                    if(pulseApplied)SystemClock.sleep(400);
                }
            }
        } finally {
            if(original!=null&&attemptedMutation) {
                String restore=original.override==null?"reset":original.override;
                CommandResult restored=setSize(id,identity,epoch,restore,false);
                if(!restored.quiesced||!restored.ok) {
                    if(!restored.quiesced)writerBlocked=true;
                    geometryRestored=false;
                }
            }
        }
        if(!geometryRestored) {
            state.pulseFinished(false,recoveryPulse?false:navAvailable,recoveryPulse?false:navFalse);
            publish("UNSAFE:geometry-restore");
            return;
        }
        boolean navAfterAvailable=false,navAfterFalse=false;
        try{navAfterFalse=!nativeHasNavigationBar(id);navAfterAvailable=true;}catch(Throwable ignored){}
        if(pulseApplied) {
            clearMarker();
            orphanRecoveryPulsePending=false;
            if(recoveryPulse)recoveredPointerComplete=true;
        }
        state.pulseFinished(pulseApplied,recoveryPulse?false:navAfterAvailable,recoveryPulse?false:navAfterFalse);
        publish(statusForState());
    }

    private static final class SizeSnapshot {
        final int width,height;
        final String override;
        SizeSnapshot(int width,int height,String override){this.width=width;this.height=height;this.override=override;}
    }

    private SizeSnapshot readSizeSnapshot(int id,String identity,long epoch) throws Exception {
        if(!sameCandidate(id,identity,epoch))return null;
        String output=run("/system/bin/wm","size","-d",Integer.toString(id));
        Matcher matcher=SIZE_LINE.matcher(output);
        int physicalW=-1,physicalH=-1,overrideW=-1,overrideH=-1;
        while(matcher.find()) {
            int w=Integer.parseInt(matcher.group(2)),h=Integer.parseInt(matcher.group(3));
            if("Physical".equals(matcher.group(1))){physicalW=w;physicalH=h;}
            else {overrideW=w;overrideH=h;}
        }
        if(physicalW<=0||physicalH<=0)return null;
        if((overrideW>0)!=(overrideH>0))return null;
        return overrideW>0?new SizeSnapshot(overrideW,overrideH,overrideW+"x"+overrideH)
                :new SizeSnapshot(physicalW,physicalH,null);
    }

    private CommandResult setSize(int id,String identity,long epoch,String size,boolean requireSelection) throws Exception {
        if(writerBlocked)return new CommandResult(false,false);
        if(!(requireSelection?sameCandidate(id,identity,epoch):sameDisplayGeneration(id,identity,epoch)))return new CommandResult(true,false);
        List<String> args=new ArrayList<>();args.add("/system/bin/wm");args.add("size");args.add(size);args.add("-d");args.add(Integer.toString(id));
        CommandResult result=runResult(args.toArray(new String[0]));
        if(!result.quiesced||!result.ok)return result;
        if(!(requireSelection?sameCandidate(id,identity,epoch):sameDisplayGeneration(id,identity,epoch)))return new CommandResult(true,false);
        String verified=run("/system/bin/wm","size","-d",Integer.toString(id));
        if("reset".equals(size))return new CommandResult(true,!verified.contains("Override size:"));
        return new CommandResult(true,verified.contains("Override size: "+size));
    }

    private boolean sameCandidate(int id,String identity,long epoch) throws Exception {
        return requestedSelection.get()==id&&selectedDisplayId==id&&sameLiveGeneration(id,identity,epoch);
    }

    private boolean sameLiveGeneration(int id,String identity,long epoch) throws Exception {
        List<Display> all=publicNonDefaultDisplays();
        return all.size()==1&&sameDisplayGeneration(id,identity,epoch);
    }

    private boolean sameDisplayGeneration(int id,String identity,long epoch) throws Exception {
        if(id<=0||identity==null)return false;
        if(displayEpoch(id)!=epoch)return false;
        Display current=displayForId(id);
        return current!=null&&isType2Physical(current)&&identity.equals(uniqueId(current))
                &&(current.getFlags()&Display.FLAG_PRIVATE)==0;
    }

    private boolean nativeHasNavigationBar(int displayId) throws Exception {
        Method method=findMethod(windowManager.getClass(),"hasNavigationBar",int.class);
        if(method==null)throw new NoSuchMethodException("hasNavigationBar");
        method.setAccessible(true);
        Object value=method.invoke(windowManager,displayId);
        if(!(value instanceof Boolean))throw new IllegalStateException("Unexpected nav result");
        return (Boolean)value;
    }

    private boolean recoverOrphan() throws Exception {
        File marker=new File(MARKER_PATH);
        if(!marker.exists()) {
            File temp=new File(MARKER_PATH+".tmp");
            if(temp.exists())temp.delete();
            return true;
        }
        Journal journal;
        try(FileInputStream input=new FileInputStream(marker)) {
            byte[] bytes=input.readNBytes(96);
            if(bytes.length==96&&input.read()!=-1)return false;
            String value=new String(bytes,StandardCharsets.US_ASCII);
            if(value.matches("v1\\n1\\n")) journal=new Journal("1",JOURNAL_ARMED);
            else {
                String[] fields=value.split("\\n",-1);
                if(fields.length!=4||!"v2".equals(fields[0])||!"1".equals(fields[1])
                        ||!(JOURNAL_ARMING.equals(fields[2])||JOURNAL_ARMED.equals(fields[2])
                        ||JOURNAL_PULSE_PENDING.equals(fields[2]))||!fields[3].isEmpty())return false;
                journal=new Journal(fields[1],fields[2]);
            }
        }
        String live=readSetting(FORCE);
        if("0".equals(live)) {
            // Persist the recovery intent before the settings write. If this process dies
            // immediately afterward, the next guardian can still finish the repair.
            if(!writeMarker(journal.baseline,JOURNAL_PULSE_PENDING))return false;
            CommandResult restored=writeSetting(FORCE,journal.baseline);
            if(!restored.quiesced||!restored.ok)return false;
            live=readSetting(FORCE);
            if(!journal.baseline.equals(live))return false;
            orphanRecoveryPulsePending=true;
            pointerJournalPending=true;
            return true;
        }
        if(!journal.baseline.equals(live))return clearMarker();
        if(JOURNAL_ARMED.equals(journal.phase)||JOURNAL_PULSE_PENDING.equals(journal.phase)) {
            orphanRecoveryPulsePending=true;
            pointerJournalPending=true;
            return writeMarker(journal.baseline,JOURNAL_PULSE_PENDING);
        }
        // An ARMING record with force=1 means the zero write never took effect.
        return clearMarker();
    }

    private boolean writeMarker(String baseline,String phase) throws Exception {
        if(!"1".equals(baseline))throw new IllegalArgumentException("Invalid baseline");
        if(!(JOURNAL_ARMING.equals(phase)||JOURNAL_ARMED.equals(phase)||JOURNAL_PULSE_PENDING.equals(phase)))
            throw new IllegalArgumentException("Invalid journal phase");
        File target=new File(MARKER_PATH);
        File temp=new File(MARKER_PATH+".tmp");
        byte[] bytes=("v2\n"+baseline+"\n"+phase+"\n").getBytes(StandardCharsets.US_ASCII);
        try(FileOutputStream output=new FileOutputStream(temp,false)){output.write(bytes);output.getFD().sync();}
        temp.setReadable(false,false);temp.setWritable(false,false);temp.setReadable(true,true);temp.setWritable(true,true);
        java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        target.setReadable(false,false);target.setWritable(false,false);target.setReadable(true,true);target.setWritable(true,true);
        pointerJournalPending=JOURNAL_PULSE_PENDING.equals(phase);
        return true;
    }

    private boolean clearMarker() {
        File marker=new File(MARKER_PATH);
        boolean cleared=!marker.exists()||marker.delete();
        if(cleared)pointerJournalPending=false;
        return cleared;
    }

    private void rememberIdentities(List<Display> list) {
        for(Display display:list)try{observedIdentities.put(display.getDisplayId(),uniqueId(display));}catch(Throwable ignored){}
    }

    private List<Display> publicNonDefaultDisplays() {
        List<Display> result=new ArrayList<>();
        for(Display display:displays.getDisplays()) {
            if(display!=null&&display.isValid()&&display.getDisplayId()>0
                    &&(display.getFlags()&Display.FLAG_PRIVATE)==0)result.add(display);
        }
        return result;
    }

    private Display displayForId(int id) {
        Display display=displays.getDisplay(id);
        return display!=null&&display.isValid()&&display.getDisplayId()>0
                &&(display.getFlags()&Display.FLAG_PRIVATE)==0?display:null;
    }

    private boolean isType2Physical(Display display) {
        try{return display!=null&&displayType(display)==TYPE_EXTERNAL&&(display.getFlags()&Display.FLAG_PRIVATE)==0;}
        catch(Throwable ignored){return false;}
    }

    private static String uniqueId(Display display) throws Exception {
        Method method=findMethod(display.getClass(),"getUniqueId");
        if(method==null)throw new NoSuchMethodException("getUniqueId");
        method.setAccessible(true);Object value=method.invoke(display);
        if(!(value instanceof String)||((String)value).isEmpty())throw new IllegalStateException("Missing display identity");
        return (String)value;
    }

    private static int displayType(Display display) throws Exception {
        Method method=findMethod(display.getClass(),"getType");
        if(method==null)throw new NoSuchMethodException("getType");
        method.setAccessible(true);Object value=method.invoke(display);
        if(!(value instanceof Number))throw new IllegalStateException("Missing display type");
        return ((Number)value).intValue();
    }

    private String readSetting(String key) throws Exception {
        String value=run("/system/bin/cmd","settings","get","global",key).trim();
        if(!validSetting(value))throw new IllegalStateException("Unexpected global setting");
        return value;
    }

    private CommandResult writeSetting(String key,String value) throws Exception {
        if(!validSetting(value))return new CommandResult(true,false);
        if(writerBlocked)return new CommandResult(false,false);
        String[] args="null".equals(value)
                ?new String[]{"/system/bin/cmd","settings","delete","global",key}
                :new String[]{"/system/bin/cmd","settings","put","global",key,value};
        CommandResult result=runResult(args);
        if(!result.quiesced){writerBlocked=true;return result;}
        if(!result.ok)return result;
        return new CommandResult(true,value.equals(readSetting(key)));
    }

    private static boolean validSetting(String value) { return "0".equals(value)||"1".equals(value)||"null".equals(value); }

    private static final class CommandResult {
        final boolean quiesced,ok;
        CommandResult(boolean quiesced,boolean ok){this.quiesced=quiesced;this.ok=ok;}
    }

    private static CommandResult runResult(String... args) throws Exception {
        java.lang.Process process=new ProcessBuilder(args).redirectInput(new File("/dev/null")).redirectErrorStream(true).start();
        boolean quiesced;
        try { quiesced=process.waitFor(COMMAND_TIMEOUT_MS,TimeUnit.MILLISECONDS); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();quiesced=false;}
        if(!quiesced) {
            process.destroyForcibly();
            try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            // Killing the client process does not prove the Settings/WMS Binder call ended.
            return new CommandResult(false,false);
        }
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(java.io.InputStream input=process.getInputStream()) { input.transferTo(output); }
        String text=output.toString(StandardCharsets.UTF_8.name());
        boolean ok=process.exitValue()==0&&!text.contains("Error:")&&!text.contains("Exception");
        return new CommandResult(true,ok);
    }

    private static String run(String... args) throws Exception {
        java.lang.Process process=new ProcessBuilder(args).redirectInput(new File("/dev/null")).redirectErrorStream(true).start();
        boolean done;
        try{done=process.waitFor(COMMAND_TIMEOUT_MS,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();done=false;}
        if(!done){process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}throw new java.io.IOException("Command timed out");}
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(java.io.InputStream input=process.getInputStream()){input.transferTo(output);}
        String result=output.toString(StandardCharsets.UTF_8.name()).trim();
        if(process.exitValue()!=0||result.contains("Error:")||result.contains("Exception"))throw new java.io.IOException("Command rejected");
        return result;
    }

    private static Context createSystemContext() throws Exception {
        Class<?> threadClass=Class.forName("android.app.ActivityThread");
        Object current=null;
        try{current=invokeStatic(threadClass,"currentActivityThread");}catch(NoSuchMethodException ignored){}
        if(current!=null)try{Object value=invokeNoArgs(current,"getSystemContext");if(value instanceof Context)return (Context)value;}catch(NoSuchMethodException ignored){}
        if(Looper.myLooper()==null)Looper.prepare();
        Constructor<?> constructor=threadClass.getDeclaredConstructor();constructor.setAccessible(true);
        Object unattached=constructor.newInstance();
        Class<?> contextImpl=Class.forName("android.app.ContextImpl");
        Method create=contextImpl.getDeclaredMethod("createSystemContext",threadClass);create.setAccessible(true);
        Object value=create.invoke(null,unattached);
        if(!(value instanceof Context))throw new IllegalStateException("System context unavailable");
        return (Context)value;
    }

    // This entry point runs only as shell UID in app_process, not the app's restricted VM.
    // The exact API was verified on the SDK 34 SOG06; failure leaves normal routing available.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private static void startBinderPool() {
        Thread thread=new Thread(()->{
            try{Class<?> internal=Class.forName("com.android.internal.os.BinderInternal");Method join=internal.getDeclaredMethod("joinThreadPool");join.setAccessible(true);join.invoke(null);}
            catch(Throwable ignored){}
        },"external-display-policy-binder-pool");
        thread.setDaemon(true);thread.start();
    }

    private static Object invokeStatic(Class<?> type,String name) throws Exception {
        Method method=type.getDeclaredMethod(name);method.setAccessible(true);return method.invoke(null);
    }

    private static Object invokeNoArgs(Object receiver,String name) throws Exception {
        Method method=findMethod(receiver.getClass(),name);
        if(method==null)throw new NoSuchMethodException(name);
        method.setAccessible(true);return method.invoke(receiver);
    }

    private static Method findMethod(Class<?> type,String name,Class<?>... parameterTypes) {
        try{return type.getMethod(name,parameterTypes);}catch(NoSuchMethodException ignored){}
        for(Class<?> current=type;current!=null;current=current.getSuperclass())try{return current.getDeclaredMethod(name,parameterTypes);}catch(NoSuchMethodException ignored){}
        return null;
    }

    private void publish(String next) {
        if(next==null||next.isEmpty())next="UNSAFE:unknown";
        status=next;
        emit("EVENT",next);
    }

    private void publishClearSelection(String next) {
        if(next==null||next.isEmpty())next="UNSAFE:unknown";
        status=next;
        synchronized(outputLock){System.out.println("EVENT\t"+next+"\tCLEAR_SELECTION");System.out.flush();}
    }

    private String statusForState() {
        switch(state.phase()) {
            case ARMING: return "UNSAFE:arming";
            case RESTORING: return "UNSAFE:restoring";
            case RESTORE_PENDING: return "UNSAFE:restore-pending";
            case ARMED: return "SAFE:armed";
            case WATCHING: return "SAFE:watching";
            case RESTORED_WAITING_SELECTION: return "SAFE:awaiting-selection";
            case PULSE_PENDING: return "UNSAFE:pulse-pending";
            case READY: return recoveredPointerComplete?"SAFE:recovered-pointer-only"
                    :(state.navSuppressed()?"SAFE:ready-nav-suppressed":"SAFE:ready-nav-visible");
            case FALLBACK: return "SAFE:fallback";
            case INACTIVE: return "SAFE:inactive";
            default: return "UNSAFE:unknown";
        }
    }

    private void emit(String type,String value) {
        synchronized(outputLock){System.out.println(type+"\t"+value);System.out.flush();}
    }

    private void reply(long id,String payload) {
        synchronized(outputLock){System.out.println("RESPONSE\t"+id+"\t"+payload);System.out.flush();}
    }
}
