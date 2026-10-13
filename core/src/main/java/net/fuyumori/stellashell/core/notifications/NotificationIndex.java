package net.fuyumori.stellashell.core.notifications;

import java.util.*;

/** Live identity/group metadata only; never owns a notification payload or Android object. */
public final class NotificationIndex {
    public static final class Item {
        public final String key, packageName, group;
        public final int user;
        public final boolean grouped, summary;
        public Item(String key,String packageName,int user,String group,boolean grouped,boolean summary){
            this.key=Objects.requireNonNull(key);this.packageName=Objects.requireNonNull(packageName);
            this.user=user;this.group=group;this.grouped=grouped;this.summary=summary;
        }
        boolean same(Item other){return user==other.user&&grouped==other.grouped&&summary==other.summary
                &&packageName.equals(other.packageName)&&Objects.equals(group,other.group);}
    }
    private final Map<String,Item> items=new HashMap<>();
    private Map<Integer,Map<String,Integer>> cached;

    public void put(Item item){Item old=items.get(item.key);if(old!=null&&old.same(item))return;items.put(item.key,item);cached=null;}
    public void remove(String key){if(items.remove(key)!=null)cached=null;}
    public void retainKeys(Set<String> keys){if(items.keySet().retainAll(keys))cached=null;}
    public void clear(){items.clear();cached=null;}
    public boolean isEmpty(){return items.isEmpty();}
    public Map<String,Integer> counts(int user){
        if(cached==null){
            Map<Integer,Map<String,Set<String>>> children=new HashMap<>();
            for(Item item:items.values())if(item.grouped&&!item.summary)
                children.computeIfAbsent(item.user,k->new HashMap<>()).computeIfAbsent(item.packageName,k->new HashSet<>()).add(item.group);
            Map<Integer,Map<String,Integer>> next=new HashMap<>();
            for(Item item:items.values()){
                Set<String> groups=children.getOrDefault(item.user,Collections.emptyMap()).getOrDefault(item.packageName,Collections.emptySet());
                if(item.summary&&groups.contains(item.group))continue;
                next.computeIfAbsent(item.user,k->new HashMap<>()).merge(item.packageName,1,Integer::sum);
            }
            next.replaceAll((k,v)->Collections.unmodifiableMap(v));cached=next;
        }
        return cached.getOrDefault(user,Collections.emptyMap());
    }
}
