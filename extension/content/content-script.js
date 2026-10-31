console.log("LinguaCart content script is running");

// ---------- Settings ----------
const SKIP_TAGS = new Set([
  "SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA", "INPUT", "CODE", "SELECT", "OPTION",
]);

// Allowed pages: search results and product pages only. Everything else
// (cart, checkout, sign-in, account, addresses, payments) is off limits.
function isAllowedPage() {
  const p = location.pathname;
  return p === "/s" || p.includes("/dp/") || p.startsWith("/gp/product/");
}

// Parts of the page that can contain personal data.
// Unconfirmed ids, from memory: verify with Inspect on a TEST account, never a real one.
const PRIVATE_SELECTORS =
  "#nav-link-accountList, #glow-ingress-block, #nav-global-location-slot";

// currencies, numbers, and number+unit patterns (first draft, we will refine it)
const PROTECTED_RE =
  /(?:INR|USD|ZiG|ZWL|₹|\$)\s?\d[\d,]*(?:\.\d+)?|\d[\d,]*(?:\.\d+)?\s?(?:oz|lb|kg|g|ml|l|cm|mm|in|hrs?|hours?)?\b/gi;

// ---------- Product and listing readers (not called for now) ----------
function firstText(selectors) {
  for (const sel of selectors) {
    const el = document.querySelector(sel);
    const text = el && el.textContent.replace(/\s+/g, " ").trim();
    if (text) return text;
  }
  return null;
}

// Only the product's own price area, never "any price on the page".
const PRICE_SELECTORS = [
  // confirmed on a product page: the price inside the buy box
  "#buybox #corePrice_feature_div .a-price:not(.a-text-price) .a-offscreen",
  "#corePrice_feature_div .a-price:not(.a-text-price) .a-offscreen",
  // unconfirmed fallback, from memory
  "#corePriceDisplay_desktop_feature_div .a-price:not(.a-text-price) .a-offscreen",
];

