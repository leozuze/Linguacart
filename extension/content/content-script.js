console.log("LinguaCart content script is running");

// ---------- Settings ----------
const SKIP_TAGS = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA", "INPUT", "CODE", "SELECT", "OPTION"]);
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

// The shopper's own search text (Amazon uses ?k=...)
const SEARCH_QUERY = (new URLSearchParams(location.search).get("k") || "").trim();
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// Where on the page a string lives (selectors are from memory: check with Inspect)
const ROLE_SELECTORS = [
  ["product", '#productTitle, div[data-component-type="s-search-result"] h2'],
  ["footer", "#navFooter"],
  ["header", "#navbar, #nav-main, #nav-belt"],
  ["filters", "#s-refinements"],
  ["results", 'div[data-component-type="s-search-result"]'],
];

function roleOf(el) {
  // product titles are links to product pages, even in carousels and sponsored results
  const link = el.closest('a[href*="/dp/"], a[href*="%2Fdp%2F"]');
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

// The nearest ancestor that is displayed as a block (not inline text)
function blockOf(el) {
  let cur = el;
  while (cur && cur !== document.body) {
    const d = getComputedStyle(cur).display;
    if (!d.startsWith("inline") && d !== "contents") return cur;
    cur = cur.parentElement;
  }
  return document.body;
}

function collectSegments(root = document.body) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement;
      if (!parent || SKIP_TAGS.has(parent.tagName)) return NodeFilter.FILTER_REJECT;
      if (parent.closest(".a-price, .a-offscreen, [translate='no']")) return NodeFilter.FILTER_REJECT;
      const text = node.textContent.replace(/\s+/g, " ").trim();
      if (!text) return NodeFilter.FILTER_REJECT;
      if (!isVisible(parent)) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    },
  });

  // group text nodes by their block container
  const blocks = new Map();
  while (walker.nextNode()) {
    const node = walker.currentNode;
    const block = blockOf(node.parentElement);
    if (!blocks.has(block)) blocks.set(block, []);
    blocks.get(block).push(node);
  }

  const segments = [];
  const isNumeric = (n) => !/\p{L}/u.test(n.textContent);
  const clean = (nodes) =>
    nodes
      .map((n) => n.textContent.replace(/\s+/g, " ").trim())
      .join(" ")
      .replace(/\s+([.,!?;:)])/g, "$1") // "India ." -> "India."
      .replace(/([(])\s+/g, "$1")
      .trim();

  for (const [block, allNodes] of blocks) {
    // pagination ("Previous 1 2 3 4 Next"): keep the words as separate pieces
    const manyNumbers = allNodes.filter(isNumeric).length >= 3;
    const groups = manyNumbers
      ? allNodes.filter((n) => !isNumeric(n)).map((n) => [n])
      : [allNodes];

    for (const nodes of groups) {
      const text = clean(nodes);
      if (!/\p{L}/u.test(text)) continue; // needs letters
      segments.push({
        text,
        role: roleOf(block),
        protected: text.match(PROTECTED_RE) || [],
        inViewport: inViewport(block.getBoundingClientRect()),
        nodes, // kept so the renderer can replace the text later
      });
    }
  }
  return segments;
}

// Replace the search query, prices, dates, counts, model codes and numbers with placeholders
function toTemplate(text) {
  if (SEARCH_QUERY) {
    text = text.replace(new RegExp(`"${escapeRe(SEARCH_QUERY)}"`, "gi"), '"{QUERY}"');
  }
  return text
    .replace(PRICE_RE, "{PRICE}")
    .replace(DATE_RE, "{DATE}")
    .replace(COUNT_RE, "{N}")
    .replace(CODE_RE, "{CODE}")
    .replace(NUM_RE, "{N}");
}

