const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  openRunStateStore,
  persistCorrelationSession,
  persistRegressionSuiteState,
  upsertCorrelationObservation,
  upsertRunStateArtifact,
  upsertWatcherRun,
} = require("@tools-feature-artifact-management");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

test("run-state store bootstraps idempotently with portable Artifact linkage", async () => {
  const root = createTestTempDir("run-state-store");
  try {
    const first = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(first.ok, true);
    if (!first.ok) return;
    assert.match(
      first.databasePathAbs.replaceAll("\\", "/"),
      /\.mcpjvm\/alpha\/run-state\.sqlite$/,
    );
    assert.equal(first.schemaVersion, 4);
    assert.deepEqual(
      upsertRunStateArtifact(first, {
        artifactKind: "execution_result",
        pathRel: ".mcpjvm/alpha/plans/regression/p1/runs/r1/execution.result.json",
        planName: "p1",
        runId: "r1",
        createdAtEpochMs: 1,
      }),
      { ok: true },
    );
    assert.equal(first.database.prepare("SELECT count(*) AS count FROM artifacts").get()?.count, 1);
    first.close();

    const second = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(second.ok, true);
    if (second.ok) second.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("run-state store fails closed for invalid project and Artifact paths", async () => {
  const root = createTestTempDir("run-state-store-invalid");
  try {
    const invalidProject = await openRunStateStore({
      workspaceRootAbs: root,
      projectName: "../escape",
    });
    assert.equal(invalidProject.ok, false);
    if (!invalidProject.ok) assert.equal(invalidProject.reasonCode, "state_store_path_invalid");

    const store = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(store.ok, true);
    if (!store.ok) return;
    const invalidPath = upsertRunStateArtifact(store, {
      artifactKind: "evidence",
      pathRel: "../evidence.json",
      createdAtEpochMs: 1,
    });
    assert.equal(invalidPath.ok, false);
    if (!invalidPath.ok) assert.equal(invalidPath.reasonCode, "state_store_path_invalid");
    store.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("run-state store fails closed for a mismatched project or unsupported schema", async () => {
  const root = createTestTempDir("run-state-store-schema");
  try {
    const first = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(first.ok, true);
    if (!first.ok) return;
    first.database
      .prepare(
        "UPDATE store_metadata SET metadata_value = 'other-project' WHERE metadata_key = 'project_name'",
      )
      .run();
    first.close();
    const mismatch = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(mismatch.ok, false);
    if (!mismatch.ok) assert.equal(mismatch.reasonCode, "state_store_project_mismatch");

    const { DatabaseSync } = require("node:sqlite");
    const databasePath = path.join(root, ".mcpjvm", "alpha", "run-state.sqlite");
    const database = new DatabaseSync(databasePath);
    database
      .prepare(
        "UPDATE store_metadata SET metadata_value = 'alpha' WHERE metadata_key = 'project_name'",
      )
      .run();
    database
      .prepare(
        "INSERT INTO schema_migrations (version, applied_at_epoch_ms, migration_name, checksum) VALUES (?, ?, ?, ?)",
      )
      .run(99, 1, "future", "future");
    database.close();

    const unsupported = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(unsupported.ok, false);
    if (!unsupported.ok) assert.equal(unsupported.reasonCode, "state_store_schema_unsupported");
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("run-state store preserves a corrupt database and returns rebuild guidance", async () => {
  const root = createTestTempDir("run-state-store-corrupt");
  try {
    const storeDir = path.join(root, ".mcpjvm", "alpha");
    fs.mkdirSync(storeDir, { recursive: true });
    const databasePath = path.join(storeDir, "run-state.sqlite");
    fs.writeFileSync(databasePath, "not a SQLite database", "utf8");
    const result = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(result.ok, false);
    if (!result.ok) {
      assert.equal(result.reasonCode, "state_store_corrupt");
      assert.equal(result.nextAction, "rebuild_state_store");
    }
    assert.equal(fs.readFileSync(databasePath, "utf8"), "not a SQLite database");
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("run-state store persists suite and plan checkpoints with revision protection", async () => {
  const root = createTestTempDir("run-state-suite-checkpoint");
  try {
    const store = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(store.ok, true);
    if (!store.ok) return;
    const first = persistRegressionSuiteState({
      store,
      checkpoint: {
        suiteRunId: "suite-1",
        executionProfile: "regression",
        status: "in_progress",
        startedAtEpochMs: 10,
        updatedAtEpochMs: 10,
        nextPlanOrder: 2,
        activePlanName: "p1",
        activePlanOrder: 1,
        activeRunId: "run-1",
        activePhase: "watchers",
        continuation: { phase: "watchers", watcherIndex: 0 },
      },
      planRuns: [
        {
          planName: "p1",
          runId: "run-1",
          status: "executed",
          runStatus: "in_progress",
          runDirPathRel: ".mcpjvm/alpha/plans/regression/p1/runs/run-1",
        },
      ],
    });
    assert.deepEqual(first, { ok: true, revision: 1 });
    const stale = persistRegressionSuiteState({
      store,
      checkpoint: {
        suiteRunId: "suite-1",
        executionProfile: "regression",
        status: "in_progress",
        startedAtEpochMs: 10,
        updatedAtEpochMs: 11,
        expectedRevision: 0,
      },
      planRuns: [],
    });
    assert.equal(stale.ok, false);
    if (!stale.ok) assert.equal(stale.reasonCode, "suite_checkpoint_stale_revision");
    store.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("run-state store aggregates repeated Strict Line observations without per-hit rows", async () => {
  const root = createTestTempDir("correlation-aggregate");
  try {
    const store = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(store.ok, true);
    if (!store.ok) return;
    const base = {
      store,
      projectName: "alpha",
      planName: "p1",
      runId: "r1",
      correlationSessionId: "session-1",
      maxWindowMs: 1000,
    };
    const observation = {
      strictLineKey: "com.example.Job#run:42",
      sequenceOrder: 1,
      selectorPolicy: "aggregate",
      operator: "exact",
      expectedHitDelta: 500,
      probeId: "worker",
      runtimeInstanceId: "instance-1",
      baselineHitCount: 10,
      currentHitCount: 10,
      observedAtEpochMs: 1,
    };
    assert.equal(upsertCorrelationObservation({ ...base, observation }).ok, true);
    const matched = upsertCorrelationObservation({
      ...base,
      observation: { ...observation, currentHitCount: 510, observedAtEpochMs: 2 },
    });
    assert.deepEqual(matched, { ok: true, revision: 1, observedHitDelta: 500, status: "matched" });
    assert.equal(
      store.database.prepare("SELECT count(*) AS count FROM correlation_probe_observations").get()
        ?.count,
      1,
    );
    assert.equal(
      store.database
        .prepare("SELECT observed_hit_delta AS delta FROM correlation_probe_observations")
        .get()?.delta,
      500,
    );
    const nonMonotonic = upsertCorrelationObservation({
      ...base,
      observation: { ...observation, currentHitCount: 509, observedAtEpochMs: 3 },
    });
    assert.equal(nonMonotonic.ok, false);
    if (!nonMonotonic.ok)
      assert.equal(nonMonotonic.reasonCode, "correlation_hit_count_non_monotonic");
    store.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("run-state store rejects stale revisions, excess counts, and changed runtime instances", async () => {
  const root = createTestTempDir("correlation-fail-closed");
  try {
    const store = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(store.ok, true);
    if (!store.ok) return;
    const base = {
      store,
      projectName: "alpha",
      planName: "p1",
      runId: "r1",
      correlationSessionId: "session-1",
      maxWindowMs: 1000,
    };
    const observation = {
      strictLineKey: "com.example.Job#run:42",
      sequenceOrder: 1,
      selectorPolicy: "exact_instance" as const,
      operator: "exact" as const,
      expectedHitDelta: 1,
      probeId: "worker",
      runtimeInstanceId: "instance-1",
      baselineHitCount: 0,
      currentHitCount: 0,
      observedAtEpochMs: 1,
    };
    assert.equal(upsertCorrelationObservation({ ...base, observation }).ok, true);
    const stale = upsertCorrelationObservation({
      ...base,
      observation: { ...observation, expectedRevision: 1, observedAtEpochMs: 2 },
    });
    assert.equal(stale.ok, false);
    if (!stale.ok) assert.equal(stale.reasonCode, "correlation_revision_conflict");
    const exceeded = upsertCorrelationObservation({
      ...base,
      observation: { ...observation, currentHitCount: 2, observedAtEpochMs: 3 },
    });
    assert.equal(exceeded.ok, false);
    if (!exceeded.ok) assert.equal(exceeded.reasonCode, "correlation_expectation_exceeded");
    const runtimeChanged = upsertCorrelationObservation({
      ...base,
      observation: { ...observation, runtimeInstanceId: "instance-2", observedAtEpochMs: 4 },
    });
    assert.equal(runtimeChanged.ok, false);
    if (!runtimeChanged.ok)
      assert.equal(runtimeChanged.reasonCode, "correlation_runtime_instance_changed");
    store.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("run-state store persists a correlation session and hashed key without retaining the raw key", async () => {
  const root = createTestTempDir("correlation-session");
  try {
    const store = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(store.ok, true);
    if (!store.ok) return;
    const result = persistCorrelationSession({
      store,
      projectName: "alpha",
      session: {
        planName: "p1",
        runId: "r1",
        correlationSessionId: "session-1",
        keyType: "traceId",
        keyValue: "sensitive-trace-value",
        maxWindowMs: 1000,
        startedAtEpochMs: 1,
        status: "fail_closed",
        reasonCode: "no_matching_events",
        correlationPathRel:
          ".mcpjvm/alpha/plans/regression/p1/runs/r1/correlation/correlation.json",
      },
    });
    assert.deepEqual(result, { ok: true, revision: 0 });
    assert.equal(
      store.database.prepare("SELECT key_value_sanitized FROM correlation_keys").get()
        ?.key_value_sanitized,
      null,
    );
    assert.match(
      String(
        store.database.prepare("SELECT key_value_hash FROM correlation_keys").get()?.key_value_hash,
      ),
      /^[a-f0-9]{64}$/,
    );
    assert.equal(
      store.database.prepare("SELECT correlation_path_rel FROM correlation_runs").get()
        ?.correlation_path_rel,
      ".mcpjvm/alpha/plans/regression/p1/runs/r1/correlation/correlation.json",
    );
    store.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("declared Strict Line expectations remain collecting until their observation is persisted", async () => {
  const root = createTestTempDir("correlation-declared-expectation");
  try {
    const store = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(store.ok, true);
    if (!store.ok) return;
    const session = persistCorrelationSession({
      store,
      projectName: "alpha",
      session: {
        planName: "p1",
        runId: "r1",
        correlationSessionId: "session-1",
        keyType: "traceId",
        maxWindowMs: 1000,
        startedAtEpochMs: 1,
        status: "collecting",
        reasonCode: "collecting",
        expectations: [
          {
            strictLineKey: "com.example.Job#run:42",
            sequenceOrder: 1,
            selectorPolicy: "exact_instance",
            operator: "exact",
            expectedHitDelta: 1,
          },
        ],
      },
    });
    assert.equal(session.ok, true);
    assert.equal(
      store.database.prepare("SELECT status FROM correlation_runs").get()?.status,
      "collecting",
    );
    assert.equal(
      store.database.prepare("SELECT count(*) AS count FROM correlation_line_expectations").get()
        ?.count,
      1,
    );
    store.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("run-state store persists bounded Watcher checkpoints and rejects unsafe resumes", async () => {
  const root = createTestTempDir("watcher-checkpoint");
  let openedStore: any;
  try {
    const store = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    openedStore = store;
    assert.equal(store.ok, true);
    if (!store.ok) return;
    const base = {
      store,
      projectName: "alpha",
      projection: {
        planName: "p1",
        runId: "r1",
        suiteRunId: "suite-1",
        watcherName: "indexed-ready",
        dependencyStepOrder: 1,
        watcherIndex: 0,
        providerType: "http",
        status: "in_progress" as const,
        outcome: "blocked" as const,
        startedAtEpochMs: 100,
        deadlineAtEpochMs: 1_100,
        timeoutMs: 1_000,
        pollIntervalMs: 100,
        retryMax: 5,
        attemptCount: 1,
        nextAttemptAtEpochMs: 300,
        continuation: { phase: "watchers", watcherIndex: 0 },
        attempts: [
          {
            attemptNumber: 1,
            observedAtEpochMs: 150,
            status: "fail_http",
            durationMs: 10,
            observationSummary: { statusCode: 503, body: "secret" },
          },
        ],
      },
    };
    assert.deepEqual(upsertWatcherRun(base), { ok: true, revision: 1 });
    assert.deepEqual(
      upsertWatcherRun({
        ...base,
        projection: {
          ...base.projection,
          attemptCount: 2,
          continuation: { phase: "watchers", watcherIndex: 0 },
          attempts: [{ attemptNumber: 2, observedAtEpochMs: 250, status: "pass" }],
        },
      }),
      { ok: true, revision: 2 },
    );
    assert.equal(
      store.database.prepare("SELECT count(*) AS count FROM watcher_runs").get()?.count,
      1,
    );
    assert.equal(
      store.database.prepare("SELECT count(*) AS count FROM watcher_attempts").get()?.count,
      2,
    );
    assert.equal(
      store.database.prepare("SELECT deadline_at_epoch_ms AS deadline FROM watcher_runs").get()
        ?.deadline,
      1_100,
    );
    assert.equal(
      store.database
        .prepare("SELECT next_attempt_at_epoch_ms AS nextAttempt FROM watcher_runs")
        .get()?.nextAttempt,
      300,
    );
    assert.match(
      String(
        store.database
          .prepare(
            "SELECT observation_summary_json AS summary FROM watcher_attempts WHERE attempt_number = 1",
          )
          .get()?.summary,
      ),
      /REDACTED/,
    );

    const staleAttempt = upsertWatcherRun({
      ...base,
      projection: { ...base.projection, attemptCount: 1 },
    });
    assert.equal(staleAttempt.ok, false);
    if (!staleAttempt.ok) assert.equal(staleAttempt.reasonCode, "watcher_attempt_non_monotonic");
    const changedDeadline = upsertWatcherRun({
      ...base,
      projection: { ...base.projection, attemptCount: 2, deadlineAtEpochMs: 1_200 },
    });
    assert.equal(changedDeadline.ok, false);
    if (!changedDeadline.ok) assert.equal(changedDeadline.reasonCode, "watcher_deadline_invalid");
    const identityMismatch = upsertWatcherRun({
      ...base,
      projection: { ...base.projection, suiteRunId: "suite-2", attemptCount: 2 },
    });
    assert.equal(identityMismatch.ok, false);
    if (!identityMismatch.ok)
      assert.equal(identityMismatch.reasonCode, "watcher_resume_identity_mismatch");

    const trimmedIdentity = upsertWatcherRun({
      ...base,
      projection: {
        ...base.projection,
        planName: " p1 ",
        runId: " r1 ",
        watcherName: " indexed-ready ",
        attemptCount: 2,
      },
    });
    assert.deepEqual(trimmedIdentity, { ok: true, revision: 3 });
  } finally {
    if (openedStore?.ok) openedStore.close();
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});
