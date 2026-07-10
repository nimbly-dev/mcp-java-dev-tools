const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  writeRegressionRunArtifacts,
} = require("@tools-feature-regression-suite");
const {
  renderRegressionRunResultsTableFromArtifacts,
} = require("@tools-feature-regression-suite");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

test("watcher artifacts produce deterministic watcher summary and detail rendering", async () => {
  const root = createTestTempDir("regression-watcher-results-it");
  try {
    const projectName = "test-project";
    const projectArtifactAbs = path.join(root, ".mcpjvm", projectName, "projects.json");
    fs.mkdirSync(path.dirname(projectArtifactAbs), { recursive: true });
    fs.writeFileSync(
      projectArtifactAbs,
      `${JSON.stringify({ workspaces: [{ projectRoot: root }] }, null, 2)}\n`,
      "utf8",
    );

    const runId = "2026-04-25T10-01-22Z_02";
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId,
      planRef: {
        name: "cross-service-indexing",
      },
      resolvedContext: {
        tenantId: "tenant-social-001",
      },
      executionResult: {
        status: "blocked",
        triggerStatus: "pass",
        watcherStatus: "blocked",
        preflight: {
          status: "ready",
          reasonCode: "ok",
          missing: [],
          discoverablePending: [],
          prerequisiteResolution: [],
          requiredUserAction: [],
        },
        startedAt: "2026-04-25T10:01:22.000Z",
        endedAt: "2026-04-25T10:01:30.000Z",
        steps: [
          {
            order: 1,
            id: "trigger_index",
            status: "pass",
            httpStatus: 202,
            durationMs: 120,
            httpMethod: "POST",
            path: "/api/v1/index",
          },
        ],
        watchers: [
          {
            id: "search-index",
            dependencyStepOrder: 1,
            providerType: "http",
            status: "pass",
            outcome: "verified",
            attemptCount: 2,
            durationMs: 300,
            reasonCode: "watcher_verified",
            waitPolicy: {
              timeoutMs: 5000,
              retryMax: 4,
            },
          },
          {
            id: "feed-cache",
            dependencyStepOrder: 1,
            providerType: "http",
            status: "blocked_runtime",
            outcome: "timed_out",
            attemptCount: 4,
            durationMs: 5000,
            reasonCode: "watcher_timeout",
            waitPolicy: {
              timeoutMs: 5000,
              retryMax: 4,
            },
          },
        ],
      },
      evidence: {
        targetResolution: [
          {
            stepOrder: 1,
            fqcn: "com.example.social.event.app.controller.ExampleEventController",
            method: "triggerIndex",
          },
        ],
        watcherExecutions: [
          {
            id: "search-index",
            dependencyStepOrder: 1,
            providerType: "http",
            status: "ok",
            outcome: "verified",
            attemptCount: 2,
            durationMs: 300,
            reasonCode: "watcher_verified",
            waitPolicy: {
              timeoutMs: 5000,
              timeoutSource: "unresolved",
              retryMax: 4,
              retrySource: "unresolved",
            },
          },
          {
            id: "feed-cache",
            dependencyStepOrder: 1,
            providerType: "http",
            status: "timed_out",
            outcome: "timeout",
            attemptCount: 4,
            durationMs: 5000,
            reasonCode: "watcher_timeout",
            waitPolicy: {
              timeoutMs: 5000,
              timeoutSource: "unresolved",
              retryMax: 4,
              retrySource: "unresolved",
            },
          },
        ],
      },
      now: new Date("2026-04-25T10:01:30.500Z"),
    });

    const report = await renderRegressionRunResultsTableFromArtifacts({
      runDirAbs: written.runDirAbs,
      memoryMetricDefined: false,
    });

    assert.ok(report.watchers);
    assert.equal(report.watchers.summary.triggerStatus, "pass");
    assert.equal(report.watchers.summary.watcherStatus, "blocked");
    assert.equal(report.watchers.summary.watcherCount, 2);
    assert.equal(report.watchers.summary.verifiedCount, 1);
    assert.equal(report.watchers.summary.timedOutCount, 1);
    assert.equal(report.watchers.summary.blockedCount, 0);
    assert.equal(report.watchers.rows.length, 2);
    assert.equal(report.watchers.rows[0].id, "feed-cache");
    assert.equal(report.watchers.rows[0].reasonCode, "watcher_timeout");
    assert.equal(report.watchers.rows[1].id, "search-index");
    assert.equal(report.watchers.rows[1].timeoutMs, "5000");
    assert.match(report.watchers.table, /Watcher ID/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
