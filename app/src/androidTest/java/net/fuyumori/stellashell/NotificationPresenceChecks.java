package net.fuyumori.stellashell;

import android.app.Instrumentation;
import android.app.Notification;
import android.content.Context;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic metadata only: never posts, cancels, logs or queries personal notifications. */
final class NotificationPresenceChecks {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static StatusBarNotification item(Context c,String pkg,int user,int id,String group,boolean summary){
        Notification.Builder b=new Notification.Builder(c,"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Synthetic");
        if(group!=null)b.setGroup(group);b.setGroupSummary(summary);
        int uid=user*100000+10000;
        return new StatusBarNotification(pkg,pkg,id,null,uid,0,0,b.build(),UserHandle.getUserHandleForUid(uid),id);
    }
    static void run(Instrumentation test){
        Context c=test.getTargetContext();AtomicInteger queries=new AtomicInteger();
        StatusBarNotification summary=item(c,"fixture.chat",0,1,"group",true);
        StatusBarNotification one=item(c,"fixture.chat",0,2,"group",false);
        StatusBarNotification work=item(c,"fixture.chat",10,3,"group",false);
        StatusBarNotification own=item(c,c.getPackageName(),0,4,null,false);
        List<StatusBarNotification> active=new ArrayList<>(Arrays.asList(summary,one,work,own));
        NotificationPresence presence=new NotificationPresence(c.getPackageName(),()->{queries.incrementAndGet();return active.toArray(new StatusBarNotification[0]);});
        presence.connect();require(queries.get()==1,"Connection did not initialize once");
        for(int i=0;i<500;i++){
            presence.posted(one);
            for(int observer=0;observer<4;observer++)require(presence.counts(0).get("fixture.chat")==1,"Duplicate notification counted");
        }
        require(queries.get()==1,"Badge observers/content updates refetched full notification payloads");
        require(!presence.counts(0).containsKey(c.getPackageName()),"Own shell notification counted");
        require(presence.counts(10).get("fixture.chat")==1,"Work profile count mixed");
        require(presence.counts(0).equals(NotificationApps.collect(Arrays.asList(summary,one,work),UserHandle.getUserHandleForUid(10000))),"Metadata diverged from card grouping");
        presence.removed(one);require(presence.counts(0).get("fixture.chat")==1,"Standalone summary lost");
        presence.clear();require(!presence.any()&&presence.counts(0).isEmpty(),"Disconnected metadata retained");
        active.clear();presence.connect();require(queries.get()==2&&!presence.any(),"Reconnect kept stale entries");
        // A failed initial read stays empty; recovery is event driven, never a timer or badge read.
        AtomicInteger attempts=new AtomicInteger();
        NotificationPresence failure=new NotificationPresence(c.getPackageName(),()->{if(attempts.incrementAndGet()==1)throw new SecurityException("fixture");return new StatusBarNotification[]{one};});
        failure.connect();for(int i=0;i<20;i++)require(failure.counts(0).isEmpty(),"Failed read exposed stale state");
        require(attempts.get()==1,"Badge reads retried denied access");failure.posted(one);
        require(attempts.get()==2&&failure.counts(0).get("fixture.chat")==1,"Next event did not recover initialization");
        rankingChecks(c);
        test.runOnMainSync(()->{
            Context themed=new android.view.ContextThemeWrapper(c,R.style.AppTheme);
            android.widget.ScrollView scroll=new android.widget.ScrollView(themed);
            android.widget.LinearLayout rows=Ui.column(themed);scroll.addView(rows);
            NotificationList list=new NotificationList(rows,item->{},item->{});
            list.restoreExpandedGroups(Collections.singletonList("0:fixture.chat"));
            list.render(Arrays.asList(summary,one,work));require(rows.getChildCount()>0,"Fixture did not render");
            list.hide();require(rows.getChildCount()==0,"Hidden page retained notification views");
            require(list.expandedGroups().contains("0:fixture.chat"),"Tab hiding lost expansion state");
            list.hide();list.render(Collections.singletonList(one));require(rows.getChildCount()>0,"Reopened page did not render");
            list.clear();require(rows.getChildCount()==0,"Privacy clear retained rows");
        });
    }

