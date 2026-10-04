package net.fuyumori.stellashell;

import android.graphics.Rect;
import net.fuyumori.stellashell.core.layout.WindowGeometry;

/** Rect adapter for core geometry; the caller supplies the exact display/work-area target. */
final class BridgeWindowGeometry {
    private BridgeWindowGeometry(){}
    static Rect clamp(Rect b,Rect area){
        int[] v=WindowGeometry.clamp(b.left-area.left,b.top-area.top,b.right-area.left,b.bottom-area.top,area.width(),area.height(),1,1);
        return new Rect(v[0]+area.left,v[1]+area.top,v[2]+area.left,v[3]+area.top);
    }
}
