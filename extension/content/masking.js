// Masking: replaces prices, dates, counts, model codes and numbers with placeholders.
//
// Loaded as a content script BEFORE content-script.js (see manifest.json), and also
// loaded by the Node tests in tests/js/mask.test.js. The rules are covered by
// tests/shared/mask_cases.json, which the Java backend should reuse.
//
// This is the extension's prototype. The Java protection package is the authority.

// currencies, numbers, and number+unit patterns (used for logging only)
const PROTECTED_RE =
  /(?:INR|USD|ZiG|ZWL|₹|\$)\s?\d[\d,]*(?:\.\d+)?|\d[\d,]*(?:\.\d+)?\s?(?:oz|lb|kg|g|ml|l|cm|mm|in|hrs?|hours?)?\b/gi;

// A number may contain commas ("90,000") but never ends in one ("3, 4" is two numbers).
const PRICE_RE = /(?:INR|USD|ZiG|ZWL|₹|\$)\s?\d(?:[\d,]*\d)?(?:\.\d+)?/g;
const DATE_RE =
  /\b(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),\s)?(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s\d{1,2}(?:\s?[-–]\s?\d{1,2})?\b/g;
const COUNT_RE = /\b\d[\d,.]*[KkMm]\+?(?![A-Za-z])/g; // 17.1K, 10K+
const CODE_RE = /\b(?=[\w-]*\d)(?=[\w-]*[A-Za-z])\w+(?:-\w+)*\b/g; // WH-CH720N, IPX7
const NUM_RE = /\d(?:[\d,]*\d)?(?:\.\d+)?\+?/g;

// One pass over the text, so the values are collected in the order they appear.
// At each position the first matching kind wins: price, date, count, code, number.
const MASK_RE = new RegExp(
  [
    `(?<PRICE>${PRICE_RE.source})`,
    `(?<DATE>${DATE_RE.source})`,
    `(?<COUNT>${COUNT_RE.source})`,
    `(?<CODE>${CODE_RE.source})`,
    `(?<NUM>${NUM_RE.source})`,
  ].join("|"),
  "g"
);

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// Returns { masked, values }, for example
//   maskText("INR 5 delivery Fri, Nov 13")
//   -> { masked: "{PRICE} delivery {DATE}",
//        values: { PRICE: ["INR 5"], DATE: ["Fri, Nov 13"] } }
// searchQuery is the shopper's own search text, masked when it appears in quotes.
function maskText(text, searchQuery = "") {
  const values = {};
  const keep = (label, value) => {
    (values[label] ||= []).push(value);
  };

  if (searchQuery) {
    text = text.replace(new RegExp(`"${escapeRe(searchQuery)}"`, "gi"), (match) => {
      keep("QUERY", match.slice(1, -1));
      return '"{QUERY}"';
    });
  }

  const masked = text.replace(MASK_RE, (match, ...args) => {
    const groups = args[args.length - 1];
    let label = "N";
    if (groups.PRICE !== undefined) label = "PRICE";
    else if (groups.DATE !== undefined) label = "DATE";
    else if (groups.CODE !== undefined) label = "CODE";
    keep(label, match);
    return `{${label}}`;
  });

  return { masked, values };
}

// Puts the original values back into their placeholders, in order.
function unmaskText(masked, values) {
  const used = {};
  return masked.replace(/\{([A-Z]+)\}/g, (placeholder, label) => {
    const index = (used[label] = (used[label] ?? -1) + 1);
    const value = values[label]?.[index];
    return value === undefined ? placeholder : value;
  });
}

function toTemplate(text, searchQuery = "") {
  return maskText(text, searchQuery).masked;
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = { maskText, unmaskText, toTemplate };
}