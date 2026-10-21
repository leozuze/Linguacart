# LinguaCart

LinguaCart is a browser extension that helps Shona speakers use English
e-commerce websites.

It has two core capabilities:

1. Translate selected English product-page content into Shona.
2. Search a controlled, licensed English product catalogue using Shona queries.

The extension assists browsing and interpretation. It does not replace the
original website, handle checkout, or store marketplace catalogue data.

## Architecture

```text
Browser Extension (JavaScript)
        |
        v
Java Backend (Spring Boot)
        |
        ├── /translate --> Python model service --> NLLB
        ├── /embed ------> Python model service --> E5
        ├── /search
        |      |-- Glossary hash map
        |      |-- Inverted index
        |      |-- Vector search
        |      `-- Top-K min-heap
        `-- Protected-field checker
```

## Models used in v1

| Task | Model |
|---|---|
| English to Shona translation | `facebook/nllb-200-distilled-600M` |
| Shona query to English product retrieval | `intfloat/multilingual-e5-base` |
| Exact keyword matching | Java inverted index |
| Ranking | Java top-K min-heap |

## First milestone

1. Load one Amazon product page.
2. Extract title, description, price, quantity, unit, brand, and model.
3. Translate title and description into Shona using NLLB.
4. Preserve price, quantity, unit, brand, and model exactly.
5. Search a small local product catalogue using a Shona query.
6. Return ranked products and display their information in Shona.

## Do not build in v1

- Checkout, payment, login, or account features
- Flipkart support
- Marathi support
- Image search
- Review summarisation
- General chatbot
- Full-site crawling
- Cloud translation APIs
- LLM-based query understanding
- Training large models from scratch
