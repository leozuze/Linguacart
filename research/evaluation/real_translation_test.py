"""Translate REAL Amazon product text (from the ESCI subset) and run automatic checks.

Usage (from model-service/, service running):
  PYTHONUTF8=1 python ../research/evaluation/real_translation_test.py PRODUCTS.parquet [n_products=40] [seed=7]
Segments per product: title + up to 2 bullet points. Outputs start with esci_ (git-ignored).
Number-word stems for Shona are Claude's guess and need a Shona speaker's confirmation.
"""
import csv
import json
import random
import re
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

import httpx
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
if len(sys.argv) < 2:
    sys.exit(__doc__)
pr_path = sys.argv[1]
N = int(sys.argv[2]) if len(sys.argv) > 2 else 40
SEED = int(sys.argv[3]) if len(sys.argv) > 3 else 7

cat = [json.loads(x) for x in
       (ROOT / "data/catalogue/esci_catalogue.jsonl").read_text(encoding="utf-8").splitlines() if x.strip()]
random.seed(SEED)
ids = [p["id"] for p in random.sample(cat, min(N, len(cat)))]
try:
    pr = pd.read_parquet(pr_path, filters=[("product_locale", "==", "us"), ("product_id", "in", ids)])
except Exception as exc:
    print(f"filtered read failed ({exc}); reading whole file")
    pr = pd.read_parquet(pr_path)
    pr = pr[(pr["product_locale"] == "us") & pr["product_id"].isin(ids)]
pr = pr.drop_duplicates("product_id").set_index("product_id")


def clean(x):
    if x is None or (isinstance(x, float) and pd.isna(x)):
        return ""
    return re.sub(r"[ \t]+", " ", re.sub(r"<[^>]+>", " ", str(x))).strip()


segs = []
for pid in ids:
    if pid not in pr.index:
        continue
    row = pr.loc[pid]
    title = clean(row["product_title"])
    if title:
        segs.append((pid, "title", title[:300]))
    bullets = [b.strip() for b in clean(row["product_bullet_point"]).split("\n") if 25 <= len(b.strip()) <= 300]
    segs.extend((pid, "bullet", b) for b in bullets[:2])
print(f"{len(segs)} segments from {len(ids)} products")

client = httpx.Client(base_url="http://127.0.0.1:8001", timeout=1800.0)
texts = [s[2] for s in segs]
out, t0 = [], time.perf_counter()
for i in range(0, len(texts), 8):
    r = client.post("/translate", json={"sourceLanguage": "eng_Latn", "targetLanguage": "sna_Latn",
                                        "texts": texts[i:i + 8]})
    r.raise_for_status()
    out += r.json()["translations"]
    print(f"  translated {len(out)}/{len(texts)}", end="\r", flush=True)
secs = time.perf_counter() - t0
print()

END = re.compile(r"[.!?](?:\s|$)")
NUM = re.compile(r"\d+(?:[.,]\d+)*")
MODEL = re.compile(r"\b(?=[A-Za-z0-9-]*\d)(?=[A-Za-z0-9-]*[A-Za-z])[A-Za-z0-9-]{3,}\b")
UNIT = re.compile(r"\d\s*(inches|inch|cm|mm|oz|lbs|lb|kg|ml|gb|mah|ft)\b", re.I)
STOP = {"the", "and", "for", "with", "from", "that", "this", "your", "you", "are", "can", "will"}
# Guessed Shona numeral stems (for a speaker to confirm); digits written as words are NOT a failure.
STEMS = {2: ("viri", "piri", "biri"), 3: ("tatu",), 5: ("shanu",), 6: ("tanhatu",),
         7: ("nomwe",), 8: ("sere",), 9: ("pfumbamwe",), 10: ("gumi",)}
HARD = {"empty", "number_lost", "unit_changed", "model_changed", "currency_space", "repetition", "sentence_dropped"}


