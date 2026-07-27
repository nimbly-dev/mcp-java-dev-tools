const assert = require("node:assert/strict");
const test = require("node:test");

const {
  buildRoutingContext,
  resolveSelectedMode,
} = require("@tools-core/recipe_intent_routing.util");

test("[UT][core][recipe_intent_routing] regression intent maps to regression", () => {
  const decision = resolveSelectedMode(buildRoutingContext({ intentMode: "regression" }));
  assert.equal(decision.selectedMode, "regression");
  assert.equal(decision.probeIntentRequested, false);
  assert.equal(decision.lineTargetProvided, false);
});

test("[UT][core][recipe_intent_routing] line_probe intent with explicit line maps to single_line_probe", () => {
  const decision = resolveSelectedMode(
    buildRoutingContext({ intentMode: "line_probe", lineHint: 77 }),
  );
  assert.equal(decision.selectedMode, "single_line_probe");
  assert.equal(decision.probeIntentRequested, true);
  assert.equal(decision.lineTargetProvided, true);
});

test("[UT][core][recipe_intent_routing] line_probe intent without line target keeps probe intent enabled for fail-closed handling", () => {
  const decision = resolveSelectedMode(buildRoutingContext({ intentMode: "line_probe" }));
  assert.equal(decision.selectedMode, "single_line_probe");
  assert.equal(decision.probeIntentRequested, true);
  assert.equal(decision.lineTargetProvided, false);
});

test("[UT][core][recipe_intent_routing] regression intent remains probe-disabled even with lineHint", () => {
  const decision = resolveSelectedMode(
    buildRoutingContext({ intentMode: "regression", lineHint: 21 }),
  );
  assert.equal(decision.selectedMode, "regression");
  assert.equal(decision.probeIntentRequested, false);
  assert.equal(decision.lineTargetProvided, true);
});

test("[UT][core][recipe_intent_routing] routing decision parity is unchanged when diagnostics fields are present", () => {
  const baselineContext = buildRoutingContext({
    intentMode: "line_probe",
    lineHint: 42,
  });
  const baselineDecision = resolveSelectedMode(baselineContext);

  const diagnosticAugmentedContext = {
    ...baselineContext,
    reasonCode: "runtime_line_unresolved",
    nextActionCode: "select_resolvable_line",
    reasonMeta: { failedStep: "line_validation", unknownKey: "ignored" },
  };
  const augmentedDecision = resolveSelectedMode(diagnosticAugmentedContext as any);

  assert.deepEqual(augmentedDecision, baselineDecision);
});
