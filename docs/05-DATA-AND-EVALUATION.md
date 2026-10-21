# LinguaCart — Data and Evaluation Specification

## Do you need datasets?

Yes, but not to train NLLB or E5 from scratch.

You need datasets to:

1. Build a small searchable product catalogue.
2. Create product embeddings using E5.
3. Test whether a Shona query finds the right product.
4. Evaluate translation quality.
5. Later fine-tune retrieval or translation if the baseline is weak.

## Data principles

- Use only licensed or open datasets for the research catalogue.
- Do not scrape, bulk-collect, or store Amazon or Flipkart catalogue data.
- Keep live extension functionality separate from research evaluation data.
- Record dataset source, licence, version, and processing steps.

## Directory structure

```text
research/data/
├── catalogue/
├── embeddings/
├── glossary/
├── queries/
├── judgments/
└── translation_pairs/
```

## Research catalogue

Start with 200–500 products from a licensed/open dataset.

Fields:

```json
{
  "id": "product_12",
  "title": "Iodized table salt, 1 kg",
  "description": "Iodized table salt for cooking.",
  "brand": "BrandX",
  "category": "cooking ingredients",
  "price": "$2.99",
  "currency": "USD",
  "quantity": "1",
  "unit": "kg",
  "sourceUrl": "[https://example.com/product/12](https://example.com/product/12)"
}
```

## Product embeddings

Create one embedding per product using:

```text
passage: <title>. <description>. <attributes>.
```

Store:

```text
product_id
embedding
model_name
model_version
created_at
```

## Shona query set

Start with 50–100 reviewed Shona queries.

Fields:

```json
{
  "queryId": "q_001",
  "query": "munyu",
  "intent": "find_salt",
  "category": "cooking ingredients",
  "expectedAttributes": [],
  "annotator": "annotator_1"
}
```

## Relevance judgments

Fields:

```json
{
  "queryId": "q_001",
  "productId": "product_12",
  "relevance": 2,
  "annotator": "annotator_2"
}
```

Suggested scale:

```text
0 = not relevant
1 = partly relevant
2 = relevant
3 = highly relevant
```

## Glossary

Fields:

```json
{
  "english": "salt",
  "shona": "munyu",
  "domain": "food",
  "reviewer": "shona_speaker_1",
  "status": "approved"
}
```

## Translation pairs

Only create these if you later fine-tune NLLB.

Fields:

```json
{
  "id": "t_001",
  "english": "Iodized table salt, 1 kg",
  "shona": "Reviewed Shona translation",
  "contentType": "product_title",
  "reviewStatus": "approved",
  "annotator": "shona_speaker_1"
}
```

## Evaluation metrics

### Retrieval

Measure:

- Recall@5
- Precision@5
- MRR@10
- nDCG@5
- Latency

### Translation

Measure:

- Human adequacy
- Human fluency
- Terminology accuracy
- chrF++
- Protected-field preservation

### Protected fields

Test whether the system preserves:

- Price
- Currency
- Quantity
- Unit
- Brand
- Model number
- Allergen labels
- Warnings

A failed protected-field check must result in revert-to-English, not silent correction.

## Baselines to compare

```text
A. Glossary + Java lexical search
B. NLLB translate query -> lexical search
C. E5 direct Shona query -> English product vector search
D. Hybrid: lexical + E5 vector + glossary score
```

## First evaluation target

Create:

- 50 Shona queries
- 200–500 products
- At least 300 query-product relevance judgments
- 100 reviewed translation segments

Evaluate all four search baselines on the same query set and catalogue.
