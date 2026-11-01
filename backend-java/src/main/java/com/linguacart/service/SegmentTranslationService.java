package com.linguacart.service;

import com.linguacart.cache.CacheKeyBuilder;
import com.linguacart.cache.LruTranslationCache;
import com.linguacart.client.ModelServiceClient;
import com.linguacart.client.ModelServiceException;
import com.linguacart.dto.SegmentDtos.Request;
import com.linguacart.dto.SegmentDtos.Response;
import com.linguacart.dto.SegmentDtos.Result;
import com.linguacart.dto.SegmentDtos.Segment;
import com.linguacart.glossary.GlossaryService;
import com.linguacart.protection.NegationDetector;
import com.linguacart.protection.ProtectedFieldChecker;
import com.linguacart.protection.ProtectionResult;
import com.linguacart.protection.SegmentGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Segment-based translation for the browser extension (docs/07-API-CONTRACT.md v0.3).
 *
 * <p>Per segment: negation check, cache lookup, model call, glossary corrections, protection checks
 * ({@link ProtectedFieldChecker} and {@link SegmentGuard}), then cache store. A segment that fails any
 * step stays in English with a reason; one failure never fails the other segments.
 *
 * <p>Nothing is cached unless it passed every check. {@code translationType} is "machine" for model output
 * and "mock" in mock mode; "human" is never returned because no glossary entry has been reviewed yet.
 */
@Service
public class SegmentTranslationService {

    private static final Logger log = LoggerFactory.getLogger(SegmentTranslationService.class);

    private final ModelServiceClient modelServiceClient;
    private final GlossaryService glossaryService;
    private final ProtectedFieldChecker protectedFieldChecker;
    private final LruTranslationCache cache;
    private final String modelVersion;
    private final boolean mockMode;
    private final boolean rejectNegation;

    public SegmentTranslationService(
            ModelServiceClient modelServiceClient,
            GlossaryService glossaryService,
            ProtectedFieldChecker protectedFieldChecker,
            LruTranslationCache cache,
            @Value("${linguacart.cache.model-version:nllb-200-distilled-600M-v1}") String modelVersion,
            @Value("${linguacart.model-service.mode:mock}") String mode,
            @Value("${linguacart.translate.reject-negation:true}") boolean rejectNegation) {
        this.modelServiceClient = modelServiceClient;
        this.glossaryService = glossaryService;
        this.protectedFieldChecker = protectedFieldChecker;
        this.cache = cache;
        this.modelVersion = modelVersion;
        this.mockMode = "mock".equalsIgnoreCase(mode);
        this.rejectNegation = rejectNegation;
    }

    public Response translate(Request request) {
        String src = request.sourceLanguage();
        String tgt = request.targetLanguage();
        Map<String, String> protectedIn = request.protectedFields();

        List<Result> results = new ArrayList<>();
        List<String> notTranslated = new ArrayList<>();
        for (Segment segment : request.segments()) {
            Result result = translateOne(segment, src, tgt, protectedIn);
            results.add(result);
            if (!"translated".equals(result.status())) {
                notTranslated.add(result.id());
            }
        }

        String status;
        if (notTranslated.isEmpty()) {
            status = "translated";
        } else if (notTranslated.size() == results.size()) {
            status = "failed";
        } else {
            status = "partial";
        }
        if (!notTranslated.isEmpty()) {
            log.warn("Translate result: status='{}', {} of {} segments kept in English",
                    status, notTranslated.size(), results.size());
        }

        // Protected values are echoed exactly as received (sanitised copy), never taken from the model.
        return new Response(status, notTranslated, results,
                protectedFieldChecker.sanitizeProtectedFields(protectedIn));
    }

    private Result translateOne(Segment segment, String src, String tgt, Map<String, String> protectedFields) {
        String id = segment.id();
        String text = segment.text();

        if (rejectNegation && NegationDetector.containsNegation(text)) {
            log.info("Segment '{}' kept in English: negation or unavailability wording", id);
            return Result.notTranslated(id, "negation_mismatch");
        }

        String kind = segment.kind() == null ? "other" : segment.kind();
        String cacheKey = CacheKeyBuilder.buildKey(text, kind, src, tgt, modelVersion);
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return Result.translated(id, "cache", translationType(), cached);
        }

        String modelOutput;
        try {
            modelOutput = modelServiceClient.translate(text, src, tgt);
        } catch (ModelServiceException e) {
            log.warn("Model call failed for segment '{}' ({})", id, e.getClass().getSimpleName());
            return Result.notTranslated(id, "model_error");
        }
        if (modelOutput == null || modelOutput.isBlank()) {
            log.warn("Model returned blank output for segment '{}'", id);
            return Result.notTranslated(id, "model_error");
        }

        // Glossary corrections run before the protection checks so the checks see the final text.
        String corrected = glossaryService.applyGlossary(modelOutput);

        ProtectionResult checked = protectedFieldChecker.verifyTranslation(
                Map.of(id, text), Map.of(id, corrected), protectedFields);
        List<String> guardFailures = SegmentGuard.check(text, corrected);
        if (checked.hasReversions() || !guardFailures.isEmpty()) {
            log.warn("Segment '{}' kept in English: protected value check failed {}", id, guardFailures);
            return Result.notTranslated(id, "protected_value_changed");
        }

        cache.put(cacheKey, corrected);
        return Result.translated(id, mockMode ? "mock" : "nllb", translationType(), corrected);
    }

    private String translationType() {
        return mockMode ? "mock" : "machine";
    }
}
