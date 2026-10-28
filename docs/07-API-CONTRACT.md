# LinguaCart: API Contract (extension <-> Java backend)

**Status:** draft v0.2 for team discussion. Location: `docs/07-API-CONTRACT.md`.
**Owners:** extension developer and Java backend developer agree on this file before either builds further. Any change is made here first.

## Changes in v0.2

- `/translate` responses now carry `translationType` (`human`, `machine`, `mock`, `unknown`). The extension's marker depends on it.
- `source: "mock"` added (development only).
- Request validation rules written down (they are already implemented in the extension's mock).
- Error handling on the extension side specified.
- `/feedback` payload now includes `source` and `translationType`, and the response format is defined.
- Negation and unavailability list added, with a required test sentence.
- Masking test cases extended (price formats, dates, counts, model codes, search query, import charges).
- Open issues added: inline elements and placeholder format.

## Principles

- The extension sends **only text segments**. No cookies, credentials, form contents, account data or page URLs. Requests use `credentials: "omit"`.
- The Java backend is the authority for **masking, glossary lookup, caching, translation calls and protected-field verification**. The extension only decides what text to send. (The extension has its own prototype masking, used only to classify text for glossary building.)
- The Python model service is never called by the extension.
- If anything fails, the extension shows the **original English text** and a visible notice. It never shows a guess.
- The extension is active only on search results and product pages. It does nothing on cart, checkout, sign-in, account or address pages.
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
    {
      "id": "s3",
      "text": "INR 1,092.75 delivery Fri, Nov 13",
      "kind": "ui_template"
    },
    {
      "id": "s4",
      "text": "Sony WH-CH720N Wireless Noise Cancelling Over-Ear Headphones, Black",
      "kind": "title"
    },
    { "id": "s5", "text": "Not waterproof. Runs small.", "kind": "review" }
  ]
}
```

| Field      | Values / notes                                                                    |
| ---------- | --------------------------------------------------------------------------------- |
| `site`     | Host name without `www.`                                                          |
| `pageType` | `product`, `listing`, `cart`, `other`                                             |
| `target`   | `sna` only                                                                        |
| `kind`     | `ui_label`, `ui_template`, `title`, `description`, `attribute`, `review`, `other` |
| `segments` | Max 50 per request, max 2,000 characters each                                     |
| `id`       | Chosen by the extension, unique within the request                                |

`kind` is a hint from the extension's page analysis. The backend does the real masking and may treat a segment differently.

The extension sends batches of at most 50 segments, and at most 200 segments per page scan.

### Validation rules

Reject with the error format (see Errors) when:

- `apiVersion` is not 1
- `pageType` is not one of the listed values
- `target` is not `sna`
- `segments` is missing or empty
- there are more than 50 segments (`too_many_segments`)
- a segment has a missing or empty `id` or `text`
- two segments share an `id`
- a segment's `text` is longer than 2,000 characters
- a segment's `kind` is present but not one of the listed values

### Response

```json
{
  "apiVersion": 1,
  "results": [
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
      "source": "ui_glossary",
      "translationType": "human",
      "text": "..."
    },
    {
      "id": "s3",
      "status": "translated",
      "source": "nllb",
      "translationType": "machine",
      "text": "..."
    },
    {
      "id": "s4",
      "status": "translated",
      "source": "cache",
      "translationType": "machine",
      "text": "...",
      "lowConfidence": false
    },
    { "id": "s5", "status": "not_translated", "reasons": ["negation_mismatch"] }
  ]
}
```

| Field                             | Values                                                                                                          |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `status`                          | `translated`, `not_translated`                                                                                  |
| `source`                          | `ui_glossary`, `cache`, `nllb`, `mock` (development only)                                                       |
| `translationType`                 | `human` (UI glossary), `machine` (NLLB), `mock` (development only), `unknown`                                   |
| `reasons` (when `not_translated`) | `protected_value_changed`, `placeholder_missing`, `negation_mismatch`, `model_error`, `too_long`, `unsupported` |
| `lowConfidence`                   | Optional boolean. The extension marks these segments visibly                                                    |

`translationType` rules:

- A cached machine translation must still return `translationType: "machine"`. `source: "cache"` alone would hide how the text was produced.
- If `translationType` is missing, the extension derives it from `source`: `nllb` -> machine, `ui_glossary` -> human, `mock` -> mock, anything else -> unknown.
- The extension shows a marker from this ("Machine-translated", "Human-translated", "TEST MOCK, not Shona", "Translation type unconfirmed").

### Rules for the extension

- `translated` with a non-empty `text` -> show the Shona text, show the marker, and keep the original available.
- `not_translated`, or a missing or empty `text` -> leave the English text in place, and do not ask for it again during the same page visit.
- Segments missing from the response are treated as `not_translated`.
- A response containing an `error` object, or neither `results` nor `error`, is treated as a failure: the extension keeps the English text and shows a notice.

## Negation and unavailability

The backend's negation check must cover at least: `not`, `no`, `never`, `cannot`, `can't` and other `n't` forms, `without`, `unavailable`, `out of stock`.

Required test sentence:

> This item cannot be shipped to your selected delivery location. Please choose a different delivery location.

A wrong translation here could lead a shopper to order something that never arrives.

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
- Keep the masking rules in **one shared test file** used by both the JavaScript and Java tests, `tests/shared/mask_cases.json`. This prevents the two implementations from drifting apart.

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
| `Sony WH-CH720N Wireless ...`                      | `Sony {CODE} Wireless ...`                   | model codes mix letters and digits            |
| `1-16 of over 90,000 results for "headphones"`     | `{N}-{N} of over {N} results for "{QUERY}"`  | the shopper's own search text                 |

Example file format:

```json
[
  {
    "input": "INR 1,092.75 delivery Fri, Nov 13",
    "masked": "{PRICE} delivery {DATE}",
    "values": { "PRICE": "INR 1,092.75", "DATE": "Fri, Nov 13" }
  },
  {
    "input": "4.3 out of 5 stars",
    "masked": "{N} out of {N} stars",
    "values": { "N": ["4.3", "5"] }
  }
]
```

### Must-not-change test

A title where every token matters. Any change to a number, unit or model code must fail verification:

```text
Acer Nitro V 16S AI Gaming Laptop | AMD Ryzen 7 260 Processor | NVIDIA GeForce RTX 5060 Laptop GPU (572 AI Tops) | 16" WUXGA IPS 180Hz Display | 32GB DDR5 | 1TB Gen 4 SSD | Wi-Fi 6 | ANV16S-41-R2AJ
```

### Open issue: inline elements

The extension currently puts the whole translated string into the first text node of a block and empties the rest. A sentence with a bold word or a link in the middle loses its formatting, and Shona word order may differ from English. Options: replace the block as a whole, or let the backend return text with inline markers. Decide before the real backend is connected.

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
      "quantity": "1",
      "unit": "kg",
      "score": 0.83,
      "matchedOn": ["glossary:munyu", "vector"],
      "url": null
    }
  ]
}
```