    /**
     * Typed ranking contract only: no hidden framework fields or parcel wire format.
     * The native private-field fixture failed on REDMAGIC with NoSuchFieldException(mKey).
     * Real RankingMap creation/framework adaptation and live service dispatch are unverified.
     */
    private static NotificationPresence.Rankings ranking(StatusBarNotification[] items,String... overrides){
        require(items.length==overrides.length,"Ranking fixture length mismatch");
        Map<String,String> entries=new LinkedHashMap<>();
        for(int i=0;i<items.length;i++)entries.put(items[i].getKey(),overrides[i]);
        return new NotificationPresence.Rankings(){
            private String current;
            public String[] keys(){return entries.keySet().toArray(new String[0]);}
            public boolean find(String key){current=key;return entries.containsKey(key);}
            public String overrideGroup(){return entries.get(current);}
        };
    }

    private static void rankingChecks(Context c){
        AtomicInteger queries=new AtomicInteger();
        StatusBarNotification summary=item(c,"fixture.ranking",0,21,"summary-group",true);
        StatusBarNotification one=item(c,"fixture.ranking",0,22,"child-group",false);
        StatusBarNotification two=item(c,"fixture.ranking",0,23,"child-group",false);
        StatusBarNotification work=item(c,"fixture.ranking",10,24,"summary-group",false);
        StatusBarNotification marker=item(c,"fixture.marker",0,25,null,false);
        NotificationPresence presence=new NotificationPresence(c.getPackageName(),()->{
            queries.incrementAndGet();return new StatusBarNotification[]{summary,one,two,work};
        });
        try{
            // Structural guard only: executing service callbacks would touch static live observers.
            ShellNotifications.class.getDeclaredMethod("onNotificationPosted",StatusBarNotification.class,RankingMap.class);
            ShellNotifications.class.getDeclaredMethod("onNotificationRemoved",StatusBarNotification.class,RankingMap.class);
            presence.connect();require(presence.counts(0).get("fixture.ranking")==3,"Initial separate summary group miscounted");
            presence.posted(marker);
            StatusBarNotification[] all={summary,one,two,work,marker};
            NotificationPresence.Rankings grouped=ranking(all,"auto","auto","separate",null,null);
            require(presence.ranking(grouped),"Override-only change did not invalidate counts");
            require(presence.counts(0).get("fixture.ranking")==2,"Regrouped summary duplicated its child");
            require(presence.counts(10).get("fixture.ranking")==1,"Override grouping crossed users");
            require(presence.counts(0).get("fixture.marker")==1,"Posted item lost during ranking reconciliation");
            Map<String,Integer> unchanged=presence.counts(0);
            require(!presence.ranking(grouped)&&presence.counts(0)==unchanged,"Unchanged ranking contract rebuilt scalar cache");

            require(presence.ranking(ranking(all,"auto",null,null,null,null)),"Clearing overrides did not restore base groups");
            require(presence.counts(0).get("fixture.ranking")==3,"Base-group restoration kept stale summary suppression");
            // A ranking accompanying another callback also reconciles omitted live keys.
            require(presence.ranking(ranking(new StatusBarNotification[]{summary,one,work},null,null,null)),
                    "Missing ranking keys did not remove stale metadata");
            require(presence.counts(0).get("fixture.ranking")==2&&!presence.counts(0).containsKey("fixture.marker"),
                    "Ranking pruning retained removed child or package");
            presence.removed(one);
            presence.ranking(ranking(new StatusBarNotification[]{summary,work},null,null));
            require(presence.counts(0).get("fixture.ranking")==1,"Removal plus ranking lost standalone summary");
            require(presence.ranking(ranking(new StatusBarNotification[0])),"Empty ranking contract did not invalidate metadata");
            require(!presence.any()&&presence.counts(0).isEmpty()&&presence.counts(10).isEmpty(),"Empty ranking retained another user");
            require(queries.get()==1,"Ranking/post/removal events refetched full payloads");
            presence.clear();
            require(!presence.ranking(grouped)&&!presence.any(),"Disconnected presence accepted late ranking metadata");
            presence.connect();require(queries.get()==2&&presence.counts(0).get("fixture.ranking")==3,
                    "Reconnect reused old override metadata");
        }catch(ReflectiveOperationException unavailable){
            throw new AssertionError("RankingMap callback overload missing",unavailable);
        }finally{presence.clear();}
    }
}
