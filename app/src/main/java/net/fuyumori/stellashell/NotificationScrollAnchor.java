package net.fuyumori.stellashell;

import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** One-shot reading-position/keyboard-focus preservation; owns no notification content or state. */
final class NotificationScrollAnchor {
    private final LinearLayout rows;
    private ReadingAnchor pendingAnchor;
    private View pendingFocus;
    private ViewTreeObserver pendingObserver;
    private final ViewTreeObserver.OnGlobalLayoutListener restorePosition=this::restorePosition;

    NotificationScrollAnchor(LinearLayout rows){
        this.rows=rows;
        rows.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View view){}
            @Override public void onViewDetachedFromWindow(View view){cancel();}
        });
    }

    /** Capture before changing rows; coalesced updates share the first capture until layout. */
    void preserve(Supplier<List<View>> readingCandidates){
        if(pendingObserver!=null)return;
        ScrollView scroll=scrollParent();
        if(scroll==null||rows.getHeight()==0)return;
        List<View> candidates=readingCandidates.get();
        if(candidates.isEmpty())return;
        pendingAnchor=new ReadingAnchor(scroll,candidates);
        pendingFocus=rows.findFocus();
        pendingObserver=rows.getViewTreeObserver();pendingObserver.addOnGlobalLayoutListener(restorePosition);
        // Even an identical snapshot can bind without changing any View. Guarantee a
        // traversal so this anchor is discarded before a later user scroll/update.
        rows.requestLayout();
    }

    /** Also used at privacy/lifecycle boundaries to release pending View references immediately. */
    void cancel(){
        if(pendingObserver!=null&&pendingObserver.isAlive())pendingObserver.removeOnGlobalLayoutListener(restorePosition);
        pendingObserver=null;pendingAnchor=null;pendingFocus=null;
    }

    private void restorePosition(){
        ReadingAnchor anchor=pendingAnchor;View focus=pendingFocus;cancel();
        // A delayed snapshot must not override a newer keyboard selection, including
        // one outside this list (for example a Hub tab or settings button).
        View selected=rows.getRootView().findFocus();
        boolean newerSelection=selected!=null&&selected!=focus;
        boolean activeWindow=!rows.isAttachedToWindow()||rows.hasWindowFocus();
        if(!newerSelection&&activeWindow&&focus!=null&&inside(focus,rows)&&!focus.hasFocus())focus.requestFocus();
        // Do not programmatically assign accessibility focus. Keeping the same views
        // preserves existing focus; the accessibility service owns a newer selection.
        if(anchor!=null)anchor.restore();
    }

    private ScrollView scrollParent(){
        ViewParent parent=rows.getParent();while(parent instanceof View){if(parent instanceof ScrollView)return (ScrollView)parent;parent=parent.getParent();}return null;
    }
    private static int topInScroll(View view,ScrollView scroll){
        int top=view.getTop();ViewParent parent=view.getParent();
        while(parent instanceof View&&parent!=scroll){View ancestor=(View)parent;top+=ancestor.getTop()-ancestor.getScrollY();parent=parent.getParent();}
        return top;
    }
    private static boolean inside(View view,View ancestor){
        ViewParent parent=view.getParent();while(parent instanceof View){if(parent==ancestor)return true;parent=parent.getParent();}return false;
    }

    private final class ReadingAnchor {
        final ScrollView scroll;
        final List<View> candidates;
        final List<Integer> offsets=new ArrayList<>();
        final int first,originalY;
        ReadingAnchor(ScrollView scroll,List<View> candidates){
            this.scroll=scroll;this.candidates=candidates;originalY=scroll.getScrollY();int visible=0;
            int viewportTop=originalY+scroll.getPaddingTop();
            for(int i=0;i<candidates.size();i++){
                View view=candidates.get(i);int top=topInScroll(view,scroll);offsets.add(top-originalY);
                if(top+view.getHeight()<=viewportTop)visible=i+1;
            }
            first=Math.min(visible,candidates.size()-1);
        }
        void restore(){
            // If the item under the reading edge vanished, keep the next surviving item,
            // then the previous one, at its original position as far as scroll bounds allow.
            for(int i=first;i<candidates.size();i++)if(restore(i))return;
            for(int i=first-1;i>=0;i--)if(restore(i))return;
            scroll.scrollTo(scroll.getScrollX(),originalY);
        }
        boolean restore(int i){View view=candidates.get(i);if(view.getVisibility()!=View.VISIBLE||!inside(view,rows))return false;scroll.scrollTo(scroll.getScrollX(),topInScroll(view,scroll)-offsets.get(i));return true;}
    }
}
