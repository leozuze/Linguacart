"""Larger dev evaluation over HTTP against the RUNNING model service.

Retrieval:   Shona/English queries over a synthetic catalogue. Systems:
             C direct Shona + E5 | B translate query + E5 | A glossary + BM25 | D hybrid
             Metrics (docs/05): Recall@5, Precision@5, MRR@10, nDCG@5.
Translation: product-page segments with automatic safety checks, plus a CSV for
             Shona-speaker review (adequacy, fluency, terminology).

Run from model-service/ with the service started on :8001:
  PYTHONUTF8=1 python ../research/evaluation/run_dev_eval.py [retrieval|translation|all]
"""
import csv
import json
import math
import os
import re
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

import httpx

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent  # research/
BASE = os.environ.get("MODEL_SERVICE_URL", "http://127.0.0.1:8001")
MODE = sys.argv[1] if len(sys.argv) > 1 else "all"
W_VEC, W_LEX = 0.6, 0.4  # hybrid weights (system D); arbitrary starting point

client = httpx.Client(base_url=BASE, timeout=1800.0)
try:
    health = client.get("/health").json()
except Exception as exc:
    sys.exit(f"Cannot reach {BASE}: {exc}\nStart it: uvicorn app.main:app --port 8001")
if health.get("status") != "ok":
    sys.exit(f"Service not healthy: {health}")


def load_jsonl(path):
    return [json.loads(x) for x in Path(path).read_text(encoding="utf-8").splitlines() if x.strip()]


def api_translate(texts, src, tgt, chunk=8):
    out, secs = [], 0.0
    for i in range(0, len(texts), chunk):
        t0 = time.perf_counter()
        r = client.post("/translate", json={"sourceLanguage": src, "targetLanguage": tgt,
                                            "texts": texts[i:i + chunk]})
        r.raise_for_status()
        secs += time.perf_counter() - t0
        out.extend(r.json()["translations"])
        print(f"  translated {min(i + chunk, len(texts))}/{len(texts)}", end="\r", flush=True)
    print()
    return out, secs


def api_embed(items, chunk=32):
    out = []
    for i in range(0, len(items), chunk):
        r = client.post("/embed", json={"inputs": [{"type": k, "text": t} for k, t in items[i:i + chunk]]})
        r.raise_for_status()
        out.extend(r.json()["embeddings"])
    return out


def save(key, data):
    p = HERE / "dev_eval_results.json"
    cur = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    cur[key] = data
    p.write_text(json.dumps(cur, indent=2, ensure_ascii=False), encoding="utf-8")


# ----------------------------------------------------------------- text helpers
STOP = {"the", "a", "an", "of", "for", "and", "with", "to", "in", "is", "are", "it", "on",
        "at", "by", "or", "from", "this", "that", "your", "you", "can", "be"}
UNIT_NORM = {"litre": "l", "litres": "l", "liter": "l", "liters": "l"}
QTY = re.compile(r"(\d+(?:\.\d+)?)\s*(kg|g|ml|litres?|liters?|l|gb|mah|cm)\b")
SHONA_PREFIXES = ("dze", "ye", "we", "re", "ze", "ne")


def stem(t):
    if len(t) > 5 and t.endswith("ing"):
        return t[:-3]
    if len(t) > 3 and t.endswith("s") and not t.endswith("ss"):
        return t[:-1]
    return t


def tokens(text):
    """Lowercase words (stemmed, no stopwords) plus normalised quantities like 1kg, 2l."""
    t = text.lower()
    qty = [m.group(1) + UNIT_NORM.get(m.group(2), m.group(2)) for m in QTY.finditer(t)]
    t = QTY.sub(" ", t)
    return [stem(w) for w in re.findall(r"[a-z0-9]+", t) if w not in STOP] + qty


def strip_prefix(tok, vocab):
    """Crude Shona prefix handling: 'yesamsung' -> 'samsung' if that is a catalogue word."""
    if tok in vocab:
        return tok
    for p in SHONA_PREFIXES:
        if tok.startswith(p) and len(tok) - len(p) >= 3 and tok[len(p):] in vocab:
            return tok[len(p):]
    return tok


