package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

/** Full-canvas, touch-through container. Only the compact lower controls reserve any space. */
@android.annotation.SuppressLint("ViewConstructor") // Owned controls are supplied programmatically.
final class HubControlRail extends FrameLayout {
    private final View tabs,pages;
    private boolean widgets=true,fromRight=true;
    private java.util.function.Consumer<Rect> reservation=area->{};
    private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect occupied=new Rect();
    HubControlRail(Context context,View tabs,View pages){
        super(context);this.tabs=tabs;this.pages=pages;
        addView(tabs,new LayoutParams(-1,Ui.dp(context,192)));
        addView(pages,new LayoutParams(-1,Ui.dp(context,48)));
        flatten(tabs);flatten(pages);
    }
    void widgetPage(boolean widgets){this.widgets=widgets;requestLayout();invalidate();}
    static int controlsWidth(Context context){return Ui.dp(context,56);}
    void fromRight(boolean value){fromRight=value;requestLayout();}
    void onReservation(java.util.function.Consumer<Rect> listener){reservation=listener;}
    private void flatten(View view){
        if(view instanceof Button){
            Button button=(Button)view;
            button.setBackgroundTintList(null);button.setStateListAnimator(null);button.setElevation(0);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),null,new ColorDrawable(0xffffffff)));
            button.setTextColor(button.isSelected()?Ui.ACCENT:Ui.MUTED);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)flatten(group.getChildAt(i));}
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        pages.setVisibility(widgets&&MeasureSpec.getSize(heightSpec)>=Ui.dp(getContext(),248)?VISIBLE:GONE);
        int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);
        int controls=MeasureSpec.makeMeasureSpec(Math.min(width,controlsWidth(getContext())),MeasureSpec.EXACTLY);
        tabs.measure(controls,MeasureSpec.makeMeasureSpec(Ui.dp(getContext(),192),MeasureSpec.EXACTLY));
        pages.measure(controls,MeasureSpec.makeMeasureSpec(Ui.dp(getContext(),48),MeasureSpec.EXACTLY));
        setMeasuredDimension(width,height);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int height=b-t,width=tabs.getMeasuredWidth(),tabHeight=tabs.getMeasuredHeight();
        int left=fromRight?r-l-width:0;
        int top=Math.max(0,Math.min(height-tabHeight,Math.round(height*.72f)-Ui.dp(getContext(),48)));
        tabs.layout(left,top,left+width,top+tabHeight);
        int pageHeight=pages.getVisibility()==GONE?0:pages.getMeasuredHeight();
        int pageTop=Math.max(0,top-pageHeight-Ui.dp(getContext(),8));
        pages.layout(left,pageTop,left+width,pageTop+pageHeight);
        if(widgets)occupied.set(left,pageHeight>0?pageTop:top,left+width,top+tabHeight);else occupied.setEmpty();
        reservation.accept(occupied);
    }
    @Override protected void dispatchDraw(Canvas canvas){
        super.dispatchDraw(canvas);
        float x=fromRight?tabs.getLeft()+Ui.dp(getContext(),1):tabs.getRight()-Ui.dp(getContext(),1);
        int top=pages.getVisibility()==VISIBLE?pages.getTop():tabs.getTop();
        line.setStrokeWidth(Ui.dp(getContext(),2));line.setColor(Ui.MUTED);line.setAlpha(90);
        canvas.drawLine(x,top,x,tabs.getBottom(),line);
        int selectedTop=tabs.getTop()+(widgets?0:Ui.dp(getContext(),48));
        line.setColor(Ui.ACCENT);line.setAlpha(255);
        canvas.drawLine(x,selectedTop,x,selectedTop+Ui.dp(getContext(),48),line);
    }
}
