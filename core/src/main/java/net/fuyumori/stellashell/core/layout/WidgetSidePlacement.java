package net.fuyumori.stellashell.core.layout;

/** Reversible position mapping; never mirrors provider content or mutates saved geometry. */
public final class WidgetSidePlacement {
    private WidgetSidePlacement(){}

    /** The same mapping converts saved-left X to screen X and screen X back to saved-left X. */
    public static int x(int coordinate,int itemWidth,int canvasWidth,boolean fromRight){
        int available=Math.max(0,canvasWidth-Math.max(0,itemWidth));
        int bounded=Math.max(0,Math.min(coordinate,available));
        return fromRight?available-bounded:bounded;
    }
}