`price`, `quantity`, `unit`, `brand` and `model` come from the stored record and are never translated or converted.

## `POST /interpret-query` (later phase)

For the live-site extension: converts a Shona query into English terms that the extension submits to the site's own search.

```json
{ "apiVersion": 1, "query": "shangu dzevana vatema" }
```

```json
{
  "apiVersion": 1,
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
  "apiVersion": 1,
  "site": "amazon.com",
  "pageType": "listing",
  "sourceText": "90 days free of Amazon Music with purchase",
  "shownText": "...",
  "source": "nllb",
  "translationType": "machine",
  "issue": "wrong_meaning",
  "comment": "optional, max 500 characters"
}
```

Response:

```json
{ "accepted": true, "message": "Report sent. Thank you." }
```

Rules:

- `issue`: `wrong_meaning`, `unnatural`, `wrong_dialect`, `number_or_price_problem`, `other`.
- `site` has no `www.` prefix.
- One segment per report. The extension only allows reporting a single translated segment, and shows the user exactly what will be sent before sending.
- No page URL, cookies or account data.
- `source` and `translationType` are recorded so that error analysis can tell glossary errors from NLLB errors.
- While the backend is not connected, the extension answers `accepted: false` with an honest message, and never claims a report was sent.

## `GET /health`

Returns `{ "status": "ok", "apiVersion": 1 }`. The extension is meant to call it once at startup and show a clear notice when the backend is down. (Not implemented in the extension yet.)

## Errors

```json
{
  "apiVersion": 1,
  "error": { "code": "bad_request", "message": "segments must not be empty" }
}
```

`code`: `bad_request`, `too_many_segments`, `model_unavailable`, `internal_error`. The extension treats any error as "show English and a notice".

## Open issues

1. Placeholder format that NLLB preserves (test on about 200 real segments).
2. Inline elements (see above).
3. Whether glossary term substitution should happen before or after NLLB (compare on a sample).

## Open questions for the team

1. Inline replacement or a panel for translated text? (The current extension spec says panel.)
2. Product-page fields only for milestone 1, or page-wide segments from the start?
3. Where do the human-translated UI strings live? Suggested: `research/data/glossary/ui_strings_amazon.json`, loaded by Java at startup.
4. Which placeholder format and which NLLB variant (600M vs larger)?
5. Are `/interpret-query` and the Flipkart adapter in or out for v1?