function kindOf(seg, template) {
  if (seg.role === "product") return "content";
  // spec lines under a result, such as "MDR-7506, 40mm drivers, 10Hz-20kHz"
  if (seg.role === "results" && template.includes(",") && template.includes("{CODE}")) {
    return "content";
  }
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

  const nodeCount = segs.reduce((n, s) => n + s.nodes.length, 0);
  console.log(
    `LinguaCart: ${nodeCount} text nodes -> ${segs.length} blocks, ${unique.size} unique, ${all.length} to translate`
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
  console.log(
    "Long non-product strings:\n" +
      JSON.stringify(
        all.filter((g) => g.kind === "content" && g.role !== "product").map((g) => g.text),
        null,
        2
      )
  );
}

// To collect strings for the glossary, uncomment this line:
// setTimeout(runSegmenter, 2000);

// ---------- Translation (fake backend for now) ----------
const MAX_SEGMENTS = 200;
const BATCH_SIZE = 50; // matches the limit in the API contract

function pageType() {
  if (location.pathname.includes("/dp/")) return "product";
  if (location.pathname === "/s") return "listing";
  return "other";
}

function kindFor(seg) {
  const kind = kindOf(seg, toTemplate(seg.text));
  if (kind === "label") return "ui_label";
  if (kind === "template") return "ui_template";
  return seg.role === "product" ? "title" : "other";
}

const swaps = new Map(); // text node -> { original, shona }
const cache = new Map(); // English text -> Shona text (this page visit only)
const skipped = new Set(); // English texts the backend refused to translate
let view = "en"; // "en" or "sn"
let busy = false;
let started = false;

function pruneSwaps() {
  // Amazon replaces parts of the page; forget nodes that are no longer on it
  for (const node of swaps.keys()) if (!node.isConnected) swaps.delete(node);
}

function remember(seg, shona) {
  // the whole translation goes into the first text node; the others are emptied
  seg.nodes.forEach((node, i) => {
    swaps.set(node, { original: node.textContent, shona: i === 0 ? shona : "" });
  });
}

async function translateNew() {
  if (busy) return;
  busy = true;
  try {
    pruneSwaps();
    const fresh = collectSegments()
      .filter(
        (s) =>
          s.inViewport &&
          !skipped.has(s.text) &&
          !s.nodes.some((n) => swaps.has(n)) // not already handled
      )
      .slice(0, MAX_SEGMENTS);

    const toSend = [];
    for (const s of fresh) {
      if (cache.has(s.text)) remember(s, cache.get(s.text));
      else toSend.push(s);
    }

    let translated = 0;
    let keptEnglish = 0;
    for (let start = 0; start < toSend.length; start += BATCH_SIZE) {
      const batch = toSend.slice(start, start + BATCH_SIZE);
      const request = {
        apiVersion: 1,
        site: location.hostname.replace(/^www\./, ""),
        pageType: pageType(),
        target: "sna",
        segments: batch.map((s, i) => ({ id: "s" + i, text: s.text, kind: kindFor(s) })),
      };

      const response = await chrome.runtime.sendMessage({ type: "translate", request });
      if (!response || !response.results) throw new Error("No usable response from backend");

      for (const r of response.results) {
        const seg = batch[Number(r.id.slice(1))];
        if (!seg) continue;
        if (r.status !== "translated") {
          keptEnglish++;
          skipped.add(seg.text);
          console.log("LinguaCart kept English:", seg.text, r.reasons);
          continue;
        }
        translated++;
        cache.set(seg.text, r.text);
        remember(seg, r.text);
      }
    }
    if (fresh.length) {
      console.log(
        `LinguaCart: ${translated} translated, ${keptEnglish} kept in English, ` +
          `${fresh.length - toSend.length} from page cache`
      );
    }
  } finally {
    busy = false;
  }
}

function setView(next) {
  for (const [node, s] of swaps) {
    node.textContent = next === "sn" ? s.shona : s.original;
  }
  view = next;
}

// Translate content that appears later (scrolling, "more results", Amazon re-rendering)
let timer = null;
function scheduleTranslate() {
  if (view !== "sn") return;
  clearTimeout(timer);
  timer = setTimeout(async () => {
    try {
      await translateNew();
      if (view === "sn") setView("sn");
    } catch (err) {
      console.error("LinguaCart error:", err);
    }
  }, 500);
}

function startWatching() {
  window.addEventListener("scroll", scheduleTranslate, { passive: true });
  window.addEventListener("resize", scheduleTranslate);
  // childList only: swapping text inside existing text nodes does not trigger this
  new MutationObserver(scheduleTranslate).observe(document.body, {
    childList: true,
    subtree: true,
  });
}

function createToolbar() {
  if (document.getElementById("linguacart-toolbar")) return;
  const host = document.createElement("div");
  host.id = "linguacart-toolbar";
  host.setAttribute("translate", "no");
  const root = host.attachShadow({ mode: "open" });
  root.innerHTML = `
    <style>
      div { position: fixed; right: 16px; bottom: 16px; z-index: 2147483647; }
      button {
        all: initial; font: 600 14px system-ui, sans-serif; background: #1f6f43;
        color: #fff; padding: 10px 14px; border-radius: 20px; cursor: pointer;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
      }
      button:disabled { opacity: 0.6; cursor: wait; }
    </style>
    <div><button id="toggle">Translate to Shona</button></div>`;
  document.documentElement.appendChild(host);

  const btn = root.getElementById("toggle");
  btn.addEventListener("click", async () => {
    try {
      if (!started) {
        btn.disabled = true;
        btn.textContent = "Translating...";
        await translateNew();
        setView("sn");
        started = true;
        startWatching();
        btn.disabled = false;
        btn.textContent = "Show original";
      } else if (view === "sn") {
        setView("en");
        btn.textContent = "Show Shona";
      } else {
        await translateNew(); // pick up anything that appeared while in English
        setView("sn");
        btn.textContent = "Show original";
      }
    } catch (err) {
      console.error("LinguaCart error:", err);
      btn.disabled = false;
      btn.textContent = "Translation unavailable";
    }
  });
}

createToolbar();