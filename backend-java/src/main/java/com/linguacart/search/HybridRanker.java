package com.linguacart.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Combines vector similarity and keyword (BM25) scores. Each part is min-max scaled over the catalogue,
 * then {@code total = weightVector * vector + weightLexical * lexical}. When a translated-query vector
 * score is supplied, the vector part is {@code w * original + (1 - w) * translated}, which keeps
 * brands and English words in the query effective. The weights are NOT tuned yet; they are config values.
 */
@Component
public class HybridRanker {

    /** Scaled scores per product position. */
    public record Scores(double[] total, double[] vector, double[] lexical) {}

    private final double weightVector;
    private final double weightLexical;
    private final double weightOriginal;

    public HybridRanker(
            @Value("${linguacart.search.weight-vector:0.6}") double weightVector,
            @Value("${linguacart.search.weight-lexical:0.4}") double weightLexical,
            @Value("${linguacart.search.weight-original-query:0.4}") double weightOriginal) {
        if (weightVector < 0 || weightLexical < 0 || weightVector + weightLexical <= 0) {
            throw new IllegalArgumentException("Search weights must be non-negative and not both zero");
        }
        if (weightOriginal < 0 || weightOriginal > 1) {
            throw new IllegalArgumentException("weight-original-query must be between 0 and 1");
        }
        this.weightVector = weightVector;
        this.weightLexical = weightLexical;
        this.weightOriginal = weightOriginal;
    }

    /**
     * @param originalVector   similarity of the original query, per product
     * @param translatedVector similarity of the translated query, or null when there is none
     * @param lexical          raw BM25 scores, per product
     */
    public Scores combine(double[] originalVector, double[] translatedVector, double[] lexical) {
        int n = originalVector.length;
        double[] original = minMax(originalVector);
        double[] mixed = original;
        if (translatedVector != null) {
            double[] translated = minMax(translatedVector);
            mixed = new double[n];
            for (int i = 0; i < n; i++) {
                mixed[i] = weightOriginal * original[i] + (1 - weightOriginal) * translated[i];
            }
        }
        double[] vector = minMax(mixed);
        double[] lex = minMax(lexical);
        double[] total = new double[n];
        for (int i = 0; i < n; i++) {
            total[i] = weightVector * vector[i] + weightLexical * lex[i];
        }
        return new Scores(total, vector, lex);
    }

    /** Scales to 0..1; all zeros when every value is (almost) the same. */
    public static double[] minMax(double[] xs) {
        double[] out = new double[xs.length];
        if (xs.length == 0) {
            return out;
        }
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double x : xs) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        if (hi - lo < 1e-9) {
            return out;
        }
        for (int i = 0; i < xs.length; i++) {
            out[i] = (xs[i] - lo) / (hi - lo);
        }
        return out;
    }
}
