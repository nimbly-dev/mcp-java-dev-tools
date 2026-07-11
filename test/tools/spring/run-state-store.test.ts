const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { openRunStateStore, persistRegressionSuiteState, upsertRunStateArtifact } = require("@tools-feature-artifact-management");

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
    assert.match(first.databasePathAbs.replaceAll("\\", "/"), /\.mcpjvm\/alpha\/run-state\.sqlite$/);
    assert.equal(first.schemaVersion, 2);
    assert.deepEqual(upsertRunStateArtifact(first, {
      artifactKind: "execution_result",
      pathRel: ".mcpjvm/alpha/plans/regression/p1/runs/r1/execution.result.json",
      planName: "p1",
      runId: "r1",
      createdAtEpochMs: 1,
    }), { ok: true });
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
    const invalidProject = await openRunStateStore({ workspaceRootAbs: root, projectName: "../escape" });
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
    first.database.prepare("UPDATE store_metadata SET metadata_value = 'other-project' WHERE metadata_key = 'project_name'").run();
    first.close();
    const mismatch = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(mismatch.ok, false);
    if (!mismatch.ok) assert.equal(mismatch.reasonCode, "state_store_project_mismatch");

    const { DatabaseSync } = require("node:sqlite");
    const databasePath = path.join(root, ".mcpjvm", "alpha", "run-state.sqlite");
    const database = new DatabaseSync(databasePath);
    database.prepare("UPDATE store_metadata SET metadata_value = 'alpha' WHERE metadata_key = 'project_name'").run();
    database.prepare("INSERT INTO schema_migrations (version, applied_at_epoch_ms, migration_name, checksum) VALUES (?, ?, ?, ?)").run(99, 1, "future", "future");
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
        suiteRunId: "suite-1", executionProfile: "regression", status: "in_progress",
        startedAtEpochMs: 10, updatedAtEpochMs: 10, nextPlanOrder: 2,
        activePlanName: "p1", activePlanOrder: 1, activeRunId: "run-1", activePhase: "watchers",
        continuation: { phase: "watchers", watcherIndex: 0 },
      },
      planRuns: [{ planName: "p1", runId: "run-1", status: "executed", runStatus: "in_progress", runDirPathRel: ".mcpjvm/alpha/plans/regression/p1/runs/run-1" }],
    });
    assert.deepEqual(first, { ok: true, revision: 1 });
    const stale = persistRegressionSuiteState({
      store,
      checkpoint: { suiteRunId: "suite-1", executionProfile: "regression", status: "in_progress", startedAtEpochMs: 10, updatedAtEpochMs: 11, expectedRevision: 0 },
      planRuns: [],
    });
    assert.equal(stale.ok, false);
    if (!stale.ok) assert.equal(stale.reasonCode, "suite_checkpoint_stale_revision");
    store.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});
