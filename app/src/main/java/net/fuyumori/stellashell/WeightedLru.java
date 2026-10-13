package net.fuyumori.stellashell;

import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-synchronized byte/count budget. Eviction drops references, never disposes live values. */
final class WeightedLru<K, V> {
    private static final class Item<V> {
        final V value;
        final long weight;
        Item(V value, long weight) { this.value = value; this.weight = weight; }
    }
    private final long maxWeight;
    private final int maxEntries;
    private final LinkedHashMap<K, Item<V>> entries = new LinkedHashMap<>(16, .75f, true);
    private long weight;

    WeightedLru(long maxWeight, int maxEntries) {
        if (maxWeight <= 0 || maxEntries <= 0) throw new IllegalArgumentException("Positive budget required");
        this.maxWeight = maxWeight;
        this.maxEntries = maxEntries;
    }

    V get(K key) { Item<V> item = entries.get(key); return item == null ? null : item.value; }

    void put(K key, V value, long bytes) {
        if (value == null || bytes < 0) throw new IllegalArgumentException("Invalid value or weight");
        Item<V> previous = entries.remove(key);
        if (previous != null) weight -= previous.weight;
        if (bytes > maxWeight) return;
        // Subtraction avoids overflowing even for callers with very large budgets.
        while (!entries.isEmpty() && (weight > maxWeight - bytes || entries.size() >= maxEntries)) {
            Map.Entry<K, Item<V>> oldest = entries.entrySet().iterator().next();
            weight -= oldest.getValue().weight;
            entries.remove(oldest.getKey());
        }
        entries.put(key, new Item<>(value, bytes));
        weight += bytes;
    }

    void clear() { entries.clear(); weight = 0; }
    long weight() { return weight; }
    int size() { return entries.size(); }
}
