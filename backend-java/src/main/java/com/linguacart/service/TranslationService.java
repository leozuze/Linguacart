package com.linguacart.service;

import com.linguacart.cache.CacheKeyBuilder;
import com.linguacart.cache.LruTranslationCache;
import com.linguacart.client.ModelServiceClient;
import com.linguacart.client.ModelServiceException;
import com.linguacart.dto.TranslateRequest;
import com.linguacart.dto.TranslateResponse;
import com.linguacart.glossary.GlossaryService;
import com.linguacart.protection.ProtectedFieldChecker;
import com.linguacart.protection.ProtectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orchestrates the full per-field translation pipeline:
 * <ol>
 *   <li><b>Cache lookup</b> — return immediately if a valid cached result exists.</li>
 *   <li><b>Model call</b> — delegate to {@link ModelServiceClient}. If the call fails,
 *       revert the field to English, add to {@code notTranslated}, and continue (failure
 *       isolation: one bad field must not block the rest).</li>
 *   <li><b>Glossary corrections</b> — apply {@link GlossaryService#applyGlossary} to the
 *       model output <em>before</em> the protection check, so the checker validates the
 *       final text.</li>
 *   <li><b>Protection check</b> — run {@link ProtectedFieldChecker#verifyTranslation}.
 *       Reverted fields are added to {@code notTranslated}.</li>
 *   <li><b>Conditional cache store</b> — only cache results that passed protection
 *       (i.e. fields <em>not</em> in {@code notTranslated}). Never cache reverted fields
 *       and never cache model failures.</li>
 * </ol>
 *
 * <h3>Status rules</h3>
 * <ul>
 *   <li>{@code "translated"} — {@code notTranslated} is empty.</li>
 *   <li>{@code "partial"}    — some but not all fields were reverted.</li>
 *   <li>{@code "failed"}     — every field was reverted (model down or all protection
 *       failures). Returns HTTP 200 so the browser extension can still display the page
 *       in English. Reserve 503 for cases where the request cannot be processed at all.</li>
 * </ul>
 *
 * <h3>protectedFields</h3>
 * Always echoed exactly as received — they are never taken from model output.
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final ModelServiceClient modelServiceClient;
    private final GlossaryService glossaryService;
    private final ProtectedFieldChecker protectedFieldChecker;
    private final LruTranslationCache cache;
    private final String modelVersion;

    public TranslationService(
            ModelServiceClient modelServiceClient,
            GlossaryService glossaryService,
            ProtectedFieldChecker protectedFieldChecker,
            LruTranslationCache cache,
            @Value("${linguacart.cache.model-version:nllb-200-distilled-600M-v1}") String modelVersion) {
        this.modelServiceClient = modelServiceClient;
        this.glossaryService = glossaryService;
        this.protectedFieldChecker = protectedFieldChecker;
        this.cache = cache;
        this.modelVersion = modelVersion;
    }

    /**
     * Processes a translation request for all fields and returns a fully populated response.
     */
    public TranslateResponse translate(TranslateRequest request) {
        Map<String, String> originalFields = request.fields();
        Map<String, String> protectedFields = request.protectedFields();
        String src = request.sourceLanguage();
        String tgt = request.targetLanguage();

        // Phase 1 + 2 + 3: cache lookup → model call → glossary
        // Per-field translated text (may be English original if model failed)
        Map<String, String> rawTranslated = new LinkedHashMap<>();
        // Fields that the model failed on (before protection check)
        Set<String> modelFailedFields = new LinkedHashSet<>();
        // Cached results that skipped the model (for cache-hit tracking)
        Set<String> cacheHitFields = new LinkedHashSet<>();

        for (String fieldName : originalFields.keySet()) {
            String originalText = originalFields.get(fieldName);
            String cacheKey = CacheKeyBuilder.buildKey(originalText, fieldName, src, tgt, modelVersion);

            // 1. Cache lookup
            String cached = cache.get(cacheKey);
            if (cached != null) {
                rawTranslated.put(fieldName, cached);
                cacheHitFields.add(fieldName);
                log.debug("Cache hit for field '{}' key={}", fieldName, cacheKey);
                continue;
            }

            // 2. Model call with per-field failure isolation
            String modelOutput;
            try {
                modelOutput = modelServiceClient.translate(originalText, src, tgt);
            } catch (ModelServiceException e) {
                log.error("Model call failed for field '{}': {}", fieldName, e.getMessage());
                rawTranslated.put(fieldName, originalText);   // revert to English
                modelFailedFields.add(fieldName);
                continue;
            }

            // 3. Glossary corrections (after model, before protection check)
            String glossaryCorrected = glossaryService.applyGlossary(modelOutput);
            rawTranslated.put(fieldName, glossaryCorrected);
        }

        // Phase 4: Protection check on all non-model-failed fields
        // We run verifyTranslation on whatever rawTranslated has (model failures already
        // carry the original English text, but we exclude them from the protection check
        // to avoid false "reverted by protection" classification).
        Map<String, String> forProtection = new LinkedHashMap<>(rawTranslated);
        // Remove model-failed fields so the checker doesn't double-count them
        modelFailedFields.forEach(forProtection::remove);

        ProtectionResult protectionResult = protectedFieldChecker.verifyTranslation(
                // original fields, but only those not already failed at model level
                filteredMap(originalFields, modelFailedFields),
                forProtection,
                protectedFields
        );

        // Phase 5: Assemble final translations
        Map<String, String> finalTranslations = new LinkedHashMap<>();
        List<String> notTranslated = new ArrayList<>();

        // Model-failed fields: always reverted, never cached
        for (String fieldName : modelFailedFields) {
            finalTranslations.put(fieldName, originalFields.get(fieldName));
            notTranslated.add(fieldName);
        }

        // Protection-checked fields
        for (Map.Entry<String, String> entry : protectionResult.resolvedFields().entrySet()) {
            String fieldName = entry.getKey();
            String finalText = entry.getValue();
            finalTranslations.put(fieldName, finalText);

            boolean reverted = protectionResult.notTranslated().contains(fieldName);
            if (reverted) {
                notTranslated.add(fieldName);
                // Do NOT cache reverted fields
            } else {
                // Only cache results that passed protection and were not model failures
                boolean wasCacheHit = cacheHitFields.contains(fieldName);
                if (!wasCacheHit) {
                    String cacheKey = CacheKeyBuilder.buildKey(
                            originalFields.get(fieldName), fieldName, src, tgt, modelVersion);
                    cache.put(cacheKey, finalText);
                    log.debug("Cached translation for field '{}' key={}", fieldName, cacheKey);
                }
            }
        }

        // Preserve original field order from request
        Map<String, String> orderedTranslations = new LinkedHashMap<>();
        for (String fieldName : originalFields.keySet()) {
            orderedTranslations.put(fieldName, finalTranslations.get(fieldName));
        }

        // Status determination
        String status = computeStatus(notTranslated.size(), originalFields.size());

        if (!notTranslated.isEmpty()) {
            log.warn("Translation result: status='{}', notTranslated={}", status, notTranslated);
        }

        return new TranslateResponse(orderedTranslations, protectedFields, status, notTranslated);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String computeStatus(int revertedCount, int totalCount) {
        if (revertedCount == 0) return "translated";
        if (revertedCount == totalCount) return "failed";
        return "partial";
    }

    private Map<String, String> filteredMap(Map<String, String> source, Set<String> excludeKeys) {
        if (excludeKeys.isEmpty()) return source;
        Map<String, String> result = new LinkedHashMap<>(source);
        excludeKeys.forEach(result::remove);
        return result;
    }
}
