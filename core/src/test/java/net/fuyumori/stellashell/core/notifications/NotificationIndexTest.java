package net.fuyumori.stellashell.core.notifications;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationIndexTest {
    private static NotificationIndex.Item item(String key,String pkg,int user,String group,boolean summary){
        return new NotificationIndex.Item(key,pkg,user,group,group!=null,summary);
    }
    @Test public void summaryDeduplicationAndLastChildRemoval(){
        NotificationIndex index=new NotificationIndex();
        index.put(item("summary","chat",0,"a",true));assertEquals(Integer.valueOf(1),index.counts(0).get("chat"));
        index.put(item("one","chat",0,"a",false));index.put(item("two","chat",0,"a",false));
        assertEquals(Integer.valueOf(2),index.counts(0).get("chat"));
        index.remove("one");index.remove("two");assertEquals(Integer.valueOf(1),index.counts(0).get("chat"));
    }
    @Test public void groupPackageAndUserAreIndependent(){
        NotificationIndex index=new NotificationIndex();
        index.put(item("s","chat",0,"a",true));index.put(item("b","chat",0,"b",false));
        index.put(item("other","mail",0,"a",false));index.put(item("work","chat",10,"a",false));
        assertEquals(Integer.valueOf(2),index.counts(0).get("chat"));
        assertEquals(Integer.valueOf(1),index.counts(0).get("mail"));assertEquals(Integer.valueOf(1),index.counts(10).get("chat"));
    }
    @Test public void contentOnlyUpdatesReuseImmutableCounts(){
        NotificationIndex index=new NotificationIndex();index.put(item("a","chat",0,null,false));
        Map<String,Integer> counts=index.counts(0);
        for(int i=0;i<500;i++)index.put(item("a","chat",0,null,false));
        assertSame(counts,index.counts(0));
        assertThrows(UnsupportedOperationException.class,()->counts.put("other",3));
    }
    @Test public void metadataReplacementMovesRatherThanDuplicates(){
        NotificationIndex index=new NotificationIndex();index.put(item("a","chat",0,"a",false));
        Map<String,Integer> old=index.counts(0);index.put(item("a","mail",10,"b",true));
        assertTrue(index.counts(0).isEmpty());assertEquals(Integer.valueOf(1),index.counts(10).get("mail"));
        assertEquals(Integer.valueOf(1),old.get("chat"));
    }
    @Test public void rankingPruneAndReconnectClearOldIdentities(){
        NotificationIndex index=new NotificationIndex();index.put(item("a","chat",0,null,false));index.put(item("b","chat",0,null,false));
        index.retainKeys(Collections.singleton("b"));assertEquals(Integer.valueOf(1),index.counts(0).get("chat"));
        index.clear();assertTrue(index.isEmpty());assertTrue(index.counts(0).isEmpty());
        index.remove("gone");assertTrue(index.isEmpty());
    }
    @Test public void overrideRegroupingRestoresStandaloneSummary(){
        NotificationIndex index=new NotificationIndex();index.put(item("s","chat",0,"auto",true));index.put(item("c","chat",0,"auto",false));
        assertEquals(Integer.valueOf(1),index.counts(0).get("chat"));
        index.put(item("c","chat",0,"explicit",false));assertEquals(Integer.valueOf(2),index.counts(0).get("chat"));
    }
}
