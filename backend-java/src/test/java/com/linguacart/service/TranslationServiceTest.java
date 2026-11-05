package com.linguacart.service;

import com.linguacart.cache.CacheKeyBuilder;
import com.linguacart.cache.LruTranslationCache;
import com.linguacart.client.ModelServiceClient;
import com.linguacart.client.ModelServiceException;
import com.linguacart.dto.TranslateRequest;
import com.linguacart.dto.TranslateResponse;
import com.linguacart.glossary.GlossaryService;
import com.linguacart.protection.ProtectedFieldChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TranslationService}.
 *
 * <p>All collaborators are hand-rolled test doubles (no Mockito dependency needed).
 * The ProtectedFieldChecker is used as-is (it is a pure function with no dependencies).
 */
class TranslationServiceTest {

    private static final String SRC = "eng_Latn";
    private static final String TGT = "sna_Latn";
    private static final String MODEL_VERSION = "test-model-v1";

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    /** A ModelServiceClient that prepends "[sn] " to every text — simulates a healthy model. */
    static class SimpleTranslatingClient implements ModelServiceClient {
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public String translate(String text, String sourceLanguage, String targetLanguage) {
            callCount.incrementAndGet();
            return "[sn] " + text;
        }

        @Override
        public java.util.List<float[]> embed(java.util.List<String> texts, String prefix) {
            return List.of();
        }

        @Override
        public boolean isHealthy() { return true; }
    }

    /** A ModelServiceClient that always throws ModelServiceException. */
    static class AlwaysFailingClient implements ModelServiceClient {
        @Override
        public String translate(String text, String sourceLanguage, String targetLanguage) {
            throw new ModelServiceException("model service down");
        }

        @Override
        public java.util.List<float[]> embed(java.util.List<String> texts, String prefix) {
            throw new ModelServiceException("model service down");
        }

        @Override
        public boolean isHealthy() { return false; }
    }

    /**
     * A ModelServiceClient that fails for a specific field name (matched by text content),
     * and otherwise delegates to SimpleTranslatingClient.
     */
    static class PartialFailingClient implements ModelServiceClient {
        private final String failText;
        private final SimpleTranslatingClient delegate = new SimpleTranslatingClient();

        PartialFailingClient(String failText) {
            this.failText = failText;
        }

        @Override
        public String translate(String text, String sourceLanguage, String targetLanguage) {
            if (text != null && text.contains(failText)) {
                throw new ModelServiceException("model failure for: " + text);
            }
            return delegate.translate(text, sourceLanguage, targetLanguage);
        }

        @Override
        public java.util.List<float[]> embed(java.util.List<String> texts, String prefix) {
            return List.of();
        }

        @Override
        public boolean isHealthy() { return true; }
    }

    /**
     * A ModelServiceClient that returns a corrupt translation (altered price)
     * for the field containing the trigger text.
     */
    static class CorruptPriceClient implements ModelServiceClient {
        private final String triggerText;
        private final String corruptOutput;

        CorruptPriceClient(String triggerText, String corruptOutput) {
            this.triggerText = triggerText;
            this.corruptOutput = corruptOutput;
        }

        @Override
        public String translate(String text, String sourceLanguage, String targetLanguage) {
            if (text != null && text.contains(triggerText)) {
                return corruptOutput;
            }
            return "[sn] " + text;
        }

        @Override
        public java.util.List<float[]> embed(java.util.List<String> texts, String prefix) {
            return List.of();
        }

        @Override
        public boolean isHealthy() { return true; }
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    private GlossaryService emptyGlossary;
    private ProtectedFieldChecker checker;

    @BeforeEach
    void setUp() {
        // Empty glossary — no substitutions
        emptyGlossary = new GlossaryService((String) null);
        // Use default constructor (known brands + default safety phrases)
        checker = new ProtectedFieldChecker();
    }

    private TranslationService service(ModelServiceClient client) {
        LruTranslationCache cache = new LruTranslationCache(100);
        return new TranslationService(client, emptyGlossary, checker, cache, MODEL_VERSION);
    }

    private TranslationService serviceWithCache(ModelServiceClient client, LruTranslationCache cache) {
        return new TranslationService(client, emptyGlossary, checker, cache, MODEL_VERSION);
    }

