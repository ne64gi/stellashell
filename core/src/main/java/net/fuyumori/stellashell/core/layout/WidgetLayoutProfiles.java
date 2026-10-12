package net.fuyumori.stellashell.core.layout;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Geometry only: widget content and allocated Android IDs belong to the host catalog. */
public final class WidgetLayoutProfiles {
    public enum Profile {
        PHONE_PORTRAIT("phone_portrait"), PHONE_LANDSCAPE("phone_landscape"), EXTERNAL("external");
        public final String key;
        Profile(String key){this.key=key;}
    }
    public static Profile resolve(boolean phone,boolean landscape){
        return !phone?Profile.EXTERNAL:landscape?Profile.PHONE_LANDSCAPE:Profile.PHONE_PORTRAIT;
    }
    public static final class Geometry {
        public final int x,y,w,h,mode,baseW,baseH;
        public Geometry(int x,int y,int w,int h,int mode,int baseW,int baseH){
            this.x=Math.max(0,x);this.y=Math.max(0,y);this.w=Math.max(80,w);this.h=Math.max(60,h);
            this.mode=WidgetGeometry.mode(mode);this.baseW=Math.max(0,baseW);this.baseH=Math.max(0,baseH);
        }
    }
    private static final String HEADER="widget-layouts-v1";
    private final EnumMap<Profile,Map<Integer,Geometry>> layouts=new EnumMap<>(Profile.class);
    private final List<String> unknownProfiles=new ArrayList<>();

    /** Missing/corrupt profiles start from the legacy geometry, never a later edited profile. */
    public WidgetLayoutProfiles(String saved,Map<Integer,Geometry> catalog){
        EnumMap<Profile,Map<Integer,Geometry>> decoded=decode(saved);
        if(saved!=null&&saved.startsWith(HEADER+"\n"))for(String line:saved.substring(HEADER.length()+1).split("\\n",-1)){
            boolean known=false;for(Profile profile:Profile.values())if(line.startsWith(profile.key+"|"))known=true;
            if(!known&&!line.isEmpty())unknownProfiles.add(line);
        }
        for(Profile profile:Profile.values()){
            Map<Integer,Geometry> previous=decoded.get(profile),layout=new LinkedHashMap<>();
            for(Map.Entry<Integer,Geometry> item:catalog.entrySet()){
                Geometry geometry=previous==null?null:previous.get(item.getKey());
                layout.put(item.getKey(),geometry==null?item.getValue():geometry);
            }
            layouts.put(profile,layout);
        }
    }
    public Map<Integer,Geometry> snapshot(Profile profile){
        return Collections.unmodifiableMap(new LinkedHashMap<>(layouts.get(profile)));
    }
    /** Add/delete affects the shared catalog; changing existing geometry affects only this profile. */
    public void capture(Profile profile,Map<Integer,Geometry> catalog){
        for(Map<Integer,Geometry> layout:layouts.values()){
            layout.keySet().retainAll(catalog.keySet());
            for(Map.Entry<Integer,Geometry> item:catalog.entrySet())layout.putIfAbsent(item.getKey(),item.getValue());
        }
        layouts.put(profile,new LinkedHashMap<>(catalog));
    }
    /** Numeric-only versioned codec: content, image paths and provider IDs are not duplicated. */
    public String encode(){
        StringBuilder output=new StringBuilder(HEADER);
        for(Profile profile:Profile.values()){
            output.append('\n').append(profile.key).append('|');
            boolean first=true;
            for(Map.Entry<Integer,Geometry> item:layouts.get(profile).entrySet()){
                if(!first)output.append(';');first=false;
                Geometry g=item.getValue();
                output.append(item.getKey()).append(',').append(g.x).append(',').append(g.y).append(',').append(g.w)
                    .append(',').append(g.h).append(',').append(g.mode).append(',').append(g.baseW).append(',').append(g.baseH);
            }
        }
        for(String line:unknownProfiles)output.append('\n').append(line);
        return output.toString();
    }
    private static EnumMap<Profile,Map<Integer,Geometry>> decode(String saved){
        EnumMap<Profile,Map<Integer,Geometry>> result=new EnumMap<>(Profile.class);
        if(saved==null)return result;
        String[] lines=saved.split("\\n",-1);if(lines.length==0||!HEADER.equals(lines[0]))return result;
        for(int i=1;i<lines.length;i++)for(Profile profile:Profile.values()){
            String prefix=profile.key+"|";if(!lines[i].startsWith(prefix))continue;
            try{
                Map<Integer,Geometry> layout=new LinkedHashMap<>();String body=lines[i].substring(prefix.length());
                if(!body.isEmpty())for(String row:body.split(";",-1)){
                    String[] fields=row.split(",",-1);if(fields.length!=8)throw new IllegalArgumentException();
                    int[] n=new int[8];for(int j=0;j<n.length;j++)n[j]=Integer.parseInt(fields[j]);
                    if(layout.put(n[0],new Geometry(n[1],n[2],n[3],n[4],n[5],n[6],n[7]))!=null)throw new IllegalArgumentException();
                }
                result.put(profile,layout);
            }catch(IllegalArgumentException ignored){result.remove(profile);}
        }
        return result;
    }
}
