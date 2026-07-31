const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  renderRegressionRunResultsTable,
  renderRegressionRunResultsTableFromArtifacts,
  resolveRegressionRunDirAbs,
} = require("@tools-feature-regression-suite");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

test("[UT][regression-suite][regression_results_report] renderRegressionRunResultsTable renders deterministic endpoint table without memory column when undefined", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "pass",
      steps: [
        {
          order: 2,
          id: "delete_post",
          status: "pass",
          httpStatus: 204,
          durationMs: 88,
          httpMethod: "DELETE",
          path: "/api/v1/posts/123",
        },
        {
          order: 1,
          id: "create_post",
          status: "pass",
          httpStatus: 201,
          durationMs: 133,
          httpMethod: "POST",
          path: "/api/v1/posts",
        },
      ],
    },
    evidence: {
      probe: { status: "verified_line_hit" },
    },
    memoryMetricDefined: false,
  });

  assert.deepEqual(rendered.columns, [
    "endpoint",
    "status",
    "http_code",
    "duration_ms",
    "probe_coverage",
  ]);
  assert.equal(rendered.rows.length, 2);
  assert.equal(rendered.rows[0].endpoint, "POST /api/v1/posts");
  assert.equal(rendered.rows[0].probeCoverage, "verified_line_hit");
  assert.match(
    rendered.table,
    /\| Endpoint \| Status \| HTTP Code \| Duration \(ms\) \| Probe Coverage \|/,
  );
  assert.doesNotMatch(rendered.table, /Memory \(bytes\)/);
});

test("[UT][regression-suite][regression_results_report] renders deterministic failed assertion diagnostics without persisted actual values", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "fail",
      steps: [
        {
          order: 2,
          id: "submit_payment",
          status: "fail_assertion",
          method: "POST",
          path: "/payments",
          assertions: [
            {
              id: "z-redacted",
              actualPath: "response.bodyJson.status",
              operator: "field_equals",
              status: "fail",
              expected: "[REDACTED]",
              actual: "secret-value-that-must-not-render",
              reasonCode: "predicate_false",
            },
            {
              id: "a-escaped",
              actualPath: "response.bodyJson.detail",
              operator: "contains",
              status: "blocked_invalid",
              expected: `first|second\n${"x".repeat(300)}`,
              actual: "another-secret-value",
              reasonCode: "actual_path_missing",
            },
            {
              id: "passing",
              actualPath: "response.bodyJson.id",
              operator: "field_exists",
              status: "pass",
              reasonCode: "ok",
            },
          ],
        },
        {
          order: 1,
          id: "create_payment",
          status: "fail_assertion",
          method: "POST",
          path: "/payments",
          assertions: [
            {
              id: "first",
              actualPath: "response.bodyJson.amount",
              operator: "numeric_gte",
              status: "fail",
              expected: 10,
              reasonCode: "predicate_false",
            },
            {
              id: "optional",
              actualPath: "response.bodyJson.note",
              operator: "field_exists",
              status: "skipped_optional",
              reasonCode: "optional_actual_path_missing",
            },
          ],
        },
      ],
    },
    evidence: {},
    memoryMetricDefined: false,
  });

  assert.equal(rendered.failedAssertions.rows.length, 3);
  assert.deepEqual(
    rendered.failedAssertions.rows.map((row: { assertionId: string }) => row.assertionId),
    ["first", "a-escaped", "z-redacted"],
  );
  assert.equal(rendered.failedAssertions.rows[0].actual, "[not persisted]");
  assert.equal(rendered.failedAssertions.rows[1].expected.endsWith("..."), true);
  assert.match(rendered.failedAssertions.table, /first\\\|second x+/);
  assert.match(rendered.failedAssertions.table, /\[REDACTED\]/);
  assert.doesNotMatch(rendered.failedAssertions.table, /secret-value-that-must-not-render/);
  assert.doesNotMatch(rendered.failedAssertions.table, /another-secret-value/);
});

test("[UT][regression-suite][regression_results_report] omits failed assertion diagnostics when no trigger assertion failed", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "pass",
      steps: [
        {
          order: 1,
          id: "create_payment",
          status: "pass",
          assertions: [
            {
              id: "completed",
              actualPath: "response.bodyJson.status",
              operator: "field_equals",
              status: "pass",
              reasonCode: "ok",
            },
          ],
        },
      ],
    },
    evidence: {},
    memoryMetricDefined: false,
  });

  assert.equal(typeof rendered.failedAssertions, "undefined");
});

