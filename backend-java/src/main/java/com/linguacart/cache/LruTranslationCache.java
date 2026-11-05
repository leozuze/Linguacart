package com.linguacart.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe Least-Recently-Used (LRU) translation cache backed by a hand-written
 * doubly-linked list and a {@link HashMap}. Both {@code get} and {@code put} run in
 * amortised O(1) time. {@link java.util.LinkedHashMap} is deliberately <em>not</em>
 * used so that the recency data structure is fully explicit.
 *
 * <h3>Null-key / null-value policy</h3>
 * <ul>
 *   <li>{@code get(null)} returns {@code null} silently — there is no entry to find.</li>
 *   <li>{@code put(null, ...)} is a no-op and logs a warning — a null key is invalid.</li>
 *   <li>{@code put(key, null)} stores a {@code null} value; callers must decide whether
 *       to interpret {@code null} as a cache miss. In practice, {@code TranslationService}
 *       never caches null values, so this is a documentation-only note.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code linguacart.cache.max-size} — maximum number of entries (default 1000).</li>
 *   <li>{@code linguacart.cache.model-version} — see {@link CacheKeyBuilder}.</li>
 * </ul>
 *
 * <p>Locking: a single {@link ReentrantLock} guards all state. Because {@code get}
 * mutates the node's position in the recency list, reads also require the lock.
 */
@Component
public class LruTranslationCache {

    private static final Logger log = LoggerFactory.getLogger(LruTranslationCache.class);

    private final int maxSize;
    private final ReentrantLock lock = new ReentrantLock();

    /** Sentinel head: next → most-recent node. */
    private final Node head;
    /** Sentinel tail: prev → least-recent node. */
    private final Node tail;

    /** Key → Node map for O(1) lookup. */
    private final HashMap<String, Node> map;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public LruTranslationCache(@Value("${linguacart.cache.max-size:1000}") int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("LruTranslationCache maxSize must be >= 1, got: " + maxSize);
        }
        this.maxSize = maxSize;
        this.map = new HashMap<>(Math.min(maxSize + 1, 1 << 11)); // pre-size to avoid rehash

        // Establish sentinel doubly-linked list:  head <-> tail
        this.head = new Node(null, null);
        this.tail = new Node(null, null);
        head.next = tail;
        tail.prev = head;

        log.info("LruTranslationCache initialised with maxSize={}", maxSize);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the cached value for {@code key}, moving the entry to most-recent.
     * Returns {@code null} on cache miss or if {@code key} is null.
     */
    public String get(String key) {
        if (key == null) {
            return null;
        }
        lock.lock();
        try {
            Node node = map.get(key);
            if (node == null) {
                return null;
            }
            moveToFront(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts or updates the entry for {@code key}, evicting the least-recently-used
     * entry when the cache is at capacity. A null key is rejected with a warning.
     */
    public void put(String key, String value) {
        if (key == null) {
            log.warn("LruTranslationCache.put() called with null key — ignoring");
            return;
        }
        lock.lock();
        try {
            Node existing = map.get(key);
            if (existing != null) {
                // Update value and refresh recency
                existing.value = value;
                moveToFront(existing);
                return;
            }

            // Insert new node at head (most-recent)
            Node node = new Node(key, value);
            map.put(key, node);
            insertAtFront(node);

            // Evict least-recently-used if over capacity
            if (map.size() > maxSize) {
                Node lru = tail.prev;   // sentinel tail's prev is the LRU entry
                unlink(lru);
                map.remove(lru.key);
                log.debug("LruTranslationCache evicted key={}", lru.key);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current number of entries in the cache. */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /** Removes all entries from the cache. */
    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the configured maximum capacity. */
    public int maxSize() {
        return maxSize;
    }

    // -------------------------------------------------------------------------
    // Private linked-list helpers (called only while holding the lock)
    // -------------------------------------------------------------------------

    /** Detaches {@code node} from its current position in the list. */
    private void unlink(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /** Inserts {@code node} immediately after the head sentinel (= most-recent). */
    private void insertAtFront(Node node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /** Moves an existing {@code node} to the most-recent position. */
    private void moveToFront(Node node) {
        unlink(node);
        insertAtFront(node);
    }

    // -------------------------------------------------------------------------
    // Node
    // -------------------------------------------------------------------------

    private static class Node {
        String key;
        String value;
        Node prev;
        Node next;

        Node(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
