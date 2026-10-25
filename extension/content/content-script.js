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
function isVisible(el) {
  const rect = el.getBoundingClientRect();
  if (rect.width < 2 || rect.height < 2) return false;
  // hidden "screen reader only" text is often parked far off the page
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
      if (parent.closest(".a-price, [translate='no']")) return NodeFilter.FILTER_REJECT;
      const text = node.textContent.replace(/\s+/g, " ").trim();
      if (!text || !/\p{L}/u.test(text)) return NodeFilter.FILTER_REJECT; // needs letters
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
      protected: text.match(PROTECTED_RE) || [],
      inViewport: inViewport(rect),
    });
  }
  return segments;
}

// Replace numbers, prices and dates with placeholders so repeated patterns group together
function toTemplate(text) {
  const words = text.split(" ").length;
  if (words > 9) return text; // long strings are product content, not templates
  return text
    .replace(/(?:INR|USD|ZiG|ZWL|₹|\$)\s?\d[\d,]*(?:\.\d+)?/g, "{PRICE}")
    .replace(/\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), [A-Z][a-z]{2} \d{1,2}\b/g, "{DATE}")
    .replace(/\d[\d,]*(?:\.\d+)?[KkMm]?\+?/g, "{N}");
}

function runSegmenter() {
  const segs = collectSegments();
  const unique = [...new Set(segs.map((s) => s.text))];

  const groups = new Map();
  for (const text of unique) {
    const key = toTemplate(text);
    if (!groups.has(key)) groups.set(key, { template: key, count: 0, example: text });
    groups.get(key).count++;
  }

  // strings with no real words left after masking (like "({N})") need no translation
  const needsTranslation = [...groups.values()].filter((g) =>
    /\p{L}{2,}/u.test(g.template.replace(/\{[A-Z]+\}/g, ""))
  );
  const templated = needsTranslation.filter((g) => g.template.includes("{"));
  const content = needsTranslation.filter((g) => !g.template.includes("{") && g.template.split(" ").length > 9);
  const labels = needsTranslation.filter((g) => !g.template.includes("{") && g.template.split(" ").length <= 9);

  console.log(
    `LinguaCart: ${segs.length} visible segments, ${unique.length} unique -> ` +
      `${labels.length} UI labels, ${templated.length} templates, ${content.length} long content strings`
  );
  console.log("Templates:\n" + JSON.stringify(templated.map((g) => g.template), null, 2));
  console.log("UI labels:\n" + JSON.stringify(labels.map((g) => g.template), null, 2));
}

setTimeout(runSegmenter, 2000);