test("[UT][regression-suite][regression_results_report] fails closed when a persisted assertion cannot be mapped", async () => {
  const root = createTestTempDir("results-invalid-assertion");
  try {
    fs.writeFileSync(
      path.join(root, "execution.result.json"),
      JSON.stringify({
        status: "fail",
        preflight: { status: "ready" },
        steps: [
          {
            order: 1,
            id: "create_payment",
            status: "fail_assertion",
            assertions: [
              {
                id: "status",
                operator: "field_equals",
                status: "fail",
                reasonCode: "predicate_false",
              },
            ],
          },
        ],
      }),
      "utf8",
    );
    fs.writeFileSync(path.join(root, "evidence.json"), "{}", "utf8");

    const rendered = await renderRegressionRunResultsTableFromArtifacts({
      runDirAbs: root,
      memoryMetricDefined: false,
    });

    assert.equal(rendered.status, "blocked");
    assert.equal(rendered.reasonCode, "run_result_assertion_field_missing");
    assert.match(rendered.reason, /actualPath/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});

test("[UT][regression-suite][regression_results_report] renderRegressionRunResultsTable includes memory column only when contract defines memory metric", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "pass",
      steps: [
        {
          order: 1,
          id: "create_post",
          status: "pass",
          httpStatus: 201,
          durationMs: 100,
          memoryBytes: 2048,
        },
      ],
    },
    evidence: {},
    memoryMetricDefined: true,
  });

  assert.deepEqual(rendered.columns, [
    "endpoint",
    "status",
    "http_code",
    "duration_ms",
    "probe_coverage",
    "memory_bytes",
  ]);
  assert.equal(rendered.rows[0].memoryBytes, "2048");
  assert.match(rendered.table, /Memory \(bytes\)/);
});

test("[UT][regression-suite][regression_results_report] renderRegressionRunResultsTable emits deterministic blocked row when no endpoints executed", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "blocked",
      steps: [],
    },
    evidence: {},
    memoryMetricDefined: false,
  });

  assert.equal(rendered.rows.length, 1);
  assert.equal(rendered.rows[0].endpoint, "(no executed endpoints)");
  assert.equal(rendered.rows[0].status, "blocked");
});

test("[UT][regression-suite][regression_results_report] renderRegressionRunResultsTable supports object-map steps from persisted run artifacts", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "pass",
      steps: {
        execute_trigger: {
          status: "ok",
          httpStatus: 200,
          durationMs: 43,
          method: "GET",
          path: "/course-aggregate/3/with-details",
        },
        probe_wait_for_hit: {
          status: "ok",
          durationMs: 275,
        },
      },
    },
    evidence: {
      probeStatus: {
        hitCount: 1,
        lineValidation: "resolvable",
      },
      probe: {
        status: "verified_line_hit",
      },
    },
    memoryMetricDefined: false,
  });

  assert.equal(rendered.rows.length, 2);
  assert.equal(rendered.rows[0].endpoint, "GET /course-aggregate/3/with-details");
  assert.equal(rendered.rows[0].httpCode, "200");
  assert.equal(rendered.rows[0].probeCoverage, "verified_line_hit");
});

test("[UT][regression-suite][regression_results_report] renders object-map steps from persisted run artifacts", async () => {
  const root = createTestTempDir("results-object-map-artifact");
  try {
    fs.writeFileSync(
      path.join(root, "execution.result.json"),
      JSON.stringify({
        status: "pass",
        preflight: { status: "ready", reasonCode: "ok" },
        steps: {
          execute_trigger: {
            status: "pass",
            method: "GET",
            path: "/payments/1",
          },
        },
      }),
      "utf8",
    );
    fs.writeFileSync(path.join(root, "evidence.json"), "{}", "utf8");

    const rendered = await renderRegressionRunResultsTableFromArtifacts({
      runDirAbs: root,
      memoryMetricDefined: false,
    });

    assert.equal(rendered.rows.length, 1);
    assert.equal(rendered.rows[0].endpoint, "GET /payments/1");
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});

test("[UT][regression-suite][regression_results_report] renderRegressionRunResultsTable maps explicit http_only step coverage to unverified-line enum", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "pass",
      steps: [
        {
          order: 1,
          status: "ok",
          httpStatus: 200,
          durationMs: 31,
          method: "GET",
          path: "/courses",
          probeCoverage: "http_only_unverified_line",
        },
      ],
    },
    evidence: {},
    memoryMetricDefined: false,
  });

  assert.equal(rendered.rows.length, 1);
  assert.equal(rendered.rows[0].probeCoverage, "http_only_unverified_line");
});

