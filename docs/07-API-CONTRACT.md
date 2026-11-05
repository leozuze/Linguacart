# LinguaCart: API Contract (extension <-> Java backend)

**Status:** draft v0.3, merges the backend developer's draft with the extension's segment-based design.
**Location:** `docs/07-API-CONTRACT.md` (replaces v0.1 and v0.2, and the earlier "LinguaCart API Contracts (v1)" draft).
**Owners:** extension developer and Java backend developer. Any change is made here first.

Base URL (dev): `http://localhost:8080/api/v1`. All requests and responses are JSON (UTF-8).

## What changed from the two earlier drafts

| Topic            | Decision                                                                                                                                             | Why                                                                                                                                                           |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Request unit     | `segments[]` of page text, each with a `kind`                                                                                                        | One product record covers 2 of roughly 380 strings on a page, and nothing on search pages, UI labels or reviews. A product card can still be sent as segments |
| Protected values | Masked inside the text by Java; `protectedFields` is an optional cross-check                                                                         | Prices sit inside sentences ("INR 1,111.97 delivery Fri, Nov 13")                                                                                             |
| Response         | Per-segment results, plus the backend draft's top-level `status` and `notTranslated` summary                                                         | Batches need per-segment detail. The marker needs `translationType`. Error analysis needs `reasons`                                                           |
| Versioning       | In the URL (`/api/v1`) only. No `apiVersion` field in bodies                                                                                         | Backend draft                                                                                                                                                 |
| Languages        | NLLB codes: `sourceLanguage: "eng_Latn"`, `targetLanguage: "sna_Latn"`                                                                               | Backend draft, one convention end to end                                                                                                                      |
| Errors           | HTTP status codes with `{ "error": "code", "message": "..." }`                                                                                       | Backend draft                                                                                                                                                 |
| `/health`        | Includes `modelService`                                                                                                                              | Backend draft                                                                                                                                                 |
| `/feedback`      | Text-based (`sourceText`, `shownText`, `source`, `translationType`, `issue`), plus the backend draft's `type`, with `productId` and `field` optional | Most reports are about nav labels or sentences with no product id, and error analysis needs the text and where it came from                                   |

## Principles

- The extension sends **only text segments**. No cookies, credentials, form contents, account data or page URLs.
- The Java backend is the authority for **masking, glossary lookup, caching, translation calls and protected-field verification**. The extension only decides what text to send. (The extension has prototype masking, used only to classify text for glossary building.)
- The Python model service is never called by the extension.
- If anything fails, the extension shows the **original English text** and a visible notice. It never shows a guess.
- Machine-translated text is always marked, and the original is always one click away.

## What the extension never sends

The extension is active only on search results (`/s`) and product pages (`/dp/`, `/gp/product/`). It never sends text from:

- cart, checkout, sign-in, account, address and payment pages (the script is not injected there)
- the cart flyout on the right edge of allowed pages
- account name, delivery address and location blocks
- footer language, currency and country pickers
- form fields, dropdown options, prices inside price elements, hidden or off-screen text
- sentences that contain **several different links or buttons**. These stay in English until the backend can return inline markers

## `GET /health`

```json
{ "status": "ok", "modelService": "ok" }
```

`modelService`: `ok`, `down` or `unknown`. The extension calls it once at startup and shows a notice when the service is down.

## `POST /translate`

Translates page text from English to Shona.

### Request

```json
{
  "sourceLanguage": "eng_Latn",
  "targetLanguage": "sna_Latn",
  "site": "amazon.com",
  "pageType": "listing",
  "segments": [
    { "id": "s0", "text": "Add to cart", "kind": "ui_label" },
    { "id": "s1", "text": "4.3 out of 5 stars", "kind": "ui_template" },
    {
      "id": "s2",
      "text": "INR 1,092.75 delivery Fri, Nov 13",
      "kind": "ui_template"
    },
    {
      "id": "s3",
      "text": "Sony WH-CH720N Wireless Noise Cancelling Over-Ear Headphones, Black",
      "kind": "title"
    },
    { "id": "s4", "text": "Not waterproof. Runs small.", "kind": "review" }
  ]
}
```

