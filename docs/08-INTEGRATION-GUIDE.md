# LinguaCart: Integration Guide (model service for the Java and extension developers)

Last updated: 2026-10-31. Owner: model and evaluation developer.
All numbers come from small tests on one CPU laptop with synthetic or English-only queries; real Shona
queries and a Shona-speaker review are still pending. Treat them as indicative.

## 1. Run it

- **Without models (recommended for development):** `model-service/README.md` quick start, `STUB_MODELS=true`.
  Instant startup. Translations are fake (`[SN] ...`), embeddings are hashed words. Use it for wiring only.
- **Real models:** `pip install -r requirements.txt`, then `uvicorn app.main:app --port 8001`.
  About 3.5 GB of downloads, about 25 s startup, 4 to 6 GB RAM, CPU only for now.
- Binds to 127.0.0.1:8001. Java config: `MODEL_SERVICE_URL=http://127.0.0.1:8001`.
- Interactive docs at `/docs`. Machine-readable contract: `docs/model-service-openapi.json`.

## 2. Contract

| Endpoint | Request | Response |
|---|---|---|
| `GET /health` | | `{status: ok or degraded, translation_model, embedding_model}` (each `loaded` or `unavailable`) |
| `POST /translate` | `{sourceLanguage, targetLanguage, texts[]}` | `{translations[]}` |
| `POST /embed` | `{inputs: [{type: query or passage, text}]}` | `{embeddings: [[768 floats]]}` |

