# LinguaCart — Claude Build Tasks

## Instruction to Claude

Build the system exactly according to:

- `docs/00-PROJECT-BRIEF.md`
- `docs/01-ARCHITECTURE.md`
- `docs/02-BACKEND-JAVA.md`
- `docs/03-MODEL-SERVICE-PYTHON.md`
- `docs/04-EXTENSION-JAVASCRIPT.md`
- `docs/05-DATA-AND-EVALUATION.md`

Do not add features outside the v1 scope.

## Build order

### Task 1: Repository skeleton

Create:

```text
Linguacart/
├── backend-java/
├── model-service/
├── extension/
├── research/
├── docs/
└── README.md
```

### Task 2: Java backend skeleton

Create a Spring Boot application with:

- `GET /api/v1/health`
- `POST /api/v1/translate`
- `POST /api/v1/search`
- `POST /api/v1/feedback`
- `ProductRecord`
- `ProductNormalizer`
- `GlossaryService`
- `LruTranslationCache`
- `InvertedIndex`
- `VectorSearchService`
- `TopKHeap`
- `ProtectedFieldChecker`
- `ModelServiceClient`

Use an interface for `ModelServiceClient` so it can be mocked in tests.

### Task 3: Python model service

Create a FastAPI service with:

- `GET /health`
- `POST /translate`
- `POST /embed`

Load:

- `facebook/nllb-200-distilled-600M`
- `intfloat/multilingual-e5-base`

The service must return JSON only. It must not implement search or protection logic.

### Task 4: JavaScript extension skeleton

Create a Chrome Manifest V3 extension with:

- Amazon product-page content script
- Amazon field extractor
- Floating toolbar
- Shona search box
- Translation panel
- Show Original toggle
- Backend API client

Do not build Flipkart yet.

### Task 5: Translation flow

Implement:

```text
Extension -> Java /translate -> Python /translate -> Java protection -> Extension panel
```

Requirements:

- Cache translations in Java.
- Apply approved glossary terms in Java.
- Preserve protected fields.
- Revert failed fields to English.
- Show original text on demand.

### Task 6: Research search flow

Implement:

```text
Shona query -> Java /search -> Python /embed -> Java vector search -> top-K results
```

Requirements:

- Load a small JSONL product corpus.
- Precompute product embeddings.
- Store embeddings locally.
- Compare query vector with product vectors.
- Return top-K products.
- Combine lexical and vector scores.

### Task 7: Tests

Write tests for:

- Product normalisation
- Glossary lookup
- LRU cache behaviour
- Inverted index construction
- Top-K heap behaviour
- Vector similarity calculation
- Protected-field validation
- Translation API contract
- Search API contract

## Definition of done for coding sprint 1

1. Java backend starts successfully.
2. Python model service starts successfully.
3. Extension loads on one Amazon product page.
4. Extension extracts title, description, price, quantity, and unit.
5. Java sends title and description to Python.
6. NLLB returns Shona text.
7. Java validates protected fields.
8. Extension displays Shona text and original protected fields.
9. A Shona query returns ranked products from a small local catalogue.
10. Unit tests pass for glossary, cache, index, heap, vector search, and protection.
