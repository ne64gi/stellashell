package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Display-coordinate geometry shared by chrome, profiles and the task bridge. */
final class WorkArea {
    private static final Map<Integer,WorkArea> areas=new HashMap<>();
    final Rect physical,usable,application,content;
    final boolean compact;
    boolean imeVisible;int gestureLeft,gestureRight;
    final int caption;
    WorkArea(Rect physical,Rect usable,boolean compact,int caption,int dock){
        this.physical=new Rect(physical);this.usable=new Rect(usable);this.compact=compact;this.caption=caption;
        application=new Rect(usable);application.bottom=Math.max(application.top+1,application.bottom-dock);
        content=new Rect(application);content.top=Math.min(content.bottom-1,content.top+caption);
    }
    static WorkArea read(Context c,WindowInsets insets){
        Point size=new Point();c.getDisplay().getRealSize(size);
        float density=c.getResources().getDisplayMetrics().density;
        Rect physical=new Rect(0,0,size.x,size.y);
        int types=WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.mandatorySystemGestures();
        Insets system=insets.getInsets(types);
        Rect stable=inset(physical,insets.getInsetsIgnoringVisibility(types));
        boolean compact=WorkspaceProfile.standard(c,c.getDisplay().getDisplayId())||ShellPresentation.compact(Launches.prefs(c).getString("shell_layout","auto"),stable.width()/density,stable.height()/density);
        Insets all=insets.getInsets(types|WindowInsets.Type.ime());
        WorkArea result=new WorkArea(physical,inset(physical,all),compact,Math.round(32*density),compact?0:Math.round(60*density));
        result.imeVisible=insets.isVisible(WindowInsets.Type.ime());
        Insets gestures=insets.getInsets(WindowInsets.Type.systemGestures());result.gestureLeft=gestures.left;result.gestureRight=gestures.right;
        return result;
    }
    private static Rect inset(Rect b,Insets i){
        int l=Math.min(b.right-1,b.left+Math.max(0,i.left)),t=Math.min(b.bottom-1,b.top+Math.max(0,i.top));
        return new Rect(l,t,Math.max(l+1,b.right-Math.max(0,i.right)),Math.max(t+1,b.bottom-Math.max(0,i.bottom)));
    }
    static synchronized WorkArea get(Context c,int id){
        WorkArea area=areas.get(id);if(area!=null)return area;
        Context dc=c.createDisplayContext(Displays.require(c,id)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        return read(dc,dc.getSystemService(WindowManager.class).getCurrentWindowMetrics().getWindowInsets());
    }
    static synchronized void put(int id,WorkArea area){areas.put(id,area);}
    static synchronized void remove(int id){areas.remove(id);}
    Rect clamp(Rect b){return clamp(b,content);}
    static Rect clamp(Rect b,Rect area){
        int[] v=WindowGeometry.clamp(b.left-area.left,b.top-area.top,b.right-area.left,b.bottom-area.top,area.width(),area.height(),1,1);
        return new Rect(v[0]+area.left,v[1]+area.top,v[2]+area.left,v[3]+area.top);
    }
    boolean maximized(Rect b){return Math.abs(b.left-content.left)<=2&&Math.abs(b.top-content.top)<=2&&Math.abs(b.right-content.right)<=2&&Math.abs(b.bottom-content.bottom)<=2;}
    boolean same(WorkArea a){return a!=null&&physical.equals(a.physical)&&usable.equals(a.usable)&&application.equals(a.application)&&compact==a.compact&&caption==a.caption&&imeVisible==a.imeVisible&&gestureLeft==a.gestureLeft&&gestureRight==a.gestureRight;}
    void sync(IDesktopBridge bridge,int id)throws android.os.RemoteException {bridge.setWorkArea(id,content.left,content.top,content.right,content.bottom);}
}
