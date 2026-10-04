package net.fuyumori.stellashell.feature.search;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StartSearchSessionTest {
    @Test public void editsAndEmptyQueriesNeverLaunch() {
        FakeSettings settings=new FakeSettings();int[] attempts={0};
        StartSearchSession session=new StartSearchSession(settings,(engine,query)->{attempts[0]++;return true;},()->{});
        assertFalse(session.state("words").enabled);assertFalse(session.launch("words"));
        settings.save(SearchSettings.Provider.GOOGLE,"","");
        assertTrue(session.state("words").visible);assertEquals(0,attempts[0]);
        assertFalse(session.state("\u3000 \n").visible);assertFalse(session.launch("\u3000 \n"));
        assertEquals(0,attempts[0]);session.close();
    }
    @Test public void explicitActionReadsTheLatestDestinationRatherThanAnOldRow() {
        FakeSettings settings=new FakeSettings();settings.save(SearchSettings.Provider.GOOGLE,"","");
        List<String> opened=new ArrayList<>();
        StartSearchSession session=new StartSearchSession(settings,(engine,query)->{opened.add(engine.url(query));return true;},()->{});
        assertEquals("Google",session.state("日本語 & b").engineName);
        settings.save(SearchSettings.Provider.BING,"","");
        assertTrue(session.launch(" 日本語 & b "));
        assertEquals("https://www.bing.com/search?q=%E6%97%A5%E6%9C%AC%E8%AA%9E%20%26%20b",opened.get(0));
        settings.save(SearchSettings.Provider.NONE,"","");
        assertFalse(session.launch("日本語 & b"));assertEquals(1,opened.size());session.close();
    }
    @Test public void rejectedLaunchKeepsTheWindowUsableForRetry() {
        FakeSettings settings=new FakeSettings();settings.save(SearchSettings.Provider.DUCKDUCKGO,"","");
        int[] attempts={0};StartSearchSession session=new StartSearchSession(settings,(engine,query)->++attempts[0]>1,()->{});
        assertFalse(session.launch("retry"));assertTrue(session.state("retry").visible);
        assertTrue(session.launch("retry"));assertEquals(2,attempts[0]);session.close();
    }
    @Test public void closedSessionRejectsLateStoreRepliesAndOldActions() {
        FakeSettings settings=new FakeSettings();settings.save(SearchSettings.Provider.GOOGLE,"","");
        int[] changes={0},attempts={0};
        StartSearchSession old=new StartSearchSession(settings,(engine,query)->{attempts[0]++;return true;},()->changes[0]++);
        Consumer<SearchSettings.Snapshot> late=settings.listeners.get(0);
        old.close();old.close();
        late.accept(settings.snapshot());
        assertEquals(0,changes[0]);assertFalse(old.launch("old"));assertEquals(0,attempts[0]);
        assertFalse(old.state("old private input").enabled);assertEquals("",old.state("old private input").query);
        assertEquals(1,settings.closedSubscriptions);
        StartSearchSession fresh=new StartSearchSession(settings,(engine,query)->{attempts[0]++;return true;},()->{});
        assertTrue(fresh.launch("fresh"));assertEquals(1,attempts[0]);fresh.close();
    }
    @Test public void independentWindowsHaveIndependentSubscriptionLifetimes() {
        FakeSettings settings=new FakeSettings();int[] one={0},two={0};
        StartSearchSession first=new StartSearchSession(settings,(engine,query)->true,()->one[0]++);
        StartSearchSession second=new StartSearchSession(settings,(engine,query)->true,()->two[0]++);
        settings.save(SearchSettings.Provider.GOOGLE,"","");assertEquals(1,one[0]);assertEquals(1,two[0]);
        first.close();settings.save(SearchSettings.Provider.BING,"","");
        assertEquals(1,one[0]);assertEquals(2,two[0]);assertEquals("Bing",second.state("x").engineName);second.close();
    }
    @Test public void brokenAdapterCleanupStillLeavesNoLiveWindowCommand() {
        FakeSettings settings=new FakeSettings();settings.failClose=true;int[] changes={0};
        StartSearchSession session=new StartSearchSession(settings,(engine,query)->{fail("Retired navigator invoked");return true;},()->changes[0]++);
        Consumer<SearchSettings.Snapshot> late=settings.listeners.get(0);session.close();
        late.accept(new SearchSettings.Snapshot(SearchSettings.Provider.GOOGLE,"",""));
        assertEquals(0,changes[0]);assertFalse(session.launch("test"));assertFalse(session.state("test").visible);
    }
    private static final class FakeSettings implements SearchSettings {
        Snapshot value=new Snapshot(Provider.NONE,"","");
        final List<Consumer<Snapshot>> listeners=new ArrayList<>();int closedSubscriptions;boolean failClose;
        @Override public Snapshot snapshot(){return value;}
        @Override public void save(Provider provider,String name,String template){
            value=new Snapshot(provider,name,template);for(Consumer<Snapshot> listener:new ArrayList<>(listeners))listener.accept(value);
        }
        @Override public AutoCloseable observe(Consumer<Snapshot> listener){
            listeners.add(listener);return ()->{closedSubscriptions++;if(failClose)throw new Exception("Fixture close failure");listeners.remove(listener);};
        }
    }
}
