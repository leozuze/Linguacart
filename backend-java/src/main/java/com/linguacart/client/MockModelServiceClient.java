package com.linguacart.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mock implementation of ModelServiceClient used for local development and testing without running Python.
 */
public class MockModelServiceClient implements ModelServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MockModelServiceClient.class);
    public static final int EMBEDDING_DIMENSION = 768;

    @Override
    public String translate(String text, String sourceLanguage, String targetLanguage) {
        if (text == null) {
            return null;
        }
        return "[sn] " + text;
    }

    @Override
    public List<float[]> embed(List<String> texts, String prefix) {
        if (texts == null) {
            return List.of();
        }
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (String text : texts) {
            embeddings.add(generateMockEmbedding(text, prefix));
        }
        return embeddings;
    }

    private float[] generateMockEmbedding(String text, String prefix) {
        float[] vector = new float[EMBEDDING_DIMENSION];
        int seed = (prefix + ":" + (text != null ? text : "")).hashCode();
        Random random = new Random(seed);
        double sumSquares = 0.0;
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            float val = (float) random.nextGaussian();
            vector[i] = val;
            sumSquares += val * val;
        }
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    @Override
    public boolean isHealthy() {
        log.debug("MockModelServiceClient isHealthy check returning true");
        return true;
    }
}
