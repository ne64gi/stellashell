package net.fuyumori.stellashell.core.layout;

/** Pure geometry: focal point is a normalized source-image coordinate. */
public final class WallpaperPlacement {
    private WallpaperPlacement(){}
    public static float clamp(float value){return Float.isFinite(value)?Math.max(0,Math.min(1,value)):.5f;}
    public static float[] transform(int iw,int ih,int vw,int vh,boolean fit,float x,float y){
        if(iw<=0||ih<=0||vw<=0||vh<=0)return new float[]{1,0,0};
        float scale=fit?Math.min((float)vw/iw,(float)vh/ih):Math.max((float)vw/iw,(float)vh/ih);
        float w=iw*scale,h=ih*scale;
        float left=fit?(vw-w)/2:Math.max(vw-w,Math.min(0,vw/2f-clamp(x)*w));
        float top=fit?(vh-h)/2:Math.max(vh-h,Math.min(0,vh/2f-clamp(y)*h));
        return new float[]{scale,left,top};
    }
}
