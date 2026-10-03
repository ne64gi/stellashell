package net.fuyumori.stellashell;

import java.util.*;

/** Write-ahead descriptor ownership and retryable cleanup, independent of Android/Binder lifetime. */
final class MouseRoutingLease {
    interface Backend {
        int association(String descriptor)throws Exception;
        int generation(String descriptor)throws Exception;
        boolean targetAvailable(int target)throws Exception;
        void add(String descriptor,String uniqueId)throws Exception;
        void remove(String descriptor)throws Exception;
        boolean awaitReleased(String descriptor,int target,int generation,boolean reconfigured,int timeout)throws Exception;
    }
    private final Backend backend;private final Map<String,Integer> owned=new HashMap<>();
    MouseRoutingLease(Backend backend){this.backend=backend;}
    boolean contains(String descriptor){return owned.containsKey(descriptor);}
    int size(){return owned.size();}
    boolean isEmpty(){return owned.isEmpty();}
    Set<String> descriptors(){return new HashSet<>(owned.keySet());}
    boolean route(String descriptor,int target,String uniqueId)throws Exception{
        if(contains(descriptor))return true;
        if(backend.association(descriptor)>=0)return false;
        // A Binder transaction can take effect and then fail its reply. Retain
        // ownership before dispatch so that uncertain writes still get cleanup.
        owned.put(descriptor,target);backend.add(descriptor,uniqueId);return true;
    }
    void releaseAll(){for(String descriptor:descriptors())try{release(descriptor);}catch(Exception ignored){/* Keep it for an autonomous retry. */}}
    void release(String descriptor)throws Exception{
        Integer target=owned.get(descriptor);if(target==null)return;
        int current=backend.association(descriptor);
        if(current>=0&&current!=target){owned.remove(descriptor);return;}
        backend.remove(descriptor);
        // With a disconnected viewport, -1 can mean "display missing", not
        // "InputReader forgot the cached descriptor". Clear that cache too.
        if(current<0||!backend.awaitReleased(descriptor,target,-1,false,160)||!backend.targetAvailable(target)){
            int before=backend.generation(descriptor);backend.add(descriptor,"");
            if(!backend.awaitReleased(descriptor,target,before,true,500))throw new IllegalStateException("Mouse routing cleanup did not complete");
            int after=backend.association(descriptor);
            if(after>=0&&after!=target){owned.remove(descriptor);return;}
            backend.remove(descriptor);
        }
        owned.remove(descriptor);
    }
    /** Native InputReader cache, not the Java runtime association map or logical display ID. */
    static boolean readerCacheCleared(String dump,int deviceId){
        boolean device=false;
        for(String line:dump.split("\\r?\\n")){
            String value=line.trim();
            if(value.matches("Device \\d+:.*"))device=value.startsWith("Device "+deviceId+":");
            if(device&&value.startsWith("AssociatedDisplayUniqueIdByDescriptor:")){
                String cached=value.substring("AssociatedDisplayUniqueIdByDescriptor:".length()).trim();
                return cached.isEmpty()||"<none>".equals(cached);
            }
        }
        return false;
    }
}
