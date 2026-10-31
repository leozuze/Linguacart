# Model Service Progress

**Owner:** Model and evaluation developer
**Scope:** Python FastAPI service only (docs/03-MODEL-SERVICE-PYTHON.md)
**Shared docs:** docs/07-MODEL-SERVICE-STATUS.md (status), docs/08-INTEGRATION-GUIDE.md (contract and pipelines)
**Branch:** feature/model-evaluation (PR base: develop)
**Last updated:** 2026-10-31

## Where we are

The service works with real models and in stub mode. The smoke test passes on both, and the API key
check works. It is **ready for integration, not final**: the pytest suite is not written, Shona quality is
unreviewed by a Shona speaker, speed is about 4 s per field on CPU, and 7 team decisions are open
(see "Decisions needed from the team").

| Piece | State |
|---|---|
| `/health`, `/translate` (eng<->sna), `/embed` | Done, verified over HTTP, real and stub |
| Stub mode (`STUB_MODELS=true`) | Done. Instant start, fake outputs, no model download |
| Optional API key (`API_KEY`, header `X-API-Key`) | Done, tested (401 without key, 200 with key, /health open) |
| `scripts/smoke_test.py` | Done. Works against real or stub, any URL |
| `docs/model-service-openapi.json` | Exported from the stub |
| pytest suite | Not written |
| `research/retrieval/embed_catalogue.py` | Empty placeholder. The eval harness caches embeddings meanwhile |
| Shona review (queries, glossary, translations) | Not started: needs Shona speakers |
| Real-product translation test | Crashed (parquet engine error); not re-run |

## For the Java developer: how to connect

The call chain is: extension -> Java backend -> this Python service. Only Java calls this service.

### 1. Get the code

```bash
git clone -b feature/model-evaluation https://github.com/leozuze/Linguacart.git
cd Linguacart/model-service
```

After the PR is merged, use `develop` instead of the branch name.

### 2. Run it in stub mode (no model download, starts instantly)

```bash
python -m venv .venv
source .venv/Scripts/activate        # Windows Git Bash. Linux/macOS: source .venv/bin/activate
pip install -r requirements-stub.txt
STUB_MODELS=true uvicorn app.main:app --port 8001
```

In a second terminal, same folder, venv active:

```bash
python scripts/smoke_test.py         # expect: ALL GOOD
```

In stub mode, English->Shona returns `"[SN] <text>"`, Shona->English knows about a dozen common words
(munyu, shuga, mafuta ekubika, ...) and otherwise echoes the text, and embeddings are hashed words
(768-d, unit length). Use it to wire and test the client. Never judge quality, ranking or timing with it.

### 3. Configure Java

```yaml
linguacart:
  model-service:
    base-url: http://127.0.0.1:8001
    api-key: ""                       # only needed when the model developer shares a real instance
    connect-timeout-ms: 2000
    translate-timeout-ms: 120000
    embed-timeout-ms: 15000
```

Keep the base URL and key in config or environment variables, never in code.

### 4. Endpoints and examples

Full contract: `docs/model-service-openapi.json` (or open `http://127.0.0.1:8001/docs`).
All requests and responses are JSON. Field names are camelCase exactly as shown.

**GET /health**

```json
{ "status": "ok", "translation_model": "loaded", "embedding_model": "loaded" }
```

`status` is `"degraded"` and the model field is `"unavailable"` if a model failed to load. Treat
`degraded` as "do not use this endpoint yet".

**POST /translate** (English to Shona, for page text)

```json
{ "sourceLanguage": "eng_Latn", "targetLanguage": "sna_Latn", "texts": ["Cooking oil", ""] }
```

```json
{ "translations": ["Mafuta ekubika", ""] }
```

**POST /translate** (Shona to English, for search queries)

```json
{ "sourceLanguage": "sna_Latn", "targetLanguage": "eng_Latn", "texts": ["munyu"] }
```

```json
{ "translations": ["the salt"] }
```

**POST /embed**

```json
{ "inputs": [ { "type": "query", "text": "salt" },
              { "type": "passage", "text": "Iodized table salt, 1 kg." } ] }
```

```json
{ "embeddings": [ [0.012, "... 768 floats in total ..."], [0.034, "..."] ] }
```

Rules the Java side can rely on:

