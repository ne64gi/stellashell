package net.fuyumori.stellashell.core.settings;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;

public class SettingTransactionTest {
    static class Store implements SettingTransaction.Store {
        final Map<String,String> values=new HashMap<>();final List<String> writes=new ArrayList<>();
        boolean failFreeform,failDesktopRestore,failRead;
        Store(){values.put(SettingTransaction.DESKTOP,"0");values.put(SettingTransaction.FREEFORM,"null");}
        public String read(String key)throws Exception{if(failRead)throw new IOException("read");return values.get(key);}
        public void write(String key,String value)throws Exception{
            writes.add(key+"="+value);
            if(failFreeform && key.equals(SettingTransaction.FREEFORM) && value.equals("1"))throw new IOException("apply");
            if(failDesktopRestore && key.equals(SettingTransaction.DESKTOP) && value.equals("0"))throw new IOException("restore");
            values.put(key,value);
        }
    }
    @Test public void successfulEnableAndExactRestoration()throws Exception{
        Store s=new Store();SettingTransaction.apply(s,"1","1");assertEquals("1",s.values.get(SettingTransaction.FREEFORM));
        SettingTransaction.apply(s,"0","null");assertEquals("0",s.values.get(SettingTransaction.DESKTOP));assertEquals("null",s.values.get(SettingTransaction.FREEFORM));
    }
    @Test public void preparationDoesNotUndoAnEnabledLegacyPointerPolicy()throws Exception{
        for(int sdk:new int[]{30,34})for(String original:new String[]{"1","0","null"}){
            Store s=new Store();s.values.put(SettingTransaction.DESKTOP,original);
            SettingTransaction.apply(s,SettingTransaction.desktopForPreparation(sdk,s.read(SettingTransaction.DESKTOP)),"1");
            assertEquals(original,s.values.get(SettingTransaction.DESKTOP));assertEquals("1",s.values.get(SettingTransaction.FREEFORM));
        }
        for(int sdk:new int[]{35,36}){
            Store s=new Store();s.values.put(SettingTransaction.DESKTOP,"1");
            SettingTransaction.apply(s,SettingTransaction.desktopForPreparation(sdk,"1"),"1");
            assertEquals("0",s.values.get(SettingTransaction.DESKTOP));
        }
        assertThrows(IllegalArgumentException.class,()->SettingTransaction.desktopForPreparation(34,"invalid"));
    }
    @Test public void partialFailureRestoresBothOriginalValues(){
        Store s=new Store();s.failFreeform=true;
        assertThrows(IOException.class,()->SettingTransaction.apply(s,"1","1"));
        assertEquals("0",s.values.get(SettingTransaction.DESKTOP));assertEquals("null",s.values.get(SettingTransaction.FREEFORM));assertEquals(4,s.writes.size());
    }
    @Test public void failedFirstRollbackStillAttemptsSecond(){
        Store s=new Store();s.failFreeform=true;s.failDesktopRestore=true;
        IOException e=assertThrows(IOException.class,()->SettingTransaction.apply(s,"1","1"));
        assertEquals(1,e.getSuppressed().length);assertEquals(SettingTransaction.FREEFORM+"=null",s.writes.get(3));
    }
    @Test public void snapshotFailureDoesNotMutateAnything(){
        Store s=new Store();s.failRead=true;assertThrows(IOException.class,()->SettingTransaction.apply(s,"1","1"));assertTrue(s.writes.isEmpty());
    }
    @Test public void invalidValueDoesNotMutateAnything(){
        Store s=new Store();assertThrows(IllegalArgumentException.class,()->SettingTransaction.apply(s,"invalid","1"));assertTrue(s.writes.isEmpty());
    }
}
