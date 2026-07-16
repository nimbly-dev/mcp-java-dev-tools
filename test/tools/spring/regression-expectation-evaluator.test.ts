const assert = require("node:assert/strict");
const test = require("node:test");

const {
  deriveRunStatusFromStepOutcomes,
  evaluateStepExpectations,
} = require("@tools-feature-regression-suite");

test("evaluateStepExpectations returns pass when all required expectations pass", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      status: "pass",
      transport: {
        status_code: 201,
      },
      runtime: {
        probe: {
          hit: true,
        },
      },
    },
    transportFailure: false,
    dependencyBlocked: false,
    expectations: [
      {
        id: "status-pass",
        actualPath: "status",
        operator: "outcome_status",
        expected: "pass",
      },
      {
        id: "http-created",
        actualPath: "transport.status_code",
        operator: "numeric_gte",
        expected: 200,
      },
      {
        id: "probe-hit",
        actualPath: "runtime.probe.hit",
        operator: "probe_line_hit",
        expected: true,
      },
    ],
  });

  assert.equal(evaluated.status, "pass");
  assert.equal(evaluated.assertions.length, 3);
  assert.equal(evaluated.assertions.every((entry: { status: string }) => entry.status === "pass"), true);
});

test("evaluateStepExpectations returns fail_assertion when required predicate fails", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      status: "pass",
      transport: { status_code: 500 },
    },
    transportFailure: false,
    dependencyBlocked: false,
    expectations: [
      {
        id: "http-ok",
        actualPath: "transport.status_code",
        operator: "numeric_lte",
        expected: 299,
      },
    ],
  });
  assert.equal(evaluated.status, "fail_assertion");
  assert.equal(evaluated.assertions[0].status, "fail");
  assert.equal(evaluated.assertions[0].reasonCode, "predicate_false");
});

test("evaluateStepExpectations supports exact decimal strings for numeric comparisons", () => {
  const gte = evaluateStepExpectations({
    stepResult: {
      sql: {
        firstRow: {
          indexed_count: "500.12345678901234567890",
        },
      },
    },
    transportFailure: false,
    dependencyBlocked: false,
    expectations: [
      {
        id: "decimal-gte",
        actualPath: "sql.firstRow.indexed_count",
        operator: "numeric_gte",
        expected: "500.12345678901234567889",
      },
      {
        id: "decimal-lte",
        actualPath: "sql.firstRow.indexed_count",
        operator: "numeric_lte",
        expected: "500.12345678901234567890",
      },
    ],
  });

  assert.equal(gte.status, "pass");
  assert.equal(gte.assertions.every((entry: { status: string }) => entry.status === "pass"), true);
});

test("evaluateStepExpectations returns blocked_runtime on invalid expectation mapping", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      status: "pass",
    },
    transportFailure: false,
    dependencyBlocked: false,
    expectations: [
      {
        id: "missing-path",
        actualPath: "transport.status_code",
        operator: "field_exists",
      },
    ],
  });
  assert.equal(evaluated.status, "blocked_runtime");
  assert.equal(evaluated.assertions[0].status, "blocked_invalid");
  assert.equal(evaluated.assertions[0].reasonCode, "actual_path_missing");
});

test("evaluateStepExpectations marks an optional missing path as skipped without blocking", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: { status: "pass" },
    transportFailure: false,
    dependencyBlocked: false,
    expectations: [
      {
        id: "optional-field",
        actualPath: "response.bodyJson.state",
        operator: "field_equals",
        expected: "ready",
        required: false,
      },
    ],
  });

  assert.equal(evaluated.status, "pass");
  assert.equal(evaluated.assertions[0]?.status, "skipped_optional");
  assert.equal(evaluated.assertions[0]?.reasonCode, "optional_actual_path_missing");
});

test("evaluateStepExpectations preserves a predicate failure when another required path is missing", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: { response: { statusCode: 500 } },
    transportFailure: false,
    dependencyBlocked: false,
    expectations: [
      {
        id: "missing-required-field",
        actualPath: "response.bodyJson.state",
        operator: "field_equals",
        expected: "ready",
      },
      {
        id: "wrong-status",
        actualPath: "response.statusCode",
        operator: "field_equals",
        expected: 200,
      },
    ],
  });

  assert.equal(evaluated.status, "fail_assertion");
  assert.equal(evaluated.assertions[0]?.reasonCode, "actual_path_missing");
  assert.equal(evaluated.assertions[1]?.reasonCode, "predicate_false");
});

