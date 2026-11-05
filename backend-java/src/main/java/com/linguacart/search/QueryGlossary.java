package com.linguacart.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shona to English term lookup used only to expand SEARCH queries (docs/08-INTEGRATION-GUIDE.md, section 4).
 * Reads the research glossary in JSONL form ({"english": ..., "shona": ..., "status": ...} per line).
 * It is separate from {@code GlossaryService}, which corrects displayed translations.
 *
 * <p>Entries marked draft are used here because a wrong expansion only affects ranking, never what the
 * shopper reads. Phrases are matched first; a single Shona word matches exactly, or as a substring when
 * the glossary word has at least 4 letters (Shona attaches prefixes: "dzechikoro" contains "chikoro").
 */
@Component
public class QueryGlossary {

    private static final Logger log = LoggerFactory.getLogger(QueryGlossary.class);

    public record Entry(String shona, String english) {}

    private final List<Entry> entries;

    @org.springframework.beans.factory.annotation.Autowired
    public QueryGlossary(
            @Value("${linguacart.search.glossary-path:../research/data/glossary/dev_glossary_starter.jsonl}")
            String path) {
        this(loadFile(path));
    }

    public QueryGlossary(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        // Same order as the reference script: alphabetical first, then longest Shona term first (stable).
        sorted.sort(Comparator.comparing(Entry::shona).thenComparing(Entry::english));
        sorted.sort(Comparator.comparingInt((Entry e) -> e.shona().length()).reversed());
        this.entries = List.copyOf(sorted);
    }

    public int size() {
        return entries.size();
    }

    /** English terms for the Shona terms found in an already normalised query (see Tokenizer.normalizeQuery). */
    public List<Entry> expand(String normalisedQuery) {
        List<Entry> found = new ArrayList<>();
        if (normalisedQuery == null || normalisedQuery.isBlank()) {
            return found;
        }
        String rest = " " + normalisedQuery + " ";
        for (Entry e : entries) {
            if (e.shona().contains(" ") && rest.contains(" " + e.shona() + " ")) {
                found.add(e);
                rest = rest.replace(" " + e.shona() + " ", " ");
            }
        }
        String trimmed = rest.trim();
        if (trimmed.isEmpty()) {
            return found;
        }
        for (String token : trimmed.split("\\s+")) {
            for (Entry e : entries) {
                String sn = e.shona();
                if (!sn.contains(" ") && (token.equals(sn) || (sn.length() >= 4 && token.contains(sn)))) {
                    found.add(e);
                    break;
                }
            }
        }
        return found;
    }

    /** Parses JSONL text. Bad lines are skipped and counted in the log, never printed. */
    public static List<Entry> parseJsonl(List<String> lines) {
        ObjectMapper mapper = new ObjectMapper();
        List<Entry> out = new ArrayList<>();
        int skipped = 0;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                String sn = node.path("shona").asText("");
                String en = node.path("english").asText("");
                sn = Tokenizer.normalizeQuery(sn);
                en = en.trim().replaceAll("\\s+", " ");
                if (sn.isEmpty() || en.isEmpty()) {
                    skipped++;
                } else {
                    out.add(new Entry(sn, en));
                }
            } catch (IOException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("Query glossary: {} line(s) skipped", skipped);
        }
        return out;
    }

    private static List<Entry> loadFile(String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            log.warn("Query glossary not found at {}: Shona queries will rely on translation only",
                    file.toAbsolutePath());
            return List.of();
        }
        try {
            List<Entry> loaded = parseJsonl(Files.readAllLines(file, StandardCharsets.UTF_8));
            log.info("Loaded {} query glossary entries from {}", loaded.size(), file);
            return loaded;
        } catch (IOException e) {
            log.warn("Could not read query glossary: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }
}
