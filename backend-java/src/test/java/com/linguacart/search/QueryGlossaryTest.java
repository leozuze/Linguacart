package com.linguacart.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryGlossaryTest {

    private static QueryGlossary sample() {
        return new QueryGlossary(List.of(
                new QueryGlossary.Entry("munyu", "salt"),
                new QueryGlossary.Entry("hupfu", "flour"),
                new QueryGlossary.Entry("hupfu hwechibage", "maize meal"),
                new QueryGlossary.Entry("chikoro", "school"),
                new QueryGlossary.Entry("tea", "tea")));
    }

    @Test
    void matchesExactWords() {
        List<QueryGlossary.Entry> found = sample().expand("munyu");
        assertEquals(1, found.size());
        assertEquals("salt", found.get(0).english());
    }

    @Test
    void phraseWinsOverItsSingleWords() {
        List<QueryGlossary.Entry> found = sample().expand("hupfu hwechibage");
        assertEquals(1, found.size());
        assertEquals("maize meal", found.get(0).english());
    }

    @Test
    void longTermsMatchInsideWordsWithPrefixes() {
        List<QueryGlossary.Entry> found = sample().expand("dzechikoro");
        assertEquals(1, found.size());
        assertEquals("school", found.get(0).english());
    }

    @Test
    void shortTermsMatchExactlyOnly() {
        assertTrue(sample().expand("teapot").isEmpty());
        assertEquals(1, sample().expand("tea").size());
    }

    @Test
    void blankAndUnknownQueriesFindNothing() {
        assertTrue(sample().expand("").isEmpty());
        assertTrue(sample().expand(null).isEmpty());
        assertTrue(sample().expand("zzz").isEmpty());
    }

    @Test
    void parsesJsonlAndSkipsBadLines() {
        List<QueryGlossary.Entry> entries = QueryGlossary.parseJsonl(List.of(
                "{\"english\":\"salt\",\"shona\":\"Munyu\",\"status\":\"draft\"}",
                "not json",
                "{\"english\":\"\",\"shona\":\"x\"}",
                ""));
        assertEquals(1, entries.size());
        assertEquals("munyu", entries.get(0).shona());
    }

    @Test
    void missingFileGivesEmptyGlossary() {
        assertEquals(0, new QueryGlossary("does/not/exist.jsonl").size());
    }
}
