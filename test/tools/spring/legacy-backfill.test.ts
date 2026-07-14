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

test("legacy correlation backfill identifies the invalid entry and fields", async () => {
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
    assert.equal(result.ok, false);
    if (result.ok) return;
    assert.equal(result.reasonCode, "legacy_backfill_source_invalid");
    assert.equal(result.reasonMeta?.entryIndex, 0);
    assert.equal(result.reasonMeta?.runId, entry.runId);
    assert.equal(result.reasonMeta?.planName, entry.planName);
    assert.deepEqual(result.reasonMeta?.invalidFields, ["correlationSessionId"]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
  }
});