test("evaluateStepExpectations prefers dependency or transport failures over assertion pass", () => {
  const dependencyBlocked = evaluateStepExpectations({
    stepResult: { status: "pass" },
    transportFailure: false,
    dependencyBlocked: true,
    expectations: [
      {
        id: "status-pass",
        actualPath: "status",
        operator: "outcome_status",
        expected: "pass",
      },
    ],
  });
  assert.equal(dependencyBlocked.status, "blocked_dependency");

  const httpFailed = evaluateStepExpectations({
    stepResult: { status: "fail", response: { statusCode: 500 } },
    transportFailure: true,
    dependencyBlocked: false,
    expectations: [
      {
        id: "http-error-optional",
        actualPath: "response.statusCode",
        operator: "field_equals",
        expected: 500,
        required: false,
      },
    ],
  });
  assert.equal(httpFailed.status, "pass");
});

test("evaluateStepExpectations treats transport failures as pass when authored assertions succeed", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      status: "fail",
      response: { statusCode: 404, body: "{\"error\":\"missing\"}" },
    },
    transportFailure: true,
    dependencyBlocked: false,
    expectations: [
      {
        id: "http-not-found",
        actualPath: "response.statusCode",
        operator: "field_equals",
        expected: 404,
      },
      {
        id: "body-has-error",
        actualPath: "response.body",
        operator: "contains",
        expected: "missing",
      },
    ],
  });

  assert.equal(evaluated.status, "pass");
  assert.equal(evaluated.assertions.every((entry: { status: string }) => entry.status === "pass"), true);
});

test("evaluateStepExpectations fails step when any authored assertion fails on transport failure", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      status: "fail",
      response: { statusCode: 404, body: "{\"error\":\"unexpected\"}" },
    },
    transportFailure: true,
    dependencyBlocked: false,
    expectations: [
      {
        id: "http-not-found",
        actualPath: "response.statusCode",
        operator: "field_equals",
        expected: 404,
      },
      {
        id: "body-has-missing",
        actualPath: "response.body",
        operator: "contains",
        expected: "missing",
        required: false,
      },
    ],
  });

  assert.equal(evaluated.status, "fail_assertion");
  assert.equal(evaluated.assertions[0].status, "pass");
  assert.equal(evaluated.assertions[1].status, "fail");
});

test("evaluateStepExpectations supports array index notation in actualPath", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      response: {
        bodyJson: {
          names: [
            { locale: "*", value: "Test" },
            { locale: "en", value: "Test EN" },
          ],
        },
      },
    },
    transportFailure: false,
    dependencyBlocked: false,
    expectations: [
      {
        id: "first-name-value",
        actualPath: "response.bodyJson.names[0].value",
        operator: "field_equals",
        expected: "Test",
      },
    ],
  });

  assert.equal(evaluated.status, "pass");
  assert.equal(evaluated.assertions[0].status, "pass");
  assert.equal(evaluated.assertions[0].actual, "Test");
});

test("evaluateStepExpectations resolves documented and persisted aliases for step assertions", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      status: "pass",
      response: {
        statusCode: 200,
      },
      probe: {
        hit: true,
      },
    },
    transportFailure: false,
    dependencyBlocked: false,
    expectations: [
      {
        id: "persisted-status-code",
        actualPath: "statusCode",
        operator: "field_equals",
        expected: 200,
      },
      {
        id: "legacy-transport-status-code",
        actualPath: "transport.status_code",
        operator: "field_equals",
        expected: 200,
      },
      {
        id: "legacy-runtime-probe-hit",
        actualPath: "runtime.probe.hit",
        operator: "probe_line_hit",
        expected: true,
      },
      {
        id: "outcome-alias",
        actualPath: "outcome",
        operator: "outcome_status",
        expected: "pass",
      },
    ],
  });

  assert.equal(evaluated.status, "pass");
  assert.equal(evaluated.assertions.every((entry: { status: string }) => entry.status === "pass"), true);
});

test("deriveRunStatusFromStepOutcomes maps continue-on-fail policy deterministically", () => {
  assert.equal(
    deriveRunStatusFromStepOutcomes({
      hardRuntimeBlocker: true,
      stepOutcomes: [{ status: "pass" }],
    }),
    "blocked",
  );
  assert.equal(
    deriveRunStatusFromStepOutcomes({
      hardRuntimeBlocker: false,
      stepOutcomes: [{ status: "pass" }, { status: "fail_assertion" }],
    }),
    "fail",
  );
  assert.equal(
    deriveRunStatusFromStepOutcomes({
      hardRuntimeBlocker: false,
      stepOutcomes: [{ status: "pass" }, { status: "fail_http", required: false }],
    }),
    "pass",
  );
  assert.equal(
    deriveRunStatusFromStepOutcomes({
      hardRuntimeBlocker: false,
      stepOutcomes: [{ status: "pass" }, { status: "blocked_dependency" }],
    }),
    "fail",
  );
  assert.equal(
    deriveRunStatusFromStepOutcomes({
      hardRuntimeBlocker: false,
      stepOutcomes: [{ status: "pass" }],
    }),
    "pass",
  );
});
