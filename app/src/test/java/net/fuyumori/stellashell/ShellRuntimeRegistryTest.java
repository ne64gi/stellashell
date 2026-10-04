package net.fuyumori.stellashell;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellRuntimeRegistryTest {
    private static final class Session implements ShellRuntime.Session {
        boolean home;int starts;final int[] bounds={1,2,3,4};
        public boolean toggleStart(int id){starts++;return true;}
        public int[] navigationBounds(int id){return bounds;}
        public void homeVisible(boolean value){home=value;}
    }
    @Test public void oldDestroyAndOldReplyCannotRetireReplacement(){
        ShellRuntime.Registry owner=new ShellRuntime.Registry();Session first=new Session(),second=new Session();
        ShellRuntime.Registry.Token old=owner.bind(first);owner.publish(old,7,true);
        ShellRuntime.Registry.Token current=owner.bind(second);owner.publish(current,9,false);
        assertFalse(owner.close(old));assertFalse(owner.publish(old,7,true));
        assertTrue(owner.snapshot().running);assertEquals(9,owner.snapshot().selectedDisplay);
        assertFalse(owner.snapshot().phoneNavigationReady);owner.toggleStart(9);
        assertEquals(0,first.starts);assertEquals(1,second.starts);
        assertTrue(owner.close(current));assertFalse(owner.snapshot().running);assertEquals(-1,owner.snapshot().selectedDisplay);
    }
    @Test public void homeLeasesAggregateAcrossActivityReplacement(){
        ShellRuntime.Registry owner=new ShellRuntime.Registry();
        ShellRuntime.Registry.HomeLease first=owner.attachHome(),second=owner.attachHome();first.visible(true);second.visible(true);
        Session session=new Session();owner.bind(session);assertTrue(session.home);
        first.close();first.visible(false);assertTrue(session.home);
        second.visible(false);assertFalse(session.home);
        second.close();second.close();assertFalse(session.home);
    }
    @Test public void snapshotsRemainDetachedAndObserverUnsubscribeIsFinal(){
        ShellRuntime.Registry owner=new ShellRuntime.Registry();Session session=new Session();int[] changes={0};Runnable listener=()->changes[0]++;
        owner.observe(listener);ShellRuntime.Registry.Token token=owner.bind(session);
        ShellRuntime.Snapshot previous=owner.snapshot();owner.publish(token,3,true);assertEquals(-1,previous.selectedDisplay);
        int count=changes[0];owner.publish(token,3,true);assertEquals(count,changes[0]);
        int[] bounds=owner.navigationBounds(3);bounds[0]=900;assertEquals(1,owner.navigationBounds(3)[0]);
        owner.remove(listener);owner.close(token);assertEquals(count,changes[0]);
    }
    @Test public void registryDoesNotKeepAnUnreachableSessionAlive(){
        ShellRuntime.Registry owner=new ShellRuntime.Registry();Session session=new Session();
        ShellRuntime.Registry.Token token=owner.bind(session);owner.publish(token,8,true);
        // Deterministic weak-reference loss, without timing-sensitive GC assertions.
        token.session.clear();
        assertFalse(owner.snapshot().running);assertFalse(owner.snapshot().phoneNavigationReady);
        assertEquals(-1,owner.snapshot().selectedDisplay);assertFalse(owner.toggleStart(8));assertNull(owner.navigationBounds(8));
        owner.attachHome().visible(true);assertTrue(owner.close(token));
    }

}
