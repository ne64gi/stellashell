package net.fuyumori.stellashell;

/** Geometry in host dp; rendering and pointer transforms use the same uniform scale. */
final class WidgetGeometry {
    static final int NORMAL=0, FORCE=1, SCALE=2;
    static int mode(int value){return value>=NORMAL&&value<=SCALE?value:NORMAL;}
    static float scale(int width,int height,int baseWidth,int baseHeight){
        return Math.min(Math.max(1,width)/(float)Math.max(1,baseWidth),Math.max(1,height)/(float)Math.max(1,baseHeight));
    }
    static int resize(int requested,int available,int minimum,int maximum){
        int low=Math.max(1,minimum),high=maximum>=low?maximum:Integer.MAX_VALUE;
        return Math.max(1,Math.min(Math.max(1,available),Math.min(high,Math.max(low,requested))));
    }
}
