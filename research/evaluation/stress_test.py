"""Stress test for the model service (real models, no HTTP).

Run from model-service/:  python ../research/evaluation/stress_test.py
"""
import json
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("HF_HUB_OFFLINE", "1")
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "model-service"))

from app.config import get_settings  # noqa: E402
from app.embedding import Embedder  # noqa: E402
from app.translation import Translator  # noqa: E402

SRC, TGT = "eng_Latn", "sna_Latn"
s = get_settings()
hard: dict[str, bool] = {}
report: dict = {
    "created_at": datetime.now(timezone.utc).isoformat(),
    "device": s.device,
}


def section(title: str) -> None:
    print("\n" + "=" * 72 + f"\n{title}\n" + "=" * 72)


def timed(fn, *a, **k):
    t0 = time.perf_counter()
    r = fn(*a, **k)
    return r, time.perf_counter() - t0


def median_time(fn, n: int = 3) -> float:
    return round(statistics.median(timed(fn)[1] for _ in range(n)), 2)


def dot(a, b) -> float:
    return sum(x * y for x, y in zip(a, b))


# ---------- load ----------
tr = Translator(s.translation_model, s.device, s.max_input_tokens,
                s.max_new_tokens, s.num_beams, s.batch_size)
_, t_tr = timed(tr.load, SRC)
em = Embedder(s.embedding_model, s.device, s.max_input_tokens, s.batch_size)
_, t_em = timed(em.load)
print(f"Loaded NLLB in {t_tr:.1f}s and E5 in {t_em:.1f}s (device={s.device})")
report["load_seconds"] = {"nllb": round(t_tr, 1), "e5": round(t_em, 1)}


def tx(texts):
    return tr.translate(texts, SRC, TGT)


def ntok(text: str) -> int:
    return len(tr._tokenizer(text)["input_ids"])


# ---------- 1. protected-field survival ----------
section("1. Protected-field survival (informational; Java enforces the real check)")
cases = [
    ("Tastic Long Grain Parboiled Rice, 2 kg - Pack of 2, Model TG-2000",
     ["Tastic", "2 kg", "TG-2000"]),
    ("Samsung Galaxy A15 5G, 128GB, Model SM-A156B",
     ["Samsung", "128GB", "SM-A156B", "5G"]),
    ("Price: $2.99 (was $4.50). Save 33%.", ["$2.99", "$4.50", "33%"]),
    ("Dettol Antiseptic Liquid 500ml, kills 99.9% of germs",
     ["Dettol", "500ml", "99.9%"]),
    ("Maize meal, 10 kg bag, 100% natural", ["10 kg", "100%"]),
    ("WARNING: Contains peanuts and tree nuts. Keep out of reach of children.",
     ["peanuts"]),
    ("Allergen information: Contains wheat, milk and soy.",
     ["wheat", "milk", "soy"]),
]
outs = tx([c[0] for c in cases])
hard["protected_count_matches"] = len(outs) == len(cases)
prot = []
for (src, tokens), out in zip(cases, outs):
    print(f"\nEN: {src}\nSN: {out}")
    row = {}
    for token in tokens:
        kept = token.lower() in out.lower()
        row[token] = kept
        print(f"   {'kept   ' if kept else 'CHANGED'}  {token}")
    prot.append({"en": src, "sn": out, "tokens": row})
report["protected"] = prot
print("\nNote: CHANGED allergen words are expected. Java must revert those fields.")

# ---------- 2. long text ----------
section("2. Long and multi-sentence text")
para = (
    "This iodized table salt is ideal for everyday cooking. It is finely ground, "
    "free flowing and fortified with iodine to support healthy thyroid function. "
    "Store in a cool, dry place away from direct sunlight. Suitable for the whole "
    "family. Packed in a resealable bag to keep the salt fresh for longer."
)
long_text = " ".join([para] * 8)
long_rows = []
for label, text in [("medium description", para), ("very long description", long_text)]:
    n = ntok(text)
    out, secs = timed(lambda: tx([text])[0])
    truncated = n > s.max_input_tokens
    print(f"\n[{label}] input tokens={n} (limit {s.max_input_tokens})"
          f"{'  -> TRUNCATED' if truncated else ''}, {secs:.1f}s")
    print(f"EN: {text[:200]}...")
    print(f"SN: {out}")
    long_rows.append({"label": label, "input_tokens": n, "truncated": truncated,
                      "seconds": round(secs, 1), "output": out})
report["long_text"] = long_rows

# ---------- 3. batch order ----------
section("3. Batch order (50 texts, batch size %d)" % s.batch_size)
batch = [f"SKU-{1000 + i}: red cotton shirt, size M, pack of 2" for i in range(50)]
outs, secs = timed(tx, batch)
hard["batch_length"] = len(outs) == len(batch)
markers = [f"SKU-{1000 + i}" for i in range(50)]
found = sum(1 for i, o in enumerate(outs) if markers[i] in o)
swapped = sum(
    1 for i, o in enumerate(outs)
    if any(markers[j] in o for j in range(50) if j != i)
)
hard["batch_order_no_swaps"] = swapped == 0
print(f"{len(outs)} outputs in {secs:.1f}s; own marker kept in {found}/50; "
      f"another item's marker found in {swapped} outputs (must be 0)")
