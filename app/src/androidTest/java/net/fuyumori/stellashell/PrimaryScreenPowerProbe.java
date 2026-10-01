package net.fuyumori.stellashell;

import android.content.Context;
import android.os.Binder;
import android.os.Looper;

/** Shell UID fixture: always restores the primary panel before exiting. */
public final class PrimaryScreenPowerProbe {
    private static void expect(String expected,String actual){
        if(!expected.equals(actual))throw new AssertionError("expected "+expected+", got "+actual);
    }
    public static void main(String[] args)throws Exception {
        Looper.prepareMainLooper();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        DesktopBridgeService bridge=new DesktopBridgeService(context);
        Binder owner=new Binder();
        int external=Integer.parseInt(args[0]);
        try {
            bridge.setPrimaryMode(true);
            String rejected=bridge.syncPrimaryScreen(0,true,owner);
            if(!rejected.startsWith("ERROR:"))throw new AssertionError("primary display accepted: "+rejected);
            rejected=bridge.syncPrimaryScreen(Integer.MAX_VALUE,true,owner);
            if(!rejected.startsWith("ERROR:"))throw new AssertionError("missing display accepted: "+rejected);
            for(boolean primary:new boolean[]{true,false}) {
                bridge.setPrimaryMode(primary);
                expect("off",bridge.syncPrimaryScreen(external,true,owner));
                expect("off",bridge.syncPrimaryScreen(external,true,owner));
                Thread.sleep(500);
                expect("on",bridge.syncPrimaryScreen(external,false,owner));
            }
            System.out.println("PASS: external off/on with primary permission enabled and disabled; primary/missing display rejected");
        } finally {
            expect("on",bridge.syncPrimaryScreen(-1,false,owner));
        }
        System.exit(0);
    }
}
