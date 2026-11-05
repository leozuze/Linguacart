package com.linguacart.search;

import com.linguacart.cache.CacheKeyBuilder;
import com.linguacart.cache.LruTranslationCache;
import com.linguacart.client.ModelServiceClient;
import com.linguacart.client.ModelServiceException;
import com.linguacart.dto.ProductResult;
import com.linguacart.dto.SearchResponse;
import com.linguacart.dto.SegmentDtos;
import com.linguacart.product.ProductRecord;
import com.linguacart.service.SegmentTranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Query to ranked products (docs/08-INTEGRATION-GUIDE.md, section 4):
 * normalise, expand with the glossary, translate Shona to English, score by vectors (original and translated
 * query) plus BM25 keywords, keep the top K with a min-heap.
 *
 * <p>Ranking is decided here in Java. If query translation fails the search still runs on glossary terms
 * and the original words; if embedding fails the model-service error propagates (HTTP 503).
 * Quality can only be judged with the real models: with the mock or stub models the vector part is noise.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final String SNA = "sna_Latn";
    private static final String ENG = "eng_Latn";

    private final ModelServiceClient modelClient;
    private final CatalogueIndex catalogue;
    private final QueryGlossary glossary;
    private final HybridRanker ranker;
    private final LruTranslationCache cache;
    private final SegmentTranslationService titleTranslator;
    private final String modelVersion;
    private final boolean translateTitles;
    private final int maxTitleTranslations;

    public SearchService(
            ModelServiceClient modelClient,
            CatalogueIndex catalogue,
            QueryGlossary glossary,
            HybridRanker ranker,
            LruTranslationCache cache,
            SegmentTranslationService titleTranslator,
            @Value("${linguacart.cache.model-version:nllb-200-distilled-600M-v1}") String modelVersion,
            @Value("${linguacart.search.translate-titles:true}") boolean translateTitles,
            @Value("${linguacart.search.max-title-translations:5}") int maxTitleTranslations) {
        this.modelClient = modelClient;
        this.catalogue = catalogue;
        this.glossary = glossary;
        this.ranker = ranker;
        this.cache = cache;
        this.titleTranslator = titleTranslator;
        this.modelVersion = modelVersion;
        this.translateTitles = translateTitles;
        this.maxTitleTranslations = Math.max(0, maxTitleTranslations);
    }

    public SearchResponse search(String query, String language, int topK) {
        String normalised = Tokenizer.normalizeQuery(query);
        if (normalised.isEmpty()) {
            return new SearchResponse(query, List.of());
        }
        CatalogueIndex.Snapshot snap = catalogue.get();
        int n = snap.products().size();
        if (n == 0) {
            return new SearchResponse(query, List.of());
        }

        boolean shona = SNA.equals(language);
        Set<String> vocab = snap.index().vocabulary();

        // Glossary terms and translation (Shona queries only).
        List<QueryGlossary.Entry> glossaryHits = shona ? glossary.expand(normalised) : List.of();
        String english = shona ? translateQuery(normalised) : "";

        // Lexical query tokens: glossary English + the user's own words + translation.
        List<String> glossaryEnglish = new ArrayList<>();
        for (QueryGlossary.Entry e : glossaryHits) {
            glossaryEnglish.add(e.english());
        }
        List<String> tokens = new ArrayList<>(Tokenizer.tokens(String.join(" ", glossaryEnglish)));
        for (String t : Tokenizer.tokens(query)) {
            tokens.add(shona ? Tokenizer.stripShonaPrefix(t, vocab) : t);
        }
        tokens.addAll(Tokenizer.tokens(english));

        // Vector query: original text, plus the translation when there is one.
        boolean haveTranslation = !english.isBlank();
        List<String> toEmbed = haveTranslation ? List.of(query.strip(), english) : List.of(query.strip());
        List<float[]> qv = modelClient.embed(toEmbed, "query");
        if (qv == null || qv.size() != toEmbed.size()) {
            throw new ModelServiceException("Model service returned the wrong number of query embeddings");
        }
        double[] original = snap.vectors().scores(qv.get(0));
        double[] translated = haveTranslation ? snap.vectors().scores(qv.get(1)) : null;
        double[] bm25 = snap.index().bm25(tokens);

        HybridRanker.Scores scores = ranker.combine(original, translated, bm25);

        TopKHeap<Integer> heap = new TopKHeap<>(Math.min(topK, n));
        for (int i = 0; i < n; i++) {
            heap.offer(scores.total()[i], i);
        }

        Set<String> queryTokenSet = new HashSet<>(tokens);
        List<ProductResult> results = new ArrayList<>();
        int titlesDone = 0;
        for (TopKHeap.Scored<Integer> hit : heap.toSortedList()) {
            int i = hit.item();
            ProductRecord p = snap.products().get(i);
            List<String> why = new ArrayList<>();
            for (QueryGlossary.Entry e : glossaryHits) {
                if (Tokenizer.tokens(e.english()).stream().anyMatch(t -> snap.index().docContains(i, t))) {
                    why.add("glossary:" + e.shona());
                }
            }
            if (queryTokenSet.stream().anyMatch(t -> snap.index().docContains(i, t))) {
                why.add("keyword");
            }
            if (scores.vector()[i] >= 0.5) {
                why.add("vector");
            }
            String titleSn = null;
            if (translateTitles && titlesDone < maxTitleTranslations) {
                titleSn = translateTitle(p);
                titlesDone++;
            }
            results.add(new ProductResult(p.id(), p.titleEn(), titleSn, p.priceText(),
                    round(hit.score()), p.sourceUrl(), why));
        }
        return new SearchResponse(query, results);
    }

    /** Shona to English, cached; null-safe. Returns "" when translation is unavailable. */
    private String translateQuery(String normalised) {
        String key = CacheKeyBuilder.buildKey(normalised, "query", SNA, ENG, modelVersion);
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            String out = Tokenizer.cleanTranslation(modelClient.translate(normalised, SNA, ENG));
            if (!out.isBlank()) {
                cache.put(key, out);
            }
            return out;
        } catch (ModelServiceException e) {
            log.warn("Query translation failed ({}); searching with glossary and original words",
                    e.getClass().getSimpleName());
            return "";
        }
    }

    /** Shona title through the same protection checks as /translate; null means "show English". */
    private String translateTitle(ProductRecord p) {
        try {
            SegmentDtos.Request req = new SegmentDtos.Request(ENG, SNA, null, "search",
                    List.of(new SegmentDtos.Segment("t", p.titleEn(), "title")), java.util.Map.of());
            SegmentDtos.Result r = titleTranslator.translate(req).results().get(0);
            return "translated".equals(r.status()) ? r.text() : null;
        } catch (RuntimeException e) {
            log.warn("Title translation failed ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private static double round(double x) {
        return Math.round(x * 10000.0) / 10000.0;
    }
}
