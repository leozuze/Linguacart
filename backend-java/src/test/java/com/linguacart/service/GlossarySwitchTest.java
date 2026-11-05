package com.linguacart.service;

import com.linguacart.cache.LruTranslationCache;
import com.linguacart.client.ModelServiceClient;
import com.linguacart.dto.SegmentDtos.Request;
import com.linguacart.dto.SegmentDtos.Segment;
import com.linguacart.glossary.GlossaryService;
import com.linguacart.protection.ProtectedFieldChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The glossary corrector must be switchable, because the bundled glossary is unreviewed. */
class GlossarySwitchTest {

    private static final ModelServiceClient CLIENT = new ModelServiceClient() {
        @Override
        public String translate(String text, String sourceLanguage, String targetLanguage) {
            return "salt yakanaka";
        }

        @Override
        public List<float[]> embed(List<String> texts, String prefix) {
            return List.of();
        }

        @Override
        public boolean isHealthy() {
            return true;
        }
    };

    private static String run(boolean applyGlossary) {
        GlossaryService glossary = new GlossaryService();
        glossary.put("salt", "munyu");
        SegmentTranslationService service = new SegmentTranslationService(CLIENT, glossary,
                new ProtectedFieldChecker(), new LruTranslationCache(10), "test", "http", false, applyGlossary);
        Request request = new Request("eng_Latn", "sna_Latn", null, null,
                List.of(new Segment("s1", "Good salt", "title")), Map.of());
        return service.translate(request).results().get(0).text();
    }

    @Test
    void glossaryOffLeavesModelOutputUntouched() {
        assertEquals("salt yakanaka", run(false));
    }

    @Test
    void glossaryOnCorrectsTheOutput() {
        assertEquals("munyu yakanaka", run(true));
    }
}
