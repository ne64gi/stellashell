package net.fuyumori.stellashell.core.layout;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetPagesTest {
    @Test public void legacyCatalogStaysOnFirstPage(){
        WidgetPages pages=new WidgetPages(null,Arrays.asList(42,-1));
        assertEquals(1,pages.count());assertEquals(0,pages.current());
        assertEquals(0,pages.page(42));assertEquals(0,pages.page(-1));assertFalse(pages.empty());
    }
    @Test public void movingPreservesIdsAndSurvivesRecreation(){
        WidgetPages pages=new WidgetPages(null,Arrays.asList(42,-1));
        assertTrue(pages.add());assertTrue(pages.move(42,1));
        WidgetPages restored=new WidgetPages(pages.encode(),Arrays.asList(42,-1));
        assertEquals(2,restored.count());assertEquals(1,restored.current());
        assertEquals(1,restored.page(42));assertEquals(0,restored.page(-1));
        assertEquals(pages.encode(),restored.encode());
    }
    @Test public void newWidgetsUseActivePageAndDeletedIdsDoNotReturn(){
        WidgetPages pages=new WidgetPages(null,Arrays.asList(42,-1));pages.add();
        pages.reconcile(Arrays.asList(-1,73),pages.current());
        assertEquals(1,pages.page(73));assertFalse(pages.move(42,1));
        WidgetPages restored=new WidgetPages(pages.encode(),Arrays.asList(-1,73));
        assertEquals(1,restored.page(73));assertFalse(restored.move(42,1));
    }
    @Test public void removingEmptyMiddlePageRenumbersWithoutDeletingContent(){
        WidgetPages pages=new WidgetPages(null,Arrays.asList(42,-1));pages.add();pages.add();pages.move(42,2);
        pages.select(1);assertTrue(pages.removeEmpty());assertEquals(2,pages.count());
        assertEquals(1,pages.page(42));assertEquals(0,pages.page(-1));assertFalse(pages.removeEmpty());
    }
    @Test public void cannotRemoveLastOrNonemptyPage(){
        WidgetPages empty=new WidgetPages(null,Collections.emptyList());assertFalse(empty.removeEmpty());
        WidgetPages pages=new WidgetPages(null,Arrays.asList(42));pages.add();pages.select(0);assertFalse(pages.removeEmpty());
    }
    @Test public void invalidMovesAndSelectionAreNoops(){
        WidgetPages pages=new WidgetPages(null,Arrays.asList(42));String before=pages.encode();
        assertFalse(pages.select(-1));assertFalse(pages.select(1));assertFalse(pages.move(42,1));assertFalse(pages.move(99,0));assertEquals(before,pages.encode());
    }
    @Test public void pageLimitIsBounded(){
        WidgetPages pages=new WidgetPages(null,Collections.emptyList());
        for(int i=1;i<WidgetPages.LIMIT;i++)assertTrue(pages.add());
        String full=pages.encode();assertFalse(pages.add());assertEquals(full,pages.encode());
    }
    @Test public void corruptDataNeverLosesExistingWidgets(){
        for(String data:Arrays.asList("", "v2|2|1|42,1", "v1|100|1|42,1", "v1|2|1|42,1;42,0", "v1|2|1|42,9")){
            WidgetPages pages=new WidgetPages(data,Arrays.asList(42));
            assertEquals(1,pages.count());assertEquals(0,pages.page(42));assertFalse(pages.empty());
        }
    }
}
