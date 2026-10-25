# Model Service Progress

**Owner:** Model and evaluation developer
**Scope:** Python FastAPI service only (docs/03-MODEL-SERVICE-PYTHON.md)
**Shared status doc:** docs/07-MODEL-SERVICE-STATUS.md
**Last updated:** 2026-10-24

## Current focus
Status doc written for the team. Next: push and open PR, then Step 3 (patch schemas.py and translation.py, write main.py, first curl test).

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
- [x] config.py (env vars + limits)
- [x] schemas.py (Pydantic models)
- [x] errors.py (ModelUnavailableError)
- [x] translation.py (verified with real model)
- [x] embedding.py (verified: 768 dims, norms 1.0, order preserved)
- [x] schemas.py patch: allow sna_Latn -> eng_Latn (proposal #1)
- [x] translation.py patch: sentence splitting, repetition guard, no-letters guard, fix max_length warning (proposal #3)
- [x] main.py (lifespan loading, routes, 503 handling)
- [x] GET /health returns "loaded" for both models
- [x] POST /translate works with curl
- [x] POST /embed works with curl ("munyu" + a passage)

## Phase 2b: Quality and speed
- [x] Stress test run (research/evaluation/stress_test.py), all 7 hard checks passed
- [x] Shona -> English query translation tested: 8/8 correct
- [x] query_translation_test.py run: translate-then-E5 top-1 1.00 vs direct Shona 0.38
- [x] Sentence-by-sentence translation recovers the dropped sentence and reads better
- [x] torch threads checked: 4 of 8 logical cores (default)
- [ ] Try torch.set_num_threads(8) and benchmark with nothing else running (timings vary a lot: same text took 11.3s and 22.0s)
- [ ] Speed options after correctness: CTranslate2 int8 if a Python 3.13 wheel exists, otherwise dynamic quantisation or GPU

## Phase 3: Tests
- [ ] test_health
- [ ] test_translate (count and order preserved)
- [ ] test_translate both directions, sentence splitting, no-letters guard
- [ ] test_embed (prefixes, normalisation, count)
- [ ] test_validation (bad language pair, bad type, empty list -> 422)
- [ ] model-unavailable -> 503
- [ ] optional integration test with real models
- [ ] pytest passes

## Phase 4: Research script
- [ ] embed_catalogue.py reads JSONL from research/data/catalogue/
- [ ] Builds "passage: <title>. <description>. <attributes>."
- [ ] Writes product_id, embedding, model_name, model_version, created_at
- [ ] Tested on a tiny sample catalogue

## Phase 5: Handoff to Java developer
- [x] Status doc written with contracts and asks (docs/07-MODEL-SERVICE-STATUS.md)
- [ ] Pushed to branch and PR opened
- [ ] Team agrees the six proposed changes (then update docs 01, 02, 03, 04, 05)
- [ ] Confirmed field names match the Java client
- [ ] Shared embeddings file format and vector dimension (768 for e5-base)
- [ ] Java dev told: number/percentage check, allergen revert, leftover-English glossary scan, query cache, 60s+ timeout
- [ ] Java developer successfully called the service

## Phase 6: Data and evaluation (start early, slowest part)
- [ ] Licensed/open product dataset chosen (record source + licence)
- [ ] 200-500 products in catalogue JSONL
- [ ] 50 Shona queries written and reviewed
- [ ] Glossary reviewed by a Shona speaker (include sukari vs shuga, allergen terms)
- [ ] Shona speaker reviews sample translations below
- [ ] 300+ relevance judgments
- [ ] 100 reviewed translation segments
- [ ] Baselines A-D evaluated (Recall@5, P@5, MRR@10, nDCG@5, latency)
- [ ] Redefine baseline B/D in docs/05 for the new flow (translate query -> English search)

## Decisions made
- 2026-10-24: Loosened pins to torch>=2.9 and transformers>=4.50 because torch 2.5.1 has no wheel for Python 3.13. Code must use transformers 5.x compatible APIs.
- 2026-10-24: E5 downloaded with --include filters (json, model, safetensors, 1_Pooling) to skip the ONNX/OpenVINO copies.
- 2026-10-24: NLLB licence is CC-BY-NC 4.0 (research use only, flag if the project ever goes commercial).
- 2026-10-24: E5 vectors are attention-masked mean pooled and L2-normalised (768 dims).
- 2026-10-24: Limits in config: MAX_INPUT_TOKENS=256, MAX_NEW_TOKENS=256, NUM_BEAMS=4, BATCH_SIZE=16, MAX_ITEMS_PER_REQUEST=64 (over limit -> 422).
- 2026-10-24: Empty list -> 422. Empty/whitespace string in /translate returned unchanged. Empty text in /embed -> 422.
- 2026-10-24: /health status is "ok" or "degraded"; service starts even if a model fails to load; 503 body is {"detail": "..."}.
- 2026-10-24: Embeddings file is research/data/embeddings/embeddings.jsonl (product_id, embedding, model_name, model_version, created_at); attributes optional in catalogue.
- 2026-10-24: Docs' example Shona strings are placeholders, not expected output.
- 2026-10-24: Decided to propose translate-query-then-search flow to the team (evidence: top-1 1.00 vs 0.38 on a small test).

## Proposed spec changes (waiting for team agreement)
1. /translate also allows sna_Latn -> eng_Latn (docs 03, schemas).
2. Search flow: Java glossary -> else NLLB query translation -> English E5 + English lexical hybrid (docs 01, 02, 05).
3. Python splits long text into sentences, translates each, rejoins (doc 03).
4. Java checks every number/percentage/currency token in source appears in output; allergens and warnings revert to English on mismatch (doc 02).
5. Java glossary scans output for leftover English terms and replaces them (doc 02).
6. Java caches translations and queries; extension shows loading state and translates title first (docs 02, 04).

## Evaluation results (2026-10-24, CPU, real models)
- Hard checks: count, order, edge cases, normalisation all PASS.
- Retrieval (8 queries, 12 products, queries unreviewed): direct Shona + E5 top-1 0.38, MRR 0.59 ("munyu" ranked 12 of 12); translate query -> E5 top-1 1.00, MRR 1.00; with toy lexical bonus also 1.00. Sample too small to claim; real evaluation needs 50 reviewed queries.
- E5 scores sit in a narrow 0.74-0.82 band for Shona queries, so ranking is near noise.
- Shona -> English query translation: 8/8 correct (1.6s per query).
- Sentence splitting: all 3 sentences kept, vs one dropped when translated as a paragraph.
- Long input: 578 tokens truncated at 256 (rest silently lost). Medium text code-mixed (iodized, finely grinded, Store; "thyroid" -> "zuva").
- Latency: short title 3.4s; 74-token description 11-26s (varies); 16 titles 35.4s; 50 titles 96s; embed 1 query 0.06s; embed 12 passages 0.44s.
- Greedy decoding (1 beam) saved about 30% (8.0s vs 11.3s), so beams are not the main bottleneck.
- Names preserved: "Leo Zuze" stays intact.
- Edge cases: "!!!" -> hallucinated sentence; "a a a ..." -> repeated "ane"; accent display problem was Windows console, fixed with PYTHONUTF8=1.

## Open questions / blockers
- Team agreement on the six proposed changes.
- Translation speed on this CPU is too slow for interactive use; try more threads and int8.
- Allergen terms mistranslated ("peanuts" -> "zvipembenene", "tree nuts" -> "miti yemiti"): needs Shona speaker review and the Java revert rule.
- Where to run baseline B (research scripts calling the model directly, or the service).
- Optional: set HF_TOKEN for faster Hugging Face downloads.

## Sample translations (for team demos)
| English | NLLB Shona | Notes |
|---|---|---|
| My name is leo zuze | Zita rangu ndinonzi Leo zuze | name preserved |
| My name is Leo Zuze | Zita rangu ndinonzi Leo Zuze | name preserved |
| and I'm going home | uye ndiri kuenda kumba | matches reference |
| I'm going home | Ndiri kuenda kumba | matches reference |
| Sugar | Sukari | Claude's reference was "Shuga"; both used, speaker to choose glossary term |
| Cooking oil | Mafuta ekubika | matches reference |
| Maize | Chibage | matches reference |
| Maize meal, 10 kg bag, 100% natural | Chibage chibage, 10 kg bag, 100% yemasikirwo | "meal" lost, "bag" left English |
| Tastic Long Grain Parboiled Rice, 2 kg - Pack of 2, Model TG-2000 | Tastic Long Grain Parboiled Rice, 2 kg - Pack ye2, Model TG-2000 | mostly left in English |
| Price: $2.99 (was $4.50). Save 33%. | Mutengo: $2.99 (akanga ari $4.50). | "Save 33%." deleted: Java number check must catch this |
| WARNING: Contains peanuts and tree nuts. Keep out of reach of children. | CHIDZIDZO: Rine zvipembenene nemiti yemiti. | wrong allergen terms; second sentence dropped |
| Allergen information: Contains wheat, milk and soy. | Allergen ruzivo: Rine gorosi, mukaka uye soya. | needs speaker review |
| This iodized table salt is ideal for everyday cooking. (sentence split) | Munyu uyu wetafura une iodine unokodzera kubika zuva nezuva. | good |
| It is finely ground, free flowing and fortified with iodine. (sentence split) | Rakaomeswa zvakanaka, rinoyerera zvakasununguka uye rakasimbiswa nejodi. | needs speaker review |
| Store in a cool, dry place. (sentence split) | Chengetedza panzvimbo inotonhorera uye yakaoma. | natural |
| munyu / shuga / sukari / mafuta ekubika (sna -> eng) | the salt / sugar / sugar / cooking oil | correct |
| chibage / mupunga / foni / tauro (sna -> eng) | corn / rice / the telephone / towel | correct (catalogue says "maize", glossary should map) |

## Log
- 2026-10-21: Project setup, build prompt prepared.
- 2026-10-24: venv fixed, dependencies installed, NLLB and E5 downloaded and verified offline.
- 2026-10-24: Nine spec ambiguities settled. config.py, schemas.py, errors.py, translation.py, embedding.py written and verified.
- 2026-10-24: Stress test, Shona->English test and query-translation comparison run. Six spec changes proposed.
- 2026-10-24: Status doc docs/07-MODEL-SERVICE-STATUS.md written for the team.
## Session update 2026-10-24 (larger dev evaluation)
- [x] make_dev_data.py and run_dev_eval.py written (research/evaluation/). Synthetic dev set, unreviewed.
- [x] Retrieval run: C 0.42, B 0.89, A 0.96 (inflated), D 0.94 MRR@10. Translate-first confirmed.
- [x] Translation run: 40 segments, 151 s. Automatic checks gave 6 false alarms and missed unit/meaning errors. Details in docs/07 section 10.
- [ ] Patch harness with reference query pipeline (normalise, translate, clean) and re-run retrieval (v2)
- [ ] Shona speaker reviews translation_review.csv, dev_queries.jsonl, dev_glossary_starter.jsonl
- [ ] Team decides change #9 (English beside Shona for safety text; phrase bank later)
- [ ] Pick a licensed/open catalogue (see Phase 6)
- [ ] Step 4: pytest suite
- Log 2026-10-24: routes verified over HTTP; dev evaluation run; changes 4 (refined), 7, 8, 9 proposed.
