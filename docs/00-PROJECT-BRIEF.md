# LinguaCart — Project Brief

LinguaCart is a browser extension that helps Shona speakers use English e-commerce websites.

It has two core capabilities:

1. Translate selected English product-page content into Shona.
2. Allow Shona product search against a controlled, licensed English product catalogue.

The extension assists browsing and interpretation. It does not replace the original website, handle checkout, or store marketplace catalogue data.

## Primary language direction

- Page translation: English → Shona
- Product search: Shona query → English product catalogue

## First target site

Amazon product pages only.

## First target language

- Shona: `sna_Latn`
- English: `eng_Latn`

## Approved models for v1

| Task | Model | Purpose |
|---|---|---|
| Translation | `facebook/nllb-200-distilled-600M` | English → Shona |
| Retrieval | `intfloat/multilingual-e5-base` | Shona query → product vector matching |
| Lexical search | Java inverted index | Exact term, brand, model and attribute matching |
| Ranking | Java top-K min-heap | Select best K products |

## Technology split

| Component | Technology | Responsibility |
|---|---|---|
| Browser extension | JavaScript | DOM extraction, toolbar, panel, API calls |
| Java backend | Java 21 + Spring Boot 3 | APIs, normalisation, caching, search, ranking, protection |
| Python model service | Python + FastAPI | NLLB translation and E5 embeddings only |
| Research data | JSON/JSONL/CSV | Licensed catalogue, queries, judgments, glossary |

## Non-goals for v1

Do not implement:

- Checkout, payment, login, or account features
- Image search
- Review summarisation
- General-purpose chatbot
- Full-site crawling
- Cloud translation APIs
- LLM-based query understanding
- Flipkart adapter
- Marathi support
- Training large models from scratch

## Core safety rule

Never silently replace or translate:

- Prices
- Currency
- Quantities
- Units
- Model numbers
- Brand names
- Allergen labels
- Safety warnings

If validation fails, show the original English text and mark the field as `not_translated`.
