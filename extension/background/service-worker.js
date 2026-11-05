console.log("LinguaCart service worker started");

// ---------- Settings ----------
// USE_MOCK = true: answers locally with fake text (development only).
// USE_MOCK = false: talks to the Java backend described in docs/07-API-CONTRACT.md (v0.3).
const CONFIG = {
  USE_MOCK: true,
  BACKEND_URL: "http://localhost:8080/api/v1",
  TIMEOUT_MS: 30000,
};

const SOURCE_LANGUAGE = "eng_Latn";
const TARGET_LANGUAGE = "sna_Latn";
const MAX_SEGMENTS = 50;
const MAX_TEXT_CHARS = 2000;
const PAGE_TYPES = new Set(["product", "listing", "cart", "other"]);
const KINDS = new Set([
  "ui_label", "ui_template", "title", "description", "attribute", "review", "other",
]);

// Messages to the page always use this error shape: { error: { code, message } }
function errorResponse(code, message) {
  return { error: { code, message } };
}

// ---------- Request validation (same rules as the backend, contract v0.3) ----------
function validateRequest(request) {
  if (!request || request.sourceLanguage !== SOURCE_LANGUAGE || request.targetLanguage !== TARGET_LANGUAGE) {
    return errorResponse("validation_error", `Languages must be ${SOURCE_LANGUAGE} -> ${TARGET_LANGUAGE}`);
  }
  if (!PAGE_TYPES.has(request.pageType)) {
    return errorResponse("validation_error", "Unsupported pageType");
  }
  if (!Array.isArray(request.segments) || request.segments.length === 0) {
    return errorResponse("validation_error", "segments must not be empty");
  }
  if (request.segments.length > MAX_SEGMENTS) {
    return errorResponse("validation_error", `Maximum is ${MAX_SEGMENTS} segments`);
  }

  const ids = new Set();
  for (const segment of request.segments) {
    if (
      !segment ||
      typeof segment.id !== "string" ||
      !segment.id.trim() ||
      typeof segment.text !== "string" ||
      !segment.text.trim()
    ) {
      return errorResponse("validation_error", "Each segment needs a non-empty id and text");
    }
    if (ids.has(segment.id)) {
      return errorResponse("validation_error", "Segment IDs must be unique per request");
    }
    ids.add(segment.id);
    if (segment.text.length > MAX_TEXT_CHARS) {
      return errorResponse("validation_error", `Segment text exceeds ${MAX_TEXT_CHARS} characters`);
    }
    if (segment.kind !== undefined && !KINDS.has(segment.kind)) {
      return errorResponse("validation_error", "Unsupported segment kind");
    }
  }
  return null;
}

// ---------- Mock backend (development only, it does NOT translate into Shona) ----------
function mockTranslate(request) {
  const results = request.segments.map((seg) => {
    // Exercise the extension's safe fallback path.
    if (/\b(not|no|never|cannot|without|unavailable)\b|n['’]t\b/i.test(seg.text)) {
      return { id: seg.id, status: "not_translated", reasons: ["negation_mismatch"] };
    }
    return {
      id: seg.id,
      status: "translated",
      source: "mock",
      translationType: "mock",
      text: "[MOCK — NOT SHONA] " + seg.text,
    };
  });

  const notTranslated = results.filter((r) => r.status !== "translated").map((r) => r.id);
  const status =
    notTranslated.length === 0 ? "translated" : notTranslated.length === results.length ? "failed" : "partial";
  return { status, notTranslated, results };
}

// ---------- Real backend ----------
async function callBackend(path, { method = "GET", body } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), CONFIG.TIMEOUT_MS);
  try {
    const response = await fetch(CONFIG.BACKEND_URL + path, {
      method,
      headers: body ? { "Content-Type": "application/json" } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      credentials: "omit", // never send cookies
      cache: "no-store",
      signal: controller.signal,
    });

    let data = null;
    try {
      data = await response.json();
    } catch {
      // the body was not JSON
    }

    if (!response.ok) {
      // backend errors look like { "error": "code", "message": "..." }
      return errorResponse(
        typeof data?.error === "string" ? data.error : "backend_error",
        data?.message || `The backend answered with status ${response.status}`
      );
    }
    return data;
  } catch (err) {
    const timedOut = err?.name === "AbortError";
    return errorResponse(
      timedOut ? "backend_timeout" : "backend_unreachable",
      timedOut ? "The backend took too long to answer." : "Could not reach the backend."
    );
  } finally {
    clearTimeout(timer);
  }
}

// ---------- Handlers ----------
async function handleTranslate(request) {
  const validationError = validateRequest(request);
  if (validationError) return validationError;

  if (CONFIG.USE_MOCK) return mockTranslate(request);

  const data = await callBackend("/translate", { method: "POST", body: request });
  if (data?.error) return data;
  if (!Array.isArray(data?.results)) {
    return errorResponse("bad_response", "The backend answer had no results.");
  }
  return data;
}

async function handleFeedback(feedback) {
  if (CONFIG.USE_MOCK) {
    // Test only: logged on this computer, never sent.
    console.log("LinguaCart feedback test payload:", feedback);
    return {
      accepted: false,
      message: "Test mode: the report was only logged on this computer.",
    };
  }

  const body = { type: "bad_translation", ...feedback };
  const data = await callBackend("/feedback", { method: "POST", body });
  if (data?.error) {
    return { accepted: false, message: "Could not send the report. Nothing was submitted." };
  }
  return { accepted: data?.status === "received", message: "Report sent. Thank you." };
}

async function handleHealth() {
  if (CONFIG.USE_MOCK) return { ok: true, modelService: "mock" };

  const data = await callBackend("/health");
  if (data?.error) return { ok: false, modelService: "unknown" };
  return { ok: data?.status === "ok", modelService: data?.modelService ?? "unknown" };
}

function respond(promise, sendResponse) {
  promise
    .then(sendResponse)
    .catch((err) => sendResponse(errorResponse("internal_error", String(err))));
  return true; // keeps the channel open for the async answer
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  switch (message?.type) {
    case "translate":
      return respond(handleTranslate(message.request), sendResponse);
    case "feedback":
      return respond(handleFeedback(message.feedback), sendResponse);
    case "health":
      return respond(handleHealth(), sendResponse);
    default:
      return false;
  }
});