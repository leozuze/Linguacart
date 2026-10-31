"""Direct Shona query vs translated English query, plus sentence splitting.

Run from model-service/:
  PYTHONUTF8=1 python ../research/evaluation/query_translation_test.py
"""
import os
import re
import sys
import time
from pathlib import Path

os.environ.setdefault("HF_HUB_OFFLINE", "1")
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "model-service"))

import torch  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.embedding import Embedder  # noqa: E402
from app.translation import Translator  # noqa: E402

s = get_settings()
print(f"torch threads: {torch.get_num_threads()} of {os.cpu_count()} logical cores")

tr = Translator(s.translation_model, s.device, s.max_input_tokens,
                s.max_new_tokens, s.num_beams, s.batch_size)
tr.load(s.source_language)
em = Embedder(s.embedding_model, s.device, s.max_input_tokens, s.batch_size)
em.load()

# Same products and queries as stress_test.py so the numbers are comparable.
# Shona queries were written by Claude: a Shona speaker must review them.
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
QUERIES = [
    ("munyu", "p_salt"), ("shuga", "p_sugar"), ("sukari", "p_sugar"),
    ("mafuta ekubika", "p_oil"), ("chibage", "p_maize"), ("mupunga", "p_rice"),
    ("foni", "p_phone"), ("tauro", "p_towel"),
]
STOP = {"the", "a", "an"}


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def tokens(text):
    return set(re.findall(r"[a-z0-9]+", text.lower()))


def rank_of(expected, scores):
    order = sorted(range(len(pids)), key=lambda i: -scores[i])
    return [pids[i] for i in order].index(expected) + 1


pids = list(PRODUCTS)
pvecs = em.embed([("passage", PRODUCTS[p]) for p in pids])
ptoks = [tokens(PRODUCTS[p]) for p in pids]

print("\n--- 1. Retrieval: direct Shona vs translated English query ---")
t0 = time.perf_counter()
en_queries = tr.translate([q for q, _ in QUERIES], "sna_Latn", "eng_Latn")
dt = time.perf_counter() - t0
print(f"translated {len(QUERIES)} queries in {dt:.1f}s ({dt / len(QUERIES):.1f}s each)\n")

c_vecs = em.embed([("query", q) for q, _ in QUERIES])      # C: direct Shona
b_vecs = em.embed([("query", e) for e in en_queries])      # B: translate, then E5

rows = []
for (q, exp), en, cv, bv in zip(QUERIES, en_queries, c_vecs, b_vecs):
    c_sc = [dot(cv, pv) for pv in pvecs]
    b_sc = [dot(bv, pv) for pv in pvecs]
    qt = tokens(en) - STOP
    # Toy hybrid: +0.3 when any English query word appears in the product text.
    d_sc = [b + (0.3 if qt & pt else 0.0) for b, pt in zip(b_sc, ptoks)]
    rows.append((q, en, exp, rank_of(exp, c_sc), rank_of(exp, b_sc), rank_of(exp, d_sc)))

print(f"{'query':16}{'English':18}{'expected':10}{'C':>4}{'B':>4}{'B+lex':>7}")
for q, en, exp, c, b, d in rows:
    print(f"{q:16}{en:18}{exp:10}{c:>4}{b:>4}{d:>7}")


def summarise(name, col):
    ranks = [r[col] for r in rows]
    top1 = sum(1 for r in ranks if r == 1) / len(ranks)
    mrr = sum(1 / r for r in ranks) / len(ranks)
    print(f"{name:26} top1={top1:.2f}  MRR={mrr:.2f}")


print()
summarise("C  direct Shona + E5", 3)
summarise("B  translate -> E5", 4)
summarise("B + toy lexical", 5)

print("\n--- 2. Whole paragraph vs sentence-by-sentence ---")
para = ("This iodized table salt is ideal for everyday cooking. It is finely "
        "ground, free flowing and fortified with iodine. Store in a cool, dry place.")
SENT = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9])")
t0 = time.perf_counter()
whole = tr.translate([para], "eng_Latn", "sna_Latn")[0]
t_whole = time.perf_counter() - t0
parts = SENT.split(para)
t0 = time.perf_counter()
pieces = tr.translate(parts, "eng_Latn", "sna_Latn")
t_split = time.perf_counter() - t0
print(f"whole paragraph ({t_whole:.1f}s):\n  {whole}\n")
print(f"split into {len(parts)} sentences ({t_split:.1f}s):")
for a, b in zip(parts, pieces):
    print(f"  {a}\n  -> {b}")