function getProductInfo() {
  return {
    title: firstText(["span#productTitle", "#title span"]),
    price: firstText(PRICE_SELECTORS), // null means "no price shown"
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

// True when the nodes sit in different links/buttons (or some in one and some outside)
function hasMixedInteractive(nodes) {
  const owners = new Set(
    nodes.map((n) => n.parentElement.closest("a, button, [role='button']"))
  );
  return owners.size > 1;
}

function collectSegments(root = document.body) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement;
      if (!parent || SKIP_TAGS.has(parent.tagName)) return NodeFilter.FILTER_REJECT;
      if (parent.closest(".a-price, .a-offscreen, [translate='no']")) {
        return NodeFilter.FILTER_REJECT;
      }
      if (parent.closest(PRIVATE_SELECTORS)) return NodeFilter.FILTER_REJECT;
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
        mixedLinks: hasMixedInteractive(nodes),
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

// One record per translated segment:
//   { nodes, originals: [English text per node], shown: [Shona text per node] }
// The whole translation goes into the first node; the other nodes are emptied.
const segmentsDone = new Set();
const nodeRecord = new WeakMap(); // text node -> its segment record
const reportMeta = new WeakMap(); // text node -> source/shown text and provenance
let anyLowConfidence = false; // a WeakMap can't be looped over, so track this separately
const cache = new Map(); // English text -> translation result, this page visit only
const skipped = new Set(); // English texts the backend refused (not retried this visit)
const translatedKinds = new Set();
let toolbarRoot = null;
let notify = () => {}; // replaced by createToolbar

let view = "en"; // "en" or "sn"
let busy = false;
let started = false;
let lastErrorNoticeAt = 0;

function forget(record) {
  segmentsDone.delete(record);
  for (const node of record.nodes) {
    nodeRecord.delete(node);
    reportMeta.delete(node);
  }
}

function pruneDone() {
  // Amazon replaces parts of the page; forget segments whose nodes left the page
  for (const record of [...segmentsDone]) {
    if (record.nodes.some((n) => !n.isConnected)) forget(record);
  }
}

function normalizeTranslation(result) {
  const translationType =
    result.translationType ??
    (result.source === "nllb"
      ? "machine"
      : result.source === "ui_glossary"
        ? "human"
        : result.source === "mock"
          ? "mock"
          : "unknown");

  return {
    text: result.text,
    source: result.source ?? "unknown",
    translationType,
    lowConfidence: Boolean(result.lowConfidence),
  };
}

function remember(seg, translation) {
  const record = {
    nodes: seg.nodes,
    originals: seg.nodes.map((n) => n.textContent),
    shown: seg.nodes.map((n, i) => (i === 0 ? translation.text : "")),
  };
  const reportRecord = {
    sourceText: seg.text,
    shownText: translation.text,
    source: translation.source,
    translationType: translation.translationType,
    lowConfidence: translation.lowConfidence,
  };

  for (const node of seg.nodes) {
    nodeRecord.set(node, record);
    reportMeta.set(node, reportRecord);
  }
  segmentsDone.add(record);

  translatedKinds.add(translation.translationType);
  if (translation.lowConfidence) anyLowConfidence = true;
  updateTranslationMarker();
}

async function translateNew() {
  if (busy) return;
  busy = true;
  try {
    pruneDone();
    const fresh = collectSegments()
      .filter(
        (s) =>
          s.inViewport &&
          !skipped.has(s.text) &&
          !s.nodes.some((n) => nodeRecord.has(n)) // not already handled
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
      if (response?.error) {
        const e = response.error;
        throw new Error("Backend error: " + (e.code ?? "") + " " + (e.message ?? e));
      }
      if (!response || !response.results) throw new Error("No usable response from backend");

      for (const r of response.results) {
        if (!r || typeof r.id !== "string") continue;

        const match = /^s(\d+)$/.exec(r.id);
        const seg = match ? batch[Number(match[1])] : null;
        if (!seg) continue;

        if (r.status !== "translated" || typeof r.text !== "string" || !r.text) {
          keptEnglish++;
          // a temporary model problem may succeed later; other refusals are final
          const temporary = Array.isArray(r.reasons) && r.reasons.includes("model_error");
          if (!temporary) skipped.add(seg.text);
          console.log("LinguaCart kept English:", seg.text, r.reasons);
          continue;
        }

        const translation = normalizeTranslation(r);
        translated++;
        cache.set(seg.text, translation);
        remember(seg, translation);
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
  for (const record of [...segmentsDone]) {
    // If the page changed this text itself, stop managing it and never overwrite it.
    const changed = record.nodes.some(
      (n, i) =>
        !n.isConnected ||
        (n.textContent !== record.originals[i] && n.textContent !== record.shown[i])
    );
    if (changed) {
      // put English back on any node of this segment that still shows our Shona text
      record.nodes.forEach((n, i) => {
        if (
          n.isConnected &&
          n.textContent === record.shown[i] &&
          record.shown[i] !== record.originals[i]
        ) {
          n.textContent = record.originals[i];
        }
      });
      forget(record);
      continue;
    }

    const wanted = next === "sn" ? record.shown : record.originals;
    record.nodes.forEach((n, i) => {
      // only write when the text really changes, so selections are not wiped
      if (n.textContent !== wanted[i]) n.textContent = wanted[i];
    });
  }
  view = next;
  updateTranslationMarker();
  updateReportButton();
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
      // tell the user, but at most once every 30 seconds
      if (Date.now() - lastErrorNoticeAt > 30000) {
        lastErrorNoticeAt = Date.now();
        notify("Some text could not be translated. The original English is still shown.");
      }
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

function updateTranslationMarker() {
  const marker = toolbarRoot?.getElementById("translation-marker");
  if (!marker) return;

  const labels = [];
  if (translatedKinds.has("machine")) labels.push("Machine-translated");
  if (translatedKinds.has("human")) labels.push("Human-translated");
  if (translatedKinds.has("mock")) labels.push("TEST MOCK — not Shona");
  if (translatedKinds.has("unknown")) labels.push("Translation type unconfirmed");
  if (anyLowConfidence) labels.push("some results low-confidence");

  marker.textContent = labels.join(" · ");
  marker.hidden = view !== "sn" || labels.length === 0;
}

function selectedTranslationRecord() {
  const selection = window.getSelection();
  if (!selection || selection.isCollapsed || selection.rangeCount === 0) return null;

  const range = selection.getRangeAt(0);
  const root = range.commonAncestorContainer;

  // selection inside a single text node
  if (root.nodeType === Node.TEXT_NODE) return reportMeta.get(root) || null;

  // selection across several nodes: all translated text in it must belong to ONE segment
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const records = new Set();
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (!node.textContent.trim() || !range.intersectsNode(node)) continue;
    const record = reportMeta.get(node);
    if (record) records.add(record);
    if (records.size > 1) return null; // keep v1 reports to a single segment
  }
  return records.size === 1 ? [...records][0] : null;
}

function updateReportButton() {
  const button = toolbarRoot?.getElementById("report-translation");
  if (button) {
    button.disabled = view !== "sn" || !selectedTranslationRecord();
  }
}

function createToolbar() {
  if (document.getElementById("linguacart-toolbar")) return;

  const host = document.createElement("div");
  host.id = "linguacart-toolbar";
  host.setAttribute("translate", "no");

  const root = host.attachShadow({ mode: "open" });
  toolbarRoot = root;

  root.innerHTML = `
    <style>
      [hidden] { display: none !important; }

      .toolbar {
        position: fixed;
        right: 16px;
        bottom: 16px;
        z-index: 2147483647;
        font: 14px system-ui, sans-serif;
      }

      .panel {
        display: grid;
        justify-items: end;
        gap: 8px;
      }

      .marker, .report-form {
        max-width: 360px;
        padding: 10px 12px;
        border: 1px solid #bbb;
        border-radius: 10px;
        background: #fff;
        color: #222;
        box-shadow: 0 2px 8px #0003;
      }

      .marker { font-size: 12px; font-weight: 700; }

      .actions { display: flex; gap: 8px; align-items: center; }

      button, select, textarea { font: inherit; }

      button {
        border: 0;
        border-radius: 20px;
        padding: 10px 14px;
        cursor: pointer;
      }

      #toggle { background: #1f6f43; color: white; font-weight: 600; }

      #report-translation,
      #collapse {
        background: #fff;
        color: #222;
        border: 1px solid #888;
      }

      #collapse { padding: 10px 12px; font-weight: 700; }

      #expand {
        width: 44px;
        height: 44px;
        padding: 0;
        border-radius: 50%;
        background: #1f6f43;
        color: white;
        font-weight: 700;
        box-shadow: 0 2px 8px #0005;
      }

      button:disabled { opacity: .55; cursor: not-allowed; }

      .report-form { display: grid; gap: 8px; }
      .report-form label { display: grid; gap: 4px; }

      .preview {
        max-height: 120px;
        overflow: auto;
        padding: 8px;
        background: #f4f4f4;
        border-radius: 6px;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        font-size: 12px;
      }

      .form-actions { display: flex; gap: 8px; justify-content: flex-end; }

      .notice {
        max-width: 360px;
        color: #222;
        background: #fff;
        padding: 8px 10px;
        border-radius: 8px;
        box-shadow: 0 2px 8px #0003;
      }
    </style>

    <div class="toolbar">
      <div id="panel" class="panel">
        <div id="translation-marker" class="marker" role="status" hidden></div>

        <form id="report-form" class="report-form" hidden>
          <strong>Report this translation</strong>
          <div class="preview" id="report-preview"></div>
          <label>
            Issue
            <select id="report-issue">
              <option value="wrong_meaning">Wrong meaning</option>
              <option value="unnatural">Unnatural Shona</option>
              <option value="wrong_dialect">Wrong dialect</option>
              <option value="number_or_price_problem">Number or price problem</option>
              <option value="other">Other</option>
            </select>
          </label>
          <label>
            Optional comment
            <textarea id="report-comment" rows="2" maxlength="500"></textarea>
          </label>
          <div class="form-actions">
            <button type="button" id="cancel-report">Cancel</button>
            <button type="submit" id="submit-report">Send report</button>
          </div>
        </form>

        <div id="notice" class="notice" role="status" hidden></div>

        <div class="actions">
          <button id="report-translation" type="button" disabled>Report translation</button>
          <button id="toggle" type="button">Translate to Shona</button>
          <button id="collapse" type="button" title="Minimise LinguaCart" aria-label="Minimise LinguaCart">&minus;</button>
        </div>
      </div>

      <button id="expand" type="button" title="Open LinguaCart" aria-label="Open LinguaCart" hidden>LC</button>
    </div>
  `;

  document.documentElement.appendChild(host);

  const panel = root.getElementById("panel");
  const expand = root.getElementById("expand");
  const toggle = root.getElementById("toggle");
  const reportButton = root.getElementById("report-translation");
  const reportForm = root.getElementById("report-form");
  const preview = root.getElementById("report-preview");
  const notice = root.getElementById("notice");

  let pendingReport = null;
  let noticeTimer = null;

  function setCollapsed(collapsed) {
    panel.hidden = collapsed;
    expand.hidden = !collapsed;
  }

  function showNotice(text) {
    setCollapsed(false); // messages must be seen
    notice.textContent = text;
    notice.hidden = false;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => {
      notice.hidden = true;
    }, 5000);
  }
  notify = showNotice;

  root.getElementById("collapse").addEventListener("click", () => setCollapsed(true));
  expand.addEventListener("click", () => setCollapsed(false));

  reportButton.addEventListener("pointerdown", (event) => {
    // Preserve the page text selection when the toolbar button is pressed.
    event.preventDefault();
  });

  reportButton.addEventListener("click", () => {
    pendingReport = selectedTranslationRecord();
    if (!pendingReport) return;

    // Show exactly what will be included in the report.
    preview.textContent =
      `Original: ${pendingReport.sourceText}\n\n` +
      `Displayed: ${pendingReport.shownText}`;

    reportForm.hidden = false;
    root.getElementById("report-comment").value = "";
  });

  root.getElementById("cancel-report").addEventListener("click", () => {
    pendingReport = null;
    reportForm.hidden = true;
  });

  reportForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!pendingReport) return;

    const submitButton = root.getElementById("submit-report");
    submitButton.disabled = true;

    const feedback = {
      site: location.hostname.replace(/^www\./, ""),
      pageType: pageType(),
      sourceText: pendingReport.sourceText,
      shownText: pendingReport.shownText,
      source: pendingReport.source,
      translationType: pendingReport.translationType,
      issue: root.getElementById("report-issue").value,
      comment: root.getElementById("report-comment").value.trim() || undefined,
    };

    try {
      const response = await chrome.runtime.sendMessage({
        type: "feedback",
        feedback,
      });

      if (response?.accepted) {
        showNotice("Report sent. Thank you.");
      } else {
        showNotice(response?.message || "Report was not sent.");
      }

      reportForm.hidden = true;
      pendingReport = null;
    } catch (error) {
      console.error("LinguaCart feedback error:", error);
      showNotice("Could not send the report. Nothing was submitted.");
    } finally {
      submitButton.disabled = false;
    }
  });

  toggle.addEventListener("click", async () => {
    try {
      if (!started) {
        toggle.disabled = true;
        toggle.textContent = "Translating...";
        await translateNew();
        setView("sn");
        started = true;
        startWatching();
        toggle.disabled = false;
        toggle.textContent = "Show original";
      } else if (view === "sn") {
        setView("en");
        toggle.textContent = "Show Shona";
      } else {
        await translateNew();
        setView("sn");
        toggle.textContent = "Show original";
      }
    } catch (error) {
      console.error("LinguaCart error:", error);
      toggle.disabled = false;
      toggle.textContent = "Translation unavailable";
      showNotice("Translation failed. The original text is still available.");
    }
  });

  document.addEventListener("selectionchange", updateReportButton);
  updateTranslationMarker();
  updateReportButton();
}

if (isAllowedPage()) {
  createToolbar();
} else {
  console.log("LinguaCart: inactive on this page type");
}