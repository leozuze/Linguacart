# LinguaCart — Browser Extension Specification

## Technology

- Chrome Extension Manifest V3
- JavaScript
- HTML/CSS
- No Java in the extension

## First supported site

Amazon product pages only.

## Extension structure

```text
extension/
├── manifest.json
├── background/
│   └── service-worker.js
├── content/
│   ├── content-script.js
│   ├── amazon-adapter.js
│   ├── field-extractor.js
│   └── panel.js
├── toolbar/
│   ├── toolbar.html
│   ├── toolbar.css
│   └── toolbar.js
├── lib/
│   └── api.js
└── assets/
    └── icon.png
```

## Responsibilities

### Amazon adapter

The adapter knows how to locate:

```text
title
description
price
quantity
unit
brand
model
attributes
warnings
```

It must also identify blocked regions:

```text
checkout
payment
login
account
form inputs
```

### Field extractor

The extractor creates:

```json
{
  "platform": "amazon",
  "sourceUrl": "[https://www.amazon.com/](https://www.amazon.com/)...",
  "title": "Iodized table salt, 1 kg",
  "description": "Iodized table salt for cooking.",
  "price": "$2.99",
  "quantity": "1",
  "unit": "kg",
  "brand": "BrandX",
  "model": "BX-100",
  "warnings": []
}
```

### Shona panel

The panel displays:

```text
Title in Shona
Description in Shona
Original price
Original quantity and unit
Original brand and model
[Show Original] button
[Report translation] button
```

## User actions

### Translate page

```text
User clicks Translate
-> Extract fields
-> Send to Java /translate
-> Display Shona result
-> Keep original text available
```

### Shona search

```text
User types Shona query
-> Send to Java /search
-> Display ranked products
-> Link to original product pages where available
```

## Extension rules

- Send only extracted text fields to the backend.
- Never send cookies, credentials, form contents, or account data.
- Never modify checkout, payment, login, or account pages.
- Always provide a “Show original” option.
- Clearly mark machine-translated text.
- Handle dynamic page changes carefully.
- Fail visibly but safely if the backend is unavailable.

## First milestone

The extension is complete for the first milestone when:

1. It activates on one Amazon product-page URL pattern.
2. It extracts the required fields.
3. It sends them to the Java backend.
4. It displays Shona title and description.
5. It preserves and displays original protected fields.
6. It allows the user to return to English.
