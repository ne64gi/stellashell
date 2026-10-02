package net.fuyumori.stellashell;

import android.graphics.Rect;
import android.view.View;
import java.util.*;

/** One transient shell surface per display. App tasks are never closed by this coordinator. */
final class ShellPanels {
    private static final class Entry {final Object owner;final Runnable close;Rect bounds;Entry(Object owner,Runnable close){this.owner=owner;this.close=close;}}
    private static final Map<Integer,Entry> visible=new HashMap<>();
    private static final Set<Runnable> listeners=new LinkedHashSet<>();
    static void observe(Runnable changed){listeners.add(changed);}
    static void unobserve(Runnable changed){listeners.remove(changed);}
    private static void changed(){for(Runnable r:new ArrayList<>(listeners))r.run();}
    static void activate(int display,Object owner,Runnable close){
        Entry previous=visible.get(display);if(previous!=null&&previous.owner==owner)return;
        visible.put(display,new Entry(owner,close));if(previous!=null)previous.close.run();changed();
    }
    static void release(int display,Object owner){Entry entry=visible.get(display);if(entry!=null&&entry.owner==owner){visible.remove(display);changed();}}
    static void dismiss(int display){Entry entry=visible.remove(display);if(entry!=null){entry.close.run();changed();}}
    static boolean isOpen(int display){return visible.containsKey(display);}
    static Rect bounds(int display){Entry entry=visible.get(display);return entry==null||entry.bounds==null?null:new Rect(entry.bounds);}
    static void bounds(int display,Object owner,Rect value){
        Entry entry=visible.get(display);if(entry==null||entry.owner!=owner||value.equals(entry.bounds))return;
        entry.bounds=new Rect(value);changed();
    }
    static void track(int display,Object owner,View panel){
        Runnable update=()->{if(panel.getWidth()>0&&panel.getHeight()>0){int[] xy=new int[2];panel.getLocationOnScreen(xy);bounds(display,owner,new Rect(xy[0],xy[1],xy[0]+panel.getWidth(),xy[1]+panel.getHeight()));}};
        panel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->update.run());
        // Translation animations do not cause layout. Sample before drawing so
        // caption hit regions follow the panel throughout its slide, too.
        android.view.ViewTreeObserver.OnPreDrawListener drawn=()->{update.run();return true;};
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){v.getViewTreeObserver().addOnPreDrawListener(drawn);}
            public void onViewDetachedFromWindow(View v){v.getViewTreeObserver().removeOnPreDrawListener(drawn);}
        });
        if(panel.isAttachedToWindow())panel.getViewTreeObserver().addOnPreDrawListener(drawn);
        panel.post(update);
    }
    static boolean panelTask(String component){return component.equals("net.fuyumori.stellashell/net.fuyumori.stellashell.HubActivity")
            ||component.equals("net.fuyumori.stellashell/net.fuyumori.stellashell.QuickSettingsActivity")
            ||component.equals("net.fuyumori.stellashell/.HubActivity")||component.equals("net.fuyumori.stellashell/.QuickSettingsActivity");}
}
