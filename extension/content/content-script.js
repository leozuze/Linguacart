console.log("LinguaCart content script is running");

// ---------- Settings ----------
const SKIP_TAGS = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA", "INPUT", "CODE"]);

// currencies, numbers, and number+unit patterns (first draft, we will refine it)
const PROTECTED_RE =
  /(?:INR|USD|ZiG|ZWL|₹|\$)\s?\d[\d,]*(?:\.\d+)?|\d[\d,]*(?:\.\d+)?\s?(?:oz|lb|kg|g|ml|l|cm|mm|in|hrs?|hours?)?\b/gi;

// ---------- Product and listing readers (not called for now) ----------
function getProductInfo() {
  const titleEl = document.querySelector("#productTitle");
  const priceEl = document.querySelector(".a-price .a-offscreen");
  return {
    title: titleEl ? titleEl.textContent.trim() : null,
    price: priceEl ? priceEl.textContent.trim() : null,
  };
}

function getListingItems() {
  const results = document.querySelectorAll(
    'div[data-component-type="s-search-result"]'
  );
  return Array.from(results).map((item) => {
    const titleEl = item.querySelector("h2 span");
    const priceEl = item.querySelector(".a-price .a-offscreen");
    return {
      asin: item.getAttribute("data-asin"),
      title: titleEl ? titleEl.textContent.replace(/\s+/g, " ").trim() : null,
      price: priceEl ? priceEl.textContent.trim() : null,
    };
  });
}

// ---------- Segmenter ----------
const PRICE_RE = /(?:INR|USD|ZiG|ZWL|₹|\$)\s?\d[\d,]*(?:\.\d+)?/g;
const DATE_RE =
  /\b(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),\s)?(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s\d{1,2}(?:\s?[-–]\s?\d{1,2})?\b/g;
const COUNT_RE = /\b\d[\d,.]*[KkMm]\+?(?![A-Za-z])/g; // 17.1K, 10K+
const CODE_RE = /\b(?=[\w-]*\d)(?=[\w-]*[A-Za-z])\w+(?:-\w+)*\b/g; // WH-CH720N, IPX7
const NUM_RE = /\d[\d,]*(?:\.\d+)?\+?/g;

// Where on the page a string lives (selectors are from memory: check with Inspect)
const ROLE_SELECTORS = [
  ["product", '#productTitle, div[data-component-type="s-search-result"] h2'],
  ["footer", "#navFooter"],
  ["header", "#navbar, #nav-main, #nav-belt"],
  ["filters", "#s-refinements"],
  ["results", 'div[data-component-type="s-search-result"]'],
];

function roleOf(el) {
  // product titles are links to product pages, even in carousels
  const link = el.closest('a[href*="/dp/"]');
  if (link && el.textContent.trim().split(/\s+/).length >= 5) return "product";

  for (const [role, selector] of ROLE_SELECTORS) {
    if (el.closest(selector)) return role;
  }
  return "other";
}

function isVisible(el) {
  const rect = el.getBoundingClientRect();
  if (rect.width < 2 || rect.height < 2) return false;
  if (rect.right + window.scrollX <= 0 || rect.bottom + window.scrollY <= 0) return false;
  const style = getComputedStyle(el);
  return style.display !== "none" && style.visibility !== "hidden";
}

function inViewport(rect) {
  return (
    rect.top < window.innerHeight &&
    rect.bottom > 0 &&
    rect.left < window.innerWidth &&
    rect.right > 0
  );
}

function collectSegments(root = document.body) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement;
      if (!parent || SKIP_TAGS.has(parent.tagName)) return NodeFilter.FILTER_REJECT;
      if (parent.closest(".a-price, .a-offscreen, [translate='no']")) return NodeFilter.FILTER_REJECT;
      const text = node.textContent.replace(/\s+/g, " ").trim();
      if (!text || !/\p{L}/u.test(text)) return NodeFilter.FILTER_REJECT;
      if (!isVisible(parent)) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    },
  });

  const segments = [];
  while (walker.nextNode()) {
    const node = walker.currentNode;
    const text = node.textContent.replace(/\s+/g, " ").trim();
    const rect = node.parentElement.getBoundingClientRect();
    segments.push({
      text,
      role: roleOf(node.parentElement),
      protected: text.match(PROTECTED_RE) || [],
      inViewport: inViewport(rect),
    });
  }
  return segments;
}

// Replace prices, dates, counts, model codes and numbers with placeholders
function toTemplate(text) {
  return text
    .replace(PRICE_RE, "{PRICE}")
    .replace(DATE_RE, "{DATE}")
    .replace(COUNT_RE, "{N}")
    .replace(CODE_RE, "{CODE}")
    .replace(NUM_RE, "{N}");
}

function kindOf(seg, template) {
  if (seg.role === "product") return "content";
  if (template.split(" ").length > 9) return "content";
  return /\{[A-Z]+\}/.test(template) ? "template" : "label";
}

function runSegmenter() {
  const segs = collectSegments();
  const unique = new Map();
  for (const s of segs) if (!unique.has(s.text)) unique.set(s.text, s);

  const groups = new Map();
  for (const s of unique.values()) {
    const template = toTemplate(s.text);
    // nothing left to translate once placeholders are removed
    if (!/\p{L}{2,}/u.test(template.replace(/\{[A-Z]+\}/g, ""))) continue;
    const kind = kindOf(s, template);
    const text = kind === "content" ? s.text : template;
    const key = kind + "|" + text;
    if (!groups.has(key)) groups.set(key, { kind, role: s.role, text });
  }

  const all = [...groups.values()];
  const tally = {};
  for (const g of all) {
    const k = `${g.role} / ${g.kind}`;
    tally[k] = (tally[k] || 0) + 1;
  }

  console.log(
    `LinguaCart: ${segs.length} visible segments, ${unique.size} unique, ${all.length} to translate`
  );
  console.table(tally);
  console.log(
    "Templates:\n" +
      JSON.stringify(all.filter((g) => g.kind === "template").map((g) => g.text), null, 2)
  );
  console.log(
    "Labels (not filters or footer):\n" +
      JSON.stringify(
        all
          .filter((g) => g.kind === "label" && g.role !== "footer" && g.role !== "filters")
          .map((g) => g.text),
        null,
        2
      )
  );
}

setTimeout(runSegmenter, 2000);