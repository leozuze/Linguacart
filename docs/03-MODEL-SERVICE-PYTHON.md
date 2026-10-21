# LinguaCart — Python Model Service Specification

## Purpose

The Python model service performs neural inference only.

It is called by the Java backend. It does not communicate directly with the browser extension.

## Technology

- Python 3.11+
- FastAPI
- Uvicorn
- PyTorch
- Transformers
- Pydantic

## Models

### Translation model

```text
facebook/nllb-200-distilled-600M
```

Language codes:

```text
English: eng_Latn
Shona:   sna_Latn
```

### Embedding model

```text
intfloat/multilingual-e5-base
```

Input format:

```text
query: <shona query>
passage: <english product text>
```

## API endpoints

### `GET /health`

Response:

```json
{
  "status": "ok",
  "translation_model": "loaded",
  "embedding_model": "loaded"
}
```

### `POST /translate`

Request:

```json
{
  "sourceLanguage": "eng_Latn",
  "targetLanguage": "sna_Latn",
  "texts": [
    "Iodized table salt, 1 kg",
    "Iodized table salt for cooking."
  ]
}
```

Response:

```json
{
  "translations": [
    "Munyu weTafura unemhodzi, 1 kg",
    "Shona description"
  ]
}
```

### `POST /embed`

Request:

```json
{
  "inputs": [
    {
      "type": "query",
      "text": "munyu"
    },
    {
      "type": "passage",
      "text": "Iodized table salt, 1 kg. Iodized table salt for cooking."
    }
  ]
}
```

Response:

```json
{
  "embeddings": [
    [0.01, 0.22],
    [0.03, 0.19]
  ]
}
```

## Service rules

- Return vectors as JSON arrays of floats.
- Use `query:` prefix for search queries.
- Use `passage:` prefix for product text.
- Do not cache translations in this service; Java owns caching.
- Do not apply glossaries here.
- Do not rank products here.
- Do not validate protected fields here.
- Return clear HTTP errors if a model is unavailable.

## Local development

```bash
cd model-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8001
```

## Environment variables

```text
TRANSLATION_MODEL=facebook/nllb-200-distilled-600M
EMBEDDING_MODEL=intfloat/multilingual-e5-base
MODEL_SERVICE_PORT=8001
DEVICE=cpu
```

## First implementation target

1. Load both models.
2. Expose `/health`.
3. Translate one English product title to Shona.
4. Embed one Shona query.
5. Embed one English product passage.
6. Return both vectors to Java.
