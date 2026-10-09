package net.fuyumori.stellashell.core.layout;

/** Type-B touchscreen frames only. Neither keyboard events nor raw input are retained. */
public final class TouchFrames {
    public interface Listener {
        void down(float x,float y,long millis);
        void move(float x,float y,long millis);
        void up(float x,float y,long millis);
        void cancel();
    }
    private final Listener listener;
    private final int minX,maxX,minY,maxY,rotation;
    private final float width,height;
    private final boolean[] active=new boolean[32];
    private final int[] x=new int[32],y=new int[32],known=new int[32];
    private int slot,previous=-1;
    private boolean blocked,dropped,clearDropped,replaced;
    private float lastX,lastY;
    public TouchFrames(int minX,int maxX,int minY,int maxY,int rotation,float width,float height,Listener listener) {
        if(maxX<=minX||maxY<=minY||rotation<0||rotation>3||width<=0||height<=0)throw new IllegalArgumentException();
        this.minX=minX;this.maxX=maxX;this.minY=minY;this.maxY=maxY;
        this.rotation=rotation;this.width=width;this.height=height;this.listener=listener;
    }
    public void event(int type,int code,int value,long time) {
        if(type==0&&code==3){cancel();dropped=true;clearDropped=false;return;}
        if(dropped){
            if(type==1&&code==0x14a&&value==0)clearDropped=true;
            if(type==0&&code==0&&clearDropped){java.util.Arrays.fill(active,false);java.util.Arrays.fill(known,0);dropped=false;blocked=false;slot=0;}
            return;
        }
        if(type==3){
            if(code==0x2f){slot=value;if(slot<0||slot>=32)cancel();return;}
            if(slot<0||slot>=32)return;
            if(code==0x39){
                if(value>=0){
                    if(previous==slot)replaced=true;
                    for(int i=0;i<32;i++)if(i!=slot&&active[i]){cancel();break;}
                    // Type-B axes are per-slot state; unchanged axes may be omitted on reuse.
                    active[slot]=true;
                }
                else active[slot]=false;
            }else if(code==0x35){x[slot]=value;known[slot]|=1;}
            else if(code==0x36){y[slot]=value;known[slot]|=2;}
        }else if(type==0&&code==0)frame(time);
    }
    private void frame(long time){
        int count=0,current=-1;
        for(int i=0;i<32;i++)if(active[i]){count++;current=i;}
        if(count>1||replaced){cancel();replaced=false;}
        if(count==0){
            if(previous>=0&&!blocked)listener.up(lastX,lastY,time);
            previous=-1;blocked=false;return;
        }
        if(blocked||known[current]!=3)return;
        if(previous>=0&&current!=previous){cancel();return;}
        float nx=(x[current]-minX)/(float)(maxX-minX),ny=(y[current]-minY)/(float)(maxY-minY);
        if(nx<0||nx>1||ny<0||ny>1){cancel();return;}
        float rx=rotation==1?ny:rotation==2?1-nx:rotation==3?1-ny:nx;
        float ry=rotation==1?1-nx:rotation==2?1-ny:rotation==3?nx:ny;
        lastX=rx*width;lastY=ry*height;
        if(previous<0)listener.down(lastX,lastY,time);else listener.move(lastX,lastY,time);
        previous=current;
    }
    public void cancel(){listener.cancel();previous=-1;blocked=true;}
}
