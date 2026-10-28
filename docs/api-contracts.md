# LinguaCart API Contracts (v1)

Base URL (dev): `http://localhost:8080/api/v1`
All requests and responses are JSON (UTF-8).

## Extension -> Java backend

### GET /health
```json
{ "status": "ok", "modelService": "ok" }
```
`modelService` is `ok`, `down` or `unknown`.

### POST /translate
Request:
```json
{
  "sourceLanguage": "eng_Latn",
  "targetLanguage": "sna_Latn",
  "fields": { "title": "...", "description": "..." },
  "protectedFields": {
    "price": "$2.99", "quantity": "1", "unit": "kg",
    "brand": "BrandX", "model": "BX-100"
  }
}
```
Response:
```json
{
  "translations": { "title": "...", "description": "..." },
  "protectedFields": { "price": "$2.99", "quantity": "1", "unit": "kg", "brand": "BrandX", "model": "BX-100" },
  "status": "translated",
  "notTranslated": []
}
```
- `status`: `translated` | `partial` | `failed`
- `notTranslated`: field names reverted to original English (failed protection check or model error). **Extension: show the English text for these and label them.**
- `protectedFields` are always returned exactly as sent. Never taken from the model.
- `fields` must be non-empty (otherwise 400).

### POST /search
Request:
```json
{ "query": "munyu", "language": "sna_Latn", "topK": 10 }
```
`language` defaults to `sna_Latn`, `topK` defaults to 10 (range 1-50), `query` must be non-blank.

Response:
```json
{
  "query": "munyu",
  "results": [
    {
      "productId": "product_12",
      "titleEn": "Iodized table salt, 1 kg",
      "titleSn": "Munyu ...",
      "price": "$2.99",
      "score": 0.87,
      "sourceUrl": "https://example.com/product/12"
    }
  ]
}
```

### POST /feedback  (proposed, needs sign-off)
Request:
```json
{ "type": "bad_translation", "productId": "product_12", "field": "title", "comment": "optional" }
```
Response: `{ "status": "received" }`

### Errors
Non-2xx responses return:
```json
{ "error": "short_code", "message": "human readable" }
```
Standard error codes:
- `400 Bad Request`: `"validation_error"` — request validation failed or request body is malformed.
- `502 / 503 Service Unavailable`: `"model_service_unavailable"` — the Python model service is unreachable, timed out, or failed after retries.
- `500 Internal Server Error`: `"internal_error"` — an unexpected server error occurred.

## Java backend -> Python model service

Base URL (dev): `http://localhost:8001`

### POST /translate
Request: `{ "text": "...", "sourceLanguage": "eng_Latn", "targetLanguage": "sna_Latn" }`
Response: `{ "translation": "..." }`

### POST /embed
Request: `{ "texts": ["...", "..."], "prefix": "query" }`  (`prefix`: `query` or `passage`, for the E5 "query: " / "passage: " convention)
Response: `{ "embeddings": [[0.01, ...], [...]], "dimension": 768 }`
Vectors must be L2-normalised.

### GET /health
Response: `{ "status": "ok" }`

## Open questions for teammates
1. **Model/eval dev:** confirm the Python request/response shapes above, the vector dimension (768?), and the embeddings JSONL format (`{"id": "...", "embedding": [...]}` per line?).
2. **Model/eval dev:** where will the catalogue JSONL and glossary file live and what are their field names?
3. **Extension dev:** please send one real captured Amazon product JSON so I can test normalisation.
4. **Both:** OK with `notTranslated` and `/feedback` additions?