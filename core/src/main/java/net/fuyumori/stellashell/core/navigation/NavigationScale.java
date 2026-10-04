package net.fuyumori.stellashell.core.navigation;

/** Surface-local sizing only. Never changes display/system density or application profiles. */
public final class NavigationScale {
    private NavigationScale(){}
    public static final int MIN=50,MAX=200,STEP=10,DEFAULT=100;
    public static int percent(int value){int clamped=Math.min(MAX,Math.max(MIN,value));return Math.round(clamped/(float)STEP)*STEP;}
    public static float factor(int value){return percent(value)/100f;}
    static String key(boolean main,boolean taskbar){return (main?"phone_":"desktop_")+(taskbar?"taskbar_scale":"dock_scale");}
    public static int pixels(float density,float baseDp,int value){return baseDp<=0?0:Math.max(1,Math.round(density*baseDp*factor(value)));}
}
