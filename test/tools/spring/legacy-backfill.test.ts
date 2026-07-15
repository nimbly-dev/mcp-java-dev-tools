const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const { DatabaseSync } = require("node:sqlite");
const {
  backfillLegacyCorrelationIndex,
  cutoverRunStateStore,
} = require("@tools-feature-artifact-management");

function tempRoot(prefix: string): string {
  const root = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(root, { recursive: true });
  return fs.mkdtempSync(path.join(root, `${prefix}-`));
}

function writeLegacyIndex(root: string, projectName: string, entries: unknown[]): void {
  const projectDir = path.join(root, ".mcpjvm", projectName);
  fs.mkdirSync(projectDir, { recursive: true });
  fs.writeFileSync(
    path.join(projectDir, "correlation-index.json"),
    `${JSON.stringify({ version: 1, generatedAt: "2026-07-12T00:00:00.000Z", entries }, null, 2)}\n`,
    "utf8",
  );
}

const entry = {
  runId: "2026-07-12T00-00-00Z_01",
  planName: "legacy-plan",
  runPath: ".mcpjvm/alpha/plans/regression/legacy-plan/runs/2026-07-12T00-00-00Z_01",
  generatedAtEpochMs: 1_000,
  status: "ok",
  reasonCode: "ok",
  keyType: "traceId",
  keyValue: "trace-1",
  correlationSessionId: "legacy-session-1",
  window: { startEpochMs: 900, endEpochMs: 1_100, maxWindowMs: 60_000 },
  probeIds: ["probe-a"],
};

