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
    static Display require(Context context,int id) {
        Policy.requireTarget(id,ids(context));
        Display d=context.getSystemService(DisplayManager.class).getDisplay(id);
        if(d==null || !d.isValid()) throw new IllegalArgumentException(context.getString(R.string.ui_the_external_display_is_disconnected));
        return d;
    }
}
