package net.fuyumori.stellashell;

/** Persisted per activity; coordinates are logical display pixels, not physical panel pixels. */
public final class AppLaunchProfile {
    public enum Mode { WINDOWED, MAXIMIZED, FULLSCREEN, RESTORE_LAST }
    public enum Size { SMALL, LARGE, HALF, LAST, CUSTOM }
    public enum Position { AUTO, CENTER, LAST }
    public final String packageName,activityName;
    public Mode launchMode=Mode.WINDOWED,lastState=Mode.WINDOWED;
    public Size size=Size.SMALL;public Position position=Position.AUTO;
    public int width=800,height=600;
    public int x,y,lastWidth,lastHeight;
    public boolean rememberBounds=true,hasLastBounds;
    public String preferredDisplay="AUTO",resolvedComponent="";
    public AppLaunchProfile(String component){Policy.component(component);String[] parts=component.split("/",2);packageName=parts[0];activityName=parts[1];}
    public static final class Plan {
        public final Mode state;public final int windowingMode,left,top,right,bottom;
        Plan(Mode state,int l,int t,int r,int b){this.state=state;windowingMode=state==Mode.FULLSCREEN?1:5;left=l;top=t;right=r;bottom=b;}
    }
    public Plan plan(int screenWidth,int screenHeight,int caption,int dock,int cascade){
        int availableHeight=screenHeight-dock-caption;
        if(screenWidth<=0||availableHeight<=0)throw new IllegalArgumentException("The display is too small");
        Mode state=launchMode==Mode.RESTORE_LAST?lastState:launchMode;
        if(state==Mode.RESTORE_LAST)state=Mode.WINDOWED;
        if(state==Mode.FULLSCREEN)return new Plan(state,0,0,screenWidth,screenHeight);
        if(state==Mode.MAXIMIZED)return new Plan(state,0,caption,screenWidth,screenHeight-dock);
        boolean restore=launchMode==Mode.RESTORE_LAST && hasLastBounds;
        int w=800,h=600;
        if(restore||size==Size.LAST&&hasLastBounds){w=lastWidth;h=lastHeight;}
        else if(size==Size.LARGE){w=1280;h=720;}
        else if(size==Size.HALF){w=screenWidth/2;h=availableHeight/2;}
        else if(size==Size.CUSTOM){w=width;h=height;}
        w=Math.max(1,Math.min(screenWidth,w));h=Math.max(1,Math.min(availableHeight,h));
        int l=(screenWidth-w)/2,t=caption+(availableHeight-h)/2;
        if((restore||position==Position.LAST)&&hasLastBounds){l=x;t=y;}
        else if(position==Position.AUTO){int offset=Math.floorMod(cascade,5)*24;l+=offset;t+=offset;}
        int[] b=WindowGeometry.clamp(l,t-caption,l+w,t-caption+h,screenWidth,availableHeight,Math.min(240,screenWidth),Math.min(160,availableHeight));
        return new Plan(state,b[0],b[1]+caption,b[2],b[3]+caption);
    }
}
