"""Extract candidate glossary terms from a catalogue JSONL for a Shona reviewer.

Usage (from model-service/):
  python ../research/evaluation/extract_glossary_candidates.py [catalogue.jsonl] [min_products] [out.csv]
Words kept if they appear in >= min_products products; two-word phrases need >= 2.
Priority order for the reviewer: safety, unit, colour, normal.
"""
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
src = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "data/catalogue/dev_catalogue.jsonl"
min_products = int(sys.argv[2]) if len(sys.argv) > 2 else 1
out = Path(sys.argv[3]) if len(sys.argv) > 3 else ROOT / f"data/glossary/glossary_candidates_{src.stem}.csv"

STOP = {"the", "and", "for", "with", "from", "that", "this", "are", "has", "not", "can", "all",
        "your", "you", "its", "will", "per", "pack", "set"}
SAFETY = {"milk", "egg", "eggs", "peanut", "peanuts", "nut", "nuts", "soy", "soya", "wheat", "gluten",
          "fish", "shellfish", "sesame", "celery", "mustard", "sulphite", "allergen", "contains",
          "traces", "warning", "caution", "danger", "flammable", "choking", "hazard", "children",
          "dose", "doctor", "refrigerated", "expiry", "poison", "toxic", "external", "eyes", "fire"}
UNITS = {"kg", "g", "mg", "ml", "litre", "litres", "liter", "inch", "inches", "cm", "mm", "pack",
         "count", "size", "pair", "piece", "pieces", "dozen", "tablets", "bars", "bags"}
COLOURS = {"black", "white", "red", "blue", "green", "yellow", "brown", "grey", "gray", "navy",
           "pink", "purple", "orange", "silver", "gold", "beige", "assorted"}

rows = [json.loads(x) for x in src.read_text(encoding="utf-8").splitlines() if x.strip()]
df, example, cats = Counter(), {}, defaultdict(Counter)
for p in rows:
    text = f'{p.get("title", "")}. {p.get("description", "")}'.lower()
    terms = set()
    for chunk in re.split(r"[.;:,|()/]+", text):
        words = re.findall(r"[a-z]+", chunk)
        for i, w in enumerate(words):
            if len(w) >= 2 and w not in STOP:
                terms.add(w)
                if i + 1 < len(words) and len(words[i + 1]) >= 2 and words[i + 1] not in STOP:
                    terms.add(f"{w} {words[i + 1]}")
    for t in terms:
        df[t] += 1
        example.setdefault(t, p.get("title", ""))
        cats[t][p.get("category", "")] += 1


def priority(term):
    ws = set(term.split())
    if ws & SAFETY:
        return "1-safety"
    if ws & UNITS:
        return "2-unit"
    if ws & COLOURS:
        return "3-colour"
    return "4-normal"


keep = [t for t, n in df.items() if n >= (min_products if " " not in t else max(2, min_products))]
keep.sort(key=lambda t: (priority(t), -df[t], t))
out.parent.mkdir(parents=True, exist_ok=True)
with out.open("w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(["english", "type", "priority", "products", "category", "example_title",
                "shona", "shona_variants_and_regions", "notes", "reviewer", "status"])
    for t in keep:
        w.writerow([t, "phrase" if " " in t else "word", priority(t), df[t],
                    cats[t].most_common(1)[0][0], example[t], "", "", "", "", "needs_translation"])
counts = Counter(priority(t) for t in keep)
print(f"{len(rows)} products -> {len(keep)} candidate terms {dict(sorted(counts.items()))}")
print(f"wrote {out}")
