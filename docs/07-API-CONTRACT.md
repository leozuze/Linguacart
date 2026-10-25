# LinguaCart: API Contract Proposal (extension <-> Java backend)

**Status:** draft v0.1 for team discussion. Suggested location: `docs/07-API-CONTRACT.md`.
**Owners:** extension developer and Java backend developer agree on this file before either builds further. Any change is made here first.

## Principles

- The extension sends **only text segments**. No cookies, credentials, form contents or account data. Requests use `credentials: "omit"`.
- The Java backend is the authority for **masking, glossary lookup, caching, translation calls and protected-field verification**. The extension only decides what text to send.
- The Python model service is never called by the extension.
- If anything fails, the extension shows the **original English text** and a visible notice. It never shows a guess.
- All requests and responses carry `apiVersion: 1`.

## `POST /translate`

Translates page text from English to Shona.

### Request

```json
{
  "apiVersion": 1,
  "site": "amazon.com",
  "pageType": "listing",
  "target": "sna",
  "segments": [
    { "id": "s1", "text": "Add to cart", "kind": "ui_label" },
    { "id": "s2", "text": "4.3 out of 5 stars", "kind": "ui_template" },
    { "id": "s3", "text": "INR 1,092.75 delivery Fri, Nov 13", "kind": "ui_template" },
    { "id": "s4", "text": "Sony WH-CH720N Wireless Noise Cancelling Over-Ear Headphones, Black", "kind": "title" },
    { "id": "s5", "text": "Not waterproof. Runs small.", "kind": "review" }
  ]
}
```

| Field | Values / notes |
|---|---|
| `pageType` | `product`, `listing`, `cart`, `other` |
| `kind` | `ui_label`, `ui_template`, `title`, `description`, `attribute`, `review`, `other` |
| `segments` | Max 50 per request, max 2,000 characters each (limits to be agreed) |
| `id` | Chosen by the extension, only unique within the request |

`kind` is a hint from the extension's page analysis. The backend does the real masking and may treat a segment differently.

### Response

```json
{
  "apiVersion": 1,
  "results": [
    { "id": "s1", "status": "translated", "source": "ui_glossary", "text": "..." },
    { "id": "s2", "status": "translated", "source": "ui_glossary", "text": "..." },
    { "id": "s3", "status": "translated", "source": "nllb", "text": "..." },
    { "id": "s4", "status": "translated", "source": "cache", "text": "...", "lowConfidence": false },
    { "id": "s5", "status": "not_translated", "reasons": ["negation_mismatch"] }
  ]
}
```

| Field | Values |
|---|---|
| `status` | `translated`, `not_translated` |
| `source` | `ui_glossary` (human-translated), `cache`, `nllb` |
| `reasons` (when `not_translated`) | `protected_value_changed`, `placeholder_missing`, `negation_mismatch`, `model_error`, `too_long`, `unsupported` |
| `lowConfidence` | Optional boolean. The extension marks these segments visibly |

Rules for the extension:

- `translated` -> show the Shona `text`, mark machine-translated segments (`source` is `nllb` or `cache` of a machine result), and keep the original available.
- `not_translated` -> leave the English text in place. Optionally show a small marker.
- Segments missing from the response are treated as `not_translated`.

## Where masking and verification happen (proposal)

The Python service stays stateless. Java does this for every segment before and after calling it:

1. Detect protected spans (prices, currencies, dates, counts, model codes, numbers with units, the user's search query).
2. Replace them with placeholders.
3. Look up the masked text in the human-translated UI glossary and the cache. Call NLLB only if neither has it.
4. Restore the original values into the placeholders.
5. Verify that every protected value from the source appears unchanged and that no extra numbers were introduced. Check negations.
6. Pass, or return `not_translated` with a reason.

Notes for the team:

- Choose a placeholder format that NLLB **preserves**, and test several (for example `{P1}`, `<x1/>`, `[[1]]`) on about 200 real segments before committing.
- Applying glossary corrections **after** NLLB (as in the current architecture doc) can break Shona grammar, because replacing a word can break noun-class agreement with the words around it. Prefer glossary lookup of whole phrases first, and apply term substitutions before translation where possible. Compare both on a sample.
- Keep the masking rules in **one shared test file** used by both the JavaScript and Java tests, for example `tests/shared/mask_cases.json`:

```json
[
  { "input": "INR 1,092.75 delivery Fri, Nov 13",
    "masked": "{PRICE} delivery {DATE}",
    "values": { "PRICE": "INR 1,092.75", "DATE": "Fri, Nov 13" } },
  { "input": "4.3 out of 5 stars",
    "masked": "{N} out of {N} stars",
    "values": { "N": ["4.3", "5"] } }
]
```

This prevents the two implementations from drifting apart.

## `POST /search`

Shona search over the **research catalogue** (not live Amazon data).

```json
{ "apiVersion": 1, "query": "munyu", "topK": 10 }
```

```json
{
  "apiVersion": 1,
  "results": [
    {
      "productId": "p123",
      "titleEn": "Iodized table salt, 1 kg",
      "titleSna": "...",
      "price": { "value": "2.99", "currency": "USD", "display": "$2.99" },
      "quantity": "1", "unit": "kg",
      "score": 0.83,
      "matchedOn": ["glossary:munyu", "vector"],
      "url": null
    }
  ]
}
```

`price`, `quantity`, `unit`, `brand` and `model` come from the stored record and are never translated.

## `POST /interpret-query` (later phase)

For the live-site extension: converts a Shona query into English terms that the extension submits to the site's own search.

```json
{ "apiVersion": 1, "query": "shangu dzevana vatema" }
```

```json
{
  "apiVersion": 1,
  "englishTerms": "black children shoes",
  "attributes": { "product": "shoes", "audience": "children", "color": "black" },
  "confidence": 0.7,
  "notes": []
}
```

## `POST /feedback`

```json
{
  "apiVersion": 1,
  "site": "amazon.com",
  "pageType": "product",
  "sourceText": "Add to cart",
  "shownText": "...",
  "issue": "wrong_meaning",
  "comment": "optional free text"
}
```

`issue`: `wrong_meaning`, `unnatural`, `wrong_dialect`, `number_or_price_problem`, `other`. No account data or page URLs with personal parameters.

## `GET /health`

Returns `{ "status": "ok", "apiVersion": 1 }`. The extension calls it once at startup and shows a clear notice when the backend is down.

## Errors

```json
{ "apiVersion": 1, "error": { "code": "bad_request", "message": "segments must not be empty" } }
```

`code`: `bad_request`, `too_many_segments`, `model_unavailable`, `internal_error`. The extension treats any error as "show English and a notice".

## Open questions for the team

1. Inline replacement or a panel for translated text? (The current extension spec says panel.)
2. Product-page fields only for milestone 1, or page-wide segments from the start?
3. Where do the human-translated UI strings live? Suggested: `research/data/glossary/ui_strings_amazon.json`, loaded by Java at startup.
4. Which placeholder format and which NLLB variant (600M vs larger)?
5. Are `/interpret-query` and the Flipkart adapter in or out for v1?
