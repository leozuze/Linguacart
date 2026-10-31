# LinguaCart model service

Inference-only FastAPI service: NLLB translation (English <-> Shona) and multilingual-E5 embeddings.
Spec: `docs/03-MODEL-SERVICE-PYTHON.md`. Integration details: `docs/08-INTEGRATION-GUIDE.md`.
API reference: run the service and open http://127.0.0.1:8001/docs (or see `docs/model-service-openapi.json`).

## Quick start WITHOUT models (Java and extension development)

```bash
cd model-service
python -m venv .venv
source .venv/Scripts/activate        # Windows Git Bash. Linux/macOS: source .venv/bin/activate
pip install -r requirements-stub.txt
STUB_MODELS=true uvicorn app.main:app --port 8001
```

Starts instantly. Translations are fake (`[SN] <text>`), embeddings are hashed words.
Good for wiring, contracts and error handling. NOT for judging quality.

## Real models (about 3.5 GB download, about 25 s startup, CPU)

```bash
pip install -r requirements.txt
cp .env.example .env
PYTHONUTF8=1 uvicorn app.main:app --port 8001      # add HF_HUB_OFFLINE=1 once the models are cached
```

## Try it

```bash
curl -s localhost:8001/health
curl -s -X POST localhost:8001/translate -H "Content-Type: application/json" \
  -d '{"sourceLanguage":"eng_Latn","targetLanguage":"sna_Latn","texts":["Iodized table salt, 1 kg"]}'
curl -s -X POST localhost:8001/translate -H "Content-Type: application/json" \
  -d '{"sourceLanguage":"sna_Latn","targetLanguage":"eng_Latn","texts":["munyu"]}'
curl -s -X POST localhost:8001/embed -H "Content-Type: application/json" \
  -d '{"inputs":[{"type":"query","text":"salt"},{"type":"passage","text":"Iodized table salt, 1 kg."}]}'
```

## Settings (env vars or `.env`)

See `.env.example`: models, port, `DEVICE` (cpu), limits, `TORCH_THREADS`, `STUB_MODELS`.
