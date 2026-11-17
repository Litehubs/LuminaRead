package com.luminaread.util;

import java.util.LinkedHashMap;

/** Access-ordered cache bounded by both entry count and an estimated memory weight. */
public final class WeightedCache<K, V> {
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxEntries;
    private final long maxWeight;
    private long weight;

    public WeightedCache(int maxEntries, long maxWeight) {
        if (maxEntries <= 0 || maxWeight <= 0) throw new IllegalArgumentException("Invalid cache limits");
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
    }

    public synchronized V get(K key) {
        var entry = entries.get(key);
        return entry == null ? null : entry.value();
    }

    public synchronized void put(K key, V value, long size) {
        if (size < 0) throw new IllegalArgumentException("Negative cache weight");
        remove(key);
        if (size > maxWeight) return;
        var oldest = entries.values().iterator();
        while (entries.size() >= maxEntries || weight > maxWeight - size) {
            weight -= oldest.next().weight();
            oldest.remove();
        }
        entries.put(key, new Entry<>(value, size));
        weight += size;
    }

    public synchronized void remove(K key) {
        var old = entries.remove(key);
        if (old != null) weight -= old.weight();
    }

    private record Entry<V>(V value, long weight) {}
}
