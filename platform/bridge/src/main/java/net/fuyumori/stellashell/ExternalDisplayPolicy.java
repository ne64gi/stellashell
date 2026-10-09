package net.fuyumori.stellashell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the temporary Sony SDK 34 external-display reconnect lease. The independent
 * shell-UID guardian is the sole writer while a lease is active, so parent EOF cannot
 * race an in-flight force-desktop write.
 */
public final class ExternalDisplayPolicy implements AutoCloseable {
    private static final String PACKAGE = "net.fuyumori.stellashell";
    private static final long RPC_TIMEOUT_SECONDS = 12;

    private final Context context;
    private volatile Process guardian;
    private PrintWriter guardianInput;
    private Thread guardianOutputThread;
    private Thread guardianErrorThread;
    private final AtomicLong nextRequest = new AtomicLong(1);
    private static final class Response {
        final Process process;
        final String command;
        final CompletableFuture<String> future=new CompletableFuture<>();
        Response(Process process,String command){this.process=process;this.command=command;}
    }
    private final Map<Long, Response> responses = new ConcurrentHashMap<>();
    private final Object inputLock = new Object();
    private IBinder owner;
    private IBinder.DeathRecipient ownerDeath;
    private volatile String cachedStatus = "SAFE:inactive";
    private volatile int selectedDisplayId = Integer.MIN_VALUE;
    private volatile CompletableFuture<String> readySignal;
    private volatile boolean blockedAfterGuardianLoss;
    private volatile boolean closing;
    private volatile Process restorationConfirmed;
    private volatile Process inputClosed;

    public ExternalDisplayPolicy(Context context) {
        this.context = context;
    }

    /** Called only on regular bridge maintenance; same-owner calls are cache-only. */
    public synchronized String sync(boolean enabled, IBinder client) {
        if (!applicable()) {
            if (guardian == null&&!blockedAfterGuardianLoss) return "SAFE:unsupported";
            return stopGuardian("SAFE:unsupported");
        }
        if (!enabled || client == null || !client.isBinderAlive()) {
            return stopGuardian("SAFE:inactive");
        }
        if (owner != null && !owner.equals(client)) {
            String stopped = stopGuardian("SAFE:inactive");
            if (stopped.startsWith("UNSAFE:")) return stopped;
        }
        if (blockedAfterGuardianLoss) return cachedStatus.startsWith("UNSAFE:") ? cachedStatus : "UNSAFE:guardian-lost";
        if (guardian == null || !guardian.isAlive()) {
            if (guardian != null) {
                cachedStatus = "UNSAFE:guardian-lost";
                blockedAfterGuardianLoss = true;
                clearGuardianHandles(guardian);
                return cachedStatus;
            }
            try {
                startGuardian();
            } catch (Exception failure) {
                if(!cachedStatus.startsWith("UNSAFE:"))cachedStatus = "UNSAFE:guardian-start";
                blockedAfterGuardianLoss=guardian!=null;
                abandonStartingGuardian();
                unlinkOwner();
                return cachedStatus;
            }
            if(!linkOwner(client))return stopGuardian("SAFE:inactive");
            String status = request("ENABLE");
            if (!policyStatus(status)) {
                cachedStatus = "UNSAFE:guardian-enable";
                return cachedStatus;
            }
            return cachedStatus;
        }
        if (owner == null || !owner.equals(client)) {
            if(!linkOwner(client))return stopGuardian("SAFE:inactive");
            String status = request("ENABLE");
            if (!policyStatus(status)) return markUnsafe("guardian-enable");
            return cachedStatus;
        }
        if (!client.isBinderAlive()) return stopGuardian("SAFE:inactive");
        return cachedStatus;
    }

    /**
     * Supplies the selected target from the existing mouse-routing call. A negative
     * result means the caller must perform cleanup-only routing for this turn.
     */
    public synchronized int beforeMouseRouting(int displayId, IBinder client) {
        if (client==null||!client.isBinderAlive())return -1;
        if(blockedAfterGuardianLoss)return -1;
        if (guardian == null || owner == null || !owner.equals(client)) {
            if (cachedStatus!=null&&cachedStatus.startsWith("UNSAFE:")) return -1;
            return displayId;
        }
        if (!guardian.isAlive()) {
            blockedAfterGuardianLoss=true;
            markUnsafe("guardian-lost");return -1;
        }
        if (selectedDisplayId != displayId||!cachedStatus.startsWith("SAFE:")) {
            String status = request("SELECT\t" + displayId);
            if (!policyStatus(status)) {markUnsafe("selection-update");return -1;}
        }
        return cachedStatus.startsWith("SAFE:") ? displayId : -1;
    }

