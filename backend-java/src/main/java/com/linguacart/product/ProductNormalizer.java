package com.linguacart.product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns one raw catalogue row (a JSON object as a map) into a {@link ProductRecord}.
 *
 * <p>Text is trimmed and whitespace is collapsed. The price text is never reformatted or converted.
 * Missing optional fields stay null. A row without an id or a title is rejected (empty result).
 */
public final class ProductNormalizer {

    private ProductNormalizer() {}

    public static Optional<ProductRecord> normalize(Map<String, Object> raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String id = text(raw, "id");
        String title = text(raw, "title", "titleEn");
        if (id == null || title == null) {
            return Optional.empty();
        }

        String description = text(raw, "description", "descriptionEn");
        String currency = text(raw, "currency");
        String unit = text(raw, "unit");

        Map<String, String> attributes = new LinkedHashMap<>();
        String category = text(raw, "category");
        if (category != null) {
            attributes.put("category", category);
        }
        if (raw.get("attributes") instanceof Map<?, ?> extra) {
            for (Map.Entry<?, ?> e : extra.entrySet()) {
                String key = clean(e.getKey());
                String value = clean(e.getValue());
                if (key != null && value != null) {
                    attributes.putIfAbsent(key, value);
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        if (raw.get("warnings") instanceof List<?> list) {
            for (Object w : list) {
                String s = clean(w);
                if (s != null) {
                    warnings.add(s);
                }
            }
        }

        return Optional.of(new ProductRecord(
                id,
                text(raw, "platform", "source"),
                text(raw, "sourceUrl", "url"),
                title,
                description == null ? "" : description,
                text(raw, "price", "priceText"),
                currency == null ? null : currency.toUpperCase(Locale.ROOT),
                text(raw, "quantity"),
                normalizeUnit(unit),
                text(raw, "brand"),
                text(raw, "model"),
                attributes,
                warnings,
                null));
    }

    static String normalizeUnit(String unit) {
        if (unit == null) {
            return null;
        }
        String u = unit.toLowerCase(Locale.ROOT);
        return switch (u) {
            case "litre", "litres", "liter", "liters" -> "l";
            default -> u;
        };
    }

    private static String text(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            String value = clean(raw.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Trim and collapse whitespace; whole numbers lose the ".0"; blank becomes null. */
    private static String clean(Object value) {
        if (value == null) {
            return null;
        }
        String s;
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) < 1e15) {
            s = String.valueOf(n.longValue());
        } else {
            s = value.toString();
        }
        s = s.trim().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }
}