    // -------------------------------------------------------------------------
    // 1. All fields pass
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("All fields translated successfully: status=translated, notTranslated is empty")
    void allFieldsPass() {
        TranslationService svc = service(new SimpleTranslatingClient());

        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of("title", "Salt 500 g", "description", "Healthy table salt"),
                Map.of("price", "$2.99"));

        TranslateResponse resp = svc.translate(req);

        assertEquals("translated", resp.status());
        assertTrue(resp.notTranslated().isEmpty());
        assertEquals("[sn] Salt 500 g", resp.translations().get("title"));
        assertEquals("[sn] Healthy table salt", resp.translations().get("description"));
        // protectedFields echoed unchanged
        assertEquals(Map.of("price", "$2.99"), resp.protectedFields());
    }

    // -------------------------------------------------------------------------
    // 2. One field reverted by protection (corrupted price)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Field with corrupted price is reverted; other field is translated; status=partial")
    void oneFieldRevertedByProtection() {
        // title contains "$2.99" — model returns corrupt price "$3.00"
        ModelServiceClient client = new CorruptPriceClient("$2.99", "[sn] Salt 500 g with $3.00 only");
        TranslationService svc = service(client);

        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of(
                        "title", "Salt 500 g $2.99",
                        "description", "Healthy table salt"
                ),
                Map.of());

        TranslateResponse resp = svc.translate(req);

        assertEquals("partial", resp.status());
        assertTrue(resp.notTranslated().contains("title"), "title should be reverted");
        assertFalse(resp.notTranslated().contains("description"), "description should pass");

