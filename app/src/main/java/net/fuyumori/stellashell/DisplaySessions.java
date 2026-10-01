package net.fuyumori.stellashell;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.Display;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shell-side display inventory and fail-closed scrcpy session termination. */
final class DisplaySessions {
    interface Commands {String run(String... args)throws Exception;}
    private final Map<Integer,String> known=new HashMap<>();
    private final DisplayManager manager;private final Commands commands;
    DisplaySessions(Context c,Commands commands){manager=c.getSystemService(DisplayManager.class);this.commands=commands;}
    private static String read(String path)throws Exception{return new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8);}
    private static Object field(Display d,String method)throws Exception{return Display.class.getMethod(method).invoke(d);}
    private static int type(Display d)throws Exception{return (Integer)field(d,"getType");}
    private static String identity(Display d)throws Exception{return (String)field(d,"getUniqueId");}
    private static boolean scrcpy(Display d)throws Exception{
        return d.getDisplayId()>0&&type(d)==5&&d.getName().equals("scrcpy")
                &&(Integer)field(d,"getOwnerUid")==2000&&"com.android.shell".equals(field(d,"getOwnerPackageName"));
    }
    private String logs()throws Exception{return commands.run("/system/bin/logcat","-d","-b","main","-v","epoch","-s","scrcpy:I","*:S");}
    private long boot()throws Exception{
        for(String line:read("/proc/stat").split("\n"))if(line.startsWith("btime "))return Long.parseLong(line.substring(6).trim());
        throw new IllegalStateException("Boot identity unavailable");
    }
    private long process(int pid)throws Exception{
        if(pid<=1||Os.stat("/proc/"+pid).st_uid!=2000||!VirtualDisplaySession.command(read("/proc/"+pid+"/cmdline")))throw new IllegalStateException("Session changed");
        return VirtualDisplaySession.startTicks(read("/proc/"+pid+"/stat"));
    }
    private String token(Display d,List<VirtualDisplaySession.Creation> records,long boot)throws Exception{
        if(!scrcpy(d))return "";
        String remembered=known.get(d.getDisplayId());
        if(remembered!=null)try{
            String[] parts=remembered.split(":",3);
            if(process(Integer.parseInt(parts[0]))==Long.parseLong(parts[1])&&identity(d).equals(parts[2]))return remembered;
        }catch(Exception ignored){}
        known.remove(d.getDisplayId());
        String found="";Set<Integer> matches=new HashSet<>();
        for(VirtualDisplaySession.Creation record:records){
            if(record.display!=d.getDisplayId())continue;
            try{
                long start=process(record.pid);
                if(!VirtualDisplaySession.belongs(record,d.getDisplayId(),start,Os.sysconf(OsConstants._SC_CLK_TCK),boot))continue;
                matches.add(record.pid);found=record.pid+":"+start+":"+identity(d);
            }catch(Exception ignored){}
        }
        if(matches.size()!=1)return "";known.put(d.getDisplayId(),found);return found;
    }
    String snapshot()throws Exception{
        List<VirtualDisplaySession.Creation> records=Collections.emptyList();long boot=0;
        try{boot=boot();records=VirtualDisplaySession.creations(logs());}catch(Exception ignored){}
        JSONArray rows=new JSONArray();
        for(Display d:manager.getDisplays()){
            if(!d.isValid()||(d.getFlags()&Display.FLAG_PRIVATE)!=0)continue;
            String kind="external",close="";
            if(d.getDisplayId()==0)kind="device";
            else try{if(type(d)==5){kind="virtual";close=token(d,records,boot);}}catch(Exception ignored){}
            rows.put(new JSONObject().put("id",d.getDisplayId()).put("name",d.getName()).put("kind",kind).put("token",close));
        }
        return rows.toString();
    }
    String close(int id,String expected)throws Exception{
        Display d=manager.getDisplay(id);
        if(id<=0||d==null||!d.isValid()||expected==null||expected.isEmpty())throw new IllegalArgumentException("Virtual display session unavailable");
        List<VirtualDisplaySession.Creation> records=Collections.emptyList();long boot=0;
        try{boot=boot();records=VirtualDisplaySession.creations(logs());}catch(Exception ignored){}
        String actual=token(d,records,boot);
        if(!expected.equals(actual))throw new IllegalStateException("Session changed or its creation record is unavailable; refresh the display list");
        String[] parts=actual.split(":",3);int pid=Integer.parseInt(parts[0]);long start=Long.parseLong(parts[1]);
        if(process(pid)!=start||!identity(d).equals(parts[2]))throw new IllegalStateException("Session changed");
        Os.kill(pid,OsConstants.SIGTERM);
        for(int i=0;i<30;i++){
            SystemClock.sleep(100);Display current=manager.getDisplay(id);
            if(current==null||!current.isValid()){known.remove(id);return "OK";}
        }
        throw new IllegalStateException("Session termination requested, but display removal was not confirmed");
    }
}
