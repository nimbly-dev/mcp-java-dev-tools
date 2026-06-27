const assert = require("node:assert/strict");
const test = require("node:test");

const {
  deriveRunStatusFromStepOutcomes,
  evaluateStepExpectations,
} = require("@tools-regression-execution-plan-spec/regression_expectation_evaluator.util");

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
    httpFailure: false,
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
    httpFailure: false,
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

test("evaluateStepExpectations returns blocked_runtime on invalid expectation mapping", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      status: "pass",
    },
    httpFailure: false,
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

test("evaluateStepExpectations prefers dependency/http failures over assertion pass", () => {
  const dependencyBlocked = evaluateStepExpectations({
    stepResult: { status: "pass" },
    httpFailure: false,
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
    httpFailure: true,
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
  assert.equal(httpFailed.status, "fail_http");
});

test("evaluateStepExpectations treats expected non-2xx response as pass when required assertions succeed", () => {
  const evaluated = evaluateStepExpectations({
    stepResult: {
      status: "fail",
      response: { statusCode: 404, body: "{\"error\":\"missing\"}" },
    },
    httpFailure: true,
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
    httpFailure: false,
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

