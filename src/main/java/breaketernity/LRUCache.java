package breaketernity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A LRU cache intended for caching pure functions.
 *
 * <p>Port of src/lru-cache.ts from break_eternity.js. The original keeps an explicit
 * doubly-linked list keyed by a {@code Map}; this port uses a
 * {@link LinkedHashMap} in access order, which has the same observable behavior:
 * {@code get} refreshes a key's position, insertion appends, and eviction removes
 * the least recently used entry (the original's "last" node).
 *
 * <p>Not thread-safe, matching the original.
 */
public class LRUCache<K, V> {
    private final Map<K, V> map = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * The maximum size for this cache. The original recommends setting this to be
     * one less than a power of 2, as most hashtables - including V8's Object
     * hashtable - use powers of two for hashtable sizes. It can't exactly be a
     * power of two, as a {@link #set} call could temporarily set the size of the
     * map to be maxSize + 1.
     *
     * <p>Public and mutable, like the original: setting it below 1 disables caching
     * entirely.
     */
    public double maxSize;

    /**
     * @param maxSize The maximum size for this cache. See {@link #maxSize}.
     */
    public LRUCache(double maxSize) {
        this.maxSize = maxSize;
    }

    public int size() {
        return map.size();
    }

    /**
     * Gets the specified key from the cache, or null if it is not in the cache.
     *
     * @param key The key to get.
     * @return The cached value, or null if key is not in the cache.
     */
    public V get(K key) {
        return map.get(key);
    }

    /**
     * Sets an entry in the cache.
     *
     * @param key The key of the entry.
     * @param value The value of the entry.
     * @throws IllegalStateException if the map already contains the key.
     */
    public void set(K key, V value) {
        // Ensure that this.maxSize >= 1.
        if (this.maxSize < 1) {
            return;
        }
        if (map.containsKey(key)) {
            throw new IllegalStateException("Cannot update existing keys in the cache");
        }
        map.put(key, value);

        while (map.size() > this.maxSize) {
            // Evict the least recently used entry (the original's "last" node).
            Iterator<K> it = map.keySet().iterator();
            it.next();
            it.remove();
        }
    }
}