| Field             | Values / notes                                                                                                                                                   |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `site`            | Host name without `www.`                                                                                                                                         |
| `pageType`        | `product`, `listing`, `cart`, `other`                                                                                                                            |
| `kind`            | `ui_label`, `ui_template`, `title`, `description`, `attribute`, `review`, `other` (a hint, the backend does the real masking)                                    |
| `segments`        | 1 to 50 per request, each at most 2,000 characters                                                                                                               |
| `id`              | Chosen by the extension, unique within the request                                                                                                               |
| `protectedFields` | Optional object (price, quantity, unit, brand, model). If present, Java checks the masked values against it and returns it unchanged. Never taken from the model |

The extension sends at most 200 segments per page scan, in batches of at most 50 (the batch size may be lowered, see Performance).

### Validation (400 `validation_error`)

Reject when: `sourceLanguage` or `targetLanguage` is not the expected code, `segments` is missing or empty, there are more than 50 segments, a segment has an empty or missing `id` or `text`, two segments share an `id`, a `text` exceeds 2,000 characters, or a `kind` is present but not listed.

### Response (200)

```json
{
  "status": "partial",
  "notTranslated": ["s4"],
  "results": [
    {
      "id": "s0",
      "status": "translated",
      "source": "ui_glossary",
      "translationType": "human",
      "text": "..."
    },
    {
      "id": "s1",
      "status": "translated",
      "source": "ui_glossary",
      "translationType": "human",
      "text": "..."
    },
    {
      "id": "s2",
      "status": "translated",
      "source": "nllb",
      "translationType": "machine",
      "text": "..."
    },
    {
      "id": "s3",
      "status": "translated",
      "source": "cache",
      "translationType": "machine",
      "text": "...",
      "lowConfidence": false
    },
    { "id": "s4", "status": "not_translated", "reasons": ["negation_mismatch"] }
  ]
}
```

| Field                             | Values                                                                                                          |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| top-level `status`                | `translated` (all), `partial` (some), `failed` (none)                                                           |
| `notTranslated`                   | Ids of segments that stay English                                                                               |
| `results[].status`                | `translated`, `not_translated`                                                                                  |
| `source`                          | `ui_glossary`, `cache`, `nllb`, `mock` (development only)                                                       |
| `translationType`                 | `human` (UI glossary), `machine` (NLLB), `mock` (development only), `unknown`                                   |
| `reasons` (when `not_translated`) | `protected_value_changed`, `placeholder_missing`, `negation_mismatch`, `model_error`, `too_long`, `unsupported` |
| `lowConfidence`                   | Optional boolean. The extension marks these segments visibly                                                    |

`translationType` rules:

- A cached machine translation still returns `translationType: "machine"`. `source: "cache"` alone would hide how the text was produced.
- If missing, the extension derives it from `source`: `nllb` -> machine, `ui_glossary` -> human, `mock` -> mock, anything else -> unknown.
- `model_error` means "try again later". Every other reason is final for that text.

### Rules for the extension

- `translated` with non-empty `text` -> show it, show the marker, keep the original available.
- `not_translated`, missing, or empty `text` -> leave the English in place.
- A non-2xx response, a timeout, or a response with no `results` is a failure: keep English and show a notice.

## Negation and unavailability

The backend's negation check must cover at least: `not`, `no`, `never`, `cannot`, `can't` and other `n't` forms, `without`, `unavailable`, `out of stock`.

Required test sentence:

> This item cannot be shipped to your selected delivery location. Please choose a different delivery location.

A wrong translation here could lead a shopper to order something that never arrives.

## Masking and verification (Java, proposal)

