package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.util.*;

/** Shell-side public display inventory; never reads app preferences or runtime selection. */
final class BridgeDisplayTargets {
    private BridgeDisplayTargets(){}
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
    static List<Integer> allIds(Context context) {
        List<Integer> out=ids(context);
        Display main=context.getSystemService(DisplayManager.class).getDisplay(0);
        if(main!=null && main.isValid() && (main.getFlags()&Display.FLAG_PRIVATE)==0)out.add(0);
        return out;
    }
}
