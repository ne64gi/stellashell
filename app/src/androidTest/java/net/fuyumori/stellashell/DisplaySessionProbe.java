package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import org.json.*;

/** Run as shell. Only closes the explicitly supplied, disposable test display. */
public final class DisplaySessionProbe {
    public static void main(String[] args)throws Exception{
        Looper.prepareMainLooper();Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        DesktopBridgeService service=new DesktopBridgeService(context);
        String snapshot=service.displaySessions();System.out.println(snapshot);
        if(snapshot.startsWith("ERROR:"))throw new AssertionError(snapshot);
        if(!service.closeDisplaySession(0,"invalid").startsWith("ERROR:"))throw new AssertionError("Device display guard");
        if(args.length>0){
            int id=Integer.parseInt(args[0]);JSONArray rows=new JSONArray(snapshot);String token="";
            for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("id")==id)token=rows.getJSONObject(i).getString("token");
            if(token.isEmpty())throw new AssertionError("Test display is not closable");
            if(!service.closeDisplaySession(id,token+"stale").startsWith("ERROR:"))throw new AssertionError("Stale identity guard");
            String closed=service.closeDisplaySession(id,token);if(!closed.equals("OK"))throw new AssertionError(closed);
            DisplayManager dm=context.getSystemService(DisplayManager.class);
            if(dm.getDisplay(id)!=null)throw new AssertionError("Closed display remains");
            for(int i=0;i<rows.length();i++){int other=rows.getJSONObject(i).getInt("id");if(other!=id&&dm.getDisplay(other)==null)throw new AssertionError("Unrelated display removed: "+other);}
            if(!service.closeDisplaySession(id,token).startsWith("ERROR:"))throw new AssertionError("Repeated close guard");
            System.out.println("PASS: selected display removed; other displays preserved; stale, repeated and primary close rejected");
        }
        System.exit(0);
    }
}
