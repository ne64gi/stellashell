package net.fuyumori.stellashell.feature.launch;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/** Private scalar labels only; installed membership and identities always come from a fresh query. */
public final class LabelCache {
    static final int MAX_ENTRIES=2048,MAX_BYTES=1024*1024;
    private static final int MAGIC=0x534c4231;
    public static final class Item {
        final String component,identity;
        final Supplier<String> loader;
        public Item(String component,String identity,Supplier<String> loader){
            this.component=Objects.requireNonNull(component);this.identity=identity;this.loader=Objects.requireNonNull(loader);
        }
    }
    private static final class Value {
        final String identity,label;
        Value(String identity,String label){this.identity=identity;this.label=label;}
    }
    private static final class Snapshot {
        final String configuration;
        final Map<String,Value> values;
        Snapshot(String configuration,Map<String,Value> values){this.configuration=configuration;this.values=Collections.unmodifiableMap(values);}
    }
    private final File file;
    private final Object lock=new Object();
    private long generation;
    private Snapshot snapshot;
    public LabelCache(File file){this.file=Objects.requireNonNull(file);}

    /** Capture before querying membership/identities, not after a potentially stale query. */
    public long beginRefresh(){synchronized(lock){return generation;}}

    /** Call on a worker. A stale refresh may return to its caller but cannot publish or write. */
    public Map<String,String> resolve(String configuration,List<Item> current){
        return resolve(beginRefresh(),configuration,current);
    }
    public Map<String,String> resolve(long request,String configuration,List<Item> current){
        Objects.requireNonNull(configuration);
        Snapshot old;
        synchronized(lock){
            if(snapshot==null)snapshot=read();
            old=snapshot;
        }
        Map<String,String> result=new LinkedHashMap<>();Map<String,Value> next=new LinkedHashMap<>();
        int bytes=14+utfBytes(configuration);
        boolean same=old.configuration.equals(configuration),cacheable=configuration.length()<=4096;
        for(Item item:current){
            if(result.containsKey(item.component))continue;
            Value previous=same?old.values.get(item.component):null;
            String label=item.identity!=null&&previous!=null&&item.identity.equals(previous.identity)
                    ?previous.label:Objects.requireNonNull(item.loader.get());
            result.put(item.component,label);
            if(!cacheable||item.identity==null||item.component.length()>512||item.identity.length()>256||label.length()>2048)continue;
            int size=6+utfBytes(item.component)+utfBytes(item.identity)+utfBytes(label);
            if(next.size()<MAX_ENTRIES&&bytes+size<=MAX_BYTES){next.put(item.component,new Value(item.identity,label));bytes+=size;}
        }
        if(cacheable)synchronized(lock){
            if(request==generation){
                Snapshot replacement=new Snapshot(configuration,next);
                if(!equal(snapshot,replacement)){snapshot=replacement;write(replacement);}
            }
        }
        return Collections.unmodifiableMap(result);
    }
    /** Null invalidates all labels; a package invalidation preserves other in-memory labels. */
    public void invalidate(String packageName){
        synchronized(lock){
            generation++;
            if(packageName==null||snapshot==null)snapshot=new Snapshot("",new LinkedHashMap<>());
            else{
                Map<String,Value> remaining=new LinkedHashMap<>(snapshot.values);
                remaining.keySet().removeIf(component->component.startsWith(packageName+"/"));
                snapshot=new Snapshot(snapshot.configuration,remaining);
            }
            // Prevent a process restart from accepting a same-version package-change label.
            // A subsequent fresh query rewrites the remaining/current bounded snapshot.
            try{Files.deleteIfExists(file.toPath());}catch(IOException|RuntimeException ignored){}
        }
    }
    private Snapshot read(){
        Snapshot empty=new Snapshot("",new LinkedHashMap<>());
        try{
            long length=file.length();if(length<12||length>MAX_BYTES)return empty;
            byte[] data=new byte[(int)length];
            try(DataInputStream input=new DataInputStream(new FileInputStream(file))){input.readFully(data);if(input.read()!=-1)return empty;}
            CRC32 crc=new CRC32();crc.update(data,0,data.length-4);
            try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(data))){
                if(input.readInt()!=MAGIC)return empty;
                String configuration=input.readUTF();if(configuration.length()>4096)return empty;
                int count=input.readInt();if(count<0||count>MAX_ENTRIES)return empty;
                Map<String,Value> values=new LinkedHashMap<>();
                for(int i=0;i<count;i++){
                    String component=input.readUTF(),identity=input.readUTF(),label=input.readUTF();
                    if(component.isEmpty()||component.length()>512||identity.length()>256||label.length()>2048||values.containsKey(component))return empty;
                    values.put(component,new Value(identity,label));
                }
                if(input.readInt()!=(int)crc.getValue()||input.read()!=-1)return empty;
                return new Snapshot(configuration,values);
            }
        }catch(IOException|RuntimeException ignored){return empty;}
    }
    private void write(Snapshot value){
        File temporary=null;
        try{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream output=new DataOutputStream(bytes)){
                output.writeInt(MAGIC);output.writeUTF(value.configuration);output.writeInt(value.values.size());
                for(Map.Entry<String,Value> entry:value.values.entrySet()){
                    output.writeUTF(entry.getKey());output.writeUTF(entry.getValue().identity);output.writeUTF(entry.getValue().label);
                }
                CRC32 crc=new CRC32();crc.update(bytes.toByteArray());output.writeInt((int)crc.getValue());
            }
            byte[] data=bytes.toByteArray();if(data.length>MAX_BYTES)return;
            temporary=new File(file.getPath()+".tmp");
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(data);}
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException|RuntimeException ignored){/* Labels remain usable; persistence is optional. */}
        finally{if(temporary!=null)temporary.delete();}
    }
    private static int utfBytes(String value){
        int bytes=0;for(int i=0;i<value.length();i++){char c=value.charAt(i);bytes+=c>=1&&c<=127?1:c<=2047?2:3;}return bytes;
    }
    private static boolean equal(Snapshot left,Snapshot right){
        if(!left.configuration.equals(right.configuration)||!left.values.keySet().equals(right.values.keySet()))return false;
        for(Map.Entry<String,Value> entry:left.values.entrySet()){
            Value other=right.values.get(entry.getKey()),value=entry.getValue();
            if(!value.identity.equals(other.identity)||!value.label.equals(other.label))return false;
        }
        return true;
    }
}
