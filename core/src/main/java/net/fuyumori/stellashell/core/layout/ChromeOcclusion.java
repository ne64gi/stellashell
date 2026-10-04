package net.fuyumori.stellashell.core.layout;

import java.util.*;

/** Half-open rectangles. Each returned piece is both a paint and input window. */
public final class ChromeOcclusion {
    private ChromeOcclusion(){}
    public static List<int[]> visible(int[] area,List<int[]> blockers){
        List<int[]> pieces=new ArrayList<>();
        if(area[0]<area[2]&&area[1]<area[3])pieces.add(area.clone());
        for(int[] b:blockers){
            List<int[]> next=new ArrayList<>();
            for(int[] r:pieces){
                int l=Math.max(r[0],b[0]),t=Math.max(r[1],b[1]),rr=Math.min(r[2],b[2]),bb=Math.min(r[3],b[3]);
                if(l>=rr||t>=bb){next.add(r);continue;}
                add(next,r[0],r[1],r[2],t);add(next,r[0],bb,r[2],r[3]);
                add(next,r[0],t,l,bb);add(next,rr,t,r[2],bb);
            }
            pieces=next;
        }
        return pieces;
    }
    private static void add(List<int[]> out,int l,int t,int r,int b){if(l<r&&t<b)out.add(new int[]{l,t,r,b});}
}
