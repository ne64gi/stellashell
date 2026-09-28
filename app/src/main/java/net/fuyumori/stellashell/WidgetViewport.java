package net.fuyumori.stellashell;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Measure RemoteViews at their logical size, then transform the entire live view.
 * ViewGroup's inverse child matrix also maps clicks/scrolls to the unscaled content.
 */
final class WidgetViewport extends ViewGroup {
    private int logicalWidth=1,logicalHeight=1;
    WidgetViewport(Context context){super(context);setClipChildren(true);setClipToPadding(true);}
    void contentSize(int width,int height){
        if(logicalWidth==width&&logicalHeight==height)return;
        logicalWidth=Math.max(1,width);logicalHeight=Math.max(1,height);requestLayout();
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        setMeasuredDimension(MeasureSpec.getSize(widthSpec),MeasureSpec.getSize(heightSpec));
        if(getChildCount()>0)getChildAt(0).measure(MeasureSpec.makeMeasureSpec(logicalWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(logicalHeight,MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        if(getChildCount()==0)return;
        View child=getChildAt(0);float scale=WidgetGeometry.scale(r-l,b-t,logicalWidth,logicalHeight);
        child.layout(0,0,logicalWidth,logicalHeight);child.setPivotX(0);child.setPivotY(0);
        child.setScaleX(scale);child.setScaleY(scale);
        child.setTranslationX(((r-l)-logicalWidth*scale)/2f);child.setTranslationY(((b-t)-logicalHeight*scale)/2f);
    }
}