print("Sample:", outs[0], "|", outs[49])
report["batch"] = {"seconds": round(secs, 1), "markers_kept": found, "swapped": swapped}

# ---------- 4. edge cases ----------
section("4. Edge cases")
edge = ["", "   ", "Hello", "!!!", "1234567890", "Café crème – naïve façade",
        "Line one.\nLine two.", "a " * 1500]
outs = tx(edge)
hard["edge_length"] = len(outs) == len(edge)
hard["edge_empty_unchanged"] = outs[0] == "" and outs[1] == "   "
for e, o in zip(edge, outs):
    print(f"{e[:40]!r:46} -> {o[:70]!r}")

# ---------- 5. embedding retrieval ----------
section("5. Embedding retrieval sanity (Shona query -> English product)")
PRODUCTS = {
    "p_salt": "Iodized table salt, 1 kg. Fine salt for cooking.",
    "p_sugar": "White refined sugar, 2 kg. Sweetener for tea and baking.",
    "p_oil": "Sunflower cooking oil, 2 litres. Vegetable oil for frying.",
    "p_maize": "Maize meal, 10 kg. White maize flour for sadza.",
    "p_rice": "Long grain rice, 5 kg.",
    "p_phone": "Smartphone 128GB with 6.5-inch display.",
    "p_towel": "Cotton bath towel, large.",
    "p_headphones": "Wireless bluetooth headphones with noise cancelling.",
    "p_cooker": "Stainless steel pressure cooker, 5 litre.",
    "p_powder": "Laundry washing powder, 3 kg.",
    "p_lamp": "LED desk lamp with adjustable arm.",
    "p_bucket": "Plastic water bucket, 20 litres.",
}
# Shona queries written by Claude: indicative only, a Shona speaker must review.
QUERIES = [
    ("munyu", "p_salt"),
    ("shuga", "p_sugar"),
    ("sukari", "p_sugar"),
    ("mafuta ekubika", "p_oil"),
    ("chibage", "p_maize"),
    ("mupunga", "p_rice"),
    ("foni", "p_phone"),
    ("tauro", "p_towel"),
]
pids = list(PRODUCTS)
pvecs = em.embed([("passage", PRODUCTS[p]) for p in pids])
qvecs = em.embed([("query", q) for q, _ in QUERIES])
dim = len(pvecs[0])
norms = [sum(x * x for x in v) ** 0.5 for v in pvecs + qvecs]
hard["embed_count"] = len(pvecs) == len(pids) and len(qvecs) == len(QUERIES)
hard["embed_normalised"] = all(abs(n - 1) < 1e-3 for n in norms)
print(f"dim={dim}, norms min/max = {min(norms):.4f}/{max(norms):.4f}\n")

ranks = []
rows = []
for (q, expected), qv in zip(QUERIES, qvecs):
    sims = [dot(qv, pv) for pv in pvecs]
    order = sorted(range(len(pids)), key=lambda i: -sims[i])
    ranked = [pids[i] for i in order]
    rank = ranked.index(expected) + 1
    ranks.append(rank)
    top3 = ", ".join(f"{pids[i]} {sims[i]:.3f}" for i in order[:3])
    print(f"{q:16} expected={expected:9} rank={rank}   top3: {top3}")
    rows.append({"query": q, "expected": expected, "rank": rank})
top1 = sum(1 for r in ranks if r == 1) / len(ranks)
mrr = sum(1 / r for r in ranks) / len(ranks)
print(f"\nTop-1 accuracy: {top1:.2f}   MRR: {mrr:.2f}   ({len(QUERIES)} queries, 12 products)")
report["retrieval"] = {"top1": round(top1, 2), "mrr": round(mrr, 2), "dim": dim, "rows": rows}

# ---------- 6. latency ----------
section("6. Latency (seconds, median, CPU)")
lat = {
    "translate_1_short_title": median_time(lambda: tx(["Cooking oil"])),
    "translate_1_description": median_time(lambda: tx([para]), n=2),
    "translate_16_titles": median_time(lambda: tx(batch[:16]), n=2),
    "embed_1_query": median_time(lambda: em.embed([("query", "munyu")])),
    "embed_12_passages": median_time(
        lambda: em.embed([("passage", PRODUCTS[p]) for p in pids]), n=2),
}
for k, v in lat.items():
    print(f"{k:28} {v}")
report["latency_seconds"] = lat

# ---------- summary ----------
section("SUMMARY (hard checks)")
for k, v in hard.items():
    print(f"{'PASS' if v else 'FAIL'}  {k}")
report["hard_checks"] = hard
out_path = HERE / "stress_test_results.json"
out_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
print(f"\nSaved {out_path}")
sys.exit(0 if all(hard.values()) else 1)