        // Title reverted to original English
        assertEquals("Salt 500 g $2.99", resp.translations().get("title"));
        // Description translated
        assertEquals("[sn] Healthy table salt", resp.translations().get("description"));
    }

    // -------------------------------------------------------------------------
    // 3. Model failure on one field
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Model failure on one field: that field is reverted; rest are translated; status=partial")
    void modelFailureOnOneField() {
        // Fail only the field whose text contains "FAIL_ME"
        TranslationService svc = service(new PartialFailingClient("FAIL_ME"));

        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of(
                        "title", "Good product title",
                        "badField", "FAIL_ME text here"
                ),
                Map.of());

        TranslateResponse resp = svc.translate(req);

        assertEquals("partial", resp.status());
        assertTrue(resp.notTranslated().contains("badField"), "badField should be in notTranslated");
        assertFalse(resp.notTranslated().contains("title"));

        assertEquals("[sn] Good product title", resp.translations().get("title"));
        // Reverted to English original
        assertEquals("FAIL_ME text here", resp.translations().get("badField"));
    }

    // -------------------------------------------------------------------------
    // 4. Model failure on ALL fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Model failure on all fields: status=failed, HTTP 200 implied (all English text returned)")
    void modelFailureOnAllFields() {
        TranslationService svc = service(new AlwaysFailingClient());

        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of("title", "Salt", "description", "Table salt"),
                Map.of());

        TranslateResponse resp = svc.translate(req);

        assertEquals("failed", resp.status());
        assertEquals(2, resp.notTranslated().size());
        // All English
        assertEquals("Salt", resp.translations().get("title"));
        assertEquals("Table salt", resp.translations().get("description"));
    }

    // -------------------------------------------------------------------------
    // 5. Cache hit avoids a second model call
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Cache hit: second identical request does not call the model again")
    void cacheHitAvoidsModelCall() {
        SimpleTranslatingClient client = new SimpleTranslatingClient();
        LruTranslationCache cache = new LruTranslationCache(100);
        TranslationService svc = serviceWithCache(client, cache);

        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of("title", "Basmati rice 1 kg"),
                Map.of());

        // First call — hits model
        svc.translate(req);
        assertEquals(1, client.callCount.get(), "Model should be called once on first request");

        // Second call — should hit cache
        svc.translate(req);
        assertEquals(1, client.callCount.get(), "Model must NOT be called again on cache hit");
    }

    // -------------------------------------------------------------------------
    // 6. Reverted result is NOT cached
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Reverted (protection-failed) field is not stored in cache")
    void revertedResultNotCached() {
        // Corrupt translation: price is changed
        ModelServiceClient client = new CorruptPriceClient("$2.99", "[sn] Product $99.00 wrong");
        LruTranslationCache cache = new LruTranslationCache(100);
        SimpleTranslatingClient countingPassthrough = new SimpleTranslatingClient() {
            @Override
            public String translate(String text, String sourceLanguage, String targetLanguage) {
                callCount.incrementAndGet();
                if (text != null && text.contains("$2.99")) {
                    return "[sn] Product $99.00 wrong";  // corrupt
                }
                return "[sn] " + text;
            }
        };

        TranslationService svc = serviceWithCache(countingPassthrough, cache);
        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of("title", "Product $2.99"),
                Map.of());

        // First call — reverted (corrupt price)
        TranslateResponse resp1 = svc.translate(req);
        assertEquals("failed", resp1.status());
        int callsAfterFirst = countingPassthrough.callCount.get();

        // Second call — should NOT hit cache (reverted was never stored)
        TranslateResponse resp2 = svc.translate(req);
        assertTrue(countingPassthrough.callCount.get() > callsAfterFirst,
                "Model should be called again because reverted result was not cached");
    }

    // -------------------------------------------------------------------------
    // 7. Glossary applied before protection check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Glossary corrections applied to model output before the protection check")
    void glossaryAppliedBeforeProtectionCheck() {
        // Glossary maps "rice" -> "mupunga"
        GlossaryService glossary = new GlossaryService((String) null);
        glossary.put("rice", "mupunga");

        // Model just echoes "[sn] " prefix
        SimpleTranslatingClient client = new SimpleTranslatingClient();
        LruTranslationCache cache = new LruTranslationCache(100);
        TranslationService svc = new TranslationService(client, glossary, checker, cache, MODEL_VERSION);

        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of("title", "Basmati rice 1 kg"),
                Map.of());

        TranslateResponse resp = svc.translate(req);

        // "[sn] Basmati rice 1 kg" → after glossary → "[sn] Basmati mupunga 1 kg"
        String translated = resp.translations().get("title");
        assertTrue(translated.contains("mupunga"),
                "Glossary substitution 'rice' -> 'mupunga' must appear in final output; got: " + translated);
        assertEquals("translated", resp.status());
    }

    // -------------------------------------------------------------------------
    // 8. protectedFields echoed unchanged
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("protectedFields are echoed exactly as sent, never from model output")
    void protectedFieldsEchoedUnchanged() {
        TranslationService svc = service(new SimpleTranslatingClient());

        Map<String, String> sentProtected = Map.of(
                "price", "$2.99", "quantity", "1", "unit", "kg",
                "brand", "BrandX", "model", "BX-100"
        );

        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of("title", "BrandX BX-100 Salt 1 kg $2.99"),
                sentProtected);

        TranslateResponse resp = svc.translate(req);

        assertEquals(sentProtected, resp.protectedFields(),
                "protectedFields must be echoed exactly as sent");
    }

    // -------------------------------------------------------------------------
    // 9. MockMvc contract test — full /translate JSON shape
    // -------------------------------------------------------------------------

    // NOTE: MockMvc contract test lives in TranslationControllerMvcTest (separate class)
    // to keep unit tests and Spring integration tests cleanly separated.

    // -------------------------------------------------------------------------
    // 10. Status: "partial" when some (not all) fields reverted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("status=partial when some but not all fields revert")
    void statusPartialWhenSomeRevert() {
        // Fail one of two fields
        TranslationService svc = service(new PartialFailingClient("FAIL"));

        TranslateRequest req = new TranslateRequest(SRC, TGT,
                Map.of("good", "Good text", "bad", "FAIL this"),
                Map.of());

        TranslateResponse resp = svc.translate(req);
        assertEquals("partial", resp.status());
        assertEquals(1, resp.notTranslated().size());
        assertTrue(resp.notTranslated().contains("bad"));
    }

    // -------------------------------------------------------------------------
    // 11. Field order is stable
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("translations map preserves original field insertion order from request")
    void fieldOrderIsStable() {
        TranslationService svc = service(new SimpleTranslatingClient());

        // LinkedHashMap to guarantee insertion order
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("c", "C text");
        fields.put("a", "A text");
        fields.put("b", "B text");

        TranslateRequest req = new TranslateRequest(SRC, TGT, fields, Map.of());
        TranslateResponse resp = svc.translate(req);

        List<String> keys = new java.util.ArrayList<>(resp.translations().keySet());
        assertEquals(List.of("c", "a", "b"), keys, "Field order must be preserved");
    }
}
