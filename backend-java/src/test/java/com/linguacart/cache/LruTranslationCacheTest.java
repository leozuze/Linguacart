package com.linguacart.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LruTranslationCache}.
 *
 * <h3>Null-key / null-value decision (documented here)</h3>
 * <ul>
 *   <li>A null key on {@code put} is silently ignored (logged as WARN). Callers should
 *       never produce null keys; the key builder always returns a valid hex string.</li>
 *   <li>A null key on {@code get} returns null (treated as a miss), consistent with
 *       how any non-existent key behaves.</li>
 *   <li>A null value on {@code put} is accepted — the cache stores it. In practice,
 *       {@code TranslationService} never stores null values because it only caches
 *       successfully translated, protection-passed results.</li>
 * </ul>
 */
class LruTranslationCacheTest {

    // -------------------------------------------------------------------------
    // 1. Basic get / put
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("put then get returns the stored value")
    void putThenGet() {
        LruTranslationCache cache = new LruTranslationCache(10);
        cache.put("k1", "v1");
        assertEquals("v1", cache.get("k1"));
    }

    @Test
    @DisplayName("get for missing key returns null")
    void getMissingKeyReturnsNull() {
        LruTranslationCache cache = new LruTranslationCache(10);
        assertNull(cache.get("nope"));
    }

    @Test
    @DisplayName("put on existing key updates value and refreshes recency")
    void putExistingKeyUpdatesValueAndRefreshesRecency() {
        LruTranslationCache cache = new LruTranslationCache(2);

        cache.put("k1", "v1");
        cache.put("k2", "v2");
        // k1 is now LRU; update it so it becomes MRU
        cache.put("k1", "updated");

        // Adding k3 should evict k2 (now the LRU), not k1
        cache.put("k3", "v3");

        assertEquals("updated", cache.get("k1"), "k1 should still be present with updated value");
        assertNull(cache.get("k2"), "k2 should have been evicted");
        assertEquals("v3", cache.get("k3"));
    }

    // -------------------------------------------------------------------------
    // 2. Eviction order
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("eviction removes the least-recently-used entry when capacity is exceeded")
    void evictsLeastRecentlyUsedOnOverflow() {
        LruTranslationCache cache = new LruTranslationCache(3);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3");

        // Access k1 → it becomes MRU; k2 becomes LRU
        cache.get("k1");

        // Insert 4th entry → k2 should be evicted
        cache.put("k4", "v4");

        assertNull(cache.get("k2"), "k2 (LRU) should be evicted");
        assertNotNull(cache.get("k1"), "k1 should still be present");
        assertNotNull(cache.get("k3"), "k3 should still be present");
        assertNotNull(cache.get("k4"), "k4 should be present");
    }

    @Test
    @DisplayName("get refreshes recency so accessed entries survive eviction")
    void getRefreshesRecency() {
        LruTranslationCache cache = new LruTranslationCache(3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        // Access 'a' — makes 'b' the LRU
        cache.get("a");
        cache.put("d", "4");  // evicts 'b'

        assertNull(cache.get("b"), "b should be evicted (was LRU)");
        assertNotNull(cache.get("a"), "a was recently accessed, must survive");
        assertNotNull(cache.get("c"), "c must survive");
        assertNotNull(cache.get("d"), "d was just inserted");
    }

    // -------------------------------------------------------------------------
    // 3. Capacity-1 edge case
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("capacity-1: second put evicts the first entry")
    void capacityOne() {
        LruTranslationCache cache = new LruTranslationCache(1);
        cache.put("k1", "v1");
        assertEquals(1, cache.size());

        cache.put("k2", "v2");
        assertEquals(1, cache.size(), "size must never exceed 1");
        assertNull(cache.get("k1"), "k1 should be evicted");
        assertEquals("v2", cache.get("k2"));
    }

    @Test
    @DisplayName("capacity-1: same key update keeps size at 1")
    void capacityOneSameKeyUpdate() {
        LruTranslationCache cache = new LruTranslationCache(1);
        cache.put("k1", "v1");
        cache.put("k1", "v2");
        assertEquals(1, cache.size());
        assertEquals("v2", cache.get("k1"));
    }

    // -------------------------------------------------------------------------
    // 4. Capacity exceeded repeatedly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("size never exceeds maxSize when many entries are inserted")
    void sizeNeverExceedsMaxSizeUnderRepeatedInserts() {
        int max = 5;
        LruTranslationCache cache = new LruTranslationCache(max);

        for (int i = 0; i < 50; i++) {
            cache.put("key-" + i, "val-" + i);
            assertTrue(cache.size() <= max,
                    "Size exceeded maxSize at i=" + i + "; size=" + cache.size());
        }

        // The 50 most-recently inserted keys are key-45..key-49
        for (int i = 45; i < 50; i++) {
            assertNotNull(cache.get("key-" + i), "key-" + i + " should be in cache");
        }
        assertEquals(max, cache.size());
    }

    // -------------------------------------------------------------------------
    // 5. Null handling (documented behaviour)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null key on put is a no-op; cache remains unchanged")
    void nullKeyPutIsNoOp() {
        LruTranslationCache cache = new LruTranslationCache(5);
        cache.put("k1", "v1");
        cache.put(null, "should-be-ignored");  // must not throw
        assertEquals(1, cache.size(), "null key must not be stored");
        assertEquals("v1", cache.get("k1"));
    }

    @Test
    @DisplayName("null key on get returns null (treated as miss)")
    void nullKeyGetReturnsMiss() {
        LruTranslationCache cache = new LruTranslationCache(5);
        cache.put("k1", "v1");
        assertNull(cache.get(null), "null key get must return null");
    }

    // -------------------------------------------------------------------------
    // 6. size() and clear()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("size() tracks insertions and evictions accurately")
    void sizeTracking() {
        LruTranslationCache cache = new LruTranslationCache(3);
        assertEquals(0, cache.size());
        cache.put("a", "1");
        assertEquals(1, cache.size());
        cache.put("b", "2");
        cache.put("c", "3");
        assertEquals(3, cache.size());
        cache.put("d", "4"); // evicts one
        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("clear() empties the cache completely")
    void clearEmptiesCache() {
        LruTranslationCache cache = new LruTranslationCache(5);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("a"));
        assertNull(cache.get("b"));

        // Cache should still work normally after clear
        cache.put("c", "3");
        assertEquals("3", cache.get("c"));
    }

    // -------------------------------------------------------------------------
    // 7. Concurrency
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("concurrent get/put from many threads: no exceptions, size never exceeds maxSize")
    void concurrencyStressTest() throws InterruptedException {
        int maxSize = 50;
        int threads = 16;
        int operationsPerThread = 500;
        LruTranslationCache cache = new LruTranslationCache(maxSize);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            exec.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "key-" + ((threadId * operationsPerThread + i) % 100);
                        String value = "val-" + i;
                        if (i % 3 == 0) {
                            cache.get(key);
                        } else {
                            cache.put(key, value);
                        }
                        int s = cache.size();
                        if (s > maxSize) {
                            error.compareAndSet(null,
                                    new AssertionError("Size " + s + " exceeded maxSize " + maxSize));
                        }
                    }
                } catch (Throwable th) {
                    error.compareAndSet(null, th);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        exec.shutdownNow();

        assertNull(error.get(), () -> "Concurrency error: " + error.get());
        assertTrue(cache.size() <= maxSize, "Final size must not exceed maxSize");
    }
}
