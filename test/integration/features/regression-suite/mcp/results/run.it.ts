const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { writeRegressionRunArtifacts } = require("@tools-feature-regression-suite");
const { renderRegressionRunResultsTableFromArtifacts } = require("@tools-feature-regression-suite");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

test("[IT][regression-suite] full run artifacts produce deterministic tabular summary", async () => {
  const root = createTestTempDir("regression-results-it");
  try {
    const projectName = "test-project";
    const projectArtifactAbs = path.join(root, ".mcpjvm", projectName, "projects.json");
    fs.mkdirSync(path.dirname(projectArtifactAbs), { recursive: true });
    fs.writeFileSync(
      projectArtifactAbs,
      `${JSON.stringify({ workspaces: [{ projectRoot: root }] }, null, 2)}\n`,
      "utf8",
    );

    const runId = "2026-04-25T10-01-22Z_01";
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId,
      planRef: {
        name: "post-lifecycle-autoprovision",
      },
      resolvedContext: {
        tenantId: "tenant-social-001",
      },
      executionResult: {
        status: "pass",
        preflight: {
          status: "ready",
          reasonCode: "ok",
          missing: [],
          discoverablePending: [],
          prerequisiteResolution: [],
          requiredUserAction: [],
        },
        startedAt: "2026-04-25T10:01:22.000Z",
        endedAt: "2026-04-25T10:01:25.000Z",
        steps: [
          {
            order: 1,
            id: "create_post",
            status: "pass",
            httpStatus: 201,
            durationMs: 120,
            httpMethod: "POST",
            path: "/api/v1/posts",
            memoryBytes: 4096,
          },
        ],
      },
      evidence: {
        targetResolution: [
          {
            stepOrder: 1,
            fqcn: "com.example.social.post.app.controller.PostController",
            method: "createPost",
          },
        ],
        probe: {
          status: "verified_line_hit",
        },
        correlationPolicy: {
          keyType: "traceId",
          keyValue: "trace-it-001",
          maxWindowMs: 5000,
          expectedFlow: ["gateway-service", "post-service"],
          correlationSessionId: "sess-it-1",
        },
        correlationEvents: [
          {
            eventId: "ev-1",
            probeId: "gateway-service",
            timestampEpochMs: 1767265200000,
            keyType: "traceId",
            keyValue: "trace-it-001",
          },
          {
            eventId: "ev-2",
            probeId: "post-service",
            timestampEpochMs: 1767265200100,
            keyType: "traceId",
            keyValue: "trace-it-001",
          },
        ],
      },
      now: new Date("2026-04-25T10:01:25.500Z"),
    });

    const report = await renderRegressionRunResultsTableFromArtifacts({
      runDirAbs: written.runDirAbs,
      memoryMetricDefined: true,
    });

    assert.equal(report.rows.length, 1);
    assert.equal(report.rows[0].endpoint, "POST /api/v1/posts");
    assert.equal(report.rows[0].status, "pass");
    assert.equal(report.rows[0].httpCode, "201");
    assert.equal(report.rows[0].durationMs, "120");
    assert.equal(report.rows[0].probeCoverage, "verified_line_hit");
    assert.equal(report.rows[0].memoryBytes, "4096");
    assert.match(report.table, /Memory \(bytes\)/);
    assert.equal(report.correlation.status, "ok");
    assert.equal(report.correlation.reasonCode, "ok");
    assert.equal(report.correlation.keyType, "traceId");
    assert.equal(report.correlation.keyValue, "trace-it-001");
    assert.equal(report.correlation.matchedEvents, 2);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("[IT][regression-suite] persisted failed assertions render deterministic diagnostics without actual values", async () => {
  const root = createTestTempDir("regression-failed-assertions-it");
  try {
    const projectName = "test-project";
    const projectArtifactAbs = path.join(root, ".mcpjvm", projectName, "projects.json");
    fs.mkdirSync(path.dirname(projectArtifactAbs), { recursive: true });
    fs.writeFileSync(
      projectArtifactAbs,
      `${JSON.stringify({ workspaces: [{ projectRoot: root }] }, null, 2)}\n`,
      "utf8",
    );

    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId: "2026-04-25T10-02-22Z_01",
      planRef: { name: "payment-status" },
      resolvedContext: {},
      executionResult: {
        status: "fail",
        preflight: {
          status: "ready",
          reasonCode: "ok",
          missing: [],
          discoverablePending: [],
          prerequisiteResolution: [],
          requiredUserAction: [],
        },
        startedAt: "2026-04-25T10:02:22.000Z",
        endedAt: "2026-04-25T10:02:23.000Z",
        steps: [
          {
            order: 1,
            id: "submit_payment",
            status: "fail_assertion",
            httpMethod: "POST",
            path: "/payments",
            assertions: [
              {
                id: "payment-status",
                actualPath: "response.bodyJson.status",
                operator: "field_equals",
                required: true,
                status: "fail",
                expected: "COMPLETED",
                actual: "PENDING",
                reasonCode: "predicate_false",
              },
            ],
          },
        ],
      },
      evidence: { targetResolution: [] },
      now: new Date("2026-04-25T10:02:23.500Z"),
    });

    const persisted = JSON.parse(
      fs.readFileSync(path.join(written.runDirAbs, "execution.result.json"), "utf8"),
    );
    assert.equal(typeof persisted.steps[0].assertions[0].actual, "undefined");

    const report = await renderRegressionRunResultsTableFromArtifacts({
      runDirAbs: written.runDirAbs,
      memoryMetricDefined: false,
    });

    assert.equal(report.failedAssertions.rows.length, 1);
    assert.equal(report.failedAssertions.rows[0].endpoint, "POST /payments");
    assert.equal(report.failedAssertions.rows[0].expected, "COMPLETED");
    assert.equal(report.failedAssertions.rows[0].actual, "[not persisted]");
    assert.match(report.failedAssertions.table, /predicate_false/);
    assert.doesNotMatch(report.failedAssertions.table, /PENDING/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
