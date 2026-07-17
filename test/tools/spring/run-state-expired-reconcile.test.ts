const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const {
  cutoverRunStateStore,
  reconcileExpiredActiveState,
} = require("@tools-feature-artifact-management");

function tempRoot(): string {
  const root = fs.mkdtempSync(path.join(process.cwd(), "test", ".tmp", "expired-reconcile-"));
  return root;
}

function writeJson(filePath: string, value: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

async function seedExpiredState(root: string, withSuiteLink = true): Promise<void> {
  const projectDir = path.join(root, ".mcpjvm", "alpha");
  const cutover = await cutoverRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
  assert.equal(cutover.ok, true);
  const db = new (require("node:sqlite").DatabaseSync)(path.join(projectDir, "run-state.sqlite"));
  db.prepare(
    `INSERT INTO suite_runs (project_name, suite_run_id, execution_profile, status, active_plan_name, active_plan_order, active_run_id, active_phase, continuation_json, owner_id, lease_expires_at_epoch_ms, revision, started_at_epoch_ms, updated_at_epoch_ms)
     VALUES ('alpha', 'suite-1', 'profile-1', 'in_progress', 'p1', 1, 'r1', 'watchers', ?, 'owner-1', 1000, 1, 1, 50)`,
  ).run(JSON.stringify({ runtimeSuite: { executionProfile: "profile-1", executionPolicy: "stop_on_fail" } }));
  const suitePk = db.prepare("SELECT suite_run_pk FROM suite_runs WHERE suite_run_id = 'suite-1'").get().suite_run_pk;
  db.prepare(
    `INSERT INTO plan_runs (suite_run_pk, project_name, plan_name, run_id, status, run_dir_path_rel)
     VALUES (?, 'alpha', 'p1', 'r1', 'executed', '.mcpjvm/alpha/plans/regression/p1/runs/r1')`,
  ).run(suitePk);
  const planPk = db.prepare("SELECT plan_run_pk FROM plan_runs WHERE plan_name = 'p1' AND run_id = 'r1'").get().plan_run_pk;
  db.prepare(
    `INSERT INTO watcher_runs (plan_run_pk, project_name, plan_name, run_id, suite_run_id, watcher_name, dependency_step_order, watcher_index, provider_type, status, outcome, started_at_epoch_ms, deadline_at_epoch_ms, timeout_ms, poll_interval_ms, retry_max, attempt_count, revision, artifact_path_rel)
     VALUES (?, 'alpha', 'p1', 'r1', 'suite-1', 'watcher-1', 1, 0, 'http', 'in_progress', 'blocked', 1, 100, 1000, 25, 3, 2, 1, '.mcpjvm/alpha/plans/regression/p1/runs/r1')`,
  ).run(planPk);
  const links = withSuiteLink
    ? [
        ["execution_result", ".mcpjvm/alpha/plans/regression/p1/runs/r1/execution.result.json"],
        ["evidence", ".mcpjvm/alpha/plans/regression/p1/runs/r1/evidence.json"],
      ]
    : [["execution_result", ".mcpjvm/alpha/plans/regression/p1/runs/r1/missing.json"]];
  if (withSuiteLink) links.push(["execution_orchestration", ".mcpjvm/alpha/suite-runs/suite-1/execution_orchestration.result.json"]);
  for (const [kind, relativePath] of links) {
    db.prepare(
      "INSERT INTO artifacts (project_name, plan_name, run_id, suite_run_id, artifact_kind, path_rel, created_at_epoch_ms) VALUES ('alpha', 'p1', 'r1', 'suite-1', ?, ?, 50)",
    ).run(kind, relativePath);
  }
  db.close();
  writeJson(path.join(root, ".mcpjvm/alpha/plans/regression/p1/runs/r1/execution.result.json"), {
    status: "in_progress",
    watcherStatus: "in_progress",
    continuation: { phase: "watchers", watcherIndex: 0 },
    watchers: [{ id: "watcher-1", status: "in_progress", outcome: "blocked", attemptCount: 2 }],
  });
  writeJson(path.join(root, ".mcpjvm/alpha/plans/regression/p1/runs/r1/evidence.json"), {
    watcherExecutions: [{ id: "watcher-1", status: "ok", outcome: "verified" }],
  });
  if (withSuiteLink)
    writeJson(path.join(root, ".mcpjvm/alpha/suite-runs/suite-1/execution_orchestration.result.json"), {
      status: "in_progress",
      planRuns: [{ planName: "p1", runId: "r1", status: "executed", runStatus: "in_progress" }],
    });
}

test("reconcileExpiredActiveState terminalizes one overdue Watcher without provider execution", async () => {
  const root = tempRoot();
  try {
    await seedExpiredState(root);
    const result = await reconcileExpiredActiveState({
      workspaceRootAbs: root,
      projectName: "alpha",
      executionProfile: "profile-1",
      suiteRunId: "suite-1",
      ownerId: "owner-1",
      nowEpochMs: 200,
    });
    assert.equal(result.ok, true);
    if (!result.ok) return;
    assert.equal(result.reconciled, true);
    const db = new (require("node:sqlite").DatabaseSync)(path.join(root, ".mcpjvm/alpha/run-state.sqlite"));
    assert.equal(db.prepare("SELECT status FROM watcher_runs WHERE watcher_name = 'watcher-1'").get().status, "blocked_runtime");
    assert.equal(db.prepare("SELECT outcome, reason_code, continuation_json FROM watcher_runs WHERE watcher_name = 'watcher-1'").get().outcome, "timed_out");
    assert.equal(db.prepare("SELECT owner_id, lease_expires_at_epoch_ms, status FROM suite_runs WHERE suite_run_id = 'suite-1'").get().owner_id, null);
    db.close();
    const execution = JSON.parse(fs.readFileSync(path.join(root, ".mcpjvm/alpha/plans/regression/p1/runs/r1/execution.result.json"), "utf8"));
    assert.equal(execution.watchers[0].reasonCode, "watcher_timeout");
    assert.equal(execution.continuation, undefined);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5 });
  }
});