class BM25:
    def __init__(self, docs, k1=1.5, b=0.75):
        self.docs, self.N, self.k1, self.b = docs, len(docs), k1, b
        self.avg = sum(map(len, docs)) / self.N
        self.df, self.tf = defaultdict(int), []
        for d in docs:
            counts = defaultdict(int)
            for t in d:
                counts[t] += 1
            self.tf.append(counts)
            for t in counts:
                self.df[t] += 1

    def score(self, q_tokens):
        scores = [0.0] * self.N
        for t in set(q_tokens):
            if t not in self.df:
                continue
            idf = math.log(1 + (self.N - self.df[t] + 0.5) / (self.df[t] + 0.5))
            for i in range(self.N):
                f = self.tf[i].get(t, 0)
                if f:
                    dl = len(self.docs[i])
                    scores[i] += idf * f * (self.k1 + 1) / (f + self.k1 * (1 - self.b + self.b * dl / self.avg))
        return scores


def minmax(xs):
    lo, hi = min(xs), max(xs)
    return [0.0] * len(xs) if hi - lo < 1e-9 else [(x - lo) / (hi - lo) for x in xs]


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def metrics(ranked, rel):
    relevant = {p for p, g in rel.items() if g >= 2} or set(rel)
    top5 = ranked[:5]
    hits = len(relevant & set(top5))
    mrr = next((1 / i for i, p in enumerate(ranked[:10], 1) if p in relevant), 0.0)
    dcg = sum((2 ** rel.get(p, 0) - 1) / math.log2(i + 1) for i, p in enumerate(top5, 1))
    ideal = sorted(rel.values(), reverse=True)[:5]
    idcg = sum((2 ** g - 1) / math.log2(i + 1) for i, g in enumerate(ideal, 1))
    return {"R@5": hits / len(relevant), "P@5": hits / 5, "MRR@10": mrr,
            "nDCG@5": dcg / idcg if idcg else 0.0}


def first_rel_rank(ranked, rel):
    relevant = {p for p, g in rel.items() if g >= 2} or set(rel)
    return next((i for i, p in enumerate(ranked, 1) if p in relevant), None)


# ------------------------------------------------------------------- retrieval
def clean_translation(text):
    """Reference clean-up of a translated query (the Java dev should port this):
    lowercase, drop a leading article, strip trailing punctuation."""
    t = re.sub(r"^(the|a|an)\s+", "", text.strip().lower())
    return t.strip(" .,!?;:")


