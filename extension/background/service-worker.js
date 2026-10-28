console.log("LinguaCart service worker started");

const MAX_SEGMENTS = 50;
const MAX_TEXT_CHARS = 2000;
const PAGE_TYPES = new Set(["product", "listing", "cart", "other"]);

// Test mock only. It does not translate into Shona.
function mockTranslate(request) {
  const results = request.segments.map((seg) => {
    // Exercise the extension's safe fallback path.
    if (/\b(not|no|never|cannot|without|unavailable)\b|n['’]t\b/i.test(seg.text)) {
      return {
        id: seg.id,
        status: "not_translated",
        reasons: ["negation_mismatch"],
      };
    }

    return {
      id: seg.id,
      status: "translated",
      source: "mock",
      text: "[MOCK — NOT SHONA] " + seg.text,
    };
  });

  return { apiVersion: 1, results };
}

function errorResponse(code, message) {
  return {
    apiVersion: 1,
    error: { code, message },
  };
}

function validateRequest(request) {
  if (!request || request.apiVersion !== 1) {
    return errorResponse("bad_request", "apiVersion must be 1");
  }

  if (!PAGE_TYPES.has(request.pageType)) {
    return errorResponse("bad_request", "Unsupported pageType");
  }

  if (request.target !== "sna") {
    return errorResponse("bad_request", "target must be sna");
  }

  if (!Array.isArray(request.segments) || request.segments.length === 0) {
    return errorResponse("bad_request", "segments must not be empty");
  }

  if (request.segments.length > MAX_SEGMENTS) {
    return errorResponse("too_many_segments", `Maximum is ${MAX_SEGMENTS} segments`);
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
      return errorResponse("bad_request", "Each segment needs a non-empty id and text");
    }

    if (ids.has(segment.id)) {
      return errorResponse("bad_request", "Segment IDs must be unique per request");
    }
    ids.add(segment.id);

    if (segment.text.length > MAX_TEXT_CHARS) {
      return errorResponse("bad_request", `Segment text exceeds ${MAX_TEXT_CHARS} characters`);
    }
  }

  return null;
}

async function handleTranslate(request) {
  const validationError = validateRequest(request);
  if (validationError) return validationError;

  return mockTranslate(request);
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === "feedback") {
    // Test-only: logs the report locally; it is not sent or saved.
    console.log("LinguaCart feedback test payload:", message.feedback);

    sendResponse({
      accepted: false,
      message: "Feedback backend is not connected yet; nothing was submitted.",
    });
    return; // Finish handling this message; don't fall through to translate.
  }

  if (message?.type === "translate") {
    handleTranslate(message.request)
      .then(sendResponse)
      .catch((err) => sendResponse({ error: String(err) }));

    return true; // Keeps the channel open for the async translation response.
  }
});
