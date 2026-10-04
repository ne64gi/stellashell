package net.fuyumori.stellashell.core.layout;

/** Density-independent placement shared by preview and committed shortcut positions. */
public final class DesktopPlacement {
    private DesktopPlacement(){}
    public static int[] fit(int x,int y,int width,int height,int areaWidth,int areaHeight,boolean snap){
        if(snap){x=Math.round(x/24f)*24;y=Math.round(y/24f)*24;}
        return new int[]{Math.max(0,Math.min(x,Math.max(0,areaWidth-width))),Math.max(0,Math.min(y,Math.max(0,areaHeight-height)))};
    }
}
