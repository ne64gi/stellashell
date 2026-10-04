package net.fuyumori.stellashell.core.layout;

/** Geometry in host dp; rendering and pointer transforms use the same uniform scale. */
public final class WidgetGeometry {
    private WidgetGeometry(){}
    public static final int NORMAL=0, FORCE=1, SCALE=2;
    public static int mode(int value){return value>=NORMAL&&value<=SCALE?value:NORMAL;}
    public static float scale(int width,int height,int baseWidth,int baseHeight){
        return Math.min(Math.max(1,width)/(float)Math.max(1,baseWidth),Math.max(1,height)/(float)Math.max(1,baseHeight));
    }
    public static int resize(int requested,int available,int minimum,int maximum){
        int low=Math.max(1,minimum),high=maximum>=low?maximum:Integer.MAX_VALUE;
        return Math.max(1,Math.min(Math.max(1,available),Math.min(high,Math.max(low,requested))));
    }
}
