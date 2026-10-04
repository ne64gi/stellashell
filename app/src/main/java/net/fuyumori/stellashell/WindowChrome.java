package net.fuyumori.stellashell;

import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Per-task captions; occluded paint AND input are removed using small clipped windows. */
final class WindowChrome {
    private final Context context;private final WindowManager windows;private final TaskState taskState;
    private final Map<Integer,Frame> frames=new LinkedHashMap<>();
    private List<TaskSnapshot.Task> stack=new ArrayList<>();
    private Frame draggingFrame;private boolean dragFront;
    WindowChrome(Context c,WindowManager wm,TaskState taskState){context=c;windows=wm;this.taskState=taskState;}
    void update(List<TaskSnapshot.Task> tasks){
        TaskSnapshot snapshot=taskState.snapshot();
        if(!Bridge.get(context).ready()||!snapshot.canArrange){clear();return;}
        stack=snapshot.stackReliable?new ArrayList<>(snapshot.stack):new ArrayList<>();
        if(!snapshot.stackReliable)for(TaskSnapshot.Task t:tasks)if(t.focused)stack.add(t);
        if(draggingFrame!=null){
            boolean alive=false;for(TaskSnapshot.Task t:tasks)if(t.identity().equals(draggingFrame.task.identity())&&t.visible&&t.mode==5)alive=true;
            if(!alive)clear();else relayout();return;
        }
        Set<Integer> wanted=new HashSet<>();
        for(TaskSnapshot.Task t:tasks)if(t.visible&&t.mode==5&&(snapshot.stackReliable||t.focused)){
            wanted.add(t.id);Frame f=frames.get(t.id);
            if(f!=null&&!f.task.identity().equals(t.identity())){f.clear();frames.remove(t.id);f=null;}
            if(f==null){f=new Frame(t);frames.put(t.id,f);}else {f.task=t;f.rendered=t.bounds().toRect();}
            f.active=t.focused;
        }
        for(Integer id:new ArrayList<>(frames.keySet()))if(!wanted.contains(id)){frames.remove(id).clear();}
        relayout();
    }
    private void begin(Frame f){
        draggingFrame=f;dragFront=f.active;f.wantResize=false;f.waitingFocus=false;
        if(!f.active){
            f.waitingFocus=true;
            taskState.focusForDrag(f.task,ok->{
                f.waitingFocus=!ok;if(frames.get(f.task.id)!=f)return;
                if(!ok){f.wantResize=false;taskState.refresh();return;}
                for(Frame other:frames.values())other.active=other==f;
                if(draggingFrame==f)dragFront=true;
                // Do not send bounds until the app is actually focused.
                if(f.wantResize)taskState.resize(f.task,f.rendered);
                relayout();
            });
        }
    }
    private void end(Frame f){if(draggingFrame==f){draggingFrame=null;dragFront=false;}f.heldRoot=null;taskState.refresh();}
    static List<TaskSnapshot.Task> dragOrder(List<TaskSnapshot.Task> stack,TaskSnapshot.Task dragged){
        List<TaskSnapshot.Task> ordered=new ArrayList<>(stack);ordered.removeIf(t->t.identity().equals(dragged.identity()));
        int insertion=0;
        for(int i=0;i<ordered.size();i++){
            TaskSnapshot.Task task=ordered.get(i);
            if(task.visible&&(task.mode==2||!dragged.alwaysOnTop&&task.alwaysOnTop))insertion=i+1;
        }
        ordered.add(insertion,dragged);return ordered;
    }
    private void relayout(){
        List<TaskSnapshot.Task> ordered=new ArrayList<>(stack);
        if(draggingFrame!=null&&dragFront)ordered=dragOrder(ordered,draggingFrame.task);
        List<int[]> blockers=new ArrayList<>();Set<Integer> drawn=new HashSet<>();
        Rect panel=ShellPanels.bounds(context.getDisplay().getDisplayId());if(panel!=null)blockers.add(new int[]{panel.left,panel.top,panel.right,panel.bottom});
        for(TaskSnapshot.Task t:ordered){
            if(!t.visible||ShellPanels.panelTask(t.component))continue;
            Frame f=frames.get(t.id);Rect b=f==null?t.bounds().toRect():f.rendered;
            if(f!=null){f.layout(blockers);drawn.add(t.id);}
            int top=f!=null?Math.max(0,b.top-Ui.dp(context,32)):b.top;
            blockers.add(new int[]{b.left,top,b.right,b.bottom});
        }
        for(Frame f:frames.values())if(!drawn.contains(f.task.id))f.hide();
    }
    void clear(){for(Frame f:frames.values())f.clear();frames.clear();stack.clear();draggingFrame=null;dragFront=false;}

