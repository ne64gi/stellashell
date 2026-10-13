package net.fuyumori.stellashell.feature.launch;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LabelCacheTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private final AtomicInteger loads=new AtomicInteger();
    private File file(){return new File(temporary.getRoot(),"labels.bin");}
    private LabelCache.Item item(String component,String identity,String label){
        return new LabelCache.Item(component,identity,()->{loads.incrementAndGet();return label;});
    }
    @Test public void repeatedQueriesAndProcessRestartBypassLabelResourceLoader() {
        List<LabelCache.Item> query=Arrays.asList(item("a/A","a:1:10","First"),item("b/B","b:2:20","Second"));
        LabelCache cache=new LabelCache(file());
        Map<String,String> labels=cache.resolve("en,density420",query);assertEquals(2,loads.get());
        assertEquals(labels,cache.resolve("en,density420",query));assertEquals(2,loads.get());
        assertEquals(labels,new LabelCache(file()).resolve("en,density420",query));assertEquals(2,loads.get());
        assertThrows(UnsupportedOperationException.class,()->labels.put("c/C","Other"));
    }
    @Test public void changedPackageVersionOrUpdateIdentityReloadsOnlyChangedLabel(){
        LabelCache cache=new LabelCache(file());
        cache.resolve("en",Arrays.asList(item("a/A","a:1:10","Old"),item("b/B","b:1:10","Same")));
        Map<String,String> result=cache.resolve("en",Arrays.asList(item("a/A","a:2:10","Version"),item("b/B","b:1:10","Same")));
        assertEquals("Version",result.get("a/A"));assertEquals(3,loads.get());
        result=cache.resolve("en",Arrays.asList(item("a/A","a:2:11","Updated"),item("b/B","b:1:10","Same")));
        assertEquals("Updated",result.get("a/A"));assertEquals(4,loads.get());
    }
    @Test public void fullConfigurationMismatchReloadsAndDoesNotMixLabels(){
        LabelCache cache=new LabelCache(file());
        cache.resolve("locales=en-US;night=1;density=420",Collections.singletonList(item("a/A","1","English")));
        Map<String,String> result=cache.resolve("locales=ja-JP;night=1;density=420",Collections.singletonList(item("a/A","1","日本語")));
        assertEquals("日本語",result.get("a/A"));assertEquals(2,loads.get());
        cache.resolve("locales=ja-JP;night=2;density=420",Collections.singletonList(item("a/A","1","Night")));
        assertEquals(3,loads.get());
        new LabelCache(file()).resolve("locales=ja-JP;night=2;density=480",Collections.singletonList(item("a/A","1","Dense")));
        assertEquals(4,loads.get());
    }
    @Test public void actualQueryMembershipPrunesRemovedOrDisabledComponents(){
        LabelCache cache=new LabelCache(file());
        cache.resolve("en",Arrays.asList(item("a/A","1","A"),item("b/B","1","B")));
        assertEquals(Collections.singletonMap("b/B","B"),cache.resolve("en",Collections.singletonList(item("b/B","1","unused"))));
        assertEquals(2,loads.get());
        LabelCache restarted=new LabelCache(file());
        assertTrue(restarted.resolve("en",Collections.emptyList()).isEmpty());
        Map<String,String> result=new LabelCache(file()).resolve("en",Collections.singletonList(item("a/A","1","Readded")));
        assertEquals("Readded",result.get("a/A"));assertEquals(3,loads.get());
    }
    @Test public void sameVersionPackageInvalidationPreservesOtherInMemoryLabels(){
        LabelCache cache=new LabelCache(file());
        List<LabelCache.Item> query=Arrays.asList(item("a/A","1","Old"),item("b/B","1","Same"));
        cache.resolve("en",query);cache.invalidate("a");
        Map<String,String> result=cache.resolve("en",Arrays.asList(item("a/A","1","New"),item("b/B","1","unused")));
        assertEquals("New",result.get("a/A"));assertEquals("Same",result.get("b/B"));assertEquals(3,loads.get());
        new LabelCache(file()).resolve("en",Arrays.asList(item("a/A","1","unused"),item("b/B","1","unused")));
        assertEquals(3,loads.get());
        cache.invalidate(null);cache.resolve("en",query);assertEquals(5,loads.get());
    }
    @Test public void invalidationBeforeNextQueryCannotResurrectOldDiskLabels(){
        LabelCache cache=new LabelCache(file());cache.resolve("en",Collections.singletonList(item("a/A","1","Old")));
        cache.invalidate("a");
        Map<String,String> result=new LabelCache(file()).resolve("en",Collections.singletonList(item("a/A","1","New")));
        assertEquals("New",result.get("a/A"));assertEquals(2,loads.get());
    }
    @Test public void corruptTruncatedAndOversizedFilesFallbackWithoutPublishingPartialData() throws Exception {
        List<LabelCache.Item> query=Collections.singletonList(item("a/A","1","Fresh"));
        new LabelCache(file()).resolve("en",query);byte[] valid=Files.readAllBytes(file().toPath());
        byte[] corrupt=valid.clone();corrupt[corrupt.length-8]^=1;
        for(byte[] data:new byte[][]{corrupt,Arrays.copyOf(valid,valid.length-1),new byte[]{1,2,3},new byte[LabelCache.MAX_BYTES+1]}){
            Files.write(file().toPath(),data);
            assertEquals("Fresh",new LabelCache(file()).resolve("en",query).get("a/A"));
        }
        assertEquals(5,loads.get());assertTrue(file().length()<=LabelCache.MAX_BYTES);
    }
    @Test public void unknownIdentityAndOversizedScalarLabelsAreNeverReused(){
        LabelCache cache=new LabelCache(file());String huge="z".repeat(2049);
        List<LabelCache.Item> query=Arrays.asList(item("a/A",null,"Unknown"),item("b/B","1",huge));
        cache.resolve("en",query);cache.resolve("en",query);new LabelCache(file()).resolve("en",query);
        assertEquals(6,loads.get());
    }
    @Test public void entryAndByteBudgetsBoundPersistenceWithoutTruncatingReturnedCatalog() throws Exception {
        List<LabelCache.Item> query=new ArrayList<>();
        for(int i=0;i<LabelCache.MAX_ENTRIES+3;i++)query.add(item("p/C"+i,"1","Label "+i));
        LabelCache cache=new LabelCache(file());assertEquals(query.size(),cache.resolve("en",query).size());
        int first=loads.get();new LabelCache(file()).resolve("en",query);assertEquals(first+3,loads.get());
        assertTrue(file().length()<=LabelCache.MAX_BYTES);
        cache.invalidate(null);query.clear();String label="あ".repeat(2000);
        for(int i=0;i<1000;i++)query.add(item("p/C"+i,"1",label));
        assertEquals(1000,cache.resolve("ja",query).size());assertTrue(file().length()<=LabelCache.MAX_BYTES);
        first=loads.get();assertEquals(1000,new LabelCache(file()).resolve("ja",query).size());
        assertTrue(loads.get()>first);assertTrue(file().length()<=LabelCache.MAX_BYTES);
    }
    @Test public void unwritablePersistenceStillReturnsFreshLabels() throws Exception {
        File parent=temporary.newFile("not-a-directory");
        LabelCache cache=new LabelCache(new File(parent,"labels"));
        assertEquals("Fresh",cache.resolve("en",Collections.singletonList(item("a/A","1","Fresh"))).get("a/A"));
        assertEquals(1,loads.get());
    }
    @Test public void generationCapturedBeforeQueryPreventsStalePersistence(){
        LabelCache cache=new LabelCache(file());long oldQuery=cache.beginRefresh();
        cache.invalidate("a");
        cache.resolve("en",Collections.singletonList(item("a/A","1","New")));
        cache.resolve(oldQuery,"en",Collections.singletonList(item("a/A","0","Stale")));
        assertEquals("New",new LabelCache(file()).resolve("en",Collections.singletonList(item("a/A","1","unused"))).get("a/A"));
    }
    @Test public void blockedOldLoaderCannotOverwriteNewGeneration() throws Exception {
        LabelCache cache=new LabelCache(file());CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<?> stale=executor.submit(()->cache.resolve("en",Collections.singletonList(new LabelCache.Item("a/A","1",()->{
                started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return "Stale";
            }))));
            assertTrue(started.await(5,TimeUnit.SECONDS));cache.invalidate("a");
            cache.resolve("en",Collections.singletonList(item("a/A","1","New")));release.countDown();stale.get(5,TimeUnit.SECONDS);
            assertEquals("New",new LabelCache(file()).resolve("en",Collections.singletonList(item("a/A","1","unused"))).get("a/A"));
        }finally{release.countDown();executor.shutdownNow();}
    }
}
