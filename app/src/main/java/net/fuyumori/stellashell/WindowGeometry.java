package net.fuyumori.stellashell;

/** Integer screen-space geometry, independent of Android. */
public final class WindowGeometry {
    private WindowGeometry(){}
    public static int[] area(int width,int height,int reservedBottom){
        if(width<=0||height<=0)throw new IllegalArgumentException("Invalid display size");
        return new int[]{0,0,width,Math.max(1,height-Math.max(0,reservedBottom))};
    }
    public static int[] clamp(int left,int top,int right,int bottom,int width,int height,int minWidth,int minHeight){
        if(right<=left||bottom<=top||width<=0||height<=0)throw new IllegalArgumentException("Invalid window bounds");
        int w=Math.min(width,Math.max(Math.min(width,minWidth),right-left));
        int h=Math.min(height,Math.max(Math.min(height,minHeight),bottom-top));
        int x=Math.max(0,Math.min(left,width-w)),y=Math.max(0,Math.min(top,height-h));
        return new int[]{x,y,x+w,y+h};
    }
}