    private final class Frame {
        TaskSnapshot.Task task;Rect rendered;boolean active,dragging,waitingFocus,wantResize;long lastSend;View heldRoot;
        final List<List<Fragment>> parts=new ArrayList<>();final String label;
        Frame(TaskSnapshot.Task t){task=t;rendered=t.bounds().toRect();for(int i=0;i<9;i++)parts.add(new ArrayList<>());
            String name=t.packageName();try{name=context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(name,0)).toString();}catch(Exception ignored){}label=name;
        }
        private boolean isMaximized(){return WorkArea.get(context,context.getDisplay().getDisplayId()).maximized(rendered);}
        private View content(int edge){
            if(edge!=0){
                View h=new ResizeHandle(context,edge);h.setContentDescription(context.getString(R.string.ui_resize,label));
                int cursor=(edge==1||edge==2)?PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW:(edge==3||edge==4)?PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW:(edge==5||edge==8)?PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW:PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW;
                h.setPointerIcon(PointerIcon.getSystemIcon(context,cursor));gesture(h,edge);return h;
            }
            LinearLayout title=new LinearLayout(context);title.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=Ui.text(context,label,13,Ui.TEXT);name.setGravity(Gravity.CENTER_VERTICAL);name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(Ui.dp(context,12),0,0,0);
            title.addView(name,new LinearLayout.LayoutParams(0,-1,1));name.setContentDescription(context.getString(R.string.ui_bring_to_front_and_move,label));gesture(name,0);
            if(taskState.snapshot().canPin)button(title,context.getString(R.string.window_pin),"pin");
            button(title,context.getString(R.string.ui_snap_left),"left");button(title,context.getString(R.string.ui_snap_right),"right");button(title,context.getString(R.string.ui_minimize),"minimize");button(title,context.getString(R.string.ui_maximize_restore),"maximize");button(title,context.getString(R.string.task_close_window),"close");return title;
        }
        private void button(LinearLayout row,String description,String action){
            CaptionButton b=new CaptionButton(context,action);b.setContentDescription(label+" "+description);b.setTooltipText(description);
            b.setOnClickListener(v->{ShellPanels.dismiss(context.getDisplay().getDisplayId());String actual=action.equals("pin")?"togglePin":action.equals("maximize")?"toggleMaximize":action;
                // Closing a background window must not depend on successfully focusing it first.
                if(active||"close".equals(actual))taskState.action(task,actual);else taskState.focusForDrag(task,ok->{if(ok)taskState.action(task,actual);});});
            row.addView(b,new LinearLayout.LayoutParams(Ui.dp(context,36),-1));
        }
        private void style(View v,int edge){
            if(edge!=0)return;
            LinearLayout row=(LinearLayout)v;
            android.graphics.drawable.GradientDrawable background=Ui.rounded(context,active?Ui.PANEL:Ui.BG,8);
            float radius=Ui.dp(context,8);
            // Round only the outer top corners; the bottom joins the application content.
            background.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0});
            row.setBackground(background);
            ((TextView)row.getChildAt(0)).setTextColor(active?Ui.TEXT:Ui.MUTED);
            for(int i=1;i<row.getChildCount();i++){
                CaptionButton child=(CaptionButton)row.getChildAt(i);child.setAlpha(active?1f:.62f);
                boolean snap=child.action.equals("left")||child.action.equals("right");
                child.setVisibility(snap&&rendered.width()<Ui.dp(context,340)?View.GONE:View.VISIBLE);
                if(child.action.equals("maximize")||child.action.equals("restore"))child.action=isMaximized()?"restore":"maximize";
                if(child.action.equals("pin")){
                    child.setSelected(task.alwaysOnTop);String description=context.getString(task.alwaysOnTop?R.string.window_unpin:R.string.window_pin);
                    child.setContentDescription(label+" "+description);child.setTooltipText(description);
                }
                child.invalidate();
            }
        }
        private final class Fragment {
            final FrameLayout root=new FrameLayout(context);final View child;final WindowManager.LayoutParams p;
            Rect previousWhole;int[] previousPiece;boolean previousActive,previousMaximized,previousPinned;
            Fragment(int edge){
                child=content(edge);root.setClipChildren(true);root.addView(child);
                p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
                p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle("StellaShell "+(edge==0?"window title":"resize "+edge)+" task="+task.id);
                windows.addView(root,p);
            }
            void place(int[] piece,Rect whole,int edge){
                boolean maximized=isMaximized();
                if(whole.equals(previousWhole)&&Arrays.equals(piece,previousPiece)&&previousActive==active&&previousMaximized==maximized&&previousPinned==task.alwaysOnTop)return;
                previousWhole=new Rect(whole);previousPiece=piece.clone();previousActive=active;previousMaximized=maximized;previousPinned=task.alwaysOnTop;
                FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(Math.max(1,whole.width()),Math.max(1,whole.height()));cp.leftMargin=whole.left-piece[0];cp.topMargin=whole.top-piece[1];child.setLayoutParams(cp);style(child,edge);
                p.x=piece[0];p.y=piece[1];p.width=piece[2]-piece[0];p.height=piece[3]-piece[1];windows.updateViewLayout(root,p);
            }
            void remove(){try{windows.removeViewImmediate(root);}catch(RuntimeException ignored){}}
        }
        private void part(int edge,Rect whole,List<int[]> blockers){
            android.graphics.Point size=new android.graphics.Point();context.getDisplay().getRealSize(size);
            Rect work=WorkArea.get(context,context.getDisplay().getDisplayId()).application;
            int[] area={Math.max(work.left,whole.left),Math.max(work.top,whole.top),Math.min(work.right,whole.right),Math.min(work.bottom,whole.bottom)};
            List<int[]> visible=ChromeOcclusion.visible(area,blockers);List<Fragment> list=parts.get(edge);
            // Preserve the window owning the gesture even when a split caption becomes whole.
            if(heldRoot!=null)for(int i=1;i<list.size();i++)if(list.get(i).root==heldRoot){Collections.swap(list,0,i);break;}
            while(list.size()>visible.size())list.remove(list.size()-1).remove();
            for(int i=0;i<visible.size();i++){if(i==list.size())list.add(new Fragment(edge));list.get(i).place(visible.get(i),whole,edge);}
        }
        void layout(List<int[]> blockers){
            Rect b=rendered;int e=Ui.dp(context,6),c=Ui.dp(context,16),top=Math.max(0,b.top-Ui.dp(context,32));
            part(0,new Rect(b.left,top,b.right,b.top),blockers);
            Rect[] handles={new Rect(b.left,top+c,b.left+e,b.bottom-c),new Rect(b.right-e,top+c,b.right,b.bottom-c),new Rect(b.left+c,top,b.right-c,top+e),new Rect(b.left+c,b.bottom-e,b.right-c,b.bottom),new Rect(b.left,top,b.left+c,top+e),new Rect(b.right-c,top,b.right,top+e),new Rect(b.left,b.bottom-c,b.left+c,b.bottom),new Rect(b.right-c,b.bottom-c,b.right,b.bottom)};
            for(int i=1;i<=8;i++)part(i,active?handles[i-1]:new Rect(),blockers);
        }
        void hide(){for(List<Fragment> list:parts){for(Fragment f:list)f.remove();list.clear();}}
        void clear(){hide();dragging=false;heldRoot=null;}
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Drag handles; discrete equivalents are exposed in the task menu.
    private void gesture(View target,int edge){
        target.setOnTouchListener(new View.OnTouchListener(){float x,y;Rect start;
            public boolean onTouch(View v,MotionEvent event){
                if(task==null)return false;
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){if(event.isFromSource(InputDevice.SOURCE_MOUSE)&&(event.getButtonState()&MotionEvent.BUTTON_PRIMARY)==0)return false;if(v instanceof ResizeHandle)((ResizeHandle)v).active(true);x=event.getRawX();y=event.getRawY();start=new Rect(rendered);dragging=true;heldRoot=v.getRootView();lastSend=0;begin(Frame.this);ShellPanels.dismiss(context.getDisplay().getDisplayId());return true;}
                if(start==null)return false;
                if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){if(v instanceof ResizeHandle)((ResizeHandle)v).active(false);wantResize=false;dragging=false;start=null;end(Frame.this);taskState.refresh();return true;}
                if(event.getActionMasked()!=MotionEvent.ACTION_MOVE&&event.getActionMasked()!=MotionEvent.ACTION_UP)return true;
                int dx=Math.round(event.getRawX()-x),dy=Math.round(event.getRawY()-y);Rect b=new Rect(start);
                if(edge==0)b.offset(dx,dy);
                else {if(edge==1||edge==5||edge==7)b.left+=dx;if(edge==2||edge==6||edge==8)b.right+=dx;if(edge==3||edge==5||edge==6)b.top+=dy;if(edge==4||edge==7||edge==8)b.bottom+=dy;}
                android.util.DisplayMetrics m=context.getResources().getDisplayMetrics();
                int minW=Ui.dp(context,240),minH=Ui.dp(context,160);
                if(edge==1||edge==5||edge==7)b.left=Math.min(b.left,b.right-minW);else b.right=Math.max(b.right,b.left+minW);
                if(edge==3||edge==5||edge==6)b.top=Math.min(b.top,b.bottom-minH);else b.bottom=Math.max(b.bottom,b.top+minH);
                int caption=Ui.dp(context,32);
                android.graphics.Point size=new android.graphics.Point();context.getDisplay().getRealSize(size);
                rendered=WorkArea.get(context,context.getDisplay().getDisplayId()).clamp(b);relayout();
                long now=SystemClock.uptimeMillis();boolean done=event.getActionMasked()==MotionEvent.ACTION_UP;
                if(done||now-lastSend>120){wantResize=true;if(!waitingFocus)taskState.resize(task,rendered);lastSend=now;}
                if(done){if(v instanceof ResizeHandle)((ResizeHandle)v).active(false);dragging=false;start=null;end(Frame.this);}return true;
            }
        });
    }
    }
    /** Hit regions remain generous, but only a 1dp edge is painted on hover/drag. */
    private static final class ResizeHandle extends View {
        final int edge;final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        boolean active;
        ResizeHandle(Context c,int edge){super(c);this.edge=edge;setWillNotDraw(false);}
        void active(boolean value){active=value;invalidate();}
        @Override public boolean onHoverEvent(MotionEvent event){setHovered(event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT);invalidate();return super.onHoverEvent(event);}
        @Override protected void onDraw(android.graphics.Canvas c){
            if(!active&&!isHovered())return;paint.setColor(0xb36ee7c8);paint.setStrokeWidth(Ui.dp(getContext(),1));
            float w=getWidth()-1,h=getHeight()-1;
            if(edge==1||edge==5||edge==7)c.drawLine(0,0,0,h,paint);
            if(edge==2||edge==6||edge==8)c.drawLine(w,0,w,h,paint);
            if(edge==3||edge==5||edge==6)c.drawLine(0,0,w,0,paint);
            if(edge==4||edge==7||edge==8)c.drawLine(0,h,w,h,paint);
        }
    }
    /** Draw stable vector controls instead of font-dependent Unicode squares. */
    private static final class CaptionButton extends View {
        String action;final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        CaptionButton(Context c,String action){super(c);this.action=action;setFocusable(true);setPointerIcon(PointerIcon.getSystemIcon(c,PointerIcon.TYPE_HAND));}
        @Override public boolean onHoverEvent(MotionEvent e){setHovered(e.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT);invalidate();return super.onHoverEvent(e);}
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        @Override protected void onDraw(android.graphics.Canvas c){
            if(isHovered()||isPressed()||isFocused())c.drawColor(action.equals("close")?0xffb84350:isPressed()?0x38ffffff:0x18ffffff);
            float cx=getWidth()/2f,cy=getHeight()/2f,r=Ui.dp(getContext(),5);
            paint.setColor(action.equals("pin")&&isSelected()?Ui.ACCENT:Ui.TEXT);paint.setStrokeWidth(Math.max(1,Ui.dp(getContext(),1)));paint.setStyle(android.graphics.Paint.Style.STROKE);
            switch(action){
                case "pin":
                    if(isSelected())paint.setStyle(android.graphics.Paint.Style.FILL);
                    c.drawRect(cx-r*.6f,cy-r,cx+r*.6f,cy,paint);c.drawLine(cx-r,cy,cx+r,cy,paint);c.drawLine(cx,cy,cx,cy+r*1.4f,paint);break;
                case "close":c.drawLine(cx-r,cy-r,cx+r,cy+r,paint);c.drawLine(cx+r,cy-r,cx-r,cy+r,paint);break;
                case "minimize":c.drawLine(cx-r,cy+2,cx+r,cy+2,paint);break;
                case "restore":c.drawRect(cx-r+3,cy-r,cx+r,cy+r-3,paint);c.drawRect(cx-r,cy-r+3,cx+r-3,cy+r,paint);break;
                default:c.drawRect(cx-r,cy-r,cx+r,cy+r,paint);
                    if(action.equals("left")||action.equals("right")){paint.setStyle(android.graphics.Paint.Style.FILL);c.drawRect(action.equals("left")?cx-r:cx,cy-r,action.equals("left")?cx:cx+r,cy+r,paint);}break;
            }
        }
    }
}
