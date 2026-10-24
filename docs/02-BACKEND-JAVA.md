# LinguaCart — Java Backend Specification

## Technology

- Java 21
- Spring Boot 3
- Maven
- REST APIs using JSON
- JUnit 5

## Package structure

```text
backend-java/src/main/java/com/linguacart/
├── LinguacartApplication.java
├── api/
│   ├── TranslationController.java
│   ├── SearchController.java
│   ├── FeedbackController.java
│   └── HealthController.java
├── dto/
│   ├── TranslateRequest.java
│   ├── TranslateResponse.java
│   ├── SearchRequest.java
│   ├── SearchResponse.java
│   └── ProductResult.java
├── product/
│   ├── ProductRecord.java
│   └── ProductNormalizer.java
├── glossary/
│   └── GlossaryService.java
├── cache/
│   └── LruTranslationCache.java
├── search/
│   ├── InvertedIndex.java
│   ├── VectorSearchService.java
│   ├── HybridRanker.java
│   └── TopKHeap.java
├── protection/
│   └── ProtectedFieldChecker.java
├── client/
│   ├── ModelServiceClient.java
│   └── TranslationClient.java
└── config/
    └── ModelServiceConfig.java
```

## Domain model

```java
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
) {}
```

## API endpoints

### `GET /api/v1/health`

Response:

```json
{
  "status": "ok",
  "modelService": "ok"
}
```

### `POST /api/v1/translate`

Request:

```json
{
  "sourceLanguage": "eng_Latn",
  "targetLanguage": "sna_Latn",
  "fields": {
    "title": "Iodized table salt, 1 kg",
    "description": "Iodized table salt for cooking."
  },
  "protectedFields": {
    "price": "$2.99",
    "quantity": "1",
    "unit": "kg",
    "brand": "BrandX",
    "model": "BX-100"
  }
}
```

Response:

```json
{
  "translations": {
    "title": "Munyu weTafura unemhodzi, 1 kg",
    "description": "Shona description"
  },
  "protectedFields": {
    "price": "$2.99",
    "quantity": "1",
    "unit": "kg",
    "brand": "BrandX",
    "model": "BX-100"
  },
  "status": "translated"
}
```

### `POST /api/v1/search`

Request:

```json
{
  "query": "munyu",
  "language": "sna_Latn",
  "topK": 10
}
```

Response:

```json
{
  "query": "munyu",
  "results": [
    {
      "productId": "product_12",
      "titleEn": "Iodized table salt, 1 kg",
      "titleSn": "Munyu weTafura unemhodzi, 1 kg",
      "price": "$2.99",
      "score": 0.87,
      "sourceUrl": "[https://example.com/product/12](https://example.com/product/12)"
    }
  ]
}
```

## Data structures to implement

### Glossary hash map

```text
Key: English term
Value: reviewed Shona term
```

Example:

```text
"salt" -> "munyu"
"table salt" -> reviewed Shona phrase
"iodized" -> reviewed Shona term
```

### Inverted index

```text
Token -> list of product IDs
```

Example:

```text
"iodized" -> [product_12, product_45]
"salt" -> [product_12, product_77]
"1kg" -> [product_12]
```

### LRU translation cache

Implement using:

- `HashMap` for O(1) lookup
- Doubly linked list for recency order
- Fixed maximum size

### Top-K min-heap

Use a min-heap of size K.

```text
If new score > heap minimum:
    remove minimum
    insert new score
```

### Vector search

For the first version, compare the query vector with every product vector.

Use cosine similarity or dot product on normalized vectors.

## Protected fields

These fields must never be silently translated or altered:

```text
price
currency
quantity
unit
brand
model
allergen labels
warnings
```

If a translated field fails validation:

1. Revert that field to English.
2. Mark it as `not_translated`.
3. Log the failure.
4. Return the original value to the extension.

## Java implementation rules

- Java owns all ranking and protection decisions.
- Java must never trust model output for protected fields.
- Java must retry or fail gracefully if the Python service is unavailable.
- Use timeouts for model-service calls.
- Keep external URLs and API keys in environment variables or configuration.
