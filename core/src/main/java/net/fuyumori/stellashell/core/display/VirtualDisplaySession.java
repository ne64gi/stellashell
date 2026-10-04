package net.fuyumori.stellashell.core.display;

import java.util.*;
import java.util.regex.*;

/** Only a live scrcpy new-display process with a matching creation record is eligible. */
public final class VirtualDisplaySession {
    private VirtualDisplaySession(){}
    private static final Pattern CREATED=Pattern.compile("(?m)^\\s*(\\d+\\.\\d+)\\s+(\\d+)\\s+\\d+\\s+I\\s+scrcpy\\s*:\\s*New display: \\d+x\\d+/\\d+ \\(id=(\\d+)\\)\\s*$");
    public static final class Creation {
        public final int pid,display;final double time;
        Creation(int pid,int display,double time){this.pid=pid;this.display=display;this.time=time;}
    }
    public static List<Creation> creations(String log){
        List<Creation> result=new ArrayList<>();Matcher m=CREATED.matcher(log);
        while(m.find())try{result.add(new Creation(Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),Double.parseDouble(m.group(1))));}catch(NumberFormatException ignored){}
        return result;
    }
    public static boolean command(String cmd){
        String[] args=cmd.split("\u0000");
        if(args.length<4||!(args[0].equals("app_process")||args[0].endsWith("/app_process")||args[0].endsWith("/app_process64")||args[0].endsWith("/app_process32")))return false;
        if(!args[1].equals("/")||!args[2].equals("com.genymobile.scrcpy.Server"))return false;
        for(int i=4;i<args.length;i++)if(args[i].startsWith("new_display="))return true;
        return false;
    }
    public static long startTicks(String stat){
        int end=stat.lastIndexOf(')');if(end<0)throw new IllegalArgumentException("Missing process identity");
        return Long.parseLong(stat.substring(end+1).trim().split("\\s+")[19]);
    }
    public static boolean belongs(Creation c,int display,long start,long ticksPerSecond,long bootSeconds){
        return display>0&&c.display==display&&c.pid>1&&ticksPerSecond>0&&bootSeconds>0&&start>=0
                &&c.time>=bootSeconds+(double)start/ticksPerSecond;
    }
}