test("reconcileExpiredActiveState fails closed when canonical run linkage is stale", async () => {
  const root = tempRoot();
  try {
    await seedExpiredState(root, false);
    const result = await reconcileExpiredActiveState({
      workspaceRootAbs: root,
      projectName: "alpha",
      executionProfile: "profile-1",
      suiteRunId: "suite-1",
      ownerId: "owner-1",
      nowEpochMs: 200,
    });
    assert.equal(result.ok, false);
    if (!result.ok) assert.equal(result.reasonCode, "state_store_artifact_link_invalid");
    const db = new (require("node:sqlite").DatabaseSync)(path.join(root, ".mcpjvm/alpha/run-state.sqlite"));
    assert.equal(db.prepare("SELECT status FROM watcher_runs WHERE watcher_name = 'watcher-1'").get().status, "in_progress");
    db.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5 });
  }
});

test("reconcileExpiredActiveState fails closed when a canonical checksum mismatches", async () => {
  const root = tempRoot();
  try {
    await seedExpiredState(root);
    const db = new (require("node:sqlite").DatabaseSync)(
      path.join(root, ".mcpjvm/alpha/run-state.sqlite"),
    );
    db.prepare(
      "UPDATE artifacts SET checksum = 'not-the-file-checksum' WHERE artifact_kind = 'execution_result'",
    ).run();
    db.close();
    const result = await reconcileExpiredActiveState({
      workspaceRootAbs: root,
      projectName: "alpha",
      executionProfile: "profile-1",
      suiteRunId: "suite-1",
      ownerId: "owner-1",
      nowEpochMs: 200,
    });
    assert.equal(result.ok, false);
    if (!result.ok) assert.equal(result.reasonCode, "state_store_artifact_link_invalid");
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5 });
  }
});
