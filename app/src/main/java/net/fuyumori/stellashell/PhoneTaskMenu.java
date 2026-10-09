package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.PopupMenu;
import java.util.function.Consumer;

/** Task actions plus an explicit external-app launch; no profile edits or package-wide stop. */
final class PhoneTaskMenu implements AutoCloseable {
    static final int FLOAT=1,CLOSE=2,UNPIN=3;
    private final Context context;
    private final Consumer<PopupMenu> present;
    private PopupMenu popup;
    private long generation;
    PhoneTaskMenu(Context context){this(context,PopupMenu::show);}
    /** Captured presenter permits synthetic menu-action checks without a real overlay window. */
    PhoneTaskMenu(Context context,Consumer<PopupMenu> present){this.context=context;this.present=present;}
    boolean showing(){return popup!=null;}
    void show(View anchor,Consumer<String> selected){
        show(anchor,false,selected);
    }
    void show(View anchor,boolean floating,Consumer<String> selected){show(anchor,floating,false,selected);}
    void show(View anchor,boolean floating,boolean taskbarPinned,Consumer<String> selected){
        show(anchor,floating,taskbarPinned,selected,null,()->{});
    }
    void show(View anchor,boolean floating,boolean taskbarPinned,Consumer<String> selected,String component,Runnable dismissForExternal){
        close();long token=generation;PopupMenu current=new PopupMenu(context,anchor);popup=current;
        current.getMenu().add(0,FLOAT,0,floating?R.string.phone_sidebar_fullscreen_task:R.string.phone_sidebar_float_task);
        current.getMenu().add(0,CLOSE,1,R.string.phone_sidebar_close_task);
        if(taskbarPinned)current.getMenu().add(0,UNPIN,2,R.string.ui_unpin_from_taskbar);
        if(component!=null)ExternalAppLaunch.addToMenu(context,current.getMenu(),component,
                ()->{close();dismissForExternal.run();},()->popup==current&&token==generation);
        current.setOnMenuItemClickListener(item->{
            if(popup!=current||token!=generation)return true;
            String action=item.getItemId()==FLOAT?(floating?"fullscreen":"float"):item.getItemId()==CLOSE?"close":item.getItemId()==UNPIN?"unpin":null;
            if(action==null)return false; // Let an external-display submenu open normally.
            close();selected.accept(action);return true;
        });
        current.setOnDismissListener(menu->{if(popup==current){popup=null;generation++;}});
        try{present.accept(current);}catch(RuntimeException error){close();throw error;}
    }
    static Rect floatingBounds(Rect content){
        int width=Math.max(1,content.width()),height=Math.max(1,content.height());
        int x=width/6,y=height/6;
        return new Rect(content.left+x,content.top+y,Math.max(content.left+x+1,content.right-x),Math.max(content.top+y+1,content.bottom-y));
    }
    @Override public void close(){generation++;PopupMenu current=popup;popup=null;if(current!=null)current.dismiss();}
}
