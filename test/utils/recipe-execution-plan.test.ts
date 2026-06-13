const assert = require("node:assert/strict");
const test = require("node:test");

const { buildRecipeExecutionPlan } = require("@tools-core/recipe_execution_plan.util");

const auth = {
  required: "unknown",
  status: "ok",
  strategy: "none",
  nextAction: "none",
  notes: [],
};

const requestCandidate = {
  method: "GET",
  path: "/api/catalog/items",
  queryTemplate: "?id=1",
  fullUrlHint: "http://127.0.0.1:8080/api/catalog/items?id=1",
  rationale: ["test candidate"],
};

test("regression plan does not include probe calls", () => {
  const plan = buildRecipeExecutionPlan({
    decision: {
      requestedMode: "regression",
      selectedMode: "regression",
      lineTargetProvided: false,
      probeIntentRequested: false,
      routingReason: "regression only",
    },
    requestCandidate,
    auth,
  });

  assert.equal(plan.selectedMode, "regression");
  assert.equal(plan.steps.length, 2);
  const instructions = plan.steps.map((s: any) => s.instruction).join(" ");
  assert.equal(instructions.includes("probe_"), false);
  assert.deepEqual(plan.probeCallPlan, {
    total: 0,
    verificationMethod: "probe_wait_for_hit",
    actuated: false,
    byTool: {
      probe_reset: 0,
      probe_wait_for_hit: 0,
      probe_get_status: 0,
      probe_enable: 0,
    },
  });
});

test("single_line_probe enforces reset -> execute -> verify order", () => {
  const plan = buildRecipeExecutionPlan({
    decision: {
      requestedMode: "single_line_probe",
      selectedMode: "single_line_probe",
      lineTargetProvided: true,
      probeIntentRequested: true,
      routingReason: "probe only",
    },
    requestCandidate,
    inferredTargetKey: "com.example.Foo#bar",
    lineHint: 42,
    targetFile: "src/main/java/com/example/Foo.java",
    auth,
  });

  assert.equal(plan.selectedMode, "single_line_probe");
  assert.equal(plan.steps.length, 3);
  assert.match(plan.steps[0].instruction, /probe with action=reset/);
  assert.match(plan.steps[1].instruction, /^GET /);
  assert.match(plan.steps[2].instruction, /probe action=wait_for_hit/);
  assert.doesNotMatch(plan.steps[2].instruction, /probe_get_status/);
  assert.match(plan.steps[2].instruction, /invalid_line_target/i);
  assert.match(plan.steps[2].instruction, /rebuild the app artifact and restart the JVM/i);
  assert.deepEqual(plan.probeCallPlan, {
    total: 2,
    verificationMethod: "probe_wait_for_hit",
    actuated: false,
    byTool: {
      probe_reset: 1,
      probe_wait_for_hit: 1,
      probe_get_status: 0,
      probe_enable: 0,
    },
  });
});

test("combined mode enforces reset -> API -> verify order", () => {
  const plan = buildRecipeExecutionPlan({
    decision: {
      requestedMode: "regression_plus_line_probe",
      selectedMode: "regression_plus_line_probe",
      lineTargetProvided: true,
      probeIntentRequested: true,
      routingReason: "combined",
    },
    requestCandidate,
    inferredTargetKey: "com.example.Foo#bar",
    lineHint: 21,
    targetFile: "src/main/java/com/example/Foo.java",
    auth,
  });

  assert.equal(plan.selectedMode, "regression_plus_line_probe");
  assert.equal(plan.steps.length, 3);
  assert.match(plan.steps[0].instruction, /probe with action=reset/);
  assert.match(plan.steps[1].title, /Execute regression API request/);
  assert.match(plan.steps[2].instruction, /probe action=wait_for_hit/);
  assert.doesNotMatch(plan.steps[2].instruction, /probe_get_status/);
  assert.match(plan.steps[2].instruction, /API regression assertions/);
  assert.match(plan.steps[2].instruction, /invalid_line_target/i);
  assert.match(plan.steps[2].instruction, /rebuild the app artifact and restart the JVM/i);
  assert.deepEqual(plan.probeCallPlan, {
    total: 2,
    verificationMethod: "probe_wait_for_hit",
    actuated: false,
    byTool: {
      probe_reset: 1,
      probe_wait_for_hit: 1,
      probe_get_status: 0,
      probe_enable: 0,
    },
  });
});

test("single_line_probe actuated mode adds enable and disable cleanup calls", () => {
  const plan = buildRecipeExecutionPlan({
    decision: {
      requestedMode: "single_line_probe",
      selectedMode: "single_line_probe",
      lineTargetProvided: true,
      probeIntentRequested: true,
      routingReason: "probe only",
    },
    requestCandidate,
    inferredTargetKey: "com.example.Foo#bar",
    lineHint: 42,
    targetFile: "src/main/java/com/example/Foo.java",
    actuationEnabled: true,
    actuationReturnBoolean: true,
    actuationActuatorId: "actuator-1",
    auth,
  });

  assert.equal(plan.steps.length, 5);
  assert.match(plan.steps[0].instruction, /probe with action=actuate/);
  assert.match(plan.steps[1].instruction, /probe with action=reset/);
  assert.match(plan.steps[2].instruction, /^GET /);
  assert.match(plan.steps[3].instruction, /probe action=wait_for_hit/);
  assert.equal(plan.steps[4].phase, "cleanup");
  assert.match(plan.steps[4].instruction, /probe with action=actuate/);
  assert.match(plan.steps[4].instruction, /input.action=disarm/);
  assert.deepEqual(plan.probeCallPlan, {
    total: 4,
    verificationMethod: "probe_wait_for_hit",
    actuated: true,
    byTool: {
      probe_reset: 1,
      probe_wait_for_hit: 1,
      probe_get_status: 0,
      probe_enable: 2,
    },
  });
});

test("execution request step does not claim no-auth when auth is unresolved", () => {
  const unknownAuth = {
    required: "unknown",
    status: "unknown",
    strategy: "unknown",
    nextAction: "Resolve route/auth evidence first.",
    notes: [],
  };

  const plan = buildRecipeExecutionPlan({
    decision: {
      requestedMode: "regression",
      selectedMode: "regression",
      lineTargetProvided: false,
      probeIntentRequested: false,
      routingReason: "regression only",
    },
    requestCandidate,
    auth: unknownAuth,
  });

  assert.equal(plan.steps.length, 2);
  assert.match(plan.steps[0].instruction, /Auth unresolved\./);
  assert.doesNotMatch(plan.steps[0].instruction, /No auth headers required\./);
});


