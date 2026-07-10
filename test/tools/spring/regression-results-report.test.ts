const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  renderRegressionRunResultsTable,
  resolveRegressionRunDirAbs,
} = require("@tools-feature-regression-suite");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

test("renderRegressionRunResultsTable renders deterministic endpoint table without memory column when undefined", () => {
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

  assert.deepEqual(rendered.columns, ["endpoint", "status", "http_code", "duration_ms", "probe_coverage"]);
  assert.equal(rendered.rows.length, 2);
  assert.equal(rendered.rows[0].endpoint, "POST /api/v1/posts");
  assert.equal(rendered.rows[0].probeCoverage, "verified_line_hit");
  assert.match(rendered.table, /\| Endpoint \| Status \| HTTP Code \| Duration \(ms\) \| Probe Coverage \|/);
  assert.doesNotMatch(rendered.table, /Memory \(bytes\)/);
});

test("renderRegressionRunResultsTable includes memory column only when contract defines memory metric", () => {
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

test("renderRegressionRunResultsTable emits deterministic blocked row when no endpoints executed", () => {
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

test("renderRegressionRunResultsTable supports object-map steps from persisted run artifacts", () => {
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

test("renderRegressionRunResultsTable maps explicit http_only step coverage to unverified-line enum", () => {
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

test("renderRegressionRunResultsTable treats non-canonical coverage token as unknown", () => {
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

test("renderRegressionRunResultsTable includes correlation summary when provided", () => {
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

test("renderRegressionRunResultsTable maps minimal correlation payload (matched + matchedEvents)", () => {
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

test("resolveRegressionRunDirAbs resolves only plan-local runs", async () => {
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
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("resolveRegressionRunDirAbs resolves project-scoped regression runs when project artifact exists", async () => {
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
    fs.rmSync(root, { recursive: true, force: true });
  }
});
