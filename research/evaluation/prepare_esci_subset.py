"""Build a REAL-data research subset from the Amazon Shopping Queries (ESCI) dataset.

Licence: Apache-2.0 (github.com/amazon-science/esci-data). The team must still confirm that
using Amazon-released data fits the "no Amazon catalogue data" rule before committing it.

Usage (from model-service/):
  python ../research/evaluation/prepare_esci_subset.py EXAMPLES.parquet PRODUCTS.parquet [n_queries=80] [seed=42]
Writes:
  research/data/catalogue/esci_catalogue.jsonl       products (doc-05 fields; blanks where ESCI has none)
  research/data/queries/esci_queries_en.jsonl        English queries + graded relevance (E=3, S=1)
  research/data/queries/esci_queries_for_shona.csv   sheet for Shona speakers to fill in
"""
import csv
import json
import re
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
if len(sys.argv) < 3:
    sys.exit(__doc__)
ex_path, pr_path = sys.argv[1], sys.argv[2]
N = int(sys.argv[3]) if len(sys.argv) > 3 else 80
SEED = int(sys.argv[4]) if len(sys.argv) > 4 else 42
GRADE = {"E": 3, "S": 1}  # Exact=3, Substitute=1; Complement and Irrelevant count as 0

ex = pd.read_parquet(ex_path)
ex = ex[(ex["large_version"] == 1) & (ex["product_locale"] == "us") & (ex["split"] == "test")].copy()
ex["query"] = ex["query"].astype(str).str.lower().str.strip()
ex["label"] = ex["esci_label"].astype(str).str[0].str.upper()  # works for 'E' and 'Exact'

stats = (ex.groupby(["query_id", "query"])
           .agg(n=("example_id", "size"), exact=("label", lambda s: int((s == "E").sum())))
           .reset_index())
simple = stats["query"].map(
    lambda q: 1 <= len(q.split()) <= 3 and all(re.fullmatch(r"[a-z]+", w) for w in q.split()))
import os
KW = {k.strip() for k in os.environ.get("ESCI_KEYWORDS", "").split(",") if k.strip()}
if KW:
    simple = simple & stats["query"].map(
        lambda q: any(w in KW or w.rstrip("s") in KW for w in q.split()))
stats = stats[simple & (stats["n"] >= 15) & (stats["exact"] >= 3)].drop_duplicates("query")
chosen = stats.sample(n=min(N, len(stats)), random_state=SEED).sort_values("query")
print(f"{len(stats)} eligible queries; using {len(chosen)}")

ex = ex[ex["query_id"].isin(chosen["query_id"])]
ids = ex["product_id"].unique().tolist()
try:
    pr = pd.read_parquet(pr_path, filters=[("product_locale", "==", "us"), ("product_id", "in", ids)])
except Exception as exc:
    print(f"filtered read failed ({exc}); reading the whole file (needs several GB of RAM)")
    pr = pd.read_parquet(pr_path)
    pr = pr[(pr["product_locale"] == "us") & pr["product_id"].isin(ids)]
pr = pr.drop_duplicates("product_id")


def txt(x):
    if x is None or (isinstance(x, float) and pd.isna(x)):
        return ""
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", str(x))).strip()


catalogue = []
for r in pr.itertuples(index=False):
    desc = (txt(r.product_bullet_point) + " " + txt(r.product_description)).strip()[:700]
    catalogue.append({"id": r.product_id, "title": txt(r.product_title), "description": desc,
                      "brand": txt(r.product_brand), "category": "", "price": "", "currency": "",
                      "quantity": "", "unit": "", "source": "ESCI-us (Apache-2.0)"})
have = {c["id"] for c in catalogue}

queries = []
for qid, qtext in zip(chosen["query_id"], chosen["query"]):
    sub = ex[ex["query_id"] == qid]
    rel = {r.product_id: GRADE[r.label] for r in sub.itertuples()
           if r.label in GRADE and r.product_id in have}
    if rel:
        queries.append({"queryId": f"e{qid}", "query": qtext, "type": f"{len(qtext.split())}word",
                        "relevance": rel, "annotator": "ESCI", "status": "amazon-labelled"})


def write_jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")


write_jsonl(ROOT / "data/catalogue/esci_catalogue.jsonl", catalogue)
write_jsonl(ROOT / "data/queries/esci_queries_en.jsonl", queries)
sheet = ROOT / "data/queries/esci_queries_for_shona.csv"
with sheet.open("w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(["queryId", "english_query", "shona_query", "shona_alternative",
                "region_or_dialect", "speaker_id", "skip_or_notes"])
    for q in queries:
        w.writerow([q["queryId"], q["query"], "", "", "", "", ""])

print(f"catalogue: {len(catalogue)} products | queries: {len(queries)}")
print("sample queries:", ", ".join(q["query"] for q in queries[:25]))
print(f"wrote {ROOT / 'data/catalogue/esci_catalogue.jsonl'}")
print(f"wrote {ROOT / 'data/queries/esci_queries_en.jsonl'}")
print(f"wrote {sheet}")