The Python service stays stateless. For every segment, Java:

1. Detects protected spans (prices, currencies, dates, counts, model codes, numbers with units, the user's search query).
2. Replaces them with placeholders.
3. Looks up the masked text in the human-translated UI glossary and the cache. Calls NLLB only if neither has it.
4. Restores the original values into the placeholders.
5. Verifies that every protected value appears unchanged, that no extra numbers were introduced, and checks negations.
6. Passes the segment, or returns `not_translated` with a reason.

Notes:

- Choose a placeholder format that NLLB **preserves** (test `{P1}`, `<x1/>`, `[[1]]` on about 200 real segments before committing).
- Applying glossary corrections **after** NLLB can break Shona noun-class agreement. Prefer whole-phrase lookup first, and compare term substitution before and after translation on a sample.
- Keep the masking rules in **one shared test file**, `tests/shared/mask_cases.json`, used by both the JavaScript and Java tests.

### Masking test cases

| Input                                              | Masked                                       | Notes                                         |
| -------------------------------------------------- | -------------------------------------------- | --------------------------------------------- |
| `INR 16,138.68`                                    | `{PRICE}`                                    | space after the code                          |
| `INR133,185.25`                                    | `{PRICE}`                                    | no space (product page)                       |
| `INR 1,111.97 delivery Fri, Nov 13`                | `{PRICE} delivery {DATE}`                    |                                               |
| `INR 57,530.73 Shipping & Import Charges to India` | `{PRICE} Shipping & Import Charges to India` | import charge was about 43% of the item price |
| `List: INR 22,784.41`                              | `List: {PRICE}`                              | struck-through list price                     |
| `Nov 13 - 15`                                      | `{DATE}`                                     | date range                                    |
| `4.3 out of 5 stars`                               | `{N} out of {N} stars`                       |                                               |
| `(17.1K)`                                          | `({N})`                                      | count with K or M                             |
| `10K+ bought in past month`                        | `{N} bought in past month`                   |                                               |
| `Only 3 left in stock - order soon.`               | `Only {N} left in stock - order soon.`       | scarcity message                              |
| `Sony WH-CH720N Wireless ...`                      | `Sony {CODE} Wireless ...`                   | model codes mix letters and digits            |
| `1-16 of over 90,000 results for "headphones"`     | `{N}-{N} of over {N} results for "{QUERY}"`  | the shopper's own search text                 |

Must-not-change test (every token matters; any change to a number, unit or model code must fail verification):

```text
Acer Nitro V 16S AI Gaming Laptop | AMD Ryzen 7 260 Processor | NVIDIA GeForce RTX 5060 Laptop GPU (572 AI Tops) | 16" WUXGA IPS 180Hz Display | 32GB DDR5 | 1TB Gen 4 SSD | Wi-Fi 6 | ANV16S-41-R2AJ
```

Brand and product names must come from a do-not-translate list (for example Sony, Bose, Alexa, 6pm, AbeBooks, Zappos, Ring, IMDb, Woot!).

## `POST /search`

Shona search over the **research catalogue** (not live Amazon data).

Request:

```json
{ "query": "munyu", "language": "sna_Latn", "topK": 10 }
```

`language` defaults to `sna_Latn`, `topK` to 10 (range 1 to 50), `query` must be non-blank.

Response:

```json
{
  "query": "munyu",
  "results": [
    {
      "productId": "product_12",
      "titleEn": "Iodized table salt, 1 kg",
      "titleSn": "...",
      "price": "$2.99",
      "score": 0.87,
      "matchedOn": ["glossary:munyu", "vector"],
      "sourceUrl": "https://example.com/product/12"
    }
  ]
}
```

`price`, `quantity`, `unit`, `brand` and `model` come from the stored record and are never translated or converted. `matchedOn` is optional and used for evaluation.

## `POST /interpret-query` (later phase)

For the live-site extension: converts a Shona query into English terms that the extension submits to the site's own search.

```json
{ "query": "shangu dzevana vatema", "language": "sna_Latn" }
```

```json
{
  "englishTerms": "black children shoes",
  "attributes": {
    "product": "shoes",
    "audience": "children",
    "color": "black"
  },
  "confidence": 0.7,
  "notes": []
}
```

## `POST /feedback`

Request:

```json
{
  "type": "bad_translation",
  "site": "amazon.com",
  "pageType": "listing",
  "sourceText": "90 days free of Amazon Music with purchase",
  "shownText": "...",
  "source": "nllb",
  "translationType": "machine",
  "issue": "wrong_meaning",
  "comment": "optional, max 500 characters",
  "productId": "optional",
  "field": "optional"
}
```

Response: `{ "status": "received" }`

Rules:

- `type`: `bad_translation`, `bad_search_result`, `other`.
- `issue`: `wrong_meaning`, `unnatural`, `wrong_dialect`, `number_or_price_problem`, `other`.
- `site` has no `www.` prefix.
- One segment per report. The extension shows the user exactly what will be sent before sending.
- No page URL, cookies or account data.
- `source` and `translationType` are recorded so error analysis can tell glossary errors from NLLB errors.
- Until the backend is connected, the extension says honestly that nothing was sent.

## Errors

Non-2xx responses return:

```json
{ "error": "short_code", "message": "human readable" }
```

| Status    | `error`                     | Meaning                                                               |
| --------- | --------------------------- | --------------------------------------------------------------------- |
| 400       | `validation_error`          | request validation failed or the body is malformed                    |
| 502 / 503 | `model_service_unavailable` | the Python service is unreachable, timed out, or failed after retries |
| 500       | `internal_error`            | unexpected server error                                               |

The extension treats any error, or a timeout, as "show English and a notice".

## Java backend -> Python model service

Base URL (dev): `http://localhost:8001`. Owned by the model and evaluation developer.

- `POST /translate`: `{ "text": "...", "sourceLanguage": "eng_Latn", "targetLanguage": "sna_Latn" }` -> `{ "translation": "..." }`
- `POST /embed`: `{ "texts": [...], "prefix": "query" | "passage" }` -> `{ "embeddings": [[...]], "dimension": 768 }`, vectors L2-normalised
- `GET /health`: `{ "status": "ok" }`

Consider adding a batched `/translate` (a list of texts), since the extension sends up to 50 segments at a time.

## Performance and operations (to agree)

- **Timeouts:** the extension waits at most 30 seconds per request (to be agreed). Translating 50 segments with NLLB on a CPU may be slow, so the batch size is a setting in the extension and can be lowered.
- **Order:** the extension sends text that is on screen first and the rest as the user scrolls.
- **Caching:** Java caches by masked text. The extension also caches per page visit.
- **CORS:** the extension's service worker makes the requests. With the right `host_permissions` for the backend URL this should not need CORS configuration (verify during integration).

## Open issues

1. Placeholder format that NLLB preserves.
2. Inline elements: the extension puts the whole translation into the first text node of a block and empties the rest, so inline formatting is lost and sentences with several links are skipped. Options: replace the block as a whole, or let the backend return text with inline markers.
3. Glossary term substitution before or after NLLB.
4. Footer text (about 22% of strings on a search page, mostly brand taglines): translate it or leave it in English?

## Open questions for the team

1. Inline replacement or a panel? (The extension spec says panel; the extension is currently inline with a small toolbar.)
2. Product-field mode only for milestone 1, or page-wide segments from the start?
3. Where do the human-translated UI strings live? Suggested: `research/data/glossary/ui_strings_amazon.json`, loaded by Java at startup.
4. Which NLLB variant (600M vs larger) and which placeholder format?
5. Are `/interpret-query` and the Flipkart adapter in or out for v1?
6. Footer: translate or leave in English?