test("legacy correlation backfill imports supported fields with checksum provenance", async () => {
  const root = tempRoot("legacy-backfill");
  try {
    writeLegacyIndex(root, "alpha", [entry]);
    const first = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(first.ok, true);
    if (!first.ok) return;
    assert.equal(first.summary.insertedEntries, 1);
    assert.equal(first.summary.nonReconstructibleEntries, 1);
    assert.match(first.summary.sourceChecksum, /^[a-f0-9]{64}$/);
    const second = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(second.ok, true);
    if (!second.ok) return;
    assert.equal(second.summary.backfillStatus, "noop");
    const cutover = await cutoverRunStateStore({ workspaceRootAbs: root, projectName: "alpha" });
    assert.equal(cutover.ok, true);
    const afterCutover = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(afterCutover.ok, false);
    if (!afterCutover.ok) assert.equal(afterCutover.reasonCode, "legacy_write_disabled");
    const db = new DatabaseSync(path.join(root, ".mcpjvm", "alpha", "run-state.sqlite"));
    try {
      assert.equal(db.prepare("SELECT count(*) AS count FROM correlation_runs").get().count, 1);
      assert.equal(
        db.prepare("SELECT count(*) AS count FROM legacy_backfill_imports").get().count,
        1,
      );
      const columns = db.prepare("PRAGMA table_info(legacy_backfill_imports)").all();
      assert.equal(
        columns.some((column: Record<string, unknown>) => column.name === "source_checksum"),
        true,
      );
    } finally {
      db.close();
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("legacy correlation backfill rejects a changed source after completion", async () => {
  const root = tempRoot("legacy-backfill-checksum");
  try {
    writeLegacyIndex(root, "alpha", [entry]);
    const first = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(first.ok, true);
    fs.appendFileSync(path.join(root, ".mcpjvm", "alpha", "correlation-index.json"), "\n", "utf8");
    const changed = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(changed.ok, false);
    if (!changed.ok) assert.equal(changed.reasonCode, "legacy_backfill_checksum_changed");
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("legacy correlation backfill rejects duplicate natural identities", async () => {
  const root = tempRoot("legacy-backfill-conflict");
  try {
    writeLegacyIndex(root, "alpha", [entry, entry]);
    const result = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(result.ok, false);
    if (!result.ok) assert.equal(result.reasonCode, "legacy_backfill_conflict");
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("legacy correlation backfill rejects malformed input before touching SQLite rows", async () => {
  const root = tempRoot("legacy-backfill-invalid");
  try {
    writeLegacyIndex(root, "alpha", [{ runId: "bad" }]);
    const result = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(result.ok, false);
    if (result.ok) return;
    assert.equal(result.reasonCode, "legacy_backfill_source_invalid");
    const dbPath = path.join(root, ".mcpjvm", "alpha", "run-state.sqlite");
    assert.equal(fs.existsSync(dbPath), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("legacy correlation backfill skips non-reconstructible terminal entries with persisted diagnostics", async () => {
  const root = tempRoot("legacy-backfill-invalid-diagnostic");
  try {
    writeLegacyIndex(root, "alpha", [
      {
        ...entry,
        status: "fail_closed",
        reasonCode: "correlation_key_extraction_failed",
        keyType: "messageId",
        keyValue: undefined,
        correlationSessionId: undefined,
        probeIds: [],
      },
    ]);
    const result = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(result.ok, true);
    if (!result.ok) return;
    assert.equal(result.summary.skippedEntries, 1);
    assert.equal(result.summary.nonReconstructibleEntries, 1);
    assert.deepEqual(result.summary.reasons?.[0], {
      entryIndex: 0,
      planName: entry.planName,
      runId: entry.runId,
      reasonCode: "terminal_correlation_not_reconstructible",
      missingFields: ["correlationSessionId", "keyValue"],
    });
    const db = new DatabaseSync(path.join(root, ".mcpjvm", "alpha", "run-state.sqlite"));
    try {
      assert.equal(
        db.prepare("SELECT count(*) AS count FROM legacy_backfill_audits").get().count,
        1,
      );
    } finally {
      db.close();
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("legacy correlation backfill reconciles canonical rows and imports legacy-only rows", async () => {
  const root = tempRoot("legacy-backfill-reconcile");
  try {
    writeLegacyIndex(root, "alpha", [entry]);
    const first = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(first.ok, true);
    const dbPath = path.join(root, ".mcpjvm", "alpha", "run-state.sqlite");
    const db = new DatabaseSync(dbPath);
    try {
      db.prepare("DELETE FROM legacy_backfill_imports").run();
    } finally {
      db.close();
    }
    writeLegacyIndex(root, "alpha", [
      entry,
      {
        ...entry,
        runId: "2026-07-12T00-00-01Z_02",
        runPath: ".mcpjvm/alpha/plans/regression/legacy-plan/runs/2026-07-12T00-00-01Z_02",
        correlationSessionId: "legacy-session-2",
        keyValue: "trace-2",
      },
    ]);
    const reconciled = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(reconciled.ok, true);
    if (!reconciled.ok) return;
    assert.equal(reconciled.summary.insertedEntries, 1);
    assert.equal(reconciled.summary.skippedEntries, 1);
    const after = new DatabaseSync(dbPath);
    try {
      assert.equal(after.prepare("SELECT count(*) AS count FROM plan_runs").get().count, 2);
      assert.equal(after.prepare("SELECT count(*) AS count FROM correlation_runs").get().count, 2);
    } finally {
      after.close();
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("legacy correlation backfill reports canonical divergence with bounded fields", async () => {
  const root = tempRoot("legacy-backfill-divergence");
  try {
    writeLegacyIndex(root, "alpha", [entry]);
    const first = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(first.ok, true);
    const dbPath = path.join(root, ".mcpjvm", "alpha", "run-state.sqlite");
    const db = new DatabaseSync(dbPath);
    try {
      db.prepare("DELETE FROM legacy_backfill_imports").run();
    } finally {
      db.close();
    }
    writeLegacyIndex(root, "alpha", [{ ...entry, reasonCode: "changed_reason" }]);
    const divergent = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(divergent.ok, false);
    if (divergent.ok) return;
    assert.equal(divergent.reasonCode, "legacy_backfill_conflict");
    assert.deepEqual(divergent.reasonMeta, {
      entryIndex: 0,
      planName: entry.planName,
      runId: entry.runId,
      reasonCode: "legacy_canonical_divergence",
      conflictingFields: ["reasonCode"],
    });
    writeLegacyIndex(root, "alpha", [entry]);
    const retried = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(retried.ok, true);
    if (retried.ok) assert.equal(retried.summary.skippedEntries, 1);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("legacy correlation backfill rejects divergent persisted key identity", async () => {
  const root = tempRoot("legacy-backfill-key-divergence");
  try {
    writeLegacyIndex(root, "alpha", [entry]);
    const first = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(first.ok, true);
    const db = new DatabaseSync(path.join(root, ".mcpjvm", "alpha", "run-state.sqlite"));
    try {
      db.prepare("DELETE FROM legacy_backfill_imports").run();
    } finally {
      db.close();
    }
    writeLegacyIndex(root, "alpha", [{ ...entry, keyValue: "trace-different" }]);
    const divergent = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(divergent.ok, false);
    if (!divergent.ok) {
      assert.equal(divergent.reasonCode, "legacy_backfill_conflict");
      assert.deepEqual(divergent.reasonMeta?.conflictingFields, ["keyValue"]);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});

test("legacy correlation backfill imports a reconstructible terminal failure", async () => {
  const root = tempRoot("legacy-backfill-terminal");
  try {
    writeLegacyIndex(root, "alpha", [
      {
        ...entry,
        status: "fail_closed",
        reasonCode: "correlation_key_extraction_failed",
        keyValue: undefined,
      },
    ]);
    const result = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(result.ok, true);
    if (!result.ok) return;
    assert.equal(result.summary.insertedEntries, 1);
    assert.equal(result.summary.nonReconstructibleEntries, 1);
    const db = new DatabaseSync(path.join(root, ".mcpjvm", "alpha", "run-state.sqlite"));
    try {
      assert.equal(db.prepare("SELECT status FROM correlation_runs").get().status, "fail_closed");
    } finally {
      db.close();
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});