    /** Null means this device does not use the policy and should use its legacy read. */
    public synchronized String settingsSnapshot() {
        if (!applicable()) return null;
        if(!ensureGuardian())return "ERROR: external display policy recovery unavailable";
        String result = request("SNAPSHOT");
        if(result==null)return "ERROR: external display policy guardian unavailable";
        if(result.startsWith("ERROR:"))return result;
        String[] fields=result.split("\\t",-1);
        if(fields.length!=3||!"SNAPSHOT".equals(fields[0]))return "ERROR: external display policy snapshot unavailable";
        try{return checkedSetting(fields[1])+","+checkedSetting(fields[2]);}
        catch(IllegalArgumentException invalid){return "ERROR: external display policy snapshot unavailable";}
    }

    /** Null means this device does not use the policy and should use its legacy write. */
    public synchronized String applySettings(String desktop, String freeform) {
        if (!applicable()) return null;
        if(!ensureGuardian())return "ERROR: external display policy recovery unavailable";
        final String first,second;
        try{first=checkedSetting(desktop);second=checkedSetting(freeform);}
        catch(IllegalArgumentException invalid){return "ERROR: invalid settings value";}
        String result = request("APPLY\t" + first + "\t" + second);
        if(result==null)return "ERROR: external display policy guardian unavailable";
        return "OK".equals(result)||result.startsWith("ERROR:")?result:"ERROR: external display policy settings not applied";
    }

    @Override public synchronized void close() {
        stopGuardian("SAFE:inactive");
    }

    private boolean applicable() {
        return Build.VERSION.SDK_INT == 34 && "SOG06".equals(Build.MODEL);
    }

