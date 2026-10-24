# LinguaCart — Model Service Status and Proposed Changes

**Last updated:** 2026-10-24
**Owner:** Leo Zuze
**Branch:** `feature/model-evaluation`
**Progress tracker:** `model-service/PROGRESS.md`

## 1. Summary

- Both models (NLLB-200 distilled 600M, multilingual-e5-base) load and run locally on CPU.
- The core inference code is written and verified directly against the real models. The HTTP routes, tests and the catalogue embedding script are the next things to be built.
- Real-model tests showed that **the planned search flow should change**: NLLB understands Shona queries well, but E5 does not. Translating the query to English first fixed retrieval in our small test.
- The tests also showed translation problems that **Java must guard against** (dropped numbers, wrong allergen terms).
- We need the team to agree to the six changes in section 4 before docs 01–05 are updated.

## 2. Component status

| Component | State |
|---|---|
| Model service: config, schemas, NLLB wrapper, E5 wrapper | Written, verified with real models (not yet over HTTP) |
| Model service: `main.py` (`/health`, `/translate`, `/embed`) | Next |
| Model service: pytest suite | Not started |
| `research/retrieval/embed_catalogue.py` | Not started |
| Stress test + query-translation test | Done (`research/evaluation/`) |
| Java backend | Not started by model-service owner (see section 6) |
| Extension | Not started by model-service owner (see section 6) |
| Dataset, Shona queries, glossary, judgments | Not started. This is the slowest work |

## 3. Measured results (CPU, 4 torch threads, real models)

| Check | Result |
|---|---|
| Output count and order preserved (50-item batch) | Pass |
| E5 vectors | 768 dims, L2 norm 1.0, order preserved |
| Shona → English query translation | 8/8 correct (`munyu`→"the salt", `chibage`→"corn", `foni`→"the telephone", ...) |
| Retrieval, direct Shona query + E5 | top-1 0.38, MRR 0.59. `munyu` ranked 12 of 12 |
| Retrieval, translate query → E5 | top-1 1.00, MRR 1.00 |
| Names preserved | "Leo Zuze" stays intact |
| Multi-sentence input in one pass | A sentence was dropped at every beam setting |
| Same text split into sentences | All sentences kept, better Shona |
| Input over 256 tokens | Truncated silently (578 tokens → first 256 translated) |
| Latency, short title | about 3 s |
| Latency, 74-token description | 11–26 s (varies with machine load) |
| Latency, 16 titles / 50 titles | 35 s / 96 s |
| Embedding, 1 query / 12 passages | 0.06 s / 0.44 s |

**Limits of these results:** the retrieval test used 8 short queries and 12 products, and the queries were written by Claude and not yet reviewed by a Shona speaker. The real evaluation (docs/05) uses 50 reviewed queries and 200–500 products. Treat the 1.00 as encouraging, not proven.

## 4. Proposed changes (need team agreement)

| # | Change | Evidence | Docs affected | Who acts |
|---|---|---|---|---|
| 1 | `/translate` also accepts `sna_Latn → eng_Latn` | Shona→English worked 8/8 | 03, 05 | Model dev |
| 2 | Search flow: glossary first, else translate the Shona query to English, then English E5 + English lexical hybrid | Direct Shona E5 top-1 0.38 vs 1.00 translated | 01, 02, 05 | Java dev, Model dev |
| 3 | Python splits long text into sentences, translates each, rejoins | Dropped sentence, silent truncation | 03 | Model dev |
| 4 | Java checks that every number, percentage and currency token in the English also appears in the Shona. Allergen and warning fields revert to English on any mismatch | "Save 33%." was deleted. "peanuts" became "zvipembenene" (looks wrong, needs speaker review) | 02 | Java dev |
| 5 | Java scans the Shona output for leftover English glossary terms and replaces them with approved Shona | Output contained "iodized", "finely grinded", "Store" | 02 | Java dev |
| 6 | Java caches translations and translated queries. Extension shows a loading state and translates the title first | 3 s for a title, 11–26 s for a description on CPU | 02, 04 | Java dev, Extension dev |

Query translation uses NLLB, which is already an approved model. It is not LLM-based query understanding, so it does not break the v1 non-goals.

