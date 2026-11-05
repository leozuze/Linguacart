package com.linguacart.product;

import java.util.List;
import java.util.Map;

/**
 * One catalogue product (docs/02-BACKEND-JAVA.md). The embedding is null until the catalogue index
 * has embedded the passage text. Price is kept exactly as written in the source ({@code priceText}).
 */
public record ProductRecord(
        String id,
        String platform,
        String sourceUrl,
        String titleEn,
        String descriptionEn,
        String priceText,
        String currency,
        String quantity,
        String unit,
        String brand,
        String model,
        Map<String, String> attributes,
        List<String> warnings,
        float[] embedding
) {
    public ProductRecord {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public ProductRecord withEmbedding(float[] vector) {
        return new ProductRecord(id, platform, sourceUrl, titleEn, descriptionEn, priceText, currency,
                quantity, unit, brand, model, attributes, warnings, vector);
    }

    /**
     * Text that is embedded for this product, in the format the model developer evaluated:
     * {@code {title}. {description}. Brand: {brand}. Category: {category}. Size: {quantity} {unit}.}
     */
    public String passageText() {
        return nz(titleEn) + ". " + nz(descriptionEn) + ". Brand: " + nz(brand)
                + ". Category: " + nz(attributes.get("category"))
                + ". Size: " + nz(quantity) + " " + nz(unit) + ".";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