def run_retrieval():
    cat = load_jsonl(ROOT / "data/catalogue/dev_catalogue.jsonl")
    queries = load_jsonl(ROOT / "data/queries/dev_queries.jsonl")
    gloss = sorted(((g["shona"], g["english"]) for g in
                    load_jsonl(ROOT / "data/glossary/dev_glossary_starter.jsonl")),
                   key=lambda x: -len(x[0]))
    ids = [p["id"] for p in cat]
    title = {p["id"]: p["title"] for p in cat}

    def doc_text(p):
        return (f'{p["title"]}. {p["description"]}. Brand: {p["brand"]}. '
                f'Category: {p["category"]}. Size: {p["quantity"]} {p["unit"]}.')

    doc_tokens = [tokens(doc_text(p)) for p in cat]
    vocab = {t for d in doc_tokens for t in d}
    bm25 = BM25(doc_tokens)

    def norm(q):
        return " ".join(re.sub(r"[^a-z0-9\s]", " ", q.lower()).split())

    def gloss_english(query):
        rest, found = f" {norm(query)} ", []
        for sn, en in gloss:
            if " " in sn and f" {sn} " in rest:
                found.append(en)
                rest = rest.replace(f" {sn} ", " ")
        for tok in rest.split():
            for sn, en in gloss:
                if " " not in sn and (tok == sn or (len(sn) >= 4 and sn in tok)):
                    found.append(en)
                    break
        return found

    print(f"\nCatalogue: {len(cat)} products | queries: {len(queries)} | glossary: {len(gloss)} terms")
    print("Embedding catalogue...")
    t0 = time.perf_counter()
    pvecs = api_embed([("passage", doc_text(p)) for p in cat])
    print(f"  {len(pvecs)} passages in {time.perf_counter() - t0:.1f}s")

    qtexts = [q["query"] for q in queries]
    print("Translating queries (Shona -> English)...")
    # Reference query pipeline: normalise (lowercase, no punctuation) -> translate -> clean.
    en_raw, t_secs = api_translate([norm(q) for q in qtexts], "sna_Latn", "eng_Latn", chunk=16)
    en = [clean_translation(e) for e in en_raw]
    print(f"  {t_secs:.1f}s total, {t_secs / len(qtexts):.1f}s per query")
    cvecs = api_embed([("query", q) for q in qtexts])
    tvecs = api_embed([("query", e) for e in en])

    systems = ["C direct Shona+E5", "B translate+E5", "A glossary+BM25", "D hybrid"]
    per = {s: [] for s in systems}
    detail = []
    for q, e, cv, tv in zip(queries, en, cvecs, tvecs):
        rel = q["relevance"]
        g_terms = gloss_english(q["query"])
        orig = [strip_prefix(t, vocab) for t in tokens(q["query"])]
        a_tokens = tokens(" ".join(g_terms)) + orig
        d_tokens = a_tokens + tokens(e)

        def rank(scores):
            return [ids[i] for i in sorted(range(len(ids)), key=lambda i: -scores[i])]

        c_sc = [dot(cv, pv) for pv in pvecs]
        b_sc = [dot(tv, pv) for pv in pvecs]
        a_sc = bm25.score(a_tokens)
        d_sc = [W_VEC * v + W_LEX * l for v, l in zip(minmax(b_sc), minmax(bm25.score(d_tokens)))]
        ranks = {systems[0]: rank(c_sc), systems[1]: rank(b_sc),
                 systems[2]: rank(a_sc) if max(a_sc) > 0 else [], systems[3]: rank(d_sc)}
        for s in systems:
            per[s].append((q, metrics(ranks[s], rel)))
        detail.append({"queryId": q["queryId"], "query": q["query"], "type": q["type"],
                       "english": e, "glossary": g_terms,
                       "top3": {s: ranks[s][:3] for s in systems},
                       "first_relevant_rank": {s: first_rel_rank(ranks[s], rel) for s in systems}})

    print("\n--- What NLLB made of each query (check these first) ---")
    print(f"{'id':5}{'type':11}{'query':30}{'-> English':26}glossary")
    for d in detail:
        print(f"{d['queryId']:5}{d['type']:11}{d['query']:30}{d['english']:26}{' | '.join(d['glossary'])}")

    def avg(rows, key):
        return statistics.mean(m[key] for _, m in rows) if rows else 0.0

    print("\n--- Overall ---")
    print(f"{'system':22}{'R@5':>7}{'P@5':>7}{'MRR@10':>9}{'nDCG@5':>9}")
    overall = {}
    for s in systems:
        overall[s] = {k: round(avg(per[s], k), 3) for k in ("R@5", "P@5", "MRR@10", "nDCG@5")}
        o = overall[s]
        print(f"{s:22}{o['R@5']:>7.2f}{o['P@5']:>7.2f}{o['MRR@10']:>9.2f}{o['nDCG@5']:>9.2f}")
    print("(P@5 is capped at |relevant|/5, so it is low by design when few products are relevant.)")

    print("\n--- MRR@10 by query type ---")
    types = sorted({q["type"] for q in queries})
    print(f"{'type':12}{'n':>3}" + "".join(f"{s.split()[0]:>7}" for s in systems))
    by_type = {}
    for ty in types:
        row = {s: [r for r in per[s] if r[0]["type"] == ty] for s in systems}
        n = len(row[systems[0]])
        by_type[ty] = {s: round(avg(row[s], "MRR@10"), 2) for s in systems}
        print(f"{ty:12}{n:>3}" + "".join(f"{by_type[ty][s]:>7.2f}" for s in systems))

    print("\n--- System D failures (first relevant product not in top 3) ---")
    shown = 0
    for d in detail:
        r = d["first_relevant_rank"][systems[3]]
        if r is None or r > 3:
            shown += 1
            tops = "; ".join(title[p] for p in d["top3"][systems[3]])
            print(f"{d['queryId']} {d['query']!r} [{d['type']}] EN={d['english']!r} rank={r}\n    top3: {tops}")
    if not shown:
        print("none")

    save("retrieval", {"overall": overall, "by_type_mrr": by_type, "queries": detail,
                       "weights": {"vector": W_VEC, "lexical": W_LEX},
                       "query_translate_seconds": round(t_secs, 1)})


# ----------------------------------------------------------------- translation
END = re.compile(r"[.!?](?:\s|$)")
NUM = re.compile(r"\d+(?:[.,:]\d+)*")
MODEL = re.compile(r"\b(?=[A-Za-z0-9-]*\d)(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9-]{3,}\b")
BRANDS = ["Tastic", "Samsung", "Nike", "Air Max", "Dettol"]
HARD = {"empty", "number_lost", "model_changed", "brand_changed", "sentence_dropped"}


def has_num(n, out):
    return re.search(r"(?<!\d)" + re.escape(n) + r"(?!\d)", out) is not None


