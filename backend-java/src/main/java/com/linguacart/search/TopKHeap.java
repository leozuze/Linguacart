package com.linguacart.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Keeps the K highest-scoring items using a min-heap of size K, so the full list is never sorted.
 * A new item is accepted only when the heap has room or its score is strictly greater than the
 * current minimum; the minimum is then removed. On equal scores the earlier item stays.
 */
public final class TopKHeap<T> {

    public record Scored<T>(double score, T item) {}

    private record Entry<T>(double score, long order, T item) {}

    private final int k;
    private final PriorityQueue<Entry<T>> heap;
    private long counter = 0;

    public TopKHeap(int k) {
        this.k = Math.max(0, k);
        // Smallest score on top; among equal scores the LATER item is treated as smaller (evicted first).
        this.heap = new PriorityQueue<>(Math.max(1, this.k + 1),
                Comparator.<Entry<T>>comparingDouble(Entry::score).thenComparing(Comparator.comparingLong(
                        (Entry<T> e) -> e.order()).reversed()));
    }

    /** @return true if the item was kept */
    public boolean offer(double score, T item) {
        if (k == 0) {
            return false;
        }
        Entry<T> entry = new Entry<>(score, counter++, item);
        if (heap.size() < k) {
            heap.add(entry);
            return true;
        }
        if (score > heap.peek().score()) {
            heap.poll();
            heap.add(entry);
            return true;
        }
        return false;
    }

    public int size() {
        return heap.size();
    }

    public int capacity() {
        return k;
    }

    /** Items from best to worst; equal scores keep their insertion order. */
    public List<Scored<T>> toSortedList() {
        List<Entry<T>> entries = new ArrayList<>(heap);
        entries.sort(Comparator.<Entry<T>>comparingDouble(Entry::score).reversed()
                .thenComparingLong(Entry::order));
        List<Scored<T>> out = new ArrayList<>(entries.size());
        for (Entry<T> e : entries) {
            out.add(new Scored<>(e.score(), e.item()));
        }
        return out;
    }
}
