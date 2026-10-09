package net.fuyumori.stellashell;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import net.fuyumori.stellashell.core.layout.CornerDockGesture;
import net.fuyumori.stellashell.core.layout.TouchFrames;

/** Optional passive built-in touchscreen reader. No grabs, injection, input logging or polling. */
final class DockGestureMonitor implements AutoCloseable {
    interface Command { String run(String... args) throws Exception; }
    private final Command command;
    private Session current;
    DockGestureMonitor(Command command){this.command=command;}

    synchronized String observe(IDockGestureListener listener,int width,int height,int density,int rotation){
        if(listener==null||width<100||height<100||width>16384||height>16384||density<72||density>1600||rotation<0||rotation>3)return "ERROR: invalid gesture request";
        close();
        Session session=new Session(listener,width/(density/160f),height/(density/160f),rotation);
        current=session;
        try{listener.asBinder().linkToDeath(session,0);}
        catch(Exception error){current=null;return "ERROR: gesture owner unavailable";}
        Thread setup=new Thread(session::start,"dock-touch-setup");setup.setDaemon(true);setup.start();
        return "OK";
    }
    synchronized void remove(IDockGestureListener listener){
        if(listener!=null&&current!=null&&current.listener.asBinder().equals(listener.asBinder()))close();
    }
    @Override public synchronized void close(){
        Session old=current;current=null;
        if(old==null)return;
        old.listener.asBinder().unlinkToDeath(old,0);
        for(Process process:old.processes)process.destroyForcibly();
        old.processes.clear();
    }
    private final class Session implements IBinder.DeathRecipient {
        final IDockGestureListener listener;
        final float width,height;
        final int rotation;
        final List<Process> processes=new ArrayList<>();
        long lastGesture=-1000;
        Session(IDockGestureListener listener,float width,float height,int rotation){this.listener=listener;this.width=width;this.height=height;this.rotation=rotation;}
        @Override public void binderDied(){remove(listener);}
        void start(){
            try{
                Set<String> names=new HashSet<>();
                // Reject external/virtual devices, including controllers and keyboards.
                for(int id:InputDevice.getDeviceIds()){
                    InputDevice device=InputDevice.getDevice(id);
                    if(device==null||device.isVirtual()||!device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN))continue;
                    if((Boolean)InputDevice.class.getMethod("isExternal").invoke(device))continue;
                    int associated=(Integer)InputDevice.class.getMethod("getAssociatedDisplayId").invoke(device);
                    if(associated>0)continue;
                    names.add(device.getName());
                }
                List<TouchNode> nodes=TouchNode.parse(command.run("/system/bin/getevent","-lp"),names);
                if(nodes.isEmpty()||nodes.size()>2){available(false);return;}
                synchronized(DockGestureMonitor.this){
                    if(current!=this)return;
                    for(TouchNode node:nodes){
                        Process process=new ProcessBuilder("/system/bin/getevent","-t",node.path).redirectErrorStream(true).start();
                        processes.add(process);
                        Thread reader=new Thread(()->read(process,node),"dock-touch-reader");reader.setDaemon(true);reader.start();
                    }
                    available(!processes.isEmpty());
                }
            }catch(Exception failure){
                synchronized(DockGestureMonitor.this){
                    if(current!=this)return;
                    for(Process process:processes)process.destroyForcibly();processes.clear();available(false);
                }
            }
        }
        void available(boolean value){
            synchronized(DockGestureMonitor.this){
                if(current!=this)return;
                try{listener.onAvailability(value);}catch(Exception dead){remove(listener);}
            }
        }
        void gesture(CornerDockGesture.Result result){
            synchronized(DockGestureMonitor.this){
                if(current!=this)return;
                long now=SystemClock.uptimeMillis();
                // Some phones expose mirrored touch nodes. Emit only one completion.
                if(now-lastGesture<500)return;
                lastGesture=now;
                try{listener.onGesture(result.x/width,result.y/height,result.right,rotation);}
                catch(Exception dead){remove(listener);}
            }
        }
        void read(Process process,TouchNode node){
            CornerDockGesture gesture=new CornerDockGesture();
            TouchFrames frames=new TouchFrames(node.minX,node.maxX,node.minY,node.maxY,rotation,width,height,new TouchFrames.Listener(){
                public void down(float x,float y,long t){gesture.beginOnScreen(x,y,t,width,height);}
                public void move(float x,float y,long t){gesture.move(x,y,t);}
                public void up(float x,float y,long t){CornerDockGesture.Result result=gesture.finish(x,y,t);if(result!=null)gesture(result);}
                public void cancel(){gesture.cancel();}
            });
            Pattern event=Pattern.compile("\\[\\s*(\\d+)\\.(\\d{6})\\]\\s+(?:/dev/input/event\\d+:\\s+)?([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})\\s*");
            try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.US_ASCII))){
                String line;
                while((line=input.readLine())!=null){
                    if(line.length()>160)continue;
                    Matcher m=event.matcher(line);if(!m.matches())continue;
                    long millis=Long.parseLong(m.group(1))*1000+Integer.parseInt(m.group(2))/1000;
                    frames.event(Integer.parseInt(m.group(3),16),Integer.parseInt(m.group(4),16),(int)Long.parseLong(m.group(5),16),millis);
                }
            }catch(Exception ignored){/* Closed, disconnected or unsupported: keep fallback handles. */}
            finally{
                frames.cancel();process.destroyForcibly();
                synchronized(DockGestureMonitor.this){if(current==this){processes.remove(process);if(processes.isEmpty())available(false);}}
            }
        }
    }
    private static final class TouchNode {
        String path,name;boolean direct,slot,tracking;
        int minX,maxX,minY,maxY;
        static List<TouchNode> parse(String description,Set<String> names){
            List<TouchNode> result=new ArrayList<>();TouchNode node=null;
            Pattern axis=Pattern.compile(".*(ABS_MT_POSITION_[XY]).*min (-?\\d+), max (-?\\d+).*",Pattern.CASE_INSENSITIVE);
            for(String raw:description.split("\\n")){
                String line=raw.trim();
                if(line.startsWith("add device ")){
                    add(result,node,names);node=new TouchNode();node.path=line.substring(line.indexOf(":")+1).trim();
                }else if(node!=null){
                    if(line.startsWith("name:")){int a=line.indexOf('"'),b=line.lastIndexOf('"');if(a>=0&&b>a)node.name=line.substring(a+1,b);}
                    node.direct|=line.contains("INPUT_PROP_DIRECT");node.slot|=line.contains("ABS_MT_SLOT");node.tracking|=line.contains("ABS_MT_TRACKING_ID");
                    Matcher match=axis.matcher(line);
                    if(match.matches()){
                        int min=Integer.parseInt(match.group(2)),max=Integer.parseInt(match.group(3));
                        if(match.group(1).endsWith("X")){node.minX=min;node.maxX=max;}else{node.minY=min;node.maxY=max;}
                    }
                }
            }
            add(result,node,names);return result;
        }
        static void add(List<TouchNode> result,TouchNode node,Set<String> names){
            if(node!=null&&node.path.matches("/dev/input/event[0-9]+")&&names.contains(node.name)&&node.direct&&node.slot&&node.tracking&&node.maxX>node.minX&&node.maxY>node.minY)result.add(node);
        }
    }
}
