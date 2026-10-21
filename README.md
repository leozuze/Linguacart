# LinguaCart

LinguaCart is a browser assistant that helps Shona speakers use English e-commerce websites.

It has two core capabilities:

1. **English to Shona page translation** — translates selected product-page text into Shona.
2. **Shona product search** — accepts a Shona query and retrieves relevant products from a controlled, licensed English product catalogue.

LinguaCart assists browsing and interpretation. It does **not** replace the original website, handle checkout, or store marketplace catalogue data.

## Architecture

```text
Browser Extension (JavaScript)
        |
        v
Java Backend (Spring Boot)
        |
        |-- /translate --> Python model service --> NLLB
        |-- /embed ------> Python model service --> E5
        |-- /search
        |      |-- Glossary hash map
        |      |-- Inverted index
        |      |-- Vector search
        |      `-- Top-K min-heap
        `-- Protected-field checker
```

## Technology split

| Component | Technology | Responsibility |
|---|---|---|
| Browser extension | JavaScript, Chrome Manifest V3 | Amazon page extraction, toolbar, Shona panel, API calls |
| Java backend | Java 21, Spring Boot 3 | APIs, product normalisation, caching, glossary, search, ranking, protection |
| Python model service | Python, FastAPI | NLLB translation and E5 embeddings only |
| Research data | JSON/JSONL/CSV | Licensed catalogue, Shona queries, judgments, glossary |

## Models used in v1

| Task | Model | Purpose |
|---|---|---|
| English to Shona translation | `facebook/nllb-200-distilled-600M` | Translate product titles and descriptions |
| Shona query to English product retrieval | `intfloat/multilingual-e5-base` | Match Shona queries with English product embeddings |
| Exact keyword matching | Java inverted index | Match brands, model numbers, quantities, and exact terms |
| Ranking | Java top-K min-heap | Select the best K products |

## Core flows

### Page translation

```text
User opens Amazon product page
-> JavaScript extension extracts fields
-> Java backend receives product record
-> Java checks translation cache
-> Java sends text to Python model service
-> NLLB translates English to Shona
-> Java applies glossary corrections
-> Java validates protected fields
-> Extension displays Shona panel
```

### Shona product search

```text
User types Shona query
-> JavaScript extension sends query to Java backend
-> Java checks glossary
-> Java sends query to Python model service
-> E5 creates query embedding
-> Java compares query embedding with product embeddings
-> Java keeps top-K products using a min-heap
-> Java ranks results
-> Extension displays results in Shona
```

## Protected fields

The system must never silently translate or change:

- Prices
- Currency
- Quantities
- Units
- Model numbers
- Brand names
- Allergen labels
- Safety warnings

If validation fails, LinguaCart reverts that field to the original English text and marks it as `not_translated`.

## Repository structure

See `PROJECT_TREE.md`.

## Getting started

### 1. Start the Python model service

```bash
cd model-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8001
```

### 2. Start the Java backend

```bash
cd backend-java
./mvnw spring-boot:run
```

### 3. Load the extension

1. Open Chrome.
2. Go to `chrome://extensions/`.
3. Enable **Developer mode**.
4. Click **Load unpacked**.
5. Select the `extension/` folder.

## Development principles

- Use Java for application logic, search, ranking, caching, and safety checks.
- Use Python only for neural-model inference.
- Use JavaScript only for browser interaction and page extraction.
- Do not send cookies, credentials, form contents, or account data to the backend.
- Do not operate on checkout, payment, login, or account pages.
- Do not scrape, bulk-collect, or store Amazon or Flipkart catalogue data.
- Use only licensed or open datasets for research evaluation.

## Current scope

### In scope

- Amazon product pages
- English to Shona translation of selected fields
- Shona search over a licensed research catalogue
- Protected-field validation
- Shona-English glossary
- Translation cache
- Basic user feedback

### Not in scope yet

- Flipkart support
- Marathi support
- Image search
- Review summarisation
- General-purpose chatbot
- Full-site crawling
- Cloud translation APIs
- LLM-based query understanding
- Checkout, payment, login, or account features
- Training large language models from scratch

## Team ownership

| Member | Ownership |
|---|---|
| Extension developer | Chrome extension, Amazon adapter, field extractor, Shona panel |
| Java backend developer | Spring Boot APIs, data structures, search, ranking, protection |
| Model and evaluation developer | Python model service, datasets, embeddings, evaluation |

## Status

This project is under active development.

The first milestone is a working end-to-end prototype:

```text
Shona query
-> E5 embedding
-> Java ranking
-> relevant English products
-> NLLB translation
-> Shona results
-> protected original facts
```
