package com.linguacart.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopKHeapTest {

    @Test
    void keepsTheKBestInDescendingOrder() {
        TopKHeap<String> heap = new TopKHeap<>(3);
        double[] scores = {0.2, 0.9, 0.5, 0.7, 0.1, 0.8};
        for (int i = 0; i < scores.length; i++) {
            heap.offer(scores[i], "p" + i);
        }
        List<TopKHeap.Scored<String>> out = heap.toSortedList();
        assertEquals(3, out.size());
        assertEquals("p1", out.get(0).item());
        assertEquals("p5", out.get(1).item());
        assertEquals("p3", out.get(2).item());
    }

    @Test
    void equalScoresKeepTheEarlierItem() {
        TopKHeap<String> heap = new TopKHeap<>(2);
        assertTrue(heap.offer(1.0, "a"));
        assertTrue(heap.offer(1.0, "b"));
        assertFalse(heap.offer(1.0, "c"));
        List<TopKHeap.Scored<String>> out = heap.toSortedList();
        assertEquals("a", out.get(0).item());
        assertEquals("b", out.get(1).item());
    }

    @Test
    void zeroCapacityKeepsNothing() {
        TopKHeap<String> heap = new TopKHeap<>(0);
        assertFalse(heap.offer(5.0, "a"));
        assertEquals(0, heap.toSortedList().size());
    }

    @Test
    void fewerItemsThanKReturnsAll() {
        TopKHeap<Integer> heap = new TopKHeap<>(10);
        heap.offer(0.3, 1);
        heap.offer(0.6, 2);
        assertEquals(2, heap.toSortedList().size());
        assertEquals(Integer.valueOf(2), heap.toSortedList().get(0).item());
    }

    @Test
    void matchesAFullSortOnRandomData() {
        Random random = new Random(42);
        int n = 500;
        int k = 17;
        double[] scores = new double[n];
        TopKHeap<Integer> heap = new TopKHeap<>(k);
        for (int i = 0; i < n; i++) {
            scores[i] = random.nextInt(50) / 10.0; // many ties on purpose
            heap.offer(scores[i], i);
        }
        List<Integer> expected = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            expected.add(i);
        }
        expected.sort((x, y) -> {
            int c = Double.compare(scores[y], scores[x]);
            return c != 0 ? c : Integer.compare(x, y);
        });
        List<TopKHeap.Scored<Integer>> out = heap.toSortedList();
        for (int i = 0; i < k; i++) {
            assertEquals(expected.get(i), out.get(i).item());
        }
    }
}
