package com.linguacart.search;

import com.linguacart.cache.LruTranslationCache;
import com.linguacart.client.ModelServiceClient;
import com.linguacart.client.ModelServiceException;
import com.linguacart.dto.SearchResponse;
import com.linguacart.glossary.GlossaryService;
import com.linguacart.product.CatalogueLoader;
import com.linguacart.protection.ProtectedFieldChecker;
import com.linguacart.service.SegmentTranslationService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pipeline tests with a fake model client. They check wiring and fallbacks, not search quality. */
class SearchServiceTest {

    /** Fake client: embeds by hashing words, "translates" every Shona query to the word "salt". */
    static class FakeClient implements ModelServiceClient {
        boolean failTranslate;
        boolean failEmbed;
        int translateCalls;

        @Override
        public String translate(String text, String sourceLanguage, String targetLanguage) {
            translateCalls++;
            if (failTranslate) {
                throw new ModelServiceException("down");
            }
            return "eng_Latn".equals(targetLanguage) ? "Salt." : "[SN] " + text;
        }

        @Override
        public List<float[]> embed(List<String> texts, String prefix) {
            if (failEmbed) {
                throw new ModelServiceException("down");
            }
            List<float[]> out = new ArrayList<>();
            for (String t : texts) {
                float[] v = new float[32];
                for (String w : t.toLowerCase().split("\\W+")) {
                    v[Math.abs(w.hashCode()) % 32] += 1;
                }
                out.add(v);
            }
            return out;
        }

        @Override
        public boolean isHealthy() {
            return !failEmbed;
        }
    }

    private final FakeClient client = new FakeClient();

    private SearchService service(boolean translateTitles) throws IOException {
        Path catalogue = Files.createTempFile("catalogue", ".jsonl");
        Files.write(catalogue, List.of(
                "{\"id\":\"p1\",\"title\":\"Iodized table salt\",\"description\":\"Salt for cooking\",\"brand\":\"A\","
                        + "\"category\":\"food\",\"price\":\"$1.00\",\"quantity\":\"1\",\"unit\":\"kg\"}",
                "{\"id\":\"p2\",\"title\":\"White sugar\",\"description\":\"Sweet sugar\",\"brand\":\"B\","
                        + "\"category\":\"food\",\"price\":\"$2.00\",\"quantity\":\"2\",\"unit\":\"kg\"}",
                "{\"id\":\"p3\",\"title\":\"Rice\",\"description\":\"Long grain rice\",\"brand\":\"C\","
                        + "\"category\":\"food\",\"price\":\"$3.00\",\"quantity\":\"5\",\"unit\":\"kg\"}"),
                StandardCharsets.UTF_8);
        catalogue.toFile().deleteOnExit();
        return build(new CatalogueLoader(catalogue.toString()), translateTitles);
    }

    private SearchService build(CatalogueLoader loader, boolean translateTitles) {
        LruTranslationCache cache = new LruTranslationCache(100);
        SegmentTranslationService titles = new SegmentTranslationService(client, new GlossaryService(),
                new ProtectedFieldChecker(), cache, "test", "mock", true);
        QueryGlossary glossary = new QueryGlossary(List.of(new QueryGlossary.Entry("munyu", "salt")));
        return new SearchService(client, new CatalogueIndex(loader, client), glossary,
                new HybridRanker(0.6, 0.4, 0.4), cache, titles, "test", translateTitles, 2);
    }

    @Test
    void shonaQueryFindsTheProductThroughTheGlossary() throws IOException {
        SearchResponse r = service(false).search("munyu", "sna_Latn", 3);
        assertEquals(3, r.results().size());
        assertEquals("p1", r.results().get(0).productId());
        assertTrue(r.results().get(0).matchedOn().contains("glossary:munyu"));
        assertTrue(r.results().get(0).score() >= r.results().get(1).score());
    }

    @Test
    void topKLimitsResults() throws IOException {
        assertEquals(1, service(false).search("munyu", "sna_Latn", 1).results().size());
        assertEquals(3, service(false).search("munyu", "sna_Latn", 50).results().size());
    }

    @Test
    void titlesAreTranslatedForTheFirstFewResultsOnly() throws IOException {
        SearchResponse r = service(true).search("munyu", "sna_Latn", 3);
        assertNotNull(r.results().get(0).titleSn());
        assertNotNull(r.results().get(1).titleSn());
        assertEquals(null, r.results().get(2).titleSn());
    }

    @Test
    void searchStillWorksWhenQueryTranslationFails() throws IOException {
        client.failTranslate = true;
        SearchResponse r = service(false).search("munyu", "sna_Latn", 3);
        // The fake vectors are hash noise, so check the glossary still reached the keyword part, not the rank.
        assertEquals(3, r.results().size());
        assertTrue(r.results().stream().anyMatch(x -> x.productId().equals("p1")
                && x.matchedOn().contains("glossary:munyu")));
    }

    @Test
    void embeddingFailurePropagates() throws IOException {
        client.failEmbed = true;
        SearchService s = service(false);
        assertThrows(ModelServiceException.class, () -> s.search("munyu", "sna_Latn", 3));
    }

    @Test
    void queryTranslationIsCached() throws IOException {
        SearchService s = service(false);
        s.search("munyu", "sna_Latn", 3);
        int calls = client.translateCalls;
        s.search("munyu", "sna_Latn", 3);
        assertEquals(calls, client.translateCalls);
    }

    @Test
    void englishQueriesSkipTranslation() throws IOException {
        SearchResponse r = service(false).search("sugar", "eng_Latn", 3);
        assertEquals(0, client.translateCalls);
        assertEquals("p2", r.results().get(0).productId());
    }

    @Test
    void punctuationOnlyQueryReturnsNothing() throws IOException {
        assertTrue(service(false).search("?!", "sna_Latn", 3).results().isEmpty());
    }

    @Test
    void emptyCatalogueReturnsNothing() {
        SearchService s = build(new CatalogueLoader("does/not/exist.jsonl"), false);
        assertTrue(s.search("munyu", "sna_Latn", 3).results().isEmpty());
        assertFalse(client.failEmbed);
    }
}
