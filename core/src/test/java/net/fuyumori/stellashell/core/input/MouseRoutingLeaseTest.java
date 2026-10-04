package net.fuyumori.stellashell.core.input;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class MouseRoutingLeaseTest {
    private static final class Backend implements MouseRoutingLease.Backend {
        int association=-1,generation=3;boolean available=true,failAfterAdd,pending,clearOnRemove=true;
        final List<String> writes=new ArrayList<>();boolean requiredNativeBarrier;
        public int association(String descriptor){return association;}
        public int generation(String descriptor){return generation;}
        public boolean targetAvailable(int target){return available;}
        public void add(String descriptor,String value)throws Exception{
            writes.add("add:"+value);association=value.isEmpty()?-1:12;
            if(failAfterAdd&&!value.isEmpty())throw new Exception("Reply failed after server committed");
            if(value.isEmpty())generation++;
        }
        public void remove(String descriptor){writes.add("remove");if(clearOnRemove)association=-1;}
        public boolean awaitReleased(String descriptor,int target,int before,boolean reconfigured,int timeout){
            requiredNativeBarrier|=reconfigured;return !pending&&association!=target;
        }
    }
    @Test public void exceptionAfterCommittedAddStillOwnsAndRestoresTheDescriptor()throws Exception{
        Backend backend=new Backend();backend.failAfterAdd=true;MouseRoutingLease lease=new MouseRoutingLease(backend);
        assertThrows(Exception.class,()->lease.route("mouse",12,"external-unique"));assertEquals(1,lease.size());
        lease.releaseAll();assertTrue(lease.isEmpty());assertEquals(Arrays.asList("add:external-unique","remove"),backend.writes);
    }
    @Test public void neverClaimsOrRemovesAnotherOwnersValidAssociation()throws Exception{
        Backend backend=new Backend();backend.association=7;MouseRoutingLease lease=new MouseRoutingLease(backend);
        assertFalse(lease.route("mouse",12,"external-unique"));lease.releaseAll();assertTrue(backend.writes.isEmpty());
        backend.association=-1;assertTrue(lease.route("mouse",12,"external-unique"));backend.association=9;
        lease.releaseAll();assertEquals(Collections.singletonList("add:external-unique"),backend.writes);assertTrue(lease.isEmpty());
    }
    @Test public void unplugMinusOneIsNotEnoughWithoutTheNativeCacheBarrier()throws Exception{
        Backend backend=new Backend();MouseRoutingLease lease=new MouseRoutingLease(backend);lease.route("mouse",12,"external-unique");
        backend.available=false;backend.association=-1;lease.releaseAll();
        assertTrue(backend.requiredNativeBarrier);assertEquals(Arrays.asList("add:external-unique","remove","add:","remove"),backend.writes);assertTrue(lease.isEmpty());
    }
    @Test public void unsuccessfulCleanupRetainsOwnershipForALaterRetry()throws Exception{
        Backend backend=new Backend();backend.clearOnRemove=false;MouseRoutingLease lease=new MouseRoutingLease(backend);lease.route("mouse",12,"external-unique");
        backend.pending=true;lease.releaseAll();assertEquals(1,lease.size());assertTrue(backend.requiredNativeBarrier);
        backend.pending=false;lease.releaseAll();assertTrue(lease.isEmpty());
    }
    @Test public void dumpVerificationUsesOnlyTheRequestedNativeDeviceCache(){
        String dump="InputReader State:\n  Device 7: Mouse\n    AssociatedDisplayUniqueIdByDescriptor: external-old\n  Device 8: Keyboard\n    AssociatedDisplayUniqueIdByDescriptor: <none>\n";
        assertFalse(MouseRoutingLease.readerCacheCleared(dump,7));assertTrue(MouseRoutingLease.readerCacheCleared(dump,8));assertFalse(MouseRoutingLease.readerCacheCleared(dump,9));
        assertTrue(MouseRoutingLease.readerCacheCleared(dump.replace("external-old",""),7));
        assertFalse(MouseRoutingLease.readerCacheCleared("mRequestedPointerDisplayId=-1\n  Device 7: Mouse\n    AssociatedDisplayId: -1\n",7));
    }
}
