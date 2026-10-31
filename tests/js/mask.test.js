// Runs tests/shared/mask_cases.json against extension/content/masking.js.
// Usage, from the repository root:   node tests/js/mask.test.js

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const { maskText, unmaskText } = require("../../extension/content/masking.js");

const cases = JSON.parse(
  fs.readFileSync(path.join(__dirname, "..", "shared", "mask_cases.json"), "utf8")
);

let failed = 0;

for (const c of cases) {
  try {
    const { masked, values } = maskText(c.input, c.query || "");

    if (!c.roundTripOnly) {
      assert.equal(masked, c.masked, "masked text");
      assert.deepEqual(values, c.values, "extracted values");
    }

    // The most important property: putting the values back gives exactly the original.
    assert.equal(unmaskText(masked, values), c.input, "restored text");

    console.log("PASS  " + c.name);
  } catch (error) {
    failed++;
    console.log("FAIL  " + c.name);
    console.log("      input:    " + c.input);
    console.log("      " + error.message.split("\n").join("\n      "));
  }
}

console.log(`\n${cases.length - failed} of ${cases.length} passed`);
process.exit(failed ? 1 : 0);
