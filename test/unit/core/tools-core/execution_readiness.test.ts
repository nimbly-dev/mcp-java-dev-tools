const assert = require("node:assert/strict");
const test = require("node:test");

const { buildExecutionReadiness } = require("@tools-core/execution_readiness.util");

const readyAuth = {
  required: true,
  status: "auto_resolved",
  strategy: "bearer",
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

test("[UT][core][execution_readiness] execution readiness is ready when required inputs are present", () => {
  const readiness = buildExecutionReadiness({
    selectedMode: "single_line_probe",
    lineTargetProvided: true,
    requestCandidate,
    auth: readyAuth,
    actuationEnabled: false,
  });

  assert.equal(readiness.executionReadiness, "ready");
  assert.equal(readiness.missingInputs.length, 0);
});

test("[UT][core][execution_readiness] execution readiness requires user input when auth is unresolved", () => {
  const readiness = buildExecutionReadiness({
    selectedMode: "regression",
    lineTargetProvided: false,
    requestCandidate,
    auth: {
      required: "unknown",
      status: "needs_user_input",
      strategy: "unknown",
      nextAction: "Provide authToken.",
      missing: ["authToken"],
      notes: [],
    },
    actuationEnabled: false,
  });

  assert.equal(readiness.executionReadiness, "needs_user_input");
  assert.equal(
    readiness.missingInputs.some((m: any) => m.category === "auth" && m.field === "authToken"),
    true,
  );
});

test("[UT][core][execution_readiness] execution readiness requires user confirmation when candidate is uncertain", () => {
  const readiness = buildExecutionReadiness({
    selectedMode: "regression",
    lineTargetProvided: false,
    requestCandidate: {
      ...requestCandidate,
      needsConfirmation: ["Confirm endpoint path in current environment."],
    },
    auth: readyAuth,
    actuationEnabled: false,
  });

  assert.equal(readiness.executionReadiness, "needs_user_input");
  assert.equal(
    readiness.missingInputs.some((m: any) => m.category === "confirmation"),
    true,
  );
});

test("[UT][core][execution_readiness] execution readiness does not block deterministic request candidates on informational confirmations", () => {
  const readiness = buildExecutionReadiness({
    selectedMode: "regression",
    lineTargetProvided: false,
    requestCandidate: {
      ...requestCandidate,
      needsConfirmation: [
        "Best-effort candidate from controller declaration; confirm mapping/auth before execution.",
      ],
    },
    deterministicRequestInferred: true,
    auth: readyAuth,
    actuationEnabled: false,
  });

  assert.equal(readiness.executionReadiness, "ready");
  assert.equal(
    readiness.missingInputs.some((m: any) => m.category === "confirmation"),
    false,
  );
});

test("[UT][core][execution_readiness] execution readiness requires explicit actuation decision when enabled", () => {
  const readiness = buildExecutionReadiness({
    selectedMode: "single_line_probe",
    lineTargetProvided: true,
    requestCandidate,
    auth: readyAuth,
    actuationEnabled: true,
  });

  assert.equal(readiness.executionReadiness, "needs_user_input");
  assert.equal(
    readiness.missingInputs.some(
      (m: any) => m.category === "actuation" && m.field === "actuationReturnBoolean",
    ),
    true,
  );
});
