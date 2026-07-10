const assert = require("node:assert/strict");
const test = require("node:test");

const {
  deriveNextActionCode,
  normalizeReasonMeta,
} = require("@tools-core/failure_diagnostics");

test("deriveNextActionCode resolves known stable mappings", () => {
  assert.equal(deriveNextActionCode("runtime_unreachable"), "verify_probe_reachability");
  assert.equal(deriveNextActionCode("target_ambiguous"), "disambiguate_target");
});

test("deriveNextActionCode sanitizes unknown reason codes", () => {
  assert.equal(deriveNextActionCode("Custom-Reason"), "custom_reason");
});

test("normalizeReasonMeta ignores unknown keys when allow-list is provided", () => {
  const out = normalizeReasonMeta(
    {
      failedStep: "line_validation",
      classHint: "com.example.Catalog",
      ignored: "x",
    },
    ["failedStep", "classHint"],
  );
  assert.deepEqual(out, {
    failedStep: "line_validation",
    classHint: "com.example.Catalog",
  });
});

test("normalizeReasonMeta returns undefined for non-objects or empty filtered objects", () => {
  assert.equal(normalizeReasonMeta(undefined, ["failedStep"]), undefined);
  assert.equal(normalizeReasonMeta("x", ["failedStep"]), undefined);
  assert.equal(
    normalizeReasonMeta({ ignored: true }, ["failedStep"]),
    undefined,
  );
});

