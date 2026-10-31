"""NLLB baseline on FLORES-200 devtest (general text, CC BY-SA 4.0) via the RUNNING service.

Usage (from model-service/):
  python ../research/evaluation/flores_baseline.py PATH_TO_EXTRACTED_FLORES_DIR [n_sentences=100]
Needs: pip install sacrebleu
chrF++ is a sanity baseline and a fine-tuning gate. It says nothing about product text.
NEVER train on FLORES dev/devtest.
"""
import sys
import time
from pathlib import Path

import httpx
from sacrebleu.metrics import CHRF

HERE = Path(__file__).resolve().parent
if len(sys.argv) < 2:
    sys.exit(__doc__)
root = Path(sys.argv[1])
n = int(sys.argv[2]) if len(sys.argv) > 2 else 100


def read(lang):
    hits = sorted(root.rglob(f"{lang}.devtest"))
    if not hits:
        sys.exit(f"{lang}.devtest not found under {root}")
    return hits[0].read_text(encoding="utf-8").splitlines()[:n]


en, sn = read("eng_Latn"), read("sna_Latn")
client = httpx.Client(base_url="http://127.0.0.1:8001", timeout=1800.0)


def translate(texts, src, tgt, chunk=8):
    out, t0 = [], time.perf_counter()
    for i in range(0, len(texts), chunk):
        r = client.post("/translate", json={"sourceLanguage": src, "targetLanguage": tgt,
                                            "texts": texts[i:i + chunk]})
        r.raise_for_status()
        out += r.json()["translations"]
        print(f"  {src}->{tgt}: {len(out)}/{len(texts)}", end="\r", flush=True)
    print()
    return out, time.perf_counter() - t0


chrf = CHRF(word_order=2)  # word_order=2 gives chrF++
for src, tgt, sources, refs in [("eng_Latn", "sna_Latn", en, sn), ("sna_Latn", "eng_Latn", sn, en)]:
    hyp, secs = translate(sources, src, tgt)
    print(f"{src} -> {tgt}: {chrf.corpus_score(hyp, [refs])}   ({secs:.0f}s, {len(sources)} sentences)")
    (HERE / f"flores_{src[:3]}_{tgt[:3]}_hyp.txt").write_text("\n".join(hyp) + "\n", encoding="utf-8")
