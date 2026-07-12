const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const {
  cleanupRunStateRetention,
  openRunStateStore,
} = require("@tools-feature-artifact-management");

function tempRoot(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

async function createCutoverStore(root: string, projectName = "alpha"): Promise<any> {
  const opened = await openRunStateStore({ workspaceRootAbs: root, projectName });
  if (!opened.ok) throw new Error(opened.reason);
  const now = Date.now();
  opened.database
    .prepare(
      `INSERT INTO state_store_cutover (project_name, status, transition_revision, updated_at_epoch_ms, completed_at_epoch_ms)
       VALUES (?, 'cutover_complete', 1, ?, ?)`,
    )
    .run(projectName, now, now);
  return opened;
}

async function seedTerminalRuns(root: string): Promise<void> {
  await createCutoverStore(root).then((opened: any) => {
    const now = Date.now();
    const runs: Array<[string, number]> = [
      ["oldest", 200],
      ["older", 180],
      ["old", 100],
      ["newest", 10],
    ];
    for (const [runId, ageDays] of runs) {
      const runDir = `.mcpjvm/alpha/plans/regression/p1/runs/${runId}`;
      fs.mkdirSync(path.join(root, runDir), { recursive: true });
      const artifact = `${runDir}/execution.result.json`;
      fs.writeFileSync(path.join(root, artifact), `{ "runId": "${runId}" }\n`, "utf8");
      opened.database
        .prepare(
          `INSERT INTO plan_runs (project_name, plan_name, run_id, status, started_at_epoch_ms, completed_at_epoch_ms, run_dir_path_rel)
         VALUES (?, ?, ?, 'executed', ?, ?, ?)`,
        )
        .run("alpha", "p1", runId, now - ageDays * 86400000, now - ageDays * 86400000, runDir);
      opened.database
        .prepare(
          `INSERT INTO artifacts (project_name, plan_name, run_id, artifact_kind, path_rel, created_at_epoch_ms)
         VALUES (?, ?, ?, 'execution_result', ?, ?)`,
        )
        .run("alpha", "p1", runId, artifact, now - ageDays * 86400000);
    }
    opened.database.exec("PRAGMA wal_checkpoint(TRUNCATE);");
    opened.close();
  });
}

test("retention cleanup applies count, age, batch, audit, and idempotency rules", async () => {
  const root = tempRoot("retention-cleanup");
  try {
    await seedTerminalRuns(root);
    const first = await cleanupRunStateRetention({
      workspaceRootAbs: root,
      projectName: "alpha",
      retention: {
        terminalOlderThanDays: 90,
        keepMostRecentTerminalRuns: 1,
        maxDeleteBatch: 1,
        dryRun: false,
      },
    });
    assert.equal(first.ok, true);
    if (!first.ok) return;
    assert.equal(first.summary.scannedRuns, 4);
    assert.equal(first.summary.retainedByCount, 1);
    assert.equal(first.summary.retainedByAge, 0);
    assert.equal(first.summary.eligibleRuns, 3);
    assert.equal(first.summary.deletedRuns, 1);
    assert.equal(first.summary.outcome, "batch_limited");
    assert.equal(first.summary.remainingEligibleRuns, 2);

    const second = await cleanupRunStateRetention({
      workspaceRootAbs: root,
      projectName: "alpha",
      retention: {
        terminalOlderThanDays: 90,
        keepMostRecentTerminalRuns: 1,
        maxDeleteBatch: 500,
        dryRun: false,
      },
    });
    assert.equal(second.ok, true);
    if (!second.ok) return;
    assert.equal(second.summary.deletedRuns, 2);

    const third = await cleanupRunStateRetention({
      workspaceRootAbs: root,
      projectName: "alpha",
      retention: {
        terminalOlderThanDays: 90,
        keepMostRecentTerminalRuns: 1,
        maxDeleteBatch: 500,
        dryRun: false,
      },
    });
    assert.equal(third.ok, true);
    if (!third.ok) return;
    assert.equal(third.summary.scannedRuns, 1);
    assert.equal(third.summary.deletedRuns, 0);

    const reopened = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(reopened.ok, true);
    if (!reopened.ok) return;
    const audits = reopened.database
      .prepare("SELECT COUNT(*) AS count FROM state_store_cleanup_audits WHERE project_name = ?")
      .get("alpha");
    assert.equal(audits?.count, 3);
    reopened.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("applied retention cleanup preserves canonical Artifacts and the legacy index", async () => {
  const root = tempRoot("retention-preserves-artifacts");
  try {
    await seedTerminalRuns(root);
    const canonicalPath = path.join(
      root,
      ".mcpjvm",
      "alpha",
      "plans",
      "regression",
      "p1",
      "runs",
      "oldest",
      "execution.result.json",
    );
    const legacyPath = path.join(root, ".mcpjvm", "alpha", "correlation-index.json");
    const legacyContent =
      JSON.stringify({ version: 1, entries: [{ planName: "p1", runId: "oldest" }] }, null, 2) +
      "\n";
    fs.writeFileSync(legacyPath, legacyContent, "utf8");
    const canonicalContent = fs.readFileSync(canonicalPath);

    const result = await cleanupRunStateRetention({
      workspaceRootAbs: root,
      projectName: "alpha",
      retention: {
        terminalOlderThanDays: 90,
        keepMostRecentTerminalRuns: 1,
        maxDeleteBatch: 500,
        dryRun: false,
      },
    });
    assert.equal(result.ok, true);
    if (!result.ok) return;
    assert.equal(result.summary.deletedRuns, 3);
    assert.deepEqual(fs.readFileSync(canonicalPath), canonicalContent);
    assert.equal(fs.readFileSync(legacyPath, "utf8"), legacyContent);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("retention cleanup fails closed before cutover without creating state", async () => {
  const root = tempRoot("retention-not-ready");
  try {
    const result = await cleanupRunStateRetention({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(result.ok, false);
    if (!result.ok) assert.equal(result.reasonCode, "state_store_retention_not_ready");
    assert.equal(fs.existsSync(path.join(root, ".mcpjvm", "alpha", "run-state.sqlite")), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("retention cleanup rejects non-canonical Artifact links as stale", async () => {
  const root = tempRoot("retention-stale-link");
  try {
    await seedTerminalRuns(root);
    fs.writeFileSync(path.join(root, "README.md"), "not a canonical Artifact\n", "utf8");
    const opened = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(opened.ok, true);
    if (!opened.ok) return;
    const completedAt = Date.now() - 200 * 86400000;
    opened.database
      .prepare(
        `INSERT INTO plan_runs (project_name, plan_name, run_id, status, started_at_epoch_ms, completed_at_epoch_ms, run_dir_path_rel)
         VALUES (?, ?, ?, 'executed', ?, ?, ?)`,
      )
      .run(
        "alpha",
        "p1",
        "invalid-link",
        completedAt,
        completedAt,
        ".mcpjvm/alpha/plans/regression/p1/runs/invalid-link",
      );
    opened.database
      .prepare(
        `INSERT INTO artifacts (project_name, plan_name, run_id, artifact_kind, path_rel, created_at_epoch_ms)
         VALUES (?, ?, ?, 'execution_result', ?, ?)`,
      )
      .run("alpha", "p1", "invalid-link", "README.md", completedAt);
    opened.close();

    const result = await cleanupRunStateRetention({
      workspaceRootAbs: root,
      projectName: "alpha",
      retention: { terminalOlderThanDays: 90, keepMostRecentTerminalRuns: 1, dryRun: true },
    });
    assert.equal(result.ok, true);
    if (!result.ok) return;
    assert.equal(result.summary.skippedArtifactLink, 1);
    assert.deepEqual(result.summary.reasons, [
      { reasonCode: "state_store_retention_artifact_link_stale", count: 1 },
    ]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("retention cleanup excludes active suites, Watchers, leases, and missing links", async () => {
  const root = tempRoot("retention-safety");
  try {
    const opened = await createCutoverStore(root);
    const now = Date.now();
    const old = now - 200 * 86400000;
    opened.database
      .prepare(
        `INSERT INTO suite_runs (project_name, suite_run_id, status, active_run_id, lease_expires_at_epoch_ms, started_at_epoch_ms, updated_at_epoch_ms)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
      )
      .run("alpha", "active-suite", "in_progress", "active-suite-run", now + 60_000, old, now);
    const suitePk = opened.database
      .prepare("SELECT suite_run_pk FROM suite_runs WHERE suite_run_id = ?")
      .get("active-suite")?.suite_run_pk;
    opened.database
      .prepare(
        `INSERT INTO plan_runs (suite_run_pk, project_name, plan_name, run_id, status, started_at_epoch_ms, completed_at_epoch_ms, run_dir_path_rel)
         VALUES (?, ?, 'p1', 'active-suite-run', 'executed', ?, ?, '.mcpjvm/alpha/plans/regression/p1/runs/active-suite-run')`,
      )
      .run(suitePk, "alpha", old, old);
    opened.database
      .prepare(
        `INSERT INTO plan_runs (project_name, plan_name, run_id, status, started_at_epoch_ms, completed_at_epoch_ms, run_dir_path_rel)
         VALUES (?, 'p1', 'active-watcher-run', 'executed', ?, ?, '.mcpjvm/alpha/plans/regression/p1/runs/active-watcher-run')`,
      )
      .run("alpha", old, old);
    const watcherPlanPk = opened.database
      .prepare("SELECT plan_run_pk FROM plan_runs WHERE run_id = 'active-watcher-run'")
      .get()?.plan_run_pk;
    opened.database
      .prepare(
        `INSERT INTO watcher_runs (plan_run_pk, project_name, plan_name, run_id, watcher_name, dependency_step_order, watcher_index, provider_type, status, outcome, started_at_epoch_ms, deadline_at_epoch_ms, timeout_ms, poll_interval_ms, retry_max, attempt_count)
         VALUES (?, 'alpha', 'p1', 'active-watcher-run', 'watcher', 1, 0, 'http', 'in_progress', 'blocked', ?, ?, 60000, 1000, 3, 1)`,
      )
      .run(watcherPlanPk, old, now + 60_000);
    opened.database
      .prepare(
        `INSERT INTO suite_runs (project_name, suite_run_id, status, lease_expires_at_epoch_ms, started_at_epoch_ms, updated_at_epoch_ms)
         VALUES (?, 'leased-suite', 'completed', ?, ?, ?)`,
      )
      .run("alpha", now + 60_000, old, now);
    const leasedSuitePk = opened.database
      .prepare("SELECT suite_run_pk FROM suite_runs WHERE suite_run_id = ?")
      .get("leased-suite")?.suite_run_pk;
    opened.database
      .prepare(
        `INSERT INTO plan_runs (suite_run_pk, project_name, plan_name, run_id, status, started_at_epoch_ms, completed_at_epoch_ms, run_dir_path_rel)
         VALUES (?, ?, 'p1', 'leased-run', 'executed', ?, ?, '.mcpjvm/alpha/plans/regression/p1/runs/leased-run')`,
      )
      .run(leasedSuitePk, "alpha", old, old);
    opened.database
      .prepare(
        `INSERT INTO plan_runs (project_name, plan_name, run_id, status, started_at_epoch_ms, completed_at_epoch_ms, run_dir_path_rel)
         VALUES (?, 'p1', 'missing-link-run', 'executed', ?, ?, '.mcpjvm/alpha/plans/regression/p1/runs/missing-link-run')`,
      )
      .run("alpha", old, old);
    opened.close();

    const result = await cleanupRunStateRetention({
      workspaceRootAbs: root,
      projectName: "alpha",
      retention: { terminalOlderThanDays: 90, keepMostRecentTerminalRuns: 0, dryRun: true },
    });
    assert.equal(result.ok, true);
    if (!result.ok) return;
    assert.equal(result.summary.scannedRuns, 4);
    assert.equal(result.summary.skippedActive, 3);
    assert.equal(result.summary.skippedArtifactLink, 1);
    assert.equal(result.summary.eligibleRuns, 0);
    assert.deepEqual(result.summary.reasons, [
      { reasonCode: "state_store_retention_active_state", count: 3 },
      { reasonCode: "state_store_retention_artifact_link_missing", count: 1 },
    ]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("retention cleanup prunes audits to the newest 100 rows", async () => {
  const root = tempRoot("retention-audit-pruning");
  try {
    const opened = await createCutoverStore(root);
    for (let index = 1; index <= 101; index += 1) {
      opened.database
        .prepare(
          `INSERT INTO state_store_cleanup_audits (
             project_name, cleanup_id, started_at_epoch_ms, completed_at_epoch_ms, dry_run,
             terminal_older_than_days, keep_most_recent_terminal_runs, max_delete_batch,
             outcome, scanned_runs, policy_candidate_runs, eligible_runs, deleted_runs,
             skipped_active, skipped_artifact_link, retained_by_age, retained_by_count,
             remaining_eligible_runs, reasons_json
           ) VALUES (?, ?, ?, ?, 1, 90, 1000, 500, 'dry_run', 0, 0, 0, 0, 0, 0, 0, 0, 0, '[]')`,
        )
        .run("alpha", `seed-${index}`, index, index);
    }
    opened.close();
    const result = await cleanupRunStateRetention({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(result.ok, true);
    const reopened = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(reopened.ok, true);
    if (!reopened.ok) return;
    const auditRange = reopened.database
      .prepare(
        "SELECT COUNT(*) AS count, MIN(completed_at_epoch_ms) AS oldest FROM state_store_cleanup_audits WHERE project_name = ?",
      )
      .get("alpha");
    assert.equal(auditRange?.count, 100);
    assert.equal(Number(auditRange?.oldest) >= 3, true);
    reopened.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("retention cleanup preserves locked, corrupt, and unsupported-store reasons", async () => {
  const corruptRoot = tempRoot("retention-corrupt");
  const unsupportedRoot = tempRoot("retention-unsupported");
  const lockedRoot = tempRoot("retention-locked");
  try {
    const corruptPath = path.join(corruptRoot, ".mcpjvm", "alpha", "run-state.sqlite");
    fs.mkdirSync(path.dirname(corruptPath), { recursive: true });
    fs.writeFileSync(corruptPath, "not sqlite", "utf8");
    const corrupt = await cleanupRunStateRetention({
      workspaceRootAbs: corruptRoot,
      projectName: "alpha",
    });
    assert.equal(corrupt.ok, false);
    if (!corrupt.ok) assert.equal(corrupt.reasonCode, "state_store_corrupt");

    const unsupported = await createCutoverStore(unsupportedRoot);
    unsupported.close();
    const unsupportedPath = path.join(unsupportedRoot, ".mcpjvm", "alpha", "run-state.sqlite");
    const unsupportedDb = new (require("node:sqlite").DatabaseSync)(unsupportedPath);
    unsupportedDb.prepare("UPDATE schema_migrations SET version = 999 WHERE version = 9").run();
    unsupportedDb.close();
    const unsupportedResult = await cleanupRunStateRetention({
      workspaceRootAbs: unsupportedRoot,
      projectName: "alpha",
    });
    assert.equal(unsupportedResult.ok, false);
    if (!unsupportedResult.ok)
      assert.equal(unsupportedResult.reasonCode, "state_store_schema_unsupported");

    const locked = await createCutoverStore(lockedRoot);
    locked.database.exec("BEGIN EXCLUSIVE;");
    const lockedResultPromise = cleanupRunStateRetention({
      workspaceRootAbs: lockedRoot,
      projectName: "alpha",
    });
    const lockedResult = await lockedResultPromise;
    assert.equal(lockedResult.ok, false);
    if (!lockedResult.ok) assert.equal(lockedResult.reasonCode, "state_store_locked");
    locked.database.exec("ROLLBACK;");
    locked.close();
  } finally {
    fs.rmSync(corruptRoot, { recursive: true, force: true });
    fs.rmSync(unsupportedRoot, { recursive: true, force: true });
    fs.rmSync(lockedRoot, { recursive: true, force: true });
  }
});

test("concurrent retention cleanups return one deterministic retention conflict", async () => {
  const root = tempRoot("retention-concurrent");
  try {
    await seedTerminalRuns(root);
    const results = await Promise.all([
      cleanupRunStateRetention({
        workspaceRootAbs: root,
        projectName: "alpha",
        retention: {
          terminalOlderThanDays: 90,
          keepMostRecentTerminalRuns: 1,
          maxDeleteBatch: 1,
          dryRun: false,
        },
      }),
      cleanupRunStateRetention({
        workspaceRootAbs: root,
        projectName: "alpha",
        retention: {
          terminalOlderThanDays: 90,
          keepMostRecentTerminalRuns: 1,
          maxDeleteBatch: 1,
          dryRun: false,
        },
      }),
    ]);
    assert.equal(results.filter((result) => result.ok).length, 1);
    assert.equal(
      results.filter(
        (result) => !result.ok && result.reasonCode === "state_store_retention_conflict",
      ).length,
      1,
    );
    const successful = results.find((result) => result.ok);
    assert.equal(successful?.summary.deletedRuns, 1);
    const reopened = await openRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(reopened.ok, true);
    if (!reopened.ok) return;
    const remaining = reopened.database
      .prepare("SELECT COUNT(*) AS count FROM plan_runs WHERE project_name = ?")
      .get("alpha");
    assert.equal(remaining?.count, 3);
    reopened.close();
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