Baseline B and D in docs/05 should be redefined for the new flow: translate query → English search.

## 5. Revised search flow

```text
Shona query
-> Java normalises
-> Java glossary lookup (hit: use the approved English term, skip the model)
-> otherwise Java calls Python /translate (sna_Latn -> eng_Latn)
-> Java calls Python /embed with the ENGLISH query
-> Java hybrid score: English lexical index + English E5 vectors + glossary score
-> top-K min-heap
-> results shown in Shona
```

Product-side embeddings (`passage:` vectors) are unchanged.

## 6. What each person needs to do next

**Model dev (Leo):** build `main.py` and the tests, apply changes 1 and 3, then `embed_catalogue.py`.

**Java dev:(Wayne)**
- Read sections 4 and 7. Reply with agreement or objections.
- Implement changes 4, 5 and 6 and the revised search flow behind the `ModelServiceClient` interface.
- Set timeouts of at least 60 s on `/translate` until speed work is done.
- Send one field per request item (title, description separately). Python handles sentence splitting.

**Extension dev:(Tino)**
- Show a loading state. Translate the title first, then the description.
- Always show the "machine translated" label and the "Show original" toggle.
- Expect several seconds of latency per field.

**Everyone with a Shona speaker contact:**
- Review the glossary terms (e.g. `sukari` vs `shuga` for sugar), allergen words, and the sample translations in `model-service/PROGRESS.md`.
- Review and extend the 50 Shona queries.

**Team:** pick the licensed or open product dataset and record its source and licence (docs/05).

## 7. Target API contracts (what the Java client should code against)

Status: `/translate` currently accepts only `eng_Latn → sna_Latn`. The `sna_Latn → eng_Latn` direction is change #1 and is not live yet.

### `GET /health`

```json
{ "status": "ok", "translation_model": "loaded", "embedding_model": "loaded" }
```

`status` is `"degraded"` and the model field is `"unavailable"` if a model failed to load.

### `POST /translate`

Request:

```json
{
  "sourceLanguage": "eng_Latn",
  "targetLanguage": "sna_Latn",
  "texts": ["Iodized table salt, 1 kg", "Iodized table salt for cooking."]
}
```

Response (same length and order as `texts`):

```json
{ "translations": ["...", "..."] }
```

Rules: empty list → 422. Empty or whitespace-only strings are returned unchanged. More than 64 items → 422.

### `POST /embed`

Request:

```json
{
  "inputs": [
    { "type": "query", "text": "salt" },
    { "type": "passage", "text": "Iodized table salt, 1 kg. Iodized table salt for cooking." }
  ]
}
```

Response: 768-float vectors, L2-normalised (dot product equals cosine), same order as `inputs`.

```json
{ "embeddings": [[0.01, 0.22], [0.03, 0.19]] }
```

Rules: `type` must be `query` or `passage`. Empty `text` → 422. More than 64 items → 422.

### Errors

- Validation errors: HTTP 422 (FastAPI default body).
- Model not loaded: HTTP 503 with `{"detail": "..."}`.
- Java should read `detail` in both cases.

## 8. How to run and reproduce

```bash
cd model-service
python -m venv .venv            # Python 3.13 used here
source .venv/Scripts/activate   # Windows Git Bash (Linux/macOS: .venv/bin/activate)
pip install -r requirements.txt
cp .env.example .env
# models download automatically on first use; about 3.5 GB
PYTHONUTF8=1 python ../research/evaluation/stress_test.py
PYTHONUTF8=1 python ../research/evaluation/query_translation_test.py
```

Raw results are in `research/evaluation/*_output.txt` and `stress_test_results.json`.

## 9. Open risks

- **Speed:** translation is too slow for interactive use on CPU. Mitigations are caching, translating the title first, and later engineering work (int8 inference, more threads, or a GPU).
- **Shona quality on product text:** code-mixing and wrong terms. Mitigated by the glossary and Java validation. Fine-tuning needs thousands of reviewed pairs we do not have yet.
- **Licence:** NLLB is CC-BY-NC 4.0. Fine for research, but not for a commercial release.
- **Test size:** all current numbers come from tiny samples.
