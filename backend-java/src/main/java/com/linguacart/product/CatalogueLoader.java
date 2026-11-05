package com.linguacart.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the research catalogue (JSONL, one product per line). Malformed lines, rows without an id or
 * title, and duplicate ids are skipped and logged by line number only. A missing file gives an empty list.
 */
@Component
public class CatalogueLoader {

    private static final Logger log = LoggerFactory.getLogger(CatalogueLoader.class);
    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

    private final String path;
    private final ObjectMapper mapper = new ObjectMapper();

    public CatalogueLoader(
            @Value("${linguacart.catalogue.path:../research/data/catalogue/dev_catalogue.jsonl}") String path) {
        this.path = path;
    }

    public List<ProductRecord> load() {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            log.warn("Catalogue file not found: {}", file.toAbsolutePath());
            return List.of();
        }
        try {
            List<ProductRecord> products = parseLines(Files.readAllLines(file, StandardCharsets.UTF_8));
            log.info("Loaded {} products from {}", products.size(), file);
            return products;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read catalogue file " + file, e);
        }
    }

    public List<ProductRecord> parseLines(List<String> lines) {
        List<ProductRecord> products = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            int lineNo = i + 1;
            Map<String, Object> raw;
            try {
                raw = mapper.readValue(line, ROW);
            } catch (JsonProcessingException e) {
                log.warn("Catalogue line {} skipped: not valid JSON", lineNo);
                continue;
            }
            Optional<ProductRecord> product = ProductNormalizer.normalize(raw);
            if (product.isEmpty()) {
                log.warn("Catalogue line {} skipped: missing id or title", lineNo);
                continue;
            }
            if (!seen.add(product.get().id())) {
                log.warn("Catalogue line {} skipped: duplicate id", lineNo);
                continue;
            }
            products.add(product.get());
        }
        return products;
    }
}