- Language pairs: `eng_Latn -> sna_Latn` (page text) and `sna_Latn -> eng_Latn` (search queries).
  The second direction was not in the original spec. It is live in code and pending team agreement (change #1).
- Output order and length always equal input order and length.
- Empty or whitespace-only strings, and strings without letters (`!!!`, `$2.99`, `1234`), come back unchanged.
- Long text is split into sentences inside the service. Send one field per item (title, bullet, description).
- Limits: at most 64 items per request; long sentences are chunked, never silently cut.
- Embeddings are L2-normalised, so dot product equals cosine.
  The service adds the E5 prefixes itself (`query: ` and `passage: `). Do not add them in Java.
- Errors are JSON with a `detail` field: 422 for validation, 503 if a model is not loaded, 500 otherwise.

## 3. Timing and concurrency (CPU, varies about 2x with machine load)

| Operation | Time |
|---|---|
| Startup with real models | about 25 s |
| Translate a short title or bullet | about 3 to 4 s |
| Translate a 3-sentence paragraph | about 10 to 22 s |
| Translate one Shona query | about 1 to 2 s |
| Embed 1 query / 12 passages | 0.06 s / 0.44 s |
| Embed a whole catalogue (1,610 passages, first time) | about 11 min, so cache it |

- Translate requests are serialised inside the service (one at a time). Embeds are not blocked by them.
  Do not send more than a few translate calls in parallel. Keep batches to 8 items or fewer.
- Suggested client timeouts: connect 2 s, translate read 120 s, embed read 15 s.
- Cache aggressively: translation by (source, target, normalised text), queries by normalised query text.
- If the service answers 503 or times out, show the original English and mark the field `not_translated`.

## 4. Search pipeline (reference implementation: `research/evaluation/run_dev_eval.py`)

1. **Normalise** the query: lowercase, replace every non-alphanumeric character with a space, collapse spaces.
   (An ALL-CAPS Shona query was once translated as unrelated English text, so this step is essential.)
2. **Glossary:** match phrases first, then tokens (substring match for glossary terms of 4+ letters, because
   Shona attaches prefixes: `dzechikoro` contains `chikoro`). A hit yields English terms, with synonyms.
3. **Translate** the normalised query (`sna_Latn -> eng_Latn`), then clean: lowercase, drop a leading
   `the/a/an`, strip trailing `. , ! ? ; :`.
4. **Tokens:** lowercase words without stopwords, light stemming (drop `ing`, plural `s`), plus normalised
   quantities (`2 litres` -> `2l`, `1 kg` -> `1kg`, `128 GB` -> `128gb`). For the original query tokens, strip
   the Shona linking prefixes `ye we re ze ne dze` when the remainder is a catalogue word
   (`yesamsung` -> `samsung`).
5. **Vector score:** E5 query vector of the translated English query and of the original query; dot product
   with the catalogue passage vectors; min-max normalise each over the catalogue;
   `vec = 0.4 * original + 0.6 * translated` (the original protects English words and brands).
6. **Lexical score:** BM25 (k1 = 1.5, b = 0.75) over title, description, brand, category, size, using the union
   of glossary, translated and original tokens; min-max normalise.
7. **Final:** `0.6 * vec + 0.4 * lex`, then top-K with the min-heap. The weights are NOT tuned yet.

Passage text for catalogue embeddings: `{title}. {description}. Brand: {brand}. Category: {category}. Size: {quantity} {unit}.`

Observed behaviour (test vectors):

| Raw query | Normalised | Model output | After clean-up |
|---|---|---|---|
| `MAFUTA EKUBIKA` | `mafuta ekubika` | cooking oil (un-normalised input gave unrelated English) | cooking oil |
| `sipo` | `sipo` | the soap. | soap |
| `foni yeSamsung` | `foni yesamsung` | phone from samsung | phone from samsung |
| `munyu we1kg` | `munyu we1kg` | 1 kg of salt | 1 kg of salt |
| `gumbeze` | `gumbeze` | coat | coat (wrong, it means blanket; the glossary must cover it) |
| `foni` | `foni` | telephone | telephone (catalogues say "smartphone"; glossary needs synonyms) |

Indicative results (MRR@10): synthetic Shona queries over 54 products, direct Shona 0.42, translate-first 0.89,
hybrid with dual query 0.97 (glossary-assisted, so an upper bound). Real Amazon products with English
queries (1,610 products, 80 queries): MRR@10 0.93, P@5 0.84. That is the ceiling for the Shona path.
Ignore Recall@5, which is capped when a query has more than 5 exact matches.

## 5. Translation pipeline and the checks Java must run

Send title, bullets and description. Never send protected fields (price, currency, quantity, unit, brand,
model, allergen text, warnings) to the model; take them from the page and return them unchanged.
After translating, Java checks each field and reverts it to English with `not_translated` when a check fails:

| Check | Why (observed) |
|---|---|
| Every number in the source must appear in the output as digits OR as a Shona number word | Shona often writes numbers as words (6 months, 30 days). Reverting those would discard good translations. Keep a table of Shona numeral stems, confirmed by a speaker |
| Units must survive | `inch` became "masendimita" (centimetres) while the number stayed 6.5 |
| Currency format | `$50` became `$ 50` |
| Model numbers and brands must survive unchanged | Seen to survive in titles, so a failure signals a real problem |
| No dropped sentences or separators | A `|` separator was dropped; one sentence of a multi-sentence input was once lost |
| Glossary post-processing | Output often keeps English terms (iodized, thyroid). Replace leftover English glossary terms with the approved Shona. Protect terms the model gets wrong: "Save" as a discount became the word for store or keep; "Black" became "Negro" |

These checks cannot judge meaning. A sentence can pass every check and still be wrong.

**Safety text** (warnings, allergens, doses, care, children's safety): some model outputs looked wrong to me
(a choking hazard, "on the pack", tree nuts); a Shona speaker must confirm. Proposal #9: always show the
English original beside the Shona, labelled machine-translated, and build an approved phrase bank of reviewed
warning sentences that Java matches before calling the model.

## 6. Known limits

- Speed: about 4 s per segment on CPU. Translate the title first and the rest on demand.
- Quality: code-mixed output, some wrong words, unreviewed. The Shona queries, glossary and relevance
  grades used so far were drafted by Claude and are NOT reviewed.
- The stub is not the model. Never judge quality, ranking or timing with it.

## 7. Decisions we need (see section 4 and 5 for the evidence)

1. Allow `sna_Latn -> eng_Latn` on `/translate` (change #1).
2. Adopt the translate-first search pipeline above (change #2).
3. Java number check accepts Shona number words; add unit and currency checks (change #4, refined).
4. Java normalises and cleans queries as in section 4 (change #7).
5. Glossary entries carry synonym lists and protected terms (change #8).
6. Safety text policy: English beside Shona, then a phrase bank (change #9).
7. May we use the Amazon-released ESCI dataset (Apache-2.0) for research, given our rule about Amazon data?
   Kept out of git until decided.

## 8. Who does what

| Person | Next |
|---|---|
| Java developer | Call `/health`, `/translate`, `/embed` against the stub; implement the section 4 pipeline and the section 5 checks; caches; timeouts |
| Extension developer | Loading states; translate the title first; always show "machine translated" and "Show original"; handle 503 |
| Model developer | pytest suite, catalogue embedding script, speed work, glossary and Shona-speaker review workflow |
| Everyone | Find Shona speakers for the review sheets |

## 9. Files

`model-service/` (service), `research/evaluation/` (test scripts and results), `research/data/` (datasets),
`docs/07-MODEL-SERVICE-STATUS.md` (status), this guide.
