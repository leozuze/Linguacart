console.log("LinguaCart service worker started");

// Fake backend that follows docs/07-API-CONTRACT.md.
// Later, replace the body of handleTranslate with a fetch() to the Java backend.
function mockTranslate(request) {
  const results = request.segments.map((seg) => {
    // test the failure path: pretend negations fail verification
    if (/\bnot\b/i.test(seg.text)) {
      return { id: seg.id, status: "not_translated", reasons: ["negation_mismatch"] };
    }
    return { id: seg.id, status: "translated", source: "nllb", text: "[SN] " + seg.text };
  });
  return { apiVersion: 1, results };
}

async function handleTranslate(request) {
  return mockTranslate(request);
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === "translate") {
    handleTranslate(message.request)
      .then(sendResponse)
      .catch((err) => sendResponse({ error: String(err) }));
    return true; // keeps the channel open for the async answer
  }
});