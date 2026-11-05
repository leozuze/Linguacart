package com.linguacart.search;

import com.linguacart.client.ModelServiceClient;
import com.linguacart.product.CatalogueLoader;
import com.linguacart.product.ProductRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The searchable catalogue: products, keyword index and product vectors. It is built on the first search
 * (not at start-up, so the app starts even when the model service is down) and kept in memory only.
 * If building fails (for example the model service is down) nothing is kept and the next search retries.
 */
@Component
public class CatalogueIndex {

    private static final Logger log = LoggerFactory.getLogger(CatalogueIndex.class);

    /** Immutable snapshot once built. */
    public record Snapshot(List<ProductRecord> products, InvertedIndex index, VectorSearchService vectors) {}

    private final CatalogueLoader loader;
    private final ModelServiceClient modelClient;
    private volatile Snapshot snapshot;

    public CatalogueIndex(CatalogueLoader loader, ModelServiceClient modelClient) {
        this.loader = loader;
        this.modelClient = modelClient;
    }

    public Snapshot get() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (this) {
                s = snapshot;
                if (s == null) {
                    s = build();
                    snapshot = s;
                }
            }
        }
        return s;
    }

    /** Drops the built index (used by tests, or after the catalogue file changes). */
    public synchronized void reset() {
        snapshot = null;
    }

    private Snapshot build() {
        List<ProductRecord> products = loader.load();
        InvertedIndex index = new InvertedIndex();
        List<String> passages = new ArrayList<>();
        for (ProductRecord p : products) {
            String text = p.passageText();
            passages.add(text);
            index.add(p.id(), Tokenizer.tokens(text));
        }
        List<float[]> vectors = products.isEmpty() ? List.of() : modelClient.embed(passages, "passage");
        if (vectors.size() != products.size()) {
            throw new IllegalStateException("Embedding count does not match product count");
        }
        log.info("Catalogue index built: {} products", products.size());
        return new Snapshot(products, index, new VectorSearchService(vectors));
    }
}
