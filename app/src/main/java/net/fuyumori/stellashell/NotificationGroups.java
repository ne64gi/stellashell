package net.fuyumori.stellashell;

import android.app.Notification;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** A live snapshot only: one section per package and Android user, never per display label. */
final class NotificationGroups {
    static final class Group {
        final String key,packageName;
        final UserHandle user;
        final List<StatusBarNotification> items=new ArrayList<>();
        Group(StatusBarNotification item){key=key(item);packageName=item.getPackageName();user=item.getUser();}
    }
    private static String key(StatusBarNotification item){return item.getUserId()+":"+item.getPackageName();}
    private static boolean summary(StatusBarNotification item){return (item.getNotification().flags&Notification.FLAG_GROUP_SUMMARY)!=0;}

    static List<Group> collect(List<StatusBarNotification> snapshot){
        List<StatusBarNotification> sorted=new ArrayList<>(snapshot);
        sorted.sort((a,b)->{int time=Long.compare(b.getPostTime(),a.getPostTime());return time!=0?time:a.getKey().compareTo(b.getKey());});
        LinkedHashMap<String,Group> groups=new LinkedHashMap<>();
        for(StatusBarNotification item:sorted)groups.computeIfAbsent(key(item),unused->new Group(item)).items.add(item);
        for(Group group:groups.values()){
            // Android's summary would repeat the children. Keep it when it is the only
            // available representation (or belongs to a different Android group).
            Set<String> withChildren=new HashSet<>();
            for(StatusBarNotification item:group.items)if(item.isGroup()&&!summary(item))withChildren.add(item.getGroupKey());
            group.items.removeIf(item->summary(item)&&withChildren.contains(item.getGroupKey()));
        }
        List<Group> result=new ArrayList<>(groups.values());
        result.sort((a,b)->{int time=Long.compare(b.items.get(0).getPostTime(),a.items.get(0).getPostTime());return time!=0?time:a.key.compareTo(b.key);});
        return result;
    }
}
