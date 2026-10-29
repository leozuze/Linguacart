package com.linguacart.glossary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glossary service providing HashMap lookup and substitution of reviewed English-to-Shona terms.
 * <p>
 * Keys are normalized (trimmed, lowercased, whitespace collapsed). Longer multi-word phrases
 * are matched before shorter single-word terms to avoid partial-word shadowing.
 */
@Service
public class GlossaryService {

    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    private final String configuredGlossaryPath;
    private final ObjectMapper objectMapper;

    // English (normalized) -> Shona (reviewed)
    private final Map<String, String> englishToShona = new ConcurrentHashMap<>();

    // Shona (normalized) -> English (normalized) for reverse search query expansion
    private final Map<String, String> shonaToEnglish = new ConcurrentHashMap<>();

    public GlossaryService() {
        this("../research/data/glossary/glossary.json", new ObjectMapper());
    }

    public GlossaryService(@Value("${linguacart.glossary.path:../research/data/glossary/glossary.json}") String glossaryPath) {
        this(glossaryPath, new ObjectMapper());
    }

    public GlossaryService(String glossaryPath, ObjectMapper objectMapper) {
        this.configuredGlossaryPath = glossaryPath;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @PostConstruct
    public void initialize() {
        boolean loaded = false;

        // 1. Try configured external path (e.g. research/data/glossary/glossary.json)
        if (configuredGlossaryPath != null && !configuredGlossaryPath.isBlank()) {
            Path path = Path.of(configuredGlossaryPath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    loadFromStream(is);
                    log.info("Loaded {} glossary entries from external file '{}'", englishToShona.size(), path);
                    loaded = true;
                } catch (Exception e) {
                    log.warn("Failed to load glossary from '{}': {}", path, e.getMessage());
                }
            }
        }

        // 2. Fall back to bundled classpath sample dataset if not loaded
        if (!loaded) {
            try {
                ClassPathResource resource = new ClassPathResource("data/glossary.json");
                if (resource.exists()) {
                    try (InputStream is = resource.getInputStream()) {
                        loadFromStream(is);
                        log.info("Loaded {} glossary entries from classpath resource 'data/glossary.json'", englishToShona.size());
                        loaded = true;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load fallback glossary from classpath: {}", e.getMessage());
            }
        }

        if (!loaded) {
            log.info("No glossary file found. Initialized empty GlossaryService.");
        }
    }

    /**
     * Normalizes a term key by trimming, lowercasing, and collapsing whitespace.
     */
    public static String normalizeKey(String term) {
        if (term == null) {
            return "";
        }
        return term.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Inserts or updates an entry in the glossary.
     */
    public void put(String english, String shona) {
        if (english == null || shona == null) {
            return;
        }
        String normEn = normalizeKey(english);
        String normSn = normalizeKey(shona);
        if (!normEn.isBlank() && !shona.isBlank()) {
            englishToShona.put(normEn, shona.trim());
            shonaToEnglish.put(normSn, english.trim());
        }
    }

    /**
     * Look up reviewed Shona term for an English term.
     */
    public Optional<String> getShona(String english) {
        if (english == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(englishToShona.get(normalizeKey(english)));
    }

    /**
     * Reverse lookup: find English term for a Shona term.
     */
    public Optional<String> getEnglish(String shona) {
        if (shona == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(shonaToEnglish.get(normalizeKey(shona)));
    }

    public boolean containsEnglish(String english) {
        if (english == null) return false;
        return englishToShona.containsKey(normalizeKey(english));
    }

    public boolean containsShona(String shona) {
        if (shona == null) return false;
        return shonaToEnglish.containsKey(normalizeKey(shona));
    }

    public int size() {
        return englishToShona.size();
    }

    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(englishToShona);
    }

    public void clear() {
        englishToShona.clear();
        shonaToEnglish.clear();
    }

    /**
     * Applies glossary terms to a given English text, substituting known English phrases
     * with their reviewed Shona translations. Longer multi-word phrases match first.
     */
    public String applyGlossary(String text) {
        if (text == null || text.isBlank() || englishToShona.isEmpty()) {
            return text;
        }

        // Sort terms by descending length so multi-word terms (e.g. "table salt") match before ("salt")
        List<String> sortedKeys = new ArrayList<>(englishToShona.keySet());
        sortedKeys.sort((a, b) -> Integer.compare(b.length(), a.length()));

        String result = text;
        for (String key : sortedKeys) {
            String shonaValue = englishToShona.get(key);
            if (shonaValue == null) continue;

            // Whole word, case-insensitive match on the English key
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(key) + "\\b", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll(Matcher.quoteReplacement(shonaValue));
            }
        }

        return result;
    }

    /**
     * Loads glossary entries from an InputStream containing JSON (array of objects, map, or JSONL).
     */
    public void loadFromStream(InputStream is) throws Exception {
        JsonNode root = objectMapper.readTree(is);
        if (root.isArray()) {
            for (JsonNode node : root) {
                if (node.has("english") && node.has("shona")) {
                    String en = node.get("english").asText();
                    String sn = node.get("shona").asText();
                    put(en, sn);
                }
            }
        } else if (root.isObject()) {
            Map<String, String> map = objectMapper.convertValue(root, new TypeReference<Map<String, String>>() {});
            map.forEach(this::put);
        }
    }

    /**
     * Loads glossary entries from a JSON string.
     */
    public void loadFromJson(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (root.isArray()) {
            for (JsonNode node : root) {
                if (node.has("english") && node.has("shona")) {
                    put(node.get("english").asText(), node.get("shona").asText());
                }
            }
        } else if (root.isObject()) {
            Map<String, String> map = objectMapper.convertValue(root, new TypeReference<Map<String, String>>() {});
            map.forEach(this::put);
        }
    }
}