    private void startGuardian() throws Exception {
        if(guardian!=null&&guardian.isAlive())throw new IOException("Previous guardian still active");
        cachedStatus = "UNSAFE:guardian-starting";
        closing=false;
        selectedDisplayId=Integer.MIN_VALUE;
        restorationConfirmed=null;
        inputClosed=null;
        CompletableFuture<String> ready=new CompletableFuture<>();
        readySignal = ready;
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(PACKAGE, 0);
        if (info.sourceDir == null || info.sourceDir.isEmpty()) throw new IOException("Target APK unavailable");
        ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                ExternalDisplayPolicyGuardian.class.getName(), "guardian");
        builder.environment().put("CLASSPATH", info.sourceDir);
        final Process launched = builder.start();
        guardian = launched;
        guardianInput = new PrintWriter(new OutputStreamWriter(launched.getOutputStream(), StandardCharsets.UTF_8), true);
        guardianOutputThread = new Thread(() -> readGuardianOutput(launched,ready), "external-display-policy-output");
        guardianOutputThread.setDaemon(true);
        guardianOutputThread.start();
        guardianErrorThread = new Thread(() -> drain(launched.getErrorStream()), "external-display-policy-error");
        guardianErrorThread.setDaemon(true);
        guardianErrorThread.start();
        final String startup;
        try { startup=ready.get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IOException("Guardian startup interrupted",interrupted);}
        catch (Exception failure) { throw new IOException("Guardian startup unavailable", failure); }
        if (!startup.startsWith("SAFE:")||!launched.isAlive())throw new IOException("Guardian startup unsafe");
    }

    private boolean ensureGuardian() {
        if(blockedAfterGuardianLoss)return false;
        if(guardian!=null) {
            if(guardian.isAlive())return true;
            blockedAfterGuardianLoss=true;
            cachedStatus="UNSAFE:guardian-lost";
            return false;
        }
        try { startGuardian(); return guardian!=null&&guardian.isAlive(); }
        catch(Exception failure) {
            if(!cachedStatus.startsWith("UNSAFE:"))cachedStatus="UNSAFE:guardian-start";
            blockedAfterGuardianLoss=guardian!=null;
            abandonStartingGuardian();
            return false;
        }
    }

    private void readGuardianOutput(Process source,CompletableFuture<String> ready) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\t", 3);
                if (fields.length >= 2 && "READY".equals(fields[0])) {
                    if(guardian==source)cachedStatus = fields[1];
                    ready.complete(fields[1]);
                } else if (fields.length >= 2 && "EVENT".equals(fields[0])) {
                    if(guardian==source){
                        cachedStatus = fields[1];
                        if("SAFE:inactive".equals(fields[1])&&(closing||inputClosed==source))restorationConfirmed=source;
                        else if(!"SAFE:inactive".equals(fields[1]))restorationConfirmed=null;
                        if(fields.length==3&&"CLEAR_SELECTION".equals(fields[2]))selectedDisplayId=Integer.MIN_VALUE;
                    }
                } else if (fields.length >= 3 && "RESPONSE".equals(fields[0])) {
                    try {
                        long id = Long.parseLong(fields[1]);
                        Response response=responses.get(id);
                        if(response!=null&&response.process==source&&responses.remove(id,response)){
                            if(guardian==source&&policyStatus(fields[2])){
                                // Publish in stream order; a later EVENT must not be overwritten by the RPC waiter.
                                cachedStatus=fields[2];
                                if("STOP".equals(response.command)&&fields[2].startsWith("SAFE:"))restorationConfirmed=source;
                                if(response.command.startsWith("SELECT\t"))selectedDisplayId=Integer.parseInt(response.command.substring(7));
                            }
                            response.future.complete(fields[2]);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {
        } finally {
            ready.completeExceptionally(new IOException("Guardian exited before READY"));
            failResponses(source);
            if (guardian == source && !closing){cachedStatus = "UNSAFE:guardian-lost";blockedAfterGuardianLoss=true;}
        }
    }

    private static void drain(java.io.InputStream input) {
        try (java.io.InputStream stream = input) { byte[] buffer = new byte[512]; while (stream.read(buffer) != -1) {} }
        catch (IOException ignored) {}
    }

    private String request(String command) {
        Process current = guardian;
        PrintWriter output = guardianInput;
        if (current == null || output == null || !current.isAlive()) return null;
        if(!"STOP".equals(command))restorationConfirmed=null;
        long id = nextRequest.getAndIncrement();
        Response response=new Response(current,command);
        responses.put(id,response);
        try {
            synchronized (inputLock) {
                if(guardian!=current||!current.isAlive())throw new IOException("Guardian changed");
                output.println(id + "\t" + command);
                if (output.checkError()) throw new IOException("Guardian input closed");
            }
            return response.future.get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            responses.remove(id,response);
            cachedStatus = "UNSAFE:guardian-rpc";
            return null;
        }
    }

    private String stopGuardian(String inactiveStatus) {
        Process current=guardian;
        if (current == null) {
            unlinkOwner();
            selectedDisplayId = Integer.MIN_VALUE;
            if(restorationConfirmed!=null&&!restorationConfirmed.isAlive())blockedAfterGuardianLoss=false;
            if(blockedAfterGuardianLoss)return cachedStatus.startsWith("UNSAFE:")?cachedStatus:markUnsafe("restore-pending");
            cachedStatus = inactiveStatus;
            return cachedStatus;
        }
        closing = true;
        try{
            String result=current.isAlive()?request("STOP"):null;
            closeInput(current);
            // EOF asks the independent guardian to finish cleanup. Never kill its writer.
            boolean exited=waitBriefly(current,2_000);
            boolean drained=exited&&waitForOutput(guardianOutputThread,2_000);
            boolean restored=restorationConfirmed==current;
            if(drained)clearGuardianHandles(current);
            unlinkOwner();
            selectedDisplayId=Integer.MIN_VALUE;
            if(drained&&restored){blockedAfterGuardianLoss=false;cachedStatus=inactiveStatus;}
            else{
                blockedAfterGuardianLoss=true;
                cachedStatus=restored?"UNSAFE:shutdown-pending":result!=null&&result.startsWith("UNSAFE:")?result:"UNSAFE:restore-pending";
            }
            return cachedStatus;
        }finally{
            closing = false;
        }
    }

    private void failResponses(Process process){
        for(Map.Entry<Long,Response> entry:responses.entrySet()){
            Response response=entry.getValue();
            if(response.process==process&&responses.remove(entry.getKey(),response))
                response.future.completeExceptionally(new IOException("Guardian exited"));
        }
    }

    private void clearGuardianHandles(Process process) {
        if(process==null||guardian!=process||process.isAlive())return;
        failResponses(process);
        CompletableFuture<String> ready=readySignal;
        if(ready!=null)ready.completeExceptionally(new IOException("Guardian exited"));
        guardian = null;
        guardianInput = null;
        selectedDisplayId=Integer.MIN_VALUE;
        guardianOutputThread=null;
        guardianErrorThread=null;
        readySignal=null;
    }

    private void abandonStartingGuardian() {
        Process current=guardian;
        if(current==null)return;
        closing=true;
        try{
            closeInput(current);
            if(waitBriefly(current,2_000)&&waitForOutput(guardianOutputThread,2_000))clearGuardianHandles(current);
            // Retain a still-live child's handle: launching another writer would orphan its cleanup.
        }finally{closing=false;}
    }

    private void closeInput(Process process){
        synchronized(inputLock){
            if(guardian==process){guardianInput=null;inputClosed=process;}
            try{process.getOutputStream().close();}catch(IOException ignored){}
        }
    }

    private static boolean waitBriefly(Process process, long millis) {
        try { return process.waitFor(millis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt();return !process.isAlive(); }
    }

    private static boolean waitForOutput(Thread reader,long millis){
        if(reader==null)return false;
        try{reader.join(millis);return !reader.isAlive();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return !reader.isAlive();}
    }

    private boolean linkOwner(IBinder client) {
        unlinkOwner();
        owner = client;
        IBinder.DeathRecipient death = () -> {
            synchronized (ExternalDisplayPolicy.this) {
                if (owner == client) stopGuardian("SAFE:inactive");
            }
        };
        ownerDeath = death;
        try { client.linkToDeath(death, 0);return client.isBinderAlive(); }
        catch (android.os.RemoteException dead) { return false; }
    }

    private void unlinkOwner() {
        if (owner != null && ownerDeath != null) {
            try { owner.unlinkToDeath(ownerDeath, 0); } catch (RuntimeException ignored) {}
        }
        owner = null;
        ownerDeath = null;
    }

    private String markUnsafe(String reason) {
        cachedStatus = "UNSAFE:" + reason;
        return cachedStatus;
    }

    private static boolean policyStatus(String status){
        return status!=null&&(status.startsWith("SAFE:")||status.startsWith("UNSAFE:"));
    }

    private static String checkedSetting(String value) {
        if ("0".equals(value) || "1".equals(value) || "null".equals(value)) return value;
        throw new IllegalArgumentException("Invalid setting value");
    }

    public static final class LeaseState {
        public enum Phase { INACTIVE, WATCHING, ARMING, ARMED, RESTORING, RESTORED_WAITING_SELECTION, PULSE_PENDING, READY, FALLBACK, RESTORE_PENDING }
        public enum Action { NONE, WRITE_ZERO, RESTORE_BASELINE, PULSE_ONCE, UNSAFE }
        private Phase phase = Phase.INACTIVE;
        private String baseline;
        private String expectedIdentity;
        private String candidateIdentity;
        private int candidateDisplayId = -1;
        private int selectedDisplayId = -1;
        private boolean navSuppressed;
        private boolean ownsZero;
        private boolean candidateEligible;

        public synchronized Action enable(boolean applicable, boolean sessionEnabled, boolean ownerAlive,
                                         String currentForce, int publicNonDefaultCount) {
            if (!applicable || !sessionEnabled || !ownerAlive) return disable();
            if (phase != Phase.INACTIVE && phase != Phase.WATCHING) return Action.NONE;
            if (publicNonDefaultCount > 0 || !"1".equals(currentForce)) {
                baseline = "1".equals(currentForce) ? currentForce : null;
                phase = Phase.WATCHING;
                return Action.NONE;
            }
            baseline = currentForce;
            phase = Phase.ARMING;
            ownsZero = false;
            return Action.WRITE_ZERO;
        }

        public synchronized Action onRemoved(int removedDisplayId, String removedUniqueId, int publicNonDefaultCount) {
            int removedCandidate = candidateDisplayId;
            if (removedUniqueId != null) expectedIdentity = removedUniqueId;
            if (removedUniqueId != null && removedUniqueId.equals(candidateIdentity)) {
                candidateIdentity = null;
                candidateDisplayId = -1;
                candidateEligible = false;
            }
            if (selectedDisplayId == removedDisplayId || selectedDisplayId == removedCandidate
                    || publicNonDefaultCount == 0) selectedDisplayId = -1;
            if (publicNonDefaultCount != 0 || baseline == null || phase == Phase.RESTORE_PENDING) return Action.NONE;
            if (phase == Phase.READY || phase == Phase.FALLBACK || phase == Phase.WATCHING
                    || phase == Phase.RESTORED_WAITING_SELECTION) {
                phase = Phase.ARMING;
                ownsZero = false;
                return Action.WRITE_ZERO;
            }
            return Action.NONE;
        }

        public synchronized Action armFinished(boolean writerQuiesced, boolean readbackZero) {
            if (phase != Phase.ARMING) return Action.NONE;
            if (!writerQuiesced || !readbackZero) { phase = Phase.RESTORE_PENDING; return Action.UNSAFE; }
            ownsZero = true;
            phase = Phase.ARMED;
            return Action.NONE;
        }

        public synchronized void armSkipped(String currentForce) {
            ownsZero = false;
            baseline = "1".equals(currentForce) ? currentForce : null;
            phase = Phase.WATCHING;
        }

        public synchronized Action onAdded(int addedDisplayId, String uniqueId, int publicPhysicalCount,
                                           int publicNonDefaultCount, boolean type2Physical) {
            if (phase != Phase.ARMED || publicNonDefaultCount == 0) return Action.NONE;
            candidateDisplayId = addedDisplayId;
            candidateIdentity = uniqueId;
            candidateEligible = addedDisplayId > 0 && uniqueId != null && !uniqueId.isEmpty()
                    && publicPhysicalCount == 1 && publicNonDefaultCount == 1 && type2Physical;
            phase = Phase.RESTORING;
            return Action.RESTORE_BASELINE;
        }

        public synchronized void recoveredTarget(int displayId,String uniqueId) {
            if(phase!=Phase.WATCHING||displayId<=0||uniqueId==null||uniqueId.isEmpty())return;
            candidateDisplayId=displayId;
            candidateIdentity=uniqueId;
            candidateEligible=true;
            ownsZero=false;
            navSuppressed=false;
            phase=Phase.RESTORED_WAITING_SELECTION;
        }

        public synchronized Action restoreFinished(boolean writerQuiesced, boolean readbackBaseline,
                                                    boolean exactGeneration, boolean nativeHasNavAvailable,
                                                    boolean nativeHasNavFalse) {
            if (phase != Phase.RESTORING) return Action.NONE;
            if (!writerQuiesced || !readbackBaseline) { phase = Phase.RESTORE_PENDING; return Action.UNSAFE; }
            ownsZero = false;
            navSuppressed = nativeHasNavAvailable && nativeHasNavFalse;
            if (candidateEligible) expectedIdentity = candidateIdentity;
            if (!candidateEligible || !exactGeneration) { phase = Phase.FALLBACK; return Action.NONE; }
            if (selectedDisplayId == candidateDisplayId) { phase = Phase.PULSE_PENDING; return Action.PULSE_ONCE; }
            phase = Phase.RESTORED_WAITING_SELECTION;
            return Action.NONE;
        }

        public synchronized Action select(int displayId) {
            selectedDisplayId = displayId > 0 ? displayId : -1;
            if (phase == Phase.PULSE_PENDING && selectedDisplayId != candidateDisplayId) {
                phase = Phase.RESTORED_WAITING_SELECTION;
                return Action.NONE;
            }
            if (phase == Phase.RESTORED_WAITING_SELECTION && selectedDisplayId == candidateDisplayId) {
                phase = Phase.PULSE_PENDING;
                return Action.PULSE_ONCE;
            }
            return Action.NONE;
        }

        public synchronized void pulseFinished(boolean pulseApplied, boolean nativeHasNavAvailable,
                                               boolean nativeHasNavFalse) {
            navSuppressed = nativeHasNavAvailable && nativeHasNavFalse;
            phase = pulseApplied ? Phase.READY : Phase.FALLBACK;
        }

        public synchronized Action disable() {
            if (ownsZero || phase == Phase.ARMING || phase == Phase.ARMED || phase == Phase.RESTORING
                    || phase == Phase.RESTORE_PENDING) {
                phase = Phase.RESTORE_PENDING;
                return Action.RESTORE_BASELINE;
            }
            phase = Phase.INACTIVE;
            baseline = null;
            expectedIdentity = null;
            candidateIdentity = null;
            candidateDisplayId = -1;
            selectedDisplayId = -1;
            candidateEligible = false;
            navSuppressed = false;
            ownsZero = false;
            return Action.NONE;
        }

        public synchronized Action disableFinished(boolean writerQuiesced, boolean readbackBaseline) {
            if (phase != Phase.RESTORE_PENDING) return Action.NONE;
            if (!writerQuiesced || !readbackBaseline) return Action.UNSAFE;
            phase = Phase.INACTIVE;
            baseline = null;
            expectedIdentity = null;
            candidateIdentity = null;
            candidateDisplayId = -1;
            selectedDisplayId = -1;
            candidateEligible = false;
            navSuppressed = false;
            ownsZero = false;
            return Action.NONE;
        }

        public synchronized Phase phase() { return phase; }
        public synchronized boolean navSuppressed() { return navSuppressed; }
        public synchronized int candidateDisplayId() { return candidateDisplayId; }
        public synchronized boolean ownsZero() { return ownsZero; }
        public synchronized String baseline() { return baseline; }

        public static String visibleDesktopValue(String liveValue, boolean ownedTemporaryZero, String capturedBaseline) {
            if (ownedTemporaryZero && "0".equals(liveValue) && capturedBaseline != null) return capturedBaseline;
            return liveValue;
        }
    }
}
