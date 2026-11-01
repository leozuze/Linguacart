package com.linguacart.service;

import com.linguacart.cache.LruTranslationCache;
import com.linguacart.client.ModelServiceClient;
import com.linguacart.client.ModelServiceException;
import com.linguacart.dto.SegmentDtos.Request;
import com.linguacart.dto.SegmentDtos.Response;
import com.linguacart.dto.SegmentDtos.Segment;
import com.linguacart.glossary.GlossaryService;
import com.linguacart.protection.ProtectedFieldChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentTranslationServiceTest {

    /** Test double: counts calls and applies a function to the text. */
    static class FakeClient implements ModelServiceClient {
        int calls = 0;
        private final Function<String, String> behaviour;

        FakeClient(Function<String, String> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public String translate(String text, String sourceLanguage, String targetLanguage) {
            calls++;
            return behaviour.apply(text);
        }

        @Override
        public List<float[]> embed(List<String> texts, String prefix) {
            return List.of();
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    }

    private static GlossaryService glossary() {
        GlossaryService g = new GlossaryService();
        g.put("salt", "munyu");
        return g;
    }

    private static SegmentTranslationService service(FakeClient client, String mode, boolean rejectNegation) {
        return new SegmentTranslationService(client, glossary(), new ProtectedFieldChecker(),
                new LruTranslationCache(100), "test-v1", mode, rejectNegation);
    }

    private static Segment seg(String id, String text) {
        return new Segment(id, text, "other");
    }

    private static Request request(Segment... segments) {
        return new Request("eng_Latn", "sna_Latn", "amazon.com", "listing", List.of(segments), null);
    }


    private static FakeClient prefixing() {
        return new FakeClient(t -> "[sn] " + t);
    }

    @Test
    @DisplayName("all segments translate: order, ids, source and translationType are set")
    void allTranslated() {
        FakeClient client = prefixing();
        Response r = service(client, "http", true).translate(request(
                seg("s0", "Add to cart"),
                seg("s1", "Iodized table salt, 1 kg"),
                seg("s2", "INR 1,092.75 delivery Fri, Nov 13")));

        assertEquals("translated", r.status());
        assertTrue(r.notTranslated().isEmpty());
        assertEquals("s0", r.results().get(0).id());
        assertEquals("s2", r.results().get(2).id());
        assertEquals("nllb", r.results().get(0).source());
        assertEquals("machine", r.results().get(0).translationType());
        assertEquals(3, client.calls);
    }

    @Test
    @DisplayName("glossary corrections are applied before the protection checks")
    void glossaryApplied() {
        Response r = service(prefixing(), "http", true).translate(request(seg("s1", "Iodized table salt, 1 kg")));
        assertEquals("[sn] Iodized table munyu, 1 kg", r.results().get(0).text());
    }

    @Test
    @DisplayName("a repeated segment is served from the cache without a second model call")
    void cacheHit() {
        FakeClient client = prefixing();
        SegmentTranslationService service = service(client, "http", true);
        service.translate(request(seg("s0", "Add to cart")));
        Response second = service.translate(request(seg("s0", "Add to cart")));

        assertEquals(1, client.calls);
        assertEquals("cache", second.results().get(0).source());
        assertEquals("machine", second.results().get(0).translationType());
    }

    @Test
    @DisplayName("a changed price is reverted with protected_value_changed, others still translate")
    void priceCorrupted() {
        FakeClient client = new FakeClient(t -> t.replace("1,092.75", "1,092.57"));
        Response r = service(client, "http", true).translate(request(
                seg("s2", "INR 1,092.75 delivery"), seg("s3", "Add to cart")));

        assertEquals("partial", r.status());
        assertEquals(List.of("s2"), r.notTranslated());
        assertEquals("not_translated", r.results().get(0).status());
        assertEquals(List.of("protected_value_changed"), r.results().get(0).reasons());
        assertNull(r.results().get(0).text());
        assertEquals("translated", r.results().get(1).status());
    }

    @Test
    @DisplayName("a reverted segment is never cached")
    void revertedNotCached() {
        FakeClient client = new FakeClient(t -> t.replace("1,092.75", "1,092.57"));
        SegmentTranslationService service = service(client, "http", true);
        service.translate(request(seg("s2", "INR 1,092.75 delivery")));
        service.translate(request(seg("s2", "INR 1,092.75 delivery")));
        assertEquals(2, client.calls);
    }

    @Test
    @DisplayName("an invented number and a dropped number are both reverted")
    void inventedAndDroppedNumbers() {
        Response invented = service(new FakeClient(t -> t + " 5"), "http", true)
                .translate(request(seg("s", "Free delivery")));
        assertEquals("failed", invented.status());

        Response dropped = service(new FakeClient(t -> "Dzasara"), "http", true)
                .translate(request(seg("s", "Only 3 left in stock")));
        assertEquals(List.of("protected_value_changed"), dropped.results().get(0).reasons());
    }

    @Test
    @DisplayName("the contract's negation sentence stays English and the model is never called")
    void negationNotTranslated() {
        FakeClient client = prefixing();
        Response r = service(client, "http", true).translate(request(seg("s",
                "This item cannot be shipped to your selected delivery location. "
                        + "Please choose a different delivery location.")));

        assertEquals("failed", r.status());
        assertEquals(List.of("negation_mismatch"), r.results().get(0).reasons());
        assertEquals(0, client.calls);
    }

    @Test
    @DisplayName("negation rejection can be switched off")
    void negationSwitchedOff() {
        Response r = service(prefixing(), "http", false).translate(request(seg("s", "Not waterproof")));
        assertEquals("translated", r.status());
    }

    @Test
    @DisplayName("model failure on every segment gives status failed with model_error")
    void allModelErrors() {
        FakeClient down = new FakeClient(t -> {
            throw new ModelServiceException("down");
        });
        Response r = service(down, "http", true).translate(request(seg("a", "Add to cart"), seg("b", "Buy now")));

        assertEquals("failed", r.status());
        assertEquals(List.of("model_error"), r.results().get(0).reasons());
    }

    @Test
    @DisplayName("model failure on one segment gives status partial")
    void oneModelError() {
        FakeClient half = new FakeClient(t -> {
            if (t.startsWith("Buy")) {
                throw new ModelServiceException("x");
            }
            return "[sn] " + t;
        });
        Response r = service(half, "http", true).translate(request(seg("a", "Add to cart"), seg("b", "Buy now")));

        assertEquals("partial", r.status());
        assertEquals(List.of("b"), r.notTranslated());
    }

    @Test
    @DisplayName("blank model output is treated as a model error")
    void blankOutput() {
        Response r = service(new FakeClient(t -> "  "), "http", true).translate(request(seg("a", "Add to cart")));
        assertEquals(List.of("model_error"), r.results().get(0).reasons());
    }

    @Test
    @DisplayName("mock mode labels results as mock")
    void mockMode() {
        Response r = service(prefixing(), "mock", true).translate(request(seg("a", "Add to cart")));
        assertEquals("mock", r.results().get(0).source());
        assertEquals("mock", r.results().get(0).translationType());
    }

    @Test
    @DisplayName("protectedFields are echoed unchanged; an altered model number reverts the segment")
    void protectedFields() {
        Request withProtected = new Request("eng_Latn", "sna_Latn", null, null,
                List.of(seg("a", "Sony WH-CH720N headphones")),
                Map.of("price", "$2.99", "brand", "Sony", "model", "WH-CH720N"));

        Response ok = service(prefixing(), "http", true).translate(withProtected);
        assertEquals("$2.99", ok.protectedFields().get("price"));
        assertEquals("WH-CH720N", ok.protectedFields().get("model"));

        Response altered = service(new FakeClient(t -> t.replace("WH-CH720N", "WH-CH720M")), "http", true)
                .translate(withProtected);
        assertEquals("failed", altered.status());
    }

    @Test
    @DisplayName("segment ids must be unique and non-null")
    void idsUnique() {
        assertFalse(new Request("eng_Latn", "sna_Latn", null, null,
                List.of(seg("a", "x"), seg("a", "y")), null).isSegmentsWellFormed());
        assertTrue(new Request("eng_Latn", "sna_Latn", null, null,
                List.of(seg("a", "x"), seg("b", "y")), null).isSegmentsWellFormed());
    }
}
