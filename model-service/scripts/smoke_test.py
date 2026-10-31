"""Smoke test for a running model service (real or stub).

Usage: python scripts/smoke_test.py [BASE_URL] [API_KEY]
Default BASE_URL is http://127.0.0.1:8001. Needs only: pip install httpx
"""
import math
import sys

import httpx

base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8001"
headers = {"X-API-Key": sys.argv[2]} if len(sys.argv) > 2 else {}
c = httpx.Client(base_url=base, headers=headers, timeout=180)
fails = 0


def check(name, ok, extra=""):
    global fails
    print(("PASS  " if ok else "FAIL  ") + name + (f"   {extra}" if extra else ""))
    fails += 0 if ok else 1


try:
    h = c.get("/health").json()
except Exception as exc:
    sys.exit(f"Cannot reach {base}: {exc}")
check("health ok", h.get("status") == "ok", str(h))

r = c.post("/translate", json={"sourceLanguage": "eng_Latn", "targetLanguage": "sna_Latn",
                               "texts": ["Cooking oil", ""]})
t = r.json().get("translations", []) if r.status_code == 200 else []
check("translate eng->sna keeps count and order", len(t) == 2 and t[1] == "", f"{r.status_code} {t}")

r = c.post("/translate", json={"sourceLanguage": "sna_Latn", "targetLanguage": "eng_Latn", "texts": ["munyu"]})
check("translate sna->eng", r.status_code == 200 and len(r.json().get("translations", [])) == 1,
      f"{r.status_code} {r.text[:80]}")

r = c.post("/embed", json={"inputs": [{"type": "query", "text": "salt"},
                                      {"type": "passage", "text": "Iodized table salt, 1 kg."}]})
e = r.json().get("embeddings", []) if r.status_code == 200 else []
check("embed returns 2 unit vectors of 768 floats",
      len(e) == 2 and all(len(v) == 768 and abs(math.sqrt(sum(x * x for x in v)) - 1) < 1e-3 for v in e))

r = c.post("/translate", json={"sourceLanguage": "eng_Latn", "targetLanguage": "mar_Deva", "texts": ["x"]})
check("bad language pair gives 422 with detail", r.status_code == 422 and "detail" in r.json())
r = c.post("/embed", json={"inputs": [{"type": "document", "text": "x"}]})
check("bad embed type gives 422", r.status_code == 422)

print("\nALL GOOD" if not fails else f"\n{fails} check(s) FAILED")
sys.exit(1 if fails else 0)