def has_num(n, text):
    return re.search(r"(?<!\d)" + re.escape(n) + r"(?!\d)", text) is not None


def check(src, sn):
    flags, low = [], sn.lower()
    if src.strip() and not sn.strip():
        flags.append("empty")
    for n in NUM.findall(src):
        if has_num(n, sn):
            continue
        try:
            k = int(float(n.replace(",", "")))
        except ValueError:
            k = None
        word_ok = (k in STEMS and any(s in low for s in STEMS[k])) or \
                  (k is not None and k % 10 == 0 and 20 <= k <= 90 and "makumi" in low)
        flags.append("number_as_word" if word_ok else "number_lost")
    for u in UNIT.findall(src):
        if not re.search(r"(?<![a-z])" + re.escape(u.lower()) + r"(?![a-z])", low):
            flags.append("unit_changed")
    if any(m.lower() not in low for m in MODEL.findall(src)):
        flags.append("model_changed")
    if re.search(r"\$\d", src) and re.search(r"\$\s+\d", sn):
        flags.append("currency_space")
    if len(END.findall(sn)) < len(END.findall(src)):
        flags.append("sentence_dropped")
    words = low.split()
    grams = [" ".join(words[i:i + 3]) for i in range(len(words) - 2)]
    if any(grams.count(g) >= 2 for g in set(grams)):
        flags.append("repetition")
    src_words = [w.lower() for w in re.findall(r"[A-Za-z]{4,}", src) if w.lower() not in STOP]
    out_words = {w.lower() for w in re.findall(r"[A-Za-z]{4,}", sn)}
    leftover = (sum(w in out_words for w in src_words) / len(src_words)) if src_words else 0.0
    if len(src_words) >= 4 and leftover > 0.5:
        flags.append("english_heavy")
    return sorted(set(flags)), leftover


rows = []
for (pid, kind, src), sn in zip(segs, out):
    flags, left = check(src, sn)
    rows.append({"product_id": pid, "type": kind, "english": src, "shona": sn,
                 "flags": flags, "leftover": left})

hard = [r for r in rows if set(r["flags"]) & HARD]
print(f"\n--- Hard-flagged segments ({len(hard)}) ---")
for r in hard:
    print(f"[{r['type']}] {', '.join(r['flags'])}\n   EN: {r['english'][:200]}\n   SN: {r['shona'][:200]}")
print("\n--- Sample of the rest ---")
random.seed(1)
rest = [r for r in rows if r not in hard]
for r in random.sample(rest, min(12, len(rest))):
    print(f"[{r['type']}] {', '.join(r['flags']) or '-'}\n   EN: {r['english'][:200]}\n   SN: {r['shona'][:200]}")

counts = defaultdict(int)
for r in rows:
    for f in r["flags"]:
        counts[f] += 1
print("\n--- Summary ---")
print(f"{len(rows)} segments in {secs:.0f}s ({secs / max(1, len(rows)):.1f}s each)")
print(f"hard-flagged: {len(hard)}/{len(rows)}")
print("flag counts:", dict(counts) or "none")
for kind in ("title", "bullet"):
    sub = [r for r in rows if r["type"] == kind]
    if sub:
        print(f"{kind:7} n={len(sub):3}  hard-flagged={sum(bool(set(r['flags']) & HARD) for r in sub):3}"
              f"  mean English leftover={statistics.mean(r['leftover'] for r in sub):.2f}")
print("Automatic checks cannot judge meaning. A Shona speaker must rate the review sheet.")

sheet = HERE / "esci_translation_review.csv"
with sheet.open("w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(["product_id", "type", "english", "shona", "auto_flags",
                "adequacy_1_5", "fluency_1_5", "terminology_ok", "reviewer_notes"])
    for r in rows:
        w.writerow([r["product_id"], r["type"], r["english"], r["shona"], ";".join(r["flags"]), "", "", "", ""])
print(f"wrote {sheet}")
