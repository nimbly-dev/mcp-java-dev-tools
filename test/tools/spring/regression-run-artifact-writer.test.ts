const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  buildRunArtifactDirAbs,
  rebuildCorrelationIndex,
  writeRegressionRunArtifacts,
} = require("@tools-regression-execution-plan-spec/regression_run_artifact_writer.util");

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
          },
          {
            eventId: "e-1",
            probeId: "gateway-service",
            timestampEpochMs: 1767265200100,
            lineKey: "com.example.GatewayController#route:88",
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
    assert.ok(fs.existsSync(written.correlationIndexPathAbs));

    const context = readJson(written.contextResolvedPathAbs);
    const result = readJson(written.executionResultPathAbs);
    const evidence = readJson(written.evidencePathAbs);
    const correlation = readJson(written.correlationPathAbs);
    const correlationIndex = readJson(written.correlationIndexPathAbs);

    assert.equal(
      written.runDirAbs,
      path.join(root, ".mcpjvm", projectName, "plans", "regression", "gateway-course-review-aggregate-smoke", "runs", runId),
    );
    assert.equal(context.resolvedAt, "2026-04-19T08:01:26.000Z");
    assert.equal(context.tenantId, "tenant-social-001");
    assert.equal(typeof context.scope, "undefined");
    assert.equal(typeof context["auth.bearer"], "undefined");
    assert.equal(typeof context.requestBody.token, "undefined");
    assert.equal(result.status, "pass");
    assert.equal(result.runId, runId);
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
    assert.equal(correlationIndex.entries.length, 1);
    assert.equal(correlationIndex.entries[0].correlationSessionId, "sess-2026-04-19");
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

    const indexPath = path.join(root, ".mcpjvm", "correlation-index.json");
    fs.writeFileSync(indexPath, `${JSON.stringify({ version: 1, generatedAt: "2026-04-19T08:01:27.000Z", entries: [] }, null, 2)}\n`, "utf8");

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

