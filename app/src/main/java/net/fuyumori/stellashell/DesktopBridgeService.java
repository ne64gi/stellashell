package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs under Shizuku. Exposes only bounded settings and external app-launch operations. */
public final class DesktopBridgeService extends IDesktopBridge.Stub {
    private final Context context;
    private static final String DESKTOP = "force_desktop_mode_on_external_displays";
    private static final String FREEFORM = "enable_freeform_support";
    public DesktopBridgeService() { context = null; }
    public DesktopBridgeService(Context context) { this.context = context; }
    @Override public synchronized void destroy() { releaseWindowPins();if(primaryScreen!=null)primaryScreen.release();if(mouseRouting!=null)mouseRouting.release();if(virtualKeyboard!=null)virtualKeyboard.release();System.exit(0); }
    private static String exec(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean keepTail=args.length>0&&"/system/bin/logcat".equals(args[0]);
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = new byte[1024]; int n;
                while ((n = input.read(bytes)) != -1) {
                    synchronized (output) {
                        if(keepTail&&output.size()+n>16384){byte[] previous=output.toByteArray();int keep=16384-n;output.reset();output.write(previous,previous.length-keep,keep);}
                        if(output.size()<16384)output.write(bytes,0,Math.min(n,16384-output.size()));
                    }
                }
            } catch (IOException ignored) {}
        }, "desktop-command-output");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("Operation timed out");
            reader.join(1000);
            String result;
            synchronized (output) { result = output.toString(StandardCharsets.UTF_8.name()).trim(); }
            if (process.exitValue() != 0 || result.contains("Error:") || result.contains("Exception"))
                throw new IOException(result.isEmpty() ? "Operation was rejected" : result);
            return result;
        } finally { process.destroy(); }
    }
    private DisplaySessions displaySessions;
    private DisplaySessions displays(){if(displaySessions==null)displaySessions=new DisplaySessions(context,DesktopBridgeService::exec);return displaySessions;}
    @Override public synchronized String displaySessions() {
        try{return displays().snapshot();}
        catch(Exception e){return "ERROR: "+e.getMessage();}
    }
    @Override public synchronized String closeDisplaySession(int id,String identity) {
        try{return displays().close(id,identity);}
        catch(Exception e){return "ERROR: "+e.getMessage();}
    }
    private static String read(String key) throws Exception {
        String value = exec("/system/bin/settings", "get", "global", key); Policy.setting(value); return value;
    }
    private static void write(String key, String value) throws Exception {
        Policy.setting(value);
        if ("null".equals(value)) exec("/system/bin/settings", "delete", "global", key);
        else exec("/system/bin/settings", "put", "global", key, value);
        if (!read(key).equals(value)) throw new IOException("Could not verify settings: " + key);
    }
    @Override public synchronized String settingsSnapshot() {
        try { return read(DESKTOP) + "," + read(FREEFORM); }
        catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }
    @Override public synchronized String applySettings(String desktop, String freeform) {
        try {
            SettingTransaction.apply(new SettingTransaction.Store() {
                public String read(String key) throws Exception { return DesktopBridgeService.read(key); }
                public void write(String key,String value) throws Exception { DesktopBridgeService.write(key,value); }
            }, desktop, freeform);
            return "OK";
        } catch (Exception e) {
            String rollback = e.getSuppressed().length == 0 ? "" : " / Some settings could not be restored. Use Restore to retry.";
            return "ERROR: " + e.getMessage() + rollback;
        }
    }

    @Override public synchronized String launch(String component, int displayId, int mode) {
        try {
            String[] command = Policy.launchCommand(component, displayId, mode,primaryMode);
            if (context == null) throw new IllegalStateException("Shizuku API 13 or later is required");
            Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
            if (display == null || !display.isValid() || (display.getFlags() & Display.FLAG_PRIVATE) != 0)
                throw new IllegalStateException("The external display is disconnected");
            if (mode == 5 && !"1".equals(read(FREEFORM)))
                throw new IllegalStateException("Enable desktop features first");
            return "OK: " + exec(command);
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }
    @Override public synchronized String back(int displayId){
        try{
            if(context==null)throw new IllegalStateException("Shizuku context unavailable");
            return "OK: "+exec(Policy.backCommand(displayId,Displays.allIds(context),primaryMode));
        }catch(Exception e){return "ERROR: "+e.getMessage();}
    }
    private boolean primaryMode;
    @Override public synchronized void setPrimaryMode(boolean enabled){ primaryMode=enabled; }
    private TaskBackend taskBackend;
    private TaskBackend tasks() throws Exception {
        if(context==null)throw new IllegalStateException("Shizuku context unavailable");
        if(taskBackend==null)taskBackend=new TaskBackend(context);
        taskBackend.primaryMode=primaryMode;
        return taskBackend;
    }
    @Override public synchronized String taskSnapshot(int displayId) {
        try{return tasks().snapshot(displayId);}catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
    }
    @Override public synchronized String phoneTaskSnapshot() {
        try{return tasks().phoneSnapshot();}catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
    }
    @Override public synchronized String focusPhoneTask(int taskId,String component) {
        try{return tasks().focusPhoneTask(taskId,component);}catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
    }
    @Override public synchronized String phoneTaskOperation(int taskId,String component,String action,int l,int t,int r,int b) {
        try{
            if("float".equals(action)&&!"1".equals(read(FREEFORM)))throw new IllegalStateException("Enable desktop features first");
            return tasks().operatePhoneTask(taskId,component,action,l,t,r,b);
        }catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
    }
    @Override public synchronized void setWorkArea(int displayId,int l,int t,int r,int b) {
        try{tasks().setWorkArea(displayId,new android.graphics.Rect(l,t,r,b));}catch(Exception e){throw new IllegalArgumentException(e);}
    }
    @Override public synchronized String taskOperation(int displayId,int taskId,String action,int l,int t,int r,int b) {
        try{return tasks().operate(displayId,taskId,action,l,t,r,b);}catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
    }
    @Override public synchronized String launchProfile(String component,String resolved,int displayId,int mode,int l,int t,int r,int b,boolean newWindow){
        try{return tasks().launchProfile(component,resolved,displayId,mode,l,t,r,b,newWindow);}catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
    }

    @Override public synchronized String moveWorkspaceTask(int source,int destination,int taskId,String component){
        try{return tasks().moveWorkspaceTask(source,destination,taskId,component);}catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
    }
    private android.os.IBinder pinOwner;
    private android.os.IBinder.DeathRecipient pinDeath;
    private String releaseWindowPins(){
        try{if(taskBackend!=null)taskBackend.releasePins();}
        catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
        if(pinOwner!=null&&pinDeath!=null)pinOwner.unlinkToDeath(pinDeath,0);
        pinOwner=null;pinDeath=null;return "inactive";
    }
    @Override public synchronized String syncWindowPins(boolean enabled,android.os.IBinder owner){
        if(!enabled||owner==null||!owner.isBinderAlive())return releaseWindowPins();
        if(owner.equals(pinOwner))return "active";
        String released=releaseWindowPins();if(released.startsWith("ERROR:"))return released;
        pinOwner=owner;android.os.IBinder lease=owner;
        pinDeath=()->{synchronized(DesktopBridgeService.this){if(lease.equals(pinOwner))releaseWindowPins();}};
        try{owner.linkToDeath(pinDeath,0);}catch(android.os.RemoteException e){return releaseWindowPins();}
        return "active";
    }
    private MouseRouting mouseRouting;
    @Override public synchronized String syncMouseRouting(int displayId,android.os.IBinder owner){
        if(context==null)return "unavailable: Shizuku context";
        if(mouseRouting==null)mouseRouting=new MouseRouting(context);
        return mouseRouting.sync(displayId,owner);
    }
    private VirtualKeyboardPolicy virtualKeyboard;
    @Override public synchronized String syncVirtualKeyboard(int displayId,boolean hide,android.os.IBinder owner){
        if(context==null)return "unavailable: Shizuku context";
        if(virtualKeyboard==null)virtualKeyboard=new VirtualKeyboardPolicy(context);
        return virtualKeyboard.sync(displayId,hide,owner);
    }
    @Override public synchronized String captureDisplay(int displayId,android.os.ParcelFileDescriptor output){
        try(android.os.ParcelFileDescriptor owned=output){
            if(context==null||output==null)throw new IllegalStateException("Capture unavailable");
            if(displayId<0||(displayId==0&&!primaryMode))throw new IllegalArgumentException("Invalid desktop display");
            if(context.getSystemService(DisplayManager.class).getDisplay(displayId)==null)throw new IllegalStateException("Display disconnected");
            if(context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("Unlock the device first");
            return DesktopCapture.write(displayId,output);
        }catch(Exception e){return "ERROR: "+TaskBackend.reason(e);}
    }
    private PrimaryScreenPower primaryScreen;
    @Override public synchronized String syncPrimaryScreen(int displayId,boolean off,android.os.IBinder owner){
        if(context==null)return "ERROR: Service context unavailable";
        if(primaryScreen==null)primaryScreen=new PrimaryScreenPower(context);
        // primaryMode authorizes display 0 operations; it does not mean the workspace
        // is currently on display 0. PrimaryScreenPower validates the external lease.
        return primaryScreen.sync(displayId,off,owner);
    }
}
