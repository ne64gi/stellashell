package net.fuyumori.stellashell.core.layout;

/** Pure display-coordinate dock policy; percentages describe remaining travel, not pixels. */
public final class DockPlacement {
    private DockPlacement(){}
    public static String edge(String value){return "left".equals(value)||"right".equals(value)||"top".equals(value)?value:"bottom";}
    public static boolean vertical(String edge){return "left".equals(edge)||"right".equals(edge);}
    static int percent(int value){return Math.max(0,Math.min(100,value));}
    public static int[] bounds(int left,int top,int right,int bottom,int wantedWidth,int wantedHeight,int x,int y){
        int width=Math.min(Math.max(1,wantedWidth),Math.max(1,right-left));
        int height=Math.min(Math.max(1,wantedHeight),Math.max(1,bottom-top));
        int px=left+Math.round(Math.max(0,right-left-width)*percent(x)/100f);
        int py=top+Math.round(Math.max(0,bottom-top-height)*percent(y)/100f);
        return new int[]{px,py,px+width,py+height};
    }
    public static int[] menu(int left,int top,int right,int bottom,int width,int height,int margin,String selected,int[] dock){
        int mx=Math.min(Math.max(0,margin),Math.max(0,(right-left-width)/2));
        int my=Math.min(Math.max(0,margin),Math.max(0,(bottom-top-height)/2));
        int x=left+mx,y=bottom-height-my;
        if(dock!=null){
            String edge=edge(selected);
            if("top".equals(edge)){x=dock[0];y=dock[3]+margin;}
            else if("bottom".equals(edge)){x=dock[0];y=dock[1]-height-margin;}
            else if("left".equals(edge)){x=dock[2]+margin;y=dock[1];}
            else {x=dock[0]-width-margin;y=dock[1];}
        }
        x=Math.max(left+mx,Math.min(x,Math.max(left+mx,right-width-mx)));
        y=Math.max(top+my,Math.min(y,Math.max(top+my,bottom-height-my)));
        return new int[]{x,y};
    }
}
