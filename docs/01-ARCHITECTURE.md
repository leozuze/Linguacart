# LinguaCart — Implementation Architecture

## Services

LinguaCart has three implementation components:

1. Browser extension: JavaScript
2. Java backend: Spring Boot
3. Python model service: FastAPI

## Responsibility boundary

| Component | Owns | Must not do |
|---|---|---|
| Extension | DOM extraction, UI, user actions | Business logic, model inference |
| Java backend | APIs, normalisation, caching, search, ranking, protection | Neural inference |
| Python model service | NLLB translation, E5 embeddings | Product ranking, glossary rules, protected-field decisions |
| Research data layer | Licensed corpus, vectors, queries, judgments | Live website data |

## Architecture diagram

```text
Browser Extension
        |
        v
Java Backend
        |
        |-- /translate --> Python /translate --> NLLB
        |-- /embed ------> Python /embed ------> E5
        |-- /search
        |      |-- Glossary hash map
        |      |-- Inverted index
        |      |-- Vector search
        |      `-- Top-K min-heap
        `-- Protected-field checker
```

## Java backend responsibilities

The Java backend is the system brain.

It owns:

- REST API endpoints
- Request validation
- Product normalisation
- Translation caching
- Shona-English glossary lookup
- Inverted index construction and lookup
- E5 vector similarity search
- Top-K result selection
- Hybrid score calculation
- Protected-field validation
- Feedback storage
- Error handling and logging

## Python model service responsibilities

The Python model service is a stateless inference API.

It owns:

- Loading NLLB-200 distilled 600M
- Loading multilingual-e5-base
- Translating English text to Shona
- Creating embeddings for queries and product text
- Returning JSON responses

It must not:

- Store product catalogues
- Apply glossaries
- Decide ranking
- Validate protected fields
- Communicate directly with the browser extension

## Data flow: page translation

```text
User opens Amazon product page
-> Extension extracts supported fields
-> Extension sends fields to Java /translate
-> Java checks translation cache
-> Java sends untranslated text to Python /translate
-> NLLB returns Shona text
-> Java applies glossary corrections
-> Java validates protected fields
-> Java returns final translation payload
-> Extension displays Shona panel
```

## Data flow: Shona catalogue search

```text
User types Shona query
-> Extension sends query to Java /search
-> Java normalises query
-> Java checks glossary
-> Java sends query to Python /embed
-> E5 returns query vector
-> Java compares query vector with precomputed product vectors
-> Java keeps top-K products using min-heap
-> Java optionally combines lexical and vector scores
-> Java returns ranked products
-> Extension displays results in Shona
```

## First milestone

A completed first milestone means:

1. Extension loads on one Amazon product page.
2. Extension extracts title, description, price, quantity, and unit.
3. Java backend receives a normalised product record.
4. NLLB translates title and description into Shona.
5. Price, quantity, unit, brand, and model remain unchanged.
6. Extension displays Shona text with a “Show original” toggle.
7. A Shona query such as `munyu` retrieves relevant products from the research catalogue.
8. Results are ranked and displayed in Shona.
