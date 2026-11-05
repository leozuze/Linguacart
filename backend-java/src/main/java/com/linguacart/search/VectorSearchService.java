package com.linguacart.search;

import java.util.List;

/**
 * Brute-force vector search: compares the query with every product vector (cosine similarity).
 * Vectors are copied and L2-normalised on construction, so a dot product equals the cosine.
 */
public class VectorSearchService {

    private final float[][] vectors;
    private final int dimension;

    public VectorSearchService(List<float[]> productVectors) {
        this.vectors = new float[productVectors.size()][];
        int dim = -1;
        for (int i = 0; i < productVectors.size(); i++) {
            float[] v = productVectors.get(i);
            if (v == null || v.length == 0) {
                throw new IllegalArgumentException("Product vector " + i + " is empty");
            }
            if (dim == -1) {
                dim = v.length;
            } else if (v.length != dim) {
                throw new IllegalArgumentException("Product vector " + i + " has dimension " + v.length
                        + ", expected " + dim);
            }
            vectors[i] = normalize(v);
        }
        this.dimension = Math.max(dim, 0);
    }

    public int size() {
        return vectors.length;
    }

    public int dimension() {
        return dimension;
    }

    /** Cosine similarity of the query with every product, in product order. */
    public double[] scores(float[] query) {
        double[] out = new double[vectors.length];
        if (vectors.length == 0) {
            return out;
        }
        if (query == null || query.length != dimension) {
            throw new IllegalArgumentException("Query vector has dimension "
                    + (query == null ? 0 : query.length) + ", expected " + dimension);
        }
        float[] q = normalize(query);
        for (int i = 0; i < vectors.length; i++) {
            double dot = 0;
            for (int j = 0; j < dimension; j++) {
                dot += (double) q[j] * vectors[i][j];
            }
            out[i] = dot;
        }
        return out;
    }

    public static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        float[] out = new float[v.length];
        if (sum == 0) {
            return out;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors have different dimensions");
        }
        float[] x = normalize(a);
        float[] y = normalize(b);
        double dot = 0;
        for (int i = 0; i < x.length; i++) {
            dot += (double) x[i] * y[i];
        }
        return dot;
    }
}