def check_case(src, out):
    flags = []
    if src.strip() and not out.strip():
        flags.append("empty")
    if any(not has_num(n, out) for n in NUM.findall(src)):
        flags.append("number_lost")
    if any(m.lower() not in out.lower() for m in MODEL.findall(src)):
        flags.append("model_changed")
    if any(b.lower() in src.lower() and b.lower() not in out.lower() for b in BRANDS):
        flags.append("brand_changed")
    if len(END.findall(out)) < len(END.findall(src)):
        flags.append("sentence_dropped")  # may be a false alarm if punctuation changed
    words = out.lower().split()
    grams = [" ".join(words[i:i + 3]) for i in range(len(words) - 2)]
    if any(grams.count(g) >= 2 for g in set(grams)):
        flags.append("repetition")
    ratio = len(out) / max(1, len(src))
    if len(src) >= 10 and not 0.5 <= ratio <= 2.5:
        flags.append("length_odd")
    skip = {w.lower() for b in BRANDS for w in b.split()} | STOP
    src_words = [w.lower() for w in re.findall(r"[A-Za-z]{4,}", src) if w.lower() not in skip]
    out_words = {w.lower() for w in re.findall(r"[A-Za-z]{4,}", out)}
    leftover = (sum(w in out_words for w in src_words) / len(src_words)) if src_words else 0.0
    if len(src_words) >= 4 and leftover > 0.35:
        flags.append("english_heavy")
    return flags, leftover


def run_translation():
    cases = load_jsonl(HERE / "dev_translation_cases.jsonl")
    print(f"\nTranslating {len(cases)} product-page segments (English -> Shona)...")
    outs, secs = api_translate([c["text"] for c in cases], "eng_Latn", "sna_Latn", chunk=8)
    rows = []
    for c, out in zip(cases, outs):
        flags, leftover = check_case(c["text"], out)
        rows.append({**c, "shona": out, "flags": flags, "leftover": round(leftover, 2)})

    print("\n--- All outputs ---")
    for r in rows:
        tag = "SAFETY " if r["safety_critical"] else ""
        print(f"[{r['id']}] {tag}{r['type']}  flags: {', '.join(r['flags']) or '-'}")
        print(f"   EN: {r['text']}\n   SN: {r['shona']}")

    clean = [r for r in rows if not set(r["flags"]) & HARD]
    safety = [r for r in rows if r["safety_critical"]]
    safety_clean = [r for r in safety if not set(r["flags"]) & HARD]
    flag_counts = defaultdict(int)
    for r in rows:
        for f in r["flags"]:
            flag_counts[f] += 1

    print("\n--- Summary ---")
    print(f"time: {secs:.0f}s total, {secs / len(rows):.1f}s per segment")
    print(f"no hard flags: {len(clean)}/{len(rows)}   (hard = {', '.join(sorted(HARD))})")
    print(f"safety-critical with no hard flags: {len(safety_clean)}/{len(safety)}")
    print(f"mean English leftover share: {statistics.mean(r['leftover'] for r in rows):.2f}")
    print("flag counts:", dict(flag_counts) or "none")
    print(f"{'type':13}{'n':>3}{'hard-flagged':>14}")
    for ty in sorted({r["type"] for r in rows}):
        sub = [r for r in rows if r["type"] == ty]
        print(f"{ty:13}{len(sub):>3}{sum(bool(set(r['flags']) & HARD) for r in sub):>14}")
    print("\nAutomatic checks cannot judge meaning. 'Save 33%' -> 'Chengetedza 33%' passes them.\n"
          "Give translation_review.csv to a Shona speaker for adequacy, fluency and terminology.")

    csv_path = HERE / "translation_review.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["id", "type", "safety_critical", "english", "shona", "auto_flags",
                    "adequacy_1_5", "fluency_1_5", "terminology_ok", "reviewer_notes"])
        for r in rows:
            w.writerow([r["id"], r["type"], r["safety_critical"], r["text"], r["shona"],
                        ";".join(r["flags"]), "", "", "", ""])
    print(f"Wrote {csv_path}")
    save("translation", {"seconds": round(secs, 1), "no_hard_flags": len(clean), "total": len(rows),
                         "safety_total": len(safety), "safety_clean": len(safety_clean),
                         "flag_counts": dict(flag_counts), "rows": rows})


if MODE in ("retrieval", "all"):
    run_retrieval()
if MODE in ("translation", "all"):
    run_translation()
