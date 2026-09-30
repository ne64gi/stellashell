package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.util.*;

final class Displays {
    static List<Display> available(Context context) {
        List<Display> out = new ArrayList<>();
        // scrcpy --new-display is public but need not advertise PRESENTATION.
        // Ordinary scrcpy capture displays are private and remain excluded.
        for (Display d : context.getSystemService(DisplayManager.class).getDisplays())
            if (d.getDisplayId() > 0 && d.isValid() && (d.getFlags() & Display.FLAG_PRIVATE) == 0) out.add(d);
        out.sort(Comparator.comparingInt(Display::getDisplayId)); return out;
    }
    static List<Integer> ids(Context context) {
        List<Integer> ids=new ArrayList<>(); for(Display d:available(context)) ids.add(d.getDisplayId()); return ids;
    }
    static boolean primary(Context context) { return Launches.prefs(context).getBoolean("primary_mode",false); }
    static boolean primaryActive(Context context) { return primary(context) && Launches.prefs(context).getBoolean("enabled",false); }
    static List<Integer> allIds(Context context) {
        List<Integer> out=ids(context);
        Display main=context.getSystemService(DisplayManager.class).getDisplay(0);
        if(main!=null && main.isValid() && (main.getFlags()&Display.FLAG_PRIVATE)==0)out.add(0);
        return out;
    }
    static int target(Context context,int preferred) { return Workspace.enabled(context)?(allIds(context).contains(Workspace.target(context))?Workspace.target(context):0):Policy.selectDisplay(preferred,allIds(context),primary(context)); }
    static Display require(Context context,int id) {
        if(primary(context) && !Workspace.enabled(context) && id!=0)throw new IllegalArgumentException("Primary display mode is selected");
        Policy.requireTarget(id,allIds(context),primaryActive(context));
        Display d=context.getSystemService(DisplayManager.class).getDisplay(id);
        if(d==null || !d.isValid()) throw new IllegalArgumentException(context.getString(R.string.ui_the_external_display_is_disconnected));
        return d;
    }
}
