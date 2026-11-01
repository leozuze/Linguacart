package com.linguacart.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request and response records for POST /api/v1/translate, as defined in docs/07-API-CONTRACT.md (v0.3).
 */
public final class SegmentDtos {

    private SegmentDtos() {}

    public record Segment(
            @NotBlank String id,
            @NotBlank @Size(max = 2000) String text,
            @Pattern(regexp = "ui_label|ui_template|title|description|attribute|review|other",
                    message = "unsupported kind") String kind
    ) {}

    public record Request(
            @NotBlank @Pattern(regexp = "eng_Latn", message = "sourceLanguage must be eng_Latn") String sourceLanguage,
            @NotBlank @Pattern(regexp = "sna_Latn", message = "targetLanguage must be sna_Latn") String targetLanguage,
            String site,
            String pageType,
            @NotEmpty @Size(max = 50) @Valid List<Segment> segments,
            Map<String, String> protectedFields
    ) {
        public Request {
            if (protectedFields == null) {
                protectedFields = Map.of();
            }
        }

        /** Every segment must be present and ids must be unique within the request. */
        @JsonIgnore
        @AssertTrue(message = "segments must not contain null entries and ids must be unique")
        public boolean isSegmentsWellFormed() {
            if (segments == null) {
                return true; // reported by @NotEmpty
            }
            Set<String> ids = new HashSet<>();
            for (Segment s : segments) {
                if (s == null) {
                    return false;
                }
                if (s.id() != null && !ids.add(s.id())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One result per segment. Null fields are omitted from the JSON, as in the contract examples. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(
            String id,
            String status,
            String source,
            String translationType,
            String text,
            List<String> reasons,
            Boolean lowConfidence
    ) {
        public static Result translated(String id, String source, String translationType, String text) {
            return new Result(id, "translated", source, translationType, text, null, null);
        }

        public static Result notTranslated(String id, String reason) {
            return new Result(id, "not_translated", null, null, null, List.of(reason), null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            String status,
            List<String> notTranslated,
            List<Result> results,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> protectedFields
    ) {}
}
