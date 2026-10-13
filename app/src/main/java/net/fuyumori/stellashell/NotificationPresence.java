package net.fuyumori.stellashell;

import android.app.Notification;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import java.util.*;
import java.util.function.Supplier;
import net.fuyumori.stellashell.core.notifications.NotificationIndex;

/** Event-owned badge metadata. Full payloads are read once on connect, not by every surface. */
final class NotificationPresence {
    /** Ranking input port: native API adapter below, isolated fixtures need no hidden Android fields. */
    interface Rankings {
        String[] keys();
        boolean find(String key);
        String overrideGroup();
    }
    private static final class Metadata {
        final String key,pkg,baseGroup;
        final int user;
        final UserHandle handle;
        final boolean baseGrouped,summary;
        String override;
        Metadata(StatusBarNotification item){
            Notification n=item.getNotification();key=item.getKey();pkg=item.getPackageName();user=item.getUserId();handle=item.getUser();
            baseGrouped=n.getGroup()!=null||n.getSortKey()!=null;
            baseGroup=n.getGroup()!=null?"g:"+n.getGroup():baseGrouped?"c:"+n.getChannelId():"k:"+key;
            summary=(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0;override=item.getOverrideGroupKey();
        }
        NotificationIndex.Item item(){return new NotificationIndex.Item(key,pkg,user,override==null?baseGroup:"g:"+override,baseGrouped||override!=null,summary);}
    }
    private final String ownPackage;
    private final Supplier<StatusBarNotification[]> source;
    private final Map<String,Metadata> metadata=new HashMap<>();
    private final NotificationIndex index=new NotificationIndex();
    private boolean initialized;
    NotificationPresence(String ownPackage,Supplier<StatusBarNotification[]> source){this.ownPackage=ownPackage;this.source=source;}
    void connect(){
        clear();
        try{StatusBarNotification[] snapshot=source.get();if(snapshot!=null)for(StatusBarNotification item:snapshot)put(item);initialized=true;}
        catch(SecurityException|IllegalStateException unavailable){clear();}
    }
    private void put(StatusBarNotification item){
        if(item==null||ownPackage.equals(item.getPackageName()))return;
        Metadata next=new Metadata(item);metadata.put(next.key,next);index.put(next.item());
    }
    void posted(StatusBarNotification item){if(!initialized)connect();if(initialized)put(item);}
    void removed(StatusBarNotification item){if(item!=null){metadata.remove(item.getKey());index.remove(item.getKey());}}
    boolean ranking(RankingMap ranking){
        if(!initialized||ranking==null)return false;
        return ranking(new Rankings(){
            private final Ranking entry=new Ranking();
            public String[] keys(){return ranking.getOrderedKeys();}
            public boolean find(String key){return ranking.getRanking(key,entry);}
            public String overrideGroup(){return entry.getOverrideGroupKey();}
        });
    }
    boolean ranking(Rankings ranking){
        if(!initialized||ranking==null)return false;
        Set<String> active=new HashSet<>(Arrays.asList(ranking.keys()));
        boolean changed=metadata.keySet().retainAll(active);index.retainKeys(active);
        for(Metadata item:metadata.values())if(ranking.find(item.key))changed=regroup(item,ranking.overrideGroup())||changed;
        return changed;
    }
    private boolean regroup(Metadata item,String override){
        if(Objects.equals(item.override,override))return false;item.override=override;index.put(item.item());return true;
    }
    Map<String,Integer> counts(int user){return index.counts(user);}
    Map<String,Integer> counts(UserHandle user){
        for(Metadata item:metadata.values())if(user.equals(item.handle))return index.counts(item.user);
        return Collections.emptyMap();
    }
    boolean any(){return !index.isEmpty();}
    void clear(){initialized=false;metadata.clear();index.clear();}
}
