const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const { DatabaseSync } = require("node:sqlite");

const {
  buildRunArtifactDirAbs,
  rebuildCorrelationIndex,
  writeRegressionRunArtifacts,
} = require("@tools-feature-regression-suite");
const { rebuildRunStateStore } = require("@tools-feature-artifact-management");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function readJson(filePath: string) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function initProjectArtifact(root: string, projectName = "test-project"): string {
  const projectArtifactAbs = path.join(root, ".mcpjvm", projectName, "projects.json");
  fs.mkdirSync(path.dirname(projectArtifactAbs), { recursive: true });
  fs.writeFileSync(
    projectArtifactAbs,
    `${JSON.stringify({ workspaces: [{ projectRoot: root }] }, null, 2)}\n`,
    "utf8",
  );
  return projectName;
}

test("buildRunArtifactDirAbs fails closed for invalid run id", () => {
  const root = createTestTempDir("run-artifacts-invalid");
  try {
    initProjectArtifact(root);
    assert.throws(
      () => buildRunArtifactDirAbs(root, "post-lifecycle", "2026/04/19-01"),
      /run_id_invalid/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("rebuildRunStateStore reconstructs canonical terminal run state and replaces the live database atomically", async () => {
  const root = createTestTempDir("rebuild-run-state");
  let database;
  try {
    const projectName = initProjectArtifact(root);
    const runId = "2026-04-19T08-01-22Z_99";
    await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      projectName,
      runId,
      planRef: { name: "rebuild-plan" },
      resolvedContext: { tenantId: "tenant-1" },
      executionResult: {
        status: "pass",
        startedAt: "2026-04-19T08:01:22.000Z",
        endedAt: "2026-04-19T08:01:23.000Z",
        steps: [{ order: 1, id: "trigger", status: "pass" }],
      },
      evidence: { targetResolution: [] },
      now: new Date("2026-04-19T08:01:24.000Z"),
    });
    const databasePath = path.join(root, ".mcpjvm", projectName, "run-state.sqlite");
    const rebuilt = await rebuildRunStateStore({ workspaceRootAbs: root, projectName });
    assert.equal(rebuilt.ok, true);
    if (!rebuilt.ok) return;
    assert.equal(rebuilt.summary.scannedRuns, 1);
    assert.equal(rebuilt.summary.rebuiltRuns, 1);
    database = new DatabaseSync(databasePath);
    assert.equal(database.prepare("SELECT count(*) AS count FROM plan_runs").get().count, 1);
    assert.equal(database.prepare("SELECT count(*) AS count FROM artifacts").get().count, 3);
    assert.equal(database.prepare("SELECT status FROM plan_runs").get().status, "executed");
  } finally {
    if (database) database.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts persists context/result/evidence under .mcpjvm/<project>/plans/regression/<plan>/runs/<run_id>", async () => {
  const root = createTestTempDir("run-artifacts");
  try {
    const projectName = initProjectArtifact(root);
    const runId = "2026-04-19T08-01-22Z_01";
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId,
      planRef: {
        name: "gateway-course-review-aggregate-smoke",
        path: `.mcpjvm/${projectName}/plans/regression/gateway-course-review-aggregate-smoke`,
      },
      resolvedContext: {
        scope: "service",
        tenantId: "tenant-social-001",
        "auth.bearer": "SHOULD_NOT_PERSIST",
        requestBody: {
          title: "Hello World!",
          token: "REMOVE_ME",
        },
      },
      secretContextKeys: ["auth.bearer"],
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
        startedAt: "2026-04-19T08:01:22.111Z",
        endedAt: "2026-04-19T08:01:25.333Z",
        steps: [{ order: 1, id: "create_tag", status: "pass" }],
      },
      evidence: {
        targetResolution: [
          {
            fqcn: "com.example.gateway.tags.TagController",
            method: "createTag",
            sourceRoot: "services/gateway/src/main/java",
          },
        ],
        authMode: { scheme: "bearer", provided: true },
        discovery: {
          attempted: true,
          status: "resolved",
          reasonCode: "ok",
          outcomes: [
            {
              key: "tenantId",
              source: "datasource",
              outcome: "resolved",
              reasonCode: "ok",
              sourceRef: "public.tenants",
            },
            {
              key: "auth.bearer",
              source: "runtime_context",
              outcome: "resolved",
              reasonCode: "ok",
              sourceRef: "Bearer abcdefghijk",
              token: "REMOVE_ME",
              internalDebug: "should be stripped",
            },
          ],
        },
      },
      correlation: {
        status: "ok",
        reasonCode: "ok",
        correlationSessionId: "sess-2026-04-19",
        keyType: "traceId",
        keyValue: "trace-001",
        window: {
          startEpochMs: 1767265200000,
          endEpochMs: 1767265202000,
          maxWindowMs: 60000,
        },
        expectedFlow: ["gateway-service", "course-service"],
        timeline: [
          {
            eventId: "e-2",
            probeId: "course-service",
            timestampEpochMs: 1767265201200,
            lineKey: "com.example.CourseController#get:22",
            sequenceOrder: 2,
            selectorPolicy: "exact_instance",
            operator: "exact",
            expectedHitDelta: 1,
            runtimeInstanceId: "course-instance-1",
            baselineHitCount: 4,
            currentHitCount: 5,
          },
          {
            eventId: "e-1",
            probeId: "gateway-service",
            timestampEpochMs: 1767265200100,
            lineKey: "com.example.GatewayController#route:88",
            sequenceOrder: 1,
            selectorPolicy: "exact_instance",
            operator: "exact",
            expectedHitDelta: 1,
            runtimeInstanceId: "gateway-instance-1",
            baselineHitCount: 9,
            currentHitCount: 10,
          },
        ],
        evidenceRefs: ["ev-1", "ev-2"],
      },
      now: new Date("2026-04-19T08:01:26.000Z"),
    });

    assert.ok(fs.existsSync(written.contextResolvedPathAbs));
    assert.ok(fs.existsSync(written.executionResultPathAbs));
    assert.ok(fs.existsSync(written.evidencePathAbs));
    assert.ok(fs.existsSync(written.correlationPathAbs));
    assert.equal(
      fs.existsSync(path.join(root, ".mcpjvm", "test-project", "correlation-index.json")),
      false,
    );
    assert.equal(
      fs.existsSync(path.join(root, ".mcpjvm", "test-project", "run-state.sqlite")),
      true,
    );

    const context = readJson(written.contextResolvedPathAbs);
    const result = readJson(written.executionResultPathAbs);
    const evidence = readJson(written.evidencePathAbs);
    const correlation = readJson(written.correlationPathAbs);

    assert.equal(
      written.runDirAbs,
      path.join(
        root,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        "gateway-course-review-aggregate-smoke",
        "runs",
        runId,
      ),
    );
    assert.equal(context.resolvedAt, "2026-04-19T08:01:26.000Z");
    assert.equal(context.redaction.resolvedSecretKeyCount, 1);
    assert.deepEqual(context.redaction.resolvedSecretKeysOmitted, ["auth.bearer"]);
    assert.equal(context.tenantId, "tenant-social-001");
    assert.equal(typeof context.scope, "undefined");
    assert.equal(typeof context["auth.bearer"], "undefined");
    assert.equal(typeof context.requestBody.token, "undefined");
    assert.equal(result.status, "pass");
    assert.equal(result.runId, runId);
    assert.equal(typeof result.executionProfile, "undefined");
    assert.equal(evidence.runId, runId);
    assert.equal(evidence.authMode.scheme, "bearer");
    assert.equal(evidence.discovery.outcomes[0].key, "auth.bearer");
    assert.equal(typeof evidence.discovery.outcomes[0].token, "undefined");
    assert.equal(typeof evidence.discovery.outcomes[0].internalDebug, "undefined");
    assert.equal(evidence.discovery.outcomes[0].sourceRef, "[REDACTED]");
    assert.equal(evidence.discovery.outcomes[1].key, "tenantId");
    assert.equal(evidence.discovery.outcomes[1].sourceRef, "public.tenants");
    assert.equal(correlation.status, "ok");
    assert.equal(correlation.timeline[0].eventId, "e-1");
    assert.equal(correlation.timeline[1].eventId, "e-2");
    assert.match(
      String(written.correlationPathAbs).replaceAll("\\", "/"),
      /\/correlation\/correlation\.json$/,
    );
    const database = new DatabaseSync(path.join(root, ".mcpjvm", projectName, "run-state.sqlite"));
    assert.equal(
      database.prepare("SELECT count(*) AS count FROM correlation_probe_observations").get().count,
      2,
    );
    assert.equal(
      database.prepare("SELECT matched_line_count AS count FROM correlation_runs").get().count,
      2,
    );
    database.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts fails closed when planRef.name is missing", async () => {
  const root = createTestTempDir("run-artifacts-missing-plan");
  try {
    initProjectArtifact(root);
    await assert.rejects(
      () =>
        writeRegressionRunArtifacts({
          workspaceRootAbs: root,
          runId: "2026-04-19T08-01-22Z_01",
          resolvedContext: {},
          executionResult: {
            status: "blocked",
            preflight: {
              status: "blocked_invalid",
              reasonCode: "target_missing",
              missing: [],
              discoverablePending: [],
              prerequisiteResolution: [],
              requiredUserAction: [],
            },
            startedAt: null,
            endedAt: null,
            steps: [],
          },
          evidence: {
            targetResolution: [],
          },
        }),
      /plan_name_missing/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("buildRunArtifactDirAbs accepts epoch-like numeric run id", () => {
  const root = createTestTempDir("run-artifacts-epoch");
  try {
    initProjectArtifact(root);
    const runId = "1777691534330";
    const out = buildRunArtifactDirAbs(root, "post-lifecycle", runId);
    assert.match(out, new RegExp(`${runId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts auto-generates correlation artifact from evidence policy/events", async () => {
  const root = createTestTempDir("run-artifacts-auto-correlation");
  try {
    initProjectArtifact(root);
    const runId = "2026-04-19T08-01-22Z_02";
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId,
      planRef: {
        name: "gateway-course-review-aggregate-smoke",
      },
      resolvedContext: {
        traceId: "trace-xyz-001",
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
        startedAt: "2026-04-19T08:01:22.111Z",
        endedAt: "2026-04-19T08:01:25.333Z",
        steps: [{ order: 1, id: "create_tag", status: "pass" }],
      },
      evidence: {
        targetResolution: [],
        correlationPolicy: {
          keyType: "traceId",
          keyValueContextPath: "traceId",
          maxWindowMs: 5000,
          expectedFlow: ["gateway-service", "course-service"],
          correlationSessionId: "sess-1",
        },
        correlationEvents: [
          {
            eventId: "ev-2",
            probeId: "course-service",
            timestampEpochMs: 1767265201400,
            keyType: "traceId",
            keyValue: "trace-xyz-001",
          },
          {
            eventId: "ev-1",
            probeId: "gateway-service",
            timestampEpochMs: 1767265201000,
            keyType: "traceId",
            keyValue: "trace-xyz-001",
          },
        ],
      },
      now: new Date("2026-04-19T08:01:26.000Z"),
    });

    assert.ok(fs.existsSync(written.correlationPathAbs));
    const correlation = readJson(written.correlationPathAbs);
    assert.equal(correlation.status, "ok");
    assert.equal(correlation.keyValue, "trace-xyz-001");
    assert.equal(correlation.timeline[0].eventId, "ev-1");
    assert.equal(correlation.timeline[1].eventId, "ev-2");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts does not generate correlation artifact without canonical correlation inputs", async () => {
  const root = createTestTempDir("run-artifacts-no-correlation");
  try {
    initProjectArtifact(root);
    const runId = "2026-04-19T08-01-22Z_03";
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId,
      planRef: {
        name: "probe-registry-course-service-smoke",
      },
      resolvedContext: {},
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
        startedAt: "2026-04-19T08:01:22.111Z",
        endedAt: "2026-04-19T08:01:25.333Z",
        steps: [{ order: 1, id: "course_list", status: "pass" }],
      },
      evidence: {
        targetResolution: [],
        endpoint: "GET http://localhost:9001/api/courses",
      },
      now: new Date("2026-04-19T08:01:26.000Z"),
    });

    assert.equal(typeof written.correlationPathAbs, "undefined");
    assert.equal(typeof written.correlationIndexPathAbs, "undefined");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("rebuildCorrelationIndex regenerates canonical index from existing correlation artifacts", async () => {
  const root = createTestTempDir("rebuild-correlation-index");
  try {
    initProjectArtifact(root);
    const runId = "2026-04-19T08-01-22Z_04";
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId,
      planRef: { name: "probe-registry-course-service-smoke" },
      resolvedContext: {},
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
        startedAt: "2026-04-19T08:01:22.111Z",
        endedAt: "2026-04-19T08:01:25.333Z",
        steps: [{ order: 1, id: "course_list", status: "pass" }],
      },
      evidence: {
        targetResolution: [],
        correlationPolicy: {
          keyType: "traceId",
          keyValue: "trace-abc-002",
          maxWindowMs: 5000,
        },
        correlationEvents: [
          {
            eventId: "ev-1",
            probeId: "course-service",
            timestampEpochMs: 1767265200000,
            keyType: "traceId",
            keyValue: "trace-abc-002",
          },
        ],
      },
      now: new Date("2026-04-19T08:01:26.000Z"),
    });
    assert.ok(fs.existsSync(written.correlationPathAbs));

    const indexPath = path.join(root, ".mcpjvm", "test-project", "correlation-index.json");
    fs.writeFileSync(
      indexPath,
      `${JSON.stringify({ version: 1, generatedAt: "2026-04-19T08:01:27.000Z", entries: [] }, null, 2)}\n`,
      "utf8",
    );

    const rebuilt = await rebuildCorrelationIndex({
      workspaceRootAbs: root,
      now: new Date("2026-04-19T08:01:27.000Z"),
    });
    assert.equal(rebuilt.entriesCount, 1);
    const rebuiltIndex = readJson(rebuilt.indexPathAbs);
    assert.equal(rebuiltIndex.version, 1);
    assert.equal(rebuiltIndex.entries.length, 1);
    assert.equal(rebuiltIndex.entries[0].planName, "probe-registry-course-service-smoke");
    assert.equal(rebuiltIndex.entries[0].runId, runId);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts uses project-scoped regression root when .mcpjvm/<project>/projects.json exists", async () => {
  const root = createTestTempDir("run-artifacts-project-scoped");
  try {
    const projectName = "test-project";
    const projectArtifactAbs = path.join(root, ".mcpjvm", projectName, "projects.json");
    fs.mkdirSync(path.dirname(projectArtifactAbs), { recursive: true });
    fs.writeFileSync(
      projectArtifactAbs,
      `${JSON.stringify({ workspaces: [{ projectRoot: root }] }, null, 2)}\n`,
      "utf8",
    );

    const runId = "1777699999999";
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId,
      planRef: { name: "probe-registry-course-service-smoke" },
      resolvedContext: {},
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
        startedAt: "2026-04-19T08:01:22.111Z",
        endedAt: "2026-04-19T08:01:25.333Z",
        steps: [{ order: 1, id: "course_list", status: "pass" }],
      },
      evidence: { targetResolution: [] },
      now: new Date("2026-04-19T08:01:26.000Z"),
    });

    assert.match(
      written.runDirAbs.replaceAll("\\", "/"),
      /\.mcpjvm\/test-project\/plans\/regression\/probe-registry-course-service-smoke\/runs\/1777699999999$/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts preserves legacy watcher evidence by normalizing it to canonical values", async () => {
  const root = createTestTempDir("run-artifacts-legacy-watcher-evidence");
  try {
    initProjectArtifact(root);
    const runId = "2026-04-19T08-01-22Z_05";
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId,
      planRef: { name: "probe-registry-course-service-smoke" },
      resolvedContext: {},
      executionResult: {
        status: "blocked",
        watcherStatus: "blocked",
        preflight: {
          status: "ready",
          reasonCode: "ok",
          missing: [],
          discoverablePending: [],
          prerequisiteResolution: [],
          requiredUserAction: [],
        },
        startedAt: "2026-04-19T08:01:22.111Z",
        endedAt: "2026-04-19T08:01:25.333Z",
        steps: [{ order: 1, id: "course_list", status: "pass" }],
        watchers: [
          {
            id: "search-index",
            dependencyStepOrder: 1,
            providerType: "http",
            status: "pass",
            outcome: "verified",
            attemptCount: 3,
            durationMs: 1500,
            reasonCode: "ok",
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
            reasonCode: "watcher_timeout_exceeded",
            waitPolicy: {
              timeoutMs: 5000,
              retryMax: 4,
            },
          },
        ],
      },
      evidence: {
        targetResolution: [],
        watcherExecutions: [
          {
            id: "search-index",
            dependencyStepOrder: 1,
            providerType: "http",
            status: "pass",
            outcome: "verified",
            attemptCount: 3,
            durationMs: 1500,
            reasonCode: "ok",
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
            reasonCode: "watcher_timeout_exceeded",
            waitPolicy: {
              timeoutMs: 5000,
              retryMax: 4,
            },
          },
        ],
      },
      now: new Date("2026-04-19T08:01:26.000Z"),
    });

    const result = readJson(written.executionResultPathAbs);
    const evidence = readJson(written.evidencePathAbs);
    const database = new DatabaseSync(
      path.join(root, ".mcpjvm", "test-project", "run-state.sqlite"),
    );
    assert.equal(database.prepare("SELECT count(*) AS count FROM watcher_runs").get().count, 2);
    assert.equal(database.prepare("SELECT count(*) AS count FROM watcher_attempts").get().count, 0);
    database.close();
    assert.equal(evidence.watcherExecutions.length, 2);
    const watcherExecutions = evidence.watcherExecutions as Array<Record<string, unknown>>;
    const byId = new Map<string, Record<string, unknown>>(
      watcherExecutions.map((entry) => [String(entry.id), entry]),
    );
    const searchIndex = byId.get("search-index");
    const feedCache = byId.get("feed-cache");
    if (!searchIndex || !feedCache) {
      throw new Error("expected canonical watcher evidence entries");
    }
    assert.equal(searchIndex.status, "ok");
    assert.equal(searchIndex.reasonCode, "watcher_verified");
    assert.equal(feedCache.status, "timed_out");
    assert.equal(feedCache.outcome, "timeout");
    assert.equal(feedCache.reasonCode, "watcher_timeout");
    assert.equal((searchIndex.waitPolicy as Record<string, unknown>).timeoutSource, "unresolved");
    assert.equal((searchIndex.waitPolicy as Record<string, unknown>).retrySource, "unresolved");
    assert.equal(result.watchers.length, 2);
    assert.equal(result.watchers[0].reasonCode, "watcher_verified");
    assert.equal(result.watchers[1].reasonCode, "watcher_timeout");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts fails closed for malformed watcher evidence rows", async () => {
  const root = createTestTempDir("run-artifacts-invalid-watcher-evidence");
  try {
    initProjectArtifact(root);
    await assert.rejects(
      () =>
        writeRegressionRunArtifacts({
          workspaceRootAbs: root,
          runId: "2026-04-19T08-01-22Z_06",
          planRef: { name: "probe-registry-course-service-smoke" },
          resolvedContext: {},
          executionResult: {
            status: "blocked",
            watcherStatus: "blocked",
            preflight: {
              status: "ready",
              reasonCode: "ok",
              missing: [],
              discoverablePending: [],
              prerequisiteResolution: [],
              requiredUserAction: [],
            },
            startedAt: "2026-04-19T08:01:22.111Z",
            endedAt: "2026-04-19T08:01:25.333Z",
            steps: [{ order: 1, id: "course_list", status: "pass" }],
            watchers: [
              {
                id: "search-index",
                dependencyStepOrder: 1,
                providerType: "http",
                status: "pass",
                outcome: "verified",
                attemptCount: 2,
                durationMs: 1000,
                reasonCode: "non_canonical_reason_code",
                waitPolicy: {
                  timeoutMs: 5000,
                  retryMax: 4,
                },
              },
            ],
          },
          evidence: {
            targetResolution: [],
            watcherExecutions: [
              {
                id: "search-index",
                dependencyStepOrder: 1,
                providerType: "http",
                status: "ok",
                outcome: "verified",
                attemptCount: 2,
                durationMs: 1000,
                reasonCode: "non_canonical_reason_code",
                waitPolicy: {
                  timeoutMs: 5000,
                  retryMax: 4,
                },
              },
            ],
          },
        }),
      /watcher_execution_(?:result|evidence)_invalid/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts fails closed when explicit watcher results are not an array", async () => {
  const root = createTestTempDir("run-artifacts-invalid-watcher-results-shape");
  try {
    initProjectArtifact(root);
    await assert.rejects(
      () =>
        writeRegressionRunArtifacts({
          workspaceRootAbs: root,
          runId: "2026-04-19T08-01-22Z_07",
          planRef: { name: "probe-registry-course-service-smoke" },
          resolvedContext: {},
          executionResult: {
            status: "blocked",
            watcherStatus: "blocked",
            preflight: {
              status: "ready",
              reasonCode: "ok",
              missing: [],
              discoverablePending: [],
              prerequisiteResolution: [],
              requiredUserAction: [],
            },
            startedAt: "2026-04-19T08:01:22.111Z",
            endedAt: "2026-04-19T08:01:25.333Z",
            steps: [{ order: 1, id: "course_list", status: "pass" }],
            watchers: {
              id: "search-index",
            } as unknown as Array<Record<string, unknown>>,
          },
          evidence: {
            targetResolution: [],
          },
        }),
      /watcher_execution_result_invalid/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts persists canonical external verification results and evidence", async () => {
  const root = createTestTempDir("run-artifacts-external-verification");
  try {
    initProjectArtifact(root);
    const written = await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId: "2026-04-19T08-01-22Z_08",
      planRef: { name: "probe-registry-course-service-smoke" },
      resolvedContext: {},
      executionResult: {
        status: "pass",
        externalVerificationStatus: "pass",
        preflight: {
          status: "ready",
          reasonCode: "ok",
          missing: [],
          discoverablePending: [],
          prerequisiteResolution: [],
          requiredUserAction: [],
        },
        startedAt: "2026-04-19T08:01:22.111Z",
        endedAt: "2026-04-19T08:01:25.333Z",
        steps: [{ order: 1, id: "course_list", status: "pass" }],
        externalVerification: [
          {
            id: "verify_reindex_task_status",
            providerType: "http",
            status: "pass",
            response: {
              statusCode: 200,
              body: '{"completed":true}',
              bodyJson: { completed: true },
              headers: { "content-type": "application/json" },
              durationMs: 42,
            },
            assertions: [
              {
                id: "task_completed",
                actualPath: "response.bodyJson.completed",
                operator: "field_equals",
                status: "pass",
                expected: true,
                actual: true,
              },
            ],
            extractResults: [
              {
                from: "response.bodyJson.task.id",
                as: "taskStatusId",
                required: false,
                status: "resolved",
                value: "task-123",
              },
            ],
            extractedContext: {
              taskStatusId: "task-123",
            },
          },
        ],
      },
      evidence: {
        targetResolution: [],
        externalVerificationExecutions: [
          {
            id: "verify_reindex_task_status",
            providerType: "http",
            status: "pass",
            response: {
              statusCode: 200,
              body: '{"completed":true}',
              bodyJson: { completed: true },
              headers: { "content-type": "application/json" },
              durationMs: 42,
            },
          },
        ],
      },
      now: new Date("2026-04-19T08:01:26.000Z"),
    });

    const result = readJson(written.executionResultPathAbs);
    const evidence = readJson(written.evidencePathAbs);
    assert.equal(result.externalVerificationStatus, "pass");
    assert.equal(result.externalVerification.length, 1);
    assert.equal(result.externalVerification[0].providerType, "http");
    assert.equal(result.externalVerification[0].response.statusCode, 200);
    assert.equal(result.externalVerification[0].response.bodyFormat, "json");
    assert.equal(result.externalVerification[0].response.hasBodyJson, true);
    assert.equal(typeof result.externalVerification[0].response.body, "undefined");
    assert.equal(typeof result.externalVerification[0].response.bodyJson, "undefined");
    assert.equal(typeof result.externalVerification[0].assertions[0].actual, "undefined");
    assert.equal(typeof result.externalVerification[0].extractResults[0].value, "undefined");
    assert.equal(typeof result.externalVerification[0].extractedContext, "undefined");
    assert.equal(evidence.externalVerificationExecutions.length, 1);
    assert.equal(evidence.externalVerificationExecutions[0].status, "pass");
    assert.equal(typeof evidence.externalVerificationExecutions[0].response.headers, "undefined");
    assert.deepEqual(evidence.externalVerificationExecutions[0].response.headerNames, [
      "content-type",
    ]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts fails closed for malformed external verification rows", async () => {
  const root = createTestTempDir("run-artifacts-invalid-external-verification");
  try {
    initProjectArtifact(root);
    await assert.rejects(
      () =>
        writeRegressionRunArtifacts({
          workspaceRootAbs: root,
          runId: "2026-04-19T08-01-22Z_09",
          planRef: { name: "probe-registry-course-service-smoke" },
          resolvedContext: {},
          executionResult: {
            status: "blocked",
            externalVerificationStatus: "blocked",
            preflight: {
              status: "ready",
              reasonCode: "ok",
              missing: [],
              discoverablePending: [],
              prerequisiteResolution: [],
              requiredUserAction: [],
            },
            startedAt: "2026-04-19T08:01:22.111Z",
            endedAt: "2026-04-19T08:01:25.333Z",
            steps: [{ order: 1, id: "course_list", status: "pass" }],
            externalVerification: [
              {
                id: "verify_reindex_task_status",
                providerType: "http",
                status: "blocked_runtime",
              },
            ],
          },
          evidence: {
            targetResolution: [],
          },
        }),
      /external_verification_execution_result_invalid/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts fails closed for invalid external verification phase status", async () => {
  const root = createTestTempDir("run-artifacts-invalid-external-verification-phase-status");
  try {
    initProjectArtifact(root);
    await assert.rejects(
      () =>
        writeRegressionRunArtifacts({
          workspaceRootAbs: root,
          runId: "2026-04-19T08-01-22Z_10",
          planRef: { name: "probe-registry-course-service-smoke" },
          resolvedContext: {},
          executionResult: {
            status: "blocked",
            externalVerificationStatus: "bogus_status" as never,
            preflight: {
              status: "ready",
              reasonCode: "ok",
              missing: [],
              discoverablePending: [],
              prerequisiteResolution: [],
              requiredUserAction: [],
            },
            startedAt: "2026-04-19T08:01:22.111Z",
            endedAt: "2026-04-19T08:01:25.333Z",
            steps: [{ order: 1, id: "course_list", status: "pass" }],
          },
          evidence: {
            targetResolution: [],
          },
        }),
      /external_verification_execution_result_invalid/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeRegressionRunArtifacts persists the active Watcher continuation", async () => {
  const root = createTestTempDir("run-artifacts-active-watcher");
  let database: any;
  try {
    initProjectArtifact(root);
    await writeRegressionRunArtifacts({
      workspaceRootAbs: root,
      runId: "2026-04-19T08-01-22Z_06",
      planRef: { name: "watcher-plan" },
      resolvedContext: {},
      executionResult: {
        status: "in_progress",
        watcherStatus: "in_progress",
        preflight: {
          status: "ready",
          reasonCode: "ok",
          missing: [],
          discoverablePending: [],
          prerequisiteResolution: [],
          requiredUserAction: [],
        },
        startedAt: "2026-04-19T08:01:22.111Z",
        endedAt: "2026-04-19T08:01:23.111Z",
        steps: [],
        continuation: {
          phase: "watchers",
          watcherIndex: 0,
          watcherName: "indexed-ready",
          dependencyStepOrder: 1,
          providerType: "http",
          phaseStartedAt: "2026-04-19T08:01:22.500Z",
          deadlineAtEpochMs: Date.parse("2026-04-19T08:01:27.500Z"),
          timeoutMs: 5_000,
          pollIntervalMs: 1_250,
          retryMax: 4,
          attemptCount: 1,
          attempts: [
            {
              attempt: 1,
              status: "fail_http",
              durationMs: 10,
              observedAt: "2026-04-19T08:01:22.600Z",
            },
          ],
        },
      },
      evidence: { targetResolution: [] },
      now: new Date("2026-04-19T08:01:23.111Z"),
    });
    database = new DatabaseSync(path.join(root, ".mcpjvm", "test-project", "run-state.sqlite"));
    const watcherCheckpoint = database
      .prepare("SELECT status, deadline_at_epoch_ms, attempt_count FROM watcher_runs")
      .get();
    assert.equal(watcherCheckpoint.status, "in_progress");
    assert.equal(watcherCheckpoint.deadline_at_epoch_ms, Date.parse("2026-04-19T08:01:27.500Z"));
    assert.equal(watcherCheckpoint.attempt_count, 1);
    assert.equal(database.prepare("SELECT status FROM plan_runs").get().status, "in_progress");
  } finally {
    if (database) database.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});