test("[UT][regression-suite][regression_results_report] renderRegressionRunResultsTable treats non-canonical coverage token as unknown", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "pass",
      steps: [
        {
          order: 1,
          status: "ok",
          httpStatus: 200,
          durationMs: 31,
          method: "GET",
          path: "/courses",
          probeCoverage: "http_only",
        },
      ],
    },
    evidence: {},
    memoryMetricDefined: false,
  });

  assert.equal(rendered.rows.length, 1);
  assert.equal(rendered.rows[0].probeCoverage, "unknown");
});

test("[UT][regression-suite][regression_results_report] renderRegressionRunResultsTable includes correlation summary when provided", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "pass",
      steps: [],
    },
    evidence: {},
    memoryMetricDefined: false,
    correlation: {
      status: "ok",
      reasonCode: "ok",
      keyType: "traceId",
      keyValue: "trace-001",
      correlationSessionId: "sess-1",
      timeline: [{ eventId: "e1" }, { eventId: "e2" }],
    },
  });

  assert.equal(rendered.correlation.status, "ok");
  assert.equal(rendered.correlation.reasonCode, "ok");
  assert.equal(rendered.correlation.keyType, "traceId");
  assert.equal(rendered.correlation.keyValue, "trace-001");
  assert.equal(rendered.correlation.correlationSessionId, "sess-1");
  assert.equal(rendered.correlation.matchedEvents, 2);
});

test("[UT][regression-suite][regression_results_report] renderRegressionRunResultsTable maps minimal correlation payload (matched + matchedEvents)", () => {
  const rendered = renderRegressionRunResultsTable({
    executionResult: {
      status: "pass",
      steps: [],
    },
    evidence: {},
    memoryMetricDefined: false,
    correlation: {
      status: "matched",
      reasonCode: "correlation_event_found",
      matchedEvents: 1,
    },
  });

  assert.equal(rendered.correlation.status, "ok");
  assert.equal(rendered.correlation.reasonCode, "correlation_event_found");
  assert.equal(rendered.correlation.matchedEvents, 1);
});

test("[UT][regression-suite][regression_results_report] resolveRegressionRunDirAbs resolves only plan-local runs", async () => {
  const root = createTestTempDir("results-run-resolve");
  try {
    const projectRoot = path.join(root, ".mcpjvm", "test-project");
    fs.mkdirSync(projectRoot, { recursive: true });
    fs.writeFileSync(
      path.join(projectRoot, "projects.json"),
      `${JSON.stringify({ workspaces: [{ projectRoot: root }] }, null, 2)}\n`,
      "utf8",
    );
    const planLocalDir = path.join(
      root,
      ".mcpjvm",
      "test-project",
      "plans",
      "regression",
      "04-25-26-controller-with-auth",
      "runs",
      "1777097482619",
    );
    fs.mkdirSync(planLocalDir, { recursive: true });

    const resolvedByPlan = await resolveRegressionRunDirAbs({
      workspaceRootAbs: root,
      planName: "04-25-26-controller-with-auth",
    });
    assert.equal(resolvedByPlan, planLocalDir);

    const resolvedWithoutPlan = await resolveRegressionRunDirAbs({
      workspaceRootAbs: root,
    });
    assert.equal(resolvedWithoutPlan, null);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});

test("[UT][regression-suite][regression_results_report] resolveRegressionRunDirAbs resolves project-scoped regression runs when project artifact exists", async () => {
  const root = createTestTempDir("results-run-resolve-project");
  try {
    const projectRoot = path.join(root, ".mcpjvm", "test-project");
    fs.mkdirSync(projectRoot, { recursive: true });
    fs.writeFileSync(
      path.join(projectRoot, "projects.json"),
      `${JSON.stringify({ workspaces: [{ projectRoot: root }] }, null, 2)}\n`,
      "utf8",
    );
    const planLocalDir = path.join(
      root,
      ".mcpjvm",
      "test-project",
      "plans",
      "regression",
      "04-25-26-controller-with-auth",
      "runs",
      "1777097482619",
    );
    fs.mkdirSync(planLocalDir, { recursive: true });

    const resolvedByPlan = await resolveRegressionRunDirAbs({
      workspaceRootAbs: root,
      planName: "04-25-26-controller-with-auth",
    });
    assert.equal(resolvedByPlan, planLocalDir);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 50, retryDelay: 100 });
  }
});