- Output order and length always equal input order and length.
- Empty or whitespace-only strings, and strings without letters (`!!!`, `$2.99`, `1234`), come back unchanged.
- Long text is split into sentences inside the service. Send one field per item (title, bullet, description).
- At most 64 items per request. Keep translate batches to 8 items or fewer.
- Embeddings are L2-normalised, so a dot product equals cosine similarity.
- The service adds the E5 prefixes (`query: `, `passage: `) itself. Do not add them in Java.
- `type` must be `query` or `passage`. Empty `text` in `/embed` is rejected.
- English<->Shona is the only supported pair. Anything else is a 422.
- The `sna_Latn -> eng_Latn` direction is live in code but not yet agreed by the team (change #1).

### 5. Errors (always JSON with a `detail` field)

| Status | When | Body |
|---|---|---|
| 422 | Bad language pair, bad type, empty list, too many items | `{"detail":[{"loc":["body","targetLanguage"],"msg":"Input should be 'eng_Latn' or 'sna_Latn'","type":"literal_error"}]}` |
| 503 | A model is not loaded | `{"detail":"Translation model is not loaded"}` |
| 401 | An API key is configured and the header is missing or wrong | `{"detail":"Missing or invalid X-API-Key"}` |
| 500 | Unexpected error | `{"detail":"Internal server error"}` |

On 503, 500, a timeout, or a connection error: show the original English for that field and mark it
`not_translated`. Never show a partial result.

### 6. Timing and concurrency (CPU laptop, varies about 2x with load)

| Operation | Time |
|---|---|
| Startup with real models | about 25 s |
| Translate a short title or bullet | about 3 to 4 s |
| Translate a 3-sentence paragraph | about 10 to 22 s |
| Translate one Shona query | about 1 to 2 s |
| Embed 1 query / 12 passages | 0.06 s / 0.44 s |
| Embed a whole catalogue (1,610 passages, first time) | about 11 min, so cache the vectors |

- Translate requests are handled one at a time inside the service. Embeds are not blocked by them.
  Do not run more than a few translate calls in parallel.
- Cache translations by (source, target, normalised text) and queries by normalised query text.

### 7. What Java must implement (details and evidence in docs/08-INTEGRATION-GUIDE.md)

1. **Query pipeline:** normalise (lowercase, strip punctuation) -> glossary -> translate `sna->eng` -> clean
   (drop leading the/a/an, trailing punctuation) -> tokens (with Shona prefix stripping) -> vector score using
   both the original and translated query -> BM25 -> `0.6 * vec + 0.4 * lex` -> top-K min-heap.
   Reference code: `research/evaluation/run_dev_eval.py`. Weights are not tuned yet.
2. **Translation checks** after `/translate`, reverting a field to English with `not_translated` on failure:
   numbers (digits OR Shona number words), units (inch must not become cm), currency format (`$50` not `$ 50`),
   model numbers and brands, dropped sentences or separators.
3. **Glossary post-processing:** replace leftover English terms with approved Shona. Protect terms the model
   gets wrong ("Save" as a discount, colours, units).
4. **Safety text** (warnings, allergens, doses, care): show the English original beside the Shona, labelled
   machine translated (proposal #9). Never send protected fields (price, currency, quantity, unit, brand, model,
   allergen text, warnings) to the model; return them unchanged from the page.

### 8. Client sketch (adapt to your Spring Boot version and package names)

```java
public record TranslateRequest(String sourceLanguage, String targetLanguage, List<String> texts) {}
public record TranslateResponse(List<String> translations) {}
public record EmbedInput(String type, String text) {}          // type: "query" or "passage"
public record EmbedRequest(List<EmbedInput> inputs) {}
public record EmbedResponse(List<List<Double>> embeddings) {}

public interface ModelServiceClient {                          // interface so tests can mock it
    boolean isHealthy();
    List<String> translate(String sourceLanguage, String targetLanguage, List<String> texts);
    List<float[]> embed(List<EmbedInput> inputs);
}

@Component
class HttpModelServiceClient implements ModelServiceClient {
    private final RestTemplate http;      // use a second one with a 15 s read timeout for embed and health
    private final String baseUrl;
    private final String apiKey;

    HttpModelServiceClient(@Value("${linguacart.model-service.base-url}") String baseUrl,
                           @Value("${linguacart.model-service.api-key:}") String apiKey) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(120_000);
        this.http = new RestTemplate(factory);
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    private <T> HttpEntity<T> entity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isBlank()) headers.set("X-API-Key", apiKey);
        return new HttpEntity<>(body, headers);
    }

    public boolean isHealthy() {
        var res = http.getForObject(baseUrl + "/health", Map.class);
        return res != null && "ok".equals(res.get("status"));
    }

    public List<String> translate(String source, String target, List<String> texts) {
        var res = http.postForObject(baseUrl + "/translate",
                entity(new TranslateRequest(source, target, texts)), TranslateResponse.class);
        return res.translations();
    }

    public List<float[]> embed(List<EmbedInput> inputs) {
        var res = http.postForObject(baseUrl + "/embed",
                entity(new EmbedRequest(inputs)), EmbedResponse.class);
        return res.embeddings().stream().map(v -> {
            float[] f = new float[v.size()];
            for (int i = 0; i < f.length; i++) f[i] = v.get(i).floatValue();
            return f;
        }).toList();
    }
}
```

Catch `RestClientException` around every call and fall back to English plus `not_translated`.

### 9. Using the real models

- Run your own copy (needs `pip install -r requirements.txt`, about 3.5 GB of downloads, and **at least
  8 GB of free RAM**, because loading briefly needs more than the final size) or ask the model developer for
  a shared session with an API key.
- Wait for `/health` to say `ok` before sending real traffic. Startup is about 25 s.
- A shared instance must stay on a trusted network (same LAN or a private mesh such as Tailscale). It has no
  login beyond the optional key and must never be exposed to the open internet.

### 10. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `/health` says `degraded` with `embedding_model: unavailable` | Windows ran out of memory while loading (`MemoryError` in the log). Close big programs, restart, or use stub mode |
| Connection refused | The service is not running, or runs on another port |
| 401 on every call | `API_KEY` is set on the server. Send header `X-API-Key` |
| 422 | The request does not match the contract: check field names, languages, types |
| Translations look like `[SN] ...` | You are in stub mode. This is expected |
| Port already in use | `netstat -ano \| grep 8001`, then `taskkill //F //PID <pid>` |
| Very slow translation | Normal on CPU (about 4 s per field). Cache, send fewer items, translate the title first |

### 11. Integration milestones

- [ ] M1: Java calls `/health`, `/translate`, `/embed` against the stub (contract confirmed or change requests sent)
- [ ] M2: Java `/api/v1/translate` works end to end against the stub, including checks and `not_translated`
- [ ] M3: Same against the real models (shared session)
- [ ] M4: Java `/api/v1/search` implements the query pipeline over a small catalogue
- [ ] M5: Extension talks to the Java API; everything together on `test/integration`

## For the extension developer

- You never call the Python service. Connect to the **Java backend** (`docs/02`), using mocked responses
  until Java's endpoints exist. The manifest needs `host_permissions` for the Java address, and Java must
  allow CORS for the extension's origin.
- Plan for about 4 s per translated field: show a loading state, translate the title first and the rest
  on demand.
- Always show a "machine translated" label and a "Show original" button. Handle fields marked
  `not_translated`. Never modify checkout, payment, login or account pages (`docs/04`).

## Phase 0: Setup
- [x] Repo skeleton and folders created
- [x] venv created, requirements installed (Python 3.13, torch 2.14.1 CPU, transformers 5.20.2)
- [x] NLLB downloaded and cached
- [x] E5 downloaded and cached
- [x] Both models verified loading offline
- [x] .env copied from .env.example
- [x] All docs/*.md available to Claude

## Phase 1: Spec review
- [x] Claude read all 7 docs
- [x] Claude listed planned files and spec ambiguities
- [x] Ambiguities resolved (see decisions below)

## Phase 2: Service code
- [x] config.py, schemas.py, errors.py
- [x] translation.py (sentence splitting, repetition guard, no-letters guard, both directions)
- [x] embedding.py (768 dims, norms 1.0, order preserved)
- [x] main.py (lifespan loading, routes, 503/422/500 JSON handlers, lazy model imports)
- [x] schemas.py allows sna_Latn -> eng_Latn (proposal #1)
- [x] Stub mode (app/stub.py, requirements-stub.txt)
- [x] Optional API key (X-API-Key middleware)
- [x] scripts/smoke_test.py (real and stub: ALL GOOD)
- [x] docs/model-service-openapi.json exported
- [x] README quick start, docs/08-INTEGRATION-GUIDE.md

## Phase 2b: Quality and speed
- [x] Stress test (research/evaluation/stress_test.py), all hard checks passed
- [x] Shona -> English query translation tested
- [x] Query pipeline v2 (normalise, translate, clean) fixed the ALL-CAPS hallucination
- [x] Glossary ablation (system E) and dual-query system F
- [x] Real Amazon products (ESCI) English-query run: ceiling measured
- [ ] Fix real_translation_test.py crash: get the first 20 lines of the log and the pyarrow/pandas versions
- [ ] FLORES chrF++ baseline: data not downloaded (tinyurl did not resolve; use the direct host or a browser)
- [ ] Try torch.set_num_threads(8) and benchmark with nothing else running (timings vary 2x)
- [ ] Speed options: CTranslate2 int8 if a Python 3.13 wheel exists, otherwise quantisation or a GPU

## Phase 3: Tests
- [x] scripts/smoke_test.py
- [ ] pytest suite using the stub models: health, translate (count, order, both directions),
      embed (count, normalisation), validation 422s, model-unavailable 503, API key 401
- [ ] sentence splitting and no-letters guard tests
- [ ] optional integration test with the real models (marked, off by default)
- [ ] pytest passes

## Phase 4: Research script
- [ ] embed_catalogue.py reads JSONL from research/data/catalogue/
- [ ] Builds "passage: <title>. <description>. <attributes>."
- [ ] Writes product_id, embedding, model_name, model_version, created_at
- [ ] Tested on a tiny sample catalogue

## Phase 5: Handoff
- [x] Status doc (docs/07) and integration guide (docs/08) written
- [ ] Fresh-clone test: clone the branch into a temp folder, stub mode, smoke test passes
- [ ] Pushed and PR opened into develop
- [ ] Team replies to the 7 decisions
- [ ] Java developer runs the stub and passes the smoke test (M1)
- [ ] Java developer calls the real service in a shared session (M3)
- [ ] docs 01 to 05 updated once changes are agreed

## Phase 6: Data and evaluation (slowest part: needs people)
- [x] ESCI subset built locally (1,610 products, 80 everyday queries, git-ignored). Licence: Apache-2.0
- [x] Glossary candidate extractor (research/evaluation/extract_glossary_candidates.py)
- [ ] Team decides whether Amazon-released ESCI data may be used (rule: no Amazon catalogue data in the repo)
- [ ] Recruit 2 to 3 Shona speakers from different regions
- [ ] Speakers fill research/data/queries/esci_queries_for_shona.csv (Shona query, variant, region)
- [ ] Natural Shona queries collected using product photos (separate set)
- [ ] Real glossary: extract candidates from the real catalogue, speakers review (safety, unit, colour first)
- [ ] Speakers rate translation_review.csv (safety rows first)
- [ ] 100 reviewed product translation segments
- [ ] Shona retrieval run on real products: measure the gap against the English ceiling
- [ ] Open Food Facts considered for groceries and allergens (ODbL: attribution and share-alike)
- [ ] Redefine baselines B and D in docs/05 for the translate-first flow
- [ ] Fine-tuning decision (only after the reviewed baseline; query direction sna->eng first)

## Decisions made
- 2026-10-24: torch>=2.9 and transformers>=4.50 (torch 2.5.1 has no wheel for Python 3.13). Code uses transformers 5.x APIs.
- 2026-10-24: E5 downloaded with --include filters to skip the ONNX/OpenVINO copies.
- 2026-10-24: NLLB licence is CC-BY-NC 4.0 (research use only; check before sharing a fine-tuned model or going commercial).
- 2026-10-24: E5 vectors are attention-masked mean pooled and L2-normalised (768 dims).
- 2026-10-24: Limits: MAX_INPUT_TOKENS=256, MAX_NEW_TOKENS=256, NUM_BEAMS=4, BATCH_SIZE=16, MAX_ITEMS_PER_REQUEST=64.
- 2026-10-24: Empty list -> 422. Empty or whitespace string in /translate returned unchanged. Empty text in /embed -> 422.
- 2026-10-24: /health is "ok" or "degraded"; the service starts even if a model fails to load; 503 body is {"detail": "..."}.
- 2026-10-24: The docs' example Shona strings are placeholders, not expected output.
- 2026-10-24: Translate-first search flow proposed to the team (evidence: top-1 1.00 vs 0.38 on a small test).
- 2026-10-24: The dev set (catalogue, queries, grades, glossary) is Claude-drafted and unreviewed. Only B vs C is a fair comparison. A and D are inflated.
- 2026-10-25: ESCI relevance mapping: Exact=3, Substitute=1, Complement and Irrelevant omitted (=0). "Relevant" means grade >= 2, so Exact only.
- 2026-10-31: Stub mode and the optional API key added so colleagues can build without model weights.
- 2026-10-31: Loading both models needs at least 8 GB of free RAM (a MemoryError occurred when memory was low).
- 2026-10-31: Colleagues run their own stub copy. Real-model use is by short shared sessions with an API key on a trusted network only.

## Proposed spec changes (status)
1. /translate also allows sna_Latn -> eng_Latn. LIVE IN CODE, waiting for team agreement (docs 03, schemas).
2. Search flow: glossary, then NLLB query translation, then English E5 plus English lexical hybrid. Waiting for Java (docs 01, 02, 05).
3. Python splits long text into sentences, translates each, rejoins. DONE.
4. Java checks numbers (digits OR Shona number words), units, currency format, model numbers and brands. Revert to English on failure. Waiting for Java (doc 02).
5. Java glossary replaces leftover English terms in the output (doc 02).
6. Java caches translations and queries; the extension shows a loading state and translates the title first (docs 02, 04).
7. Java normalises queries (lowercase, strip punctuation) and cleans translated queries (doc 02).
8. Glossary entries carry synonym lists and protected terms ("Save", colours, units) (doc 02).
9. Safety text: show English beside Shona, then build an approved phrase bank (docs 02, 04).

## Decisions needed from the team
1. Allow sna_Latn -> eng_Latn on /translate (#1).
2. Adopt the translate-first search pipeline (#2).
3. Java number check accepts Shona number words, plus unit and currency checks (#4).
4. Java normalises and cleans queries (#7).
5. Glossary synonyms and protected terms (#8).
6. Safety text policy (#9).
7. May the Amazon-released ESCI dataset (Apache-2.0) be used for research despite the "no Amazon catalogue data" rule? It stays out of git until decided.

## Evaluation results (CPU laptop, real models; all queries, glossary and grades are unreviewed)

**Retrieval, synthetic dev set (54 products, 53 queries), MRR@10:**
C direct Shona + E5 0.42 | B translate + E5 0.89 | A glossary + BM25 0.99 (inflated) | D hybrid 0.95 |
E hybrid without glossary 0.89 | F hybrid + dual query 0.97. Single-word queries: C 0.16, B 0.83, D 0.96.
The glossary adds about +0.06 (D minus E); that is an upper bound because it was built from the test words.

**Retrieval, real Amazon products with English queries (ESCI, 1,610 products, 80 queries):**
C 0.91 | B 0.91 | A 0.90 | D, E, F 0.93 MRR@10. P@5 about 0.84, nDCG@5 about 0.87.
This is the ceiling for the Shona path. Recall@5 (about 0.32) is capped because many queries have more than
5 exact matches: ignore it and read P@5, MRR and nDCG. The remaining failures are product-ranking cases
("hidden shoes", "puma boys shoes"), not translation.

**NLLB damage seen on queries:** ALL CAPS input gave unrelated text (fixed by lowercasing); gumbeze -> "coat",
badza -> "beware of it", foni -> "telephone" (catalogues say "smartphone"); English input sometimes reordered
or glued ("elsa dress" -> "dress of elsa", "backpacksmall"). F protects against the last one by also using the
original query.

**Translation, 40 synthetic product-page segments:** 175 s total, about 4.4 s per segment. 34 of 40 had no hard
flags, but all 6 "number_lost" flags were false alarms: Shona writes numbers as words. The automatic checks
missed real problems: inch -> centimetres, "Save" -> the word for store/keep, "Black" -> "Negro", `$50` ->
`$ 50`, dropped `|` separators, inconsistent allergen words, and safety sentences that look wrong (all of
these need Shona-speaker confirmation).

**Latency:** short title 3 to 4 s; 3-sentence paragraph 10 to 22 s; one Shona query 0.8 to 2.1 s; embed 1 query
0.06 s; embed 12 passages 0.44 s; embedding 1,656 passages the first time about 11 min; startup about 25 s.
Greedy decoding saved only about 30%, so beam search is not the main bottleneck.

## Open questions / blockers
- Team agreement on the 7 decisions above.
- Shona speakers: none recruited yet. This blocks the real Shona evaluation, the glossary and any fine-tuning.
- real_translation_test.py crash (parquet engine error): need the first lines of the log.
- Translation is slow on CPU. Options: more threads, int8, a GPU.
- Allergen and safety translations look wrong in places: needs speaker review and the Java rules.
- Where to run baseline B for the final evaluation (research scripts or the service).
- Optional: set HF_TOKEN for faster Hugging Face downloads.

## Sample translations (for team demos; unreviewed, speaker to confirm)
| English | NLLB Shona | Notes |
|---|---|---|
| My name is Leo Zuze | Zita rangu ndinonzi Leo Zuze | name preserved |
| I'm going home | Ndiri kuenda kumba | natural |
| Cooking oil | Mafuta ekubika | natural |
| Maize | Chibage | natural |
| Sugar | Sukari | Claude's reference was "Shuga"; both are used, speaker to choose the glossary term |
| Store in a cool, dry place. | Chengetedza panzvimbo inotonhorera uye yakaoma. | natural |
| This iodized table salt is ideal for everyday cooking. | Munyu uyu wetafura une iodine unokodzera kubika zuva nezuva. | good |
| It is finely ground and fortified with iodine. | Yakanyatsodyarwa uye yakasimbiswa neyodi. | "finely ground" looks like "well planted" |
| Price: $2.99 (was $4.50). Save 33%. | Mutengo: $2.99 (akanga ari $4.50). Chengetedza 33%. | "Save" uses the word for store/keep |
| Boys School Shoes, Black Leather, Size 5 | Shangu dzechikoro dzevakomana, Negro Leather, Size 5 | "Black" became "Negro" |
| This smartphone has a 6.5 inch display. | Iyi smartphone ine kuratidzwa kwemasendimita 6.5 | inch became centimetres |
| Free delivery on orders over $50. | Kutumira kusina mubhadharo pamirairo inodarika $ 50. | `$ 50` has a space |
| Returns accepted within 30 days of delivery. | Kudzoka kunogamuchirwa mukati memazuva makumi matatu ekuendeswa. | 30 written as words |
| WARNING: Contains peanuts and tree nuts. Keep out of reach of children. | Yambiro: Inosanganisira nzungu nemiti yemiti. Ramba uri kure nevana. | "tree nuts" looks wrong; second sentence reads like "stay away from children" |
| Allergen information: Contains wheat, milk, eggs and soy. May contain traces of nuts. | Allergen ruzivo: Rine gorosi, mukaka, mazai uye soya. Inogona kunge iine zviratidzo zvemakwenzi. | "nuts" word differs from the previous row; "traces" looks like "signs" |
| Not suitable for children under 3 years. Small parts may be a choking hazard. | Hazvina kukodzera vana vari pasi pemakore matatu. Zvikamu zviduku zvinogona kuita kuti munhu anyure. | "choking" looks like sink/drown |
| Always follow the preparation instructions on the pack. | Nguva dzose tevera mirayiridzo yekugadzirira iri pakapu. | "pack" looks like "cup" |
| munyu / shuga / mafuta ekubika / mupunga (sna -> eng) | salt / sugar / cooking oil / rice | correct |
| gumbeze / badza / foni (sna -> eng) | coat / beware of it / telephone | wrong or off: the glossary must cover these |

## Log
- 2026-10-21: Project setup, build prompt prepared.
- 2026-10-24: venv fixed, dependencies installed, NLLB and E5 downloaded and verified offline.
- 2026-10-24: Nine spec ambiguities settled. config, schemas, errors, translation, embedding written and verified.
- 2026-10-24: Stress test, Shona->English test and query-translation comparison. Status doc written.
- 2026-10-24: Routes (main.py) verified over HTTP. Dev evaluation harness (make_dev_data.py, run_dev_eval.py) run.
  Query pipeline v2. Changes 4, 7, 8, 9 proposed.
- 2026-10-25: ESCI dataset downloaded; real-product subset built; English-ceiling run.
- 2026-10-29: Full re-run of the evaluations (dev retrieval, dev translation, ESCI English retrieval).
  System F added. The real-product translation run crashed.
- 2026-10-31: Stub mode, optional API key, smoke test, README, integration guide and OpenAPI file added.
  Real-model load hit a MemoryError at low free RAM; fixed by freeing memory. A config.py patch bug
  (escaped quotes) was found and fixed before pushing. Smoke tests pass on real and stub; API key tested.
  Progress file consolidated, with Java connection instructions.
