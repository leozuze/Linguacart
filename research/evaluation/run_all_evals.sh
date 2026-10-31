#!/usr/bin/env bash
# Run every evaluation against the RUNNING service. From model-service/ with the venv active.
set -u
export PYTHONUTF8=1
EV=../research/evaluation
E=../research/data/external/esci-data/shopping_queries_dataset
STAMP=$(date +%Y%m%d_%H%M)
mkdir -p "$EV/runs"

if ! curl -sf localhost:8001/health | grep -q '"status":"ok"'; then
  echo "Service not ready. In another window run:"
  echo "  cd /c/Projects/Linguacart/model-service && source .venv/Scripts/activate"
  echo "  PYTHONUTF8=1 HF_HUB_OFFLINE=1 uvicorn app.main:app --port 8001"
  exit 1
fi

run() {  # run NAME COMMAND...   (output saved to runs/STAMP_NAME.txt)
  local name=$1; shift
  echo; echo "=== $name  ($(date +%H:%M:%S)) ==="
  "$@" 2>&1 | tee "$EV/runs/${STAMP}_${name}.txt"
}

run dev_retrieval      python $EV/run_dev_eval.py retrieval
run dev_translation    python $EV/run_dev_eval.py translation
run esci_translation   python $EV/real_translation_test.py "$E/shopping_queries_dataset_products.parquet" 40
run esci_en_retrieval  env EVAL_CATALOGUE=data/catalogue/esci_catalogue.jsonl \
                           EVAL_QUERIES=data/queries/esci_queries_en.jsonl \
                           EVAL_GLOSSARY=none EVAL_TAG=_esci_en \
                       python $EV/run_dev_eval.py retrieval

if find ../research/data/external -name "sna_Latn.devtest" 2>/dev/null | grep -q .; then
  run flores           python $EV/flores_baseline.py ../research/data/external 100
else
  echo; echo "(skipping FLORES: data not downloaded)"
fi

echo; echo "################ DIGEST (paste this back) ################" | tee "$EV/runs/${STAMP}_digest.txt"
for f in "$EV"/runs/${STAMP}_*.txt; do
  case "$f" in *digest*) continue;; esac
  { echo; echo "##### $(basename "$f")"
    grep -E -A14 "^--- Overall|^--- MRR@10 by query type|^--- Summary|chrF" "$f"; } | tee -a "$EV/runs/${STAMP}_digest.txt"
done
