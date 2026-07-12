const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const { DatabaseSync } = require("node:sqlite");
const { backfillLegacyCorrelationIndex } = require("@tools-feature-artifact-management");

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

test("legacy correlation backfill imports supported fields without source checksums", async () => {
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
    assert.equal(Object.hasOwn(first.summary, "sourceChecksum"), false);
    const second = await backfillLegacyCorrelationIndex({
      workspaceRootAbs: root,
      projectName: "alpha",
    });
    assert.equal(second.ok, true);
    if (!second.ok) return;
    assert.equal(second.summary.backfillStatus, "noop");
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
        false,
      );
    } finally {
      db.close();
    }
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
