package net.fuyumori.stellashell.core.layout;

public final class FloatingDockPlacement {
    private FloatingDockPlacement() {}
    public static int[] bounds(int left,int top,int right,int bottom,int width,int height,
            float anchorX,float anchorY,boolean towardRight) {
        width=Math.min(Math.max(1,width),Math.max(1,right-left));
        height=Math.min(Math.max(1,height),Math.max(1,bottom-top));
        int x=Math.max(left,Math.min(right-width,Math.round(anchorX)-(towardRight?0:width)));
        int y=Math.max(top,Math.min(bottom-height,Math.round(anchorY)-height/2));
        return new int[]{x,y,x+width,y+height};
    }
}
