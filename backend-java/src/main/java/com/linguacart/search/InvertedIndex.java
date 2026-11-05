package com.linguacart.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inverted index: token to the products that contain it, with term counts for BM25 scoring
 * (k1 = 1.5, b = 0.75, as in the reference pipeline). Build it once, then only read from it.
 * Not thread-safe while building; safe to share afterwards.
 */
public class InvertedIndex {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /** token -> (document position -> term count), in insertion order. */
    private final Map<String, Map<Integer, Integer>> postings = new LinkedHashMap<>();
    private final List<String> productIds = new ArrayList<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private long totalLength = 0;

    /** Adds one product. Documents are numbered in the order they are added. */
    public void add(String productId, List<String> tokens) {
        int doc = productIds.size();
        productIds.add(productId);
        docLengths.add(tokens.size());
        totalLength += tokens.size();
        for (String token : tokens) {
            postings.computeIfAbsent(token, k -> new LinkedHashMap<>()).merge(doc, 1, Integer::sum);
        }
    }

    public int size() {
        return productIds.size();
    }

    public String productId(int doc) {
        return productIds.get(doc);
    }

    public Set<String> vocabulary() {
        return postings.keySet();
    }

    /** Ids of the products containing the token, in the order they were added. */
    public List<String> productIds(String token) {
        Map<Integer, Integer> docs = postings.get(token);
        List<String> out = new ArrayList<>();
        if (docs != null) {
            for (int doc : docs.keySet()) {
                out.add(productIds.get(doc));
            }
        }
        return out;
    }

    public boolean docContains(int doc, String token) {
        Map<Integer, Integer> docs = postings.get(token);
        return docs != null && docs.containsKey(doc);
    }

    /** BM25 score per document position (all zeros when nothing matches). */
    public double[] bm25(List<String> queryTokens) {
        int n = productIds.size();
        double[] scores = new double[n];
        if (n == 0) {
            return scores;
        }
        double avgLength = (double) totalLength / n;
        for (String token : new HashSet<>(queryTokens)) {
            Map<Integer, Integer> docs = postings.get(token);
            if (docs == null) {
                continue;
            }
            int df = docs.size();
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (Map.Entry<Integer, Integer> e : docs.entrySet()) {
                int doc = e.getKey();
                double f = e.getValue();
                double dl = docLengths.get(doc);
                double norm = avgLength == 0 ? 1 : 1 - B + B * dl / avgLength;
                scores[doc] += idf * f * (K1 + 1) / (f + K1 * norm);
            }
        }
        return scores;
    }
}
