package net.fuyumori.stellashell;

import static org.junit.Assert.*;
import org.junit.Test;

public class WeightedLruTest {
    @Test public void bytesAndAccessOrderBoundMixedSizes() {
        WeightedLru<String,String> cache = new WeightedLru<>(10, 5);
        cache.put("a", "A", 4); cache.put("b", "B", 4);
        assertEquals("A", cache.get("a"));
        cache.put("c", "C", 5);
        assertNull(cache.get("b")); assertEquals("A", cache.get("a"));
        assertEquals("C", cache.get("c")); assertEquals(9, cache.weight());
    }
    @Test public void entryBoundAlsoLimitsZeroWeightValues() {
        WeightedLru<Integer,Integer> cache = new WeightedLru<>(100, 2);
        for (int i=0;i<100;i++) cache.put(i,i,0);
        assertEquals(2, cache.size()); assertNull(cache.get(97));
        assertEquals(Integer.valueOf(99), cache.get(99));
    }
    @Test public void replacementAndOversizeCannotExceedBudgetOrKeepStaleValue() {
        WeightedLru<String,String> cache = new WeightedLru<>(10, 5);
        cache.put("a","small",2); cache.put("b","live",4);
        cache.put("a","large",8);
        assertNull(cache.get("b")); assertEquals(8, cache.weight());
        cache.put("a","oversize",11);
        assertNull(cache.get("a")); assertEquals(0, cache.weight());
    }
    @Test public void clearDropsOnlyCacheOwnershipAndCanRefill() {
        WeightedLru<String,Object> cache = new WeightedLru<>(10, 2);
        Object visible = new Object(); cache.put("a",visible,6);
        Object heldByView = cache.get("a"); cache.clear();
        assertSame(visible, heldByView); assertEquals(0, cache.size()); assertEquals(0, cache.weight());
        cache.put("b",new Object(),10); assertEquals(10, cache.weight());
    }
    @Test public void largeWeightsDoNotOverflow() {
        WeightedLru<String,String> cache = new WeightedLru<>(Long.MAX_VALUE,2);
        cache.put("a","a",Long.MAX_VALUE-1); cache.put("b","b",2);
        assertNull(cache.get("a")); assertEquals(2,cache.weight());
    }
}
