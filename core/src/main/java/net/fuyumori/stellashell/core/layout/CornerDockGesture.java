package net.fuyumori.stellashell.core.layout;

/** Deliberate up-then-sideways gesture in dp. Recognition happens only on release. */
public final class CornerDockGesture {
    public static final class Result {
        public final float x, y;
        public final boolean right;
        Result(float x, float y, boolean right) { this.x=x; this.y=y; this.right=right; }
    }
    private int phase, direction;
    public enum Progress { IDLE, UP, TURN, READY }
    private float startX, startY, cornerX, cornerY, farthest;
    private long began, last;

    public void begin(float x, float y, long time, float width, float height) {
        cancel();
        if (!valid(x,y) || !valid(width,height) || width<=0 || height<=0 || x<0 || x>width || y<0 || y>height) return;
        startX=cornerX=x; startY=cornerY=y; began=last=time; phase=1;
    }
    /** Screen-edge admission belongs to the global reader, not the local practice surface. */
    public void beginOnScreen(float x,float y,long time,float width,float height) {
        cancel();
        if (x<16 || x>width-16 || y<56 || y>height-40) return;
        begin(x,y,time,width,height);
    }
    public void move(float x, float y, long time) {
        if (phase==0) return;
        if (!valid(x,y) || time<last || time-began>1600) { cancel(); return; }
        last=time;
        float dx=x-startX, up=startY-y;
        if (phase==1) {
            if (up < -12 || Math.abs(dx)>Math.max(64,up*.85f)) { cancel(); return; }
            if (up>=28 && up>=Math.abs(dx)*1.2f) { phase=2; cornerX=x; cornerY=y; }
            return;
        }
        if (phase==2) {
            float side=x-cornerX, rise=cornerY-y;
            if (up>640 || rise < -48) { cancel(); return; }
            // Follow the upward leg, including a rounded corner, before fixing its anchor.
            if (rise>=12 && rise>=Math.abs(side)*.9f) { cornerX=x; cornerY=y; return; }
            if (Math.abs(side)<18 || Math.abs(side)<Math.abs(rise)*1.8f) return;
            phase=3; direction=side>0?1:-1; farthest=0;
        }
        if (phase==3) {
            float distance=(x-cornerX)*direction;
            if (Math.abs(y-cornerY)>Math.max(48,Math.min(112,distance*.6f)) || distance<farthest-20 || distance>480) { cancel(); return; }
            farthest=Math.max(farthest,distance);
        }
    }
    public Result finish(float x,float y,long time) {
        move(x,y,time);
        Result result=phase==3 && (x-cornerX)*direction>=36 && time-began>=80
                ?new Result(x,y,direction>0):null;
        cancel(); return result;
    }
    public void cancel() { phase=0; }
    public Progress progress() {
        return phase==0?Progress.IDLE:phase==1?Progress.UP:phase==3&&farthest>=36?Progress.READY:Progress.TURN;
    }
    private static boolean valid(float x,float y) { return Float.isFinite(x)&&Float.isFinite(y); }
}
