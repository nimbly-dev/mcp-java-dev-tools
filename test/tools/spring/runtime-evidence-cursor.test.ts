const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const {
  openRunStateStore,
  readRuntimeEvidenceCursor,
  upsertRuntimeEvidenceCursor,
} = require("@tools-feature-artifact-management");

test("runtime evidence cursor is durable, monotonic, and does not store raw keys", async () => {
  const root = fs.mkdtempSync(path.join(process.cwd(), "test", ".tmp", "runtime-cursor-"));
  const opened = await openRunStateStore({ workspaceRootAbs: root, projectName: "cursor-project" });
  assert.equal(opened.ok, true);
  if (!opened.ok) return;
  try {
    const first = upsertRuntimeEvidenceCursor({
      store: opened,
      projectName: "cursor-project",
      cursor: {
        runId: "run-1",
        suiteRunId: "suite-1",
        correlationSessionId: "session-1",
        probeId: "consumer",
        runtimeInstanceId: "runtime-1",
        lastSequence: 12,
        streamRuntimeInstanceId: "runtime-1",
        streamResetEpoch: 0,
        latestObservationAtEpochMs: 1000,
        status: "matched",
        reasonCode: "ok",
        dedupeIdentity: "session-1/consumer/runtime-1/12",
      },
    });
    assert.equal(first.ok, true);
    const read = readRuntimeEvidenceCursor({
      store: opened,
      projectName: "cursor-project",
      runId: "run-1",
      correlationSessionId: "session-1",
      probeId: "consumer",
      runtimeInstanceId: "runtime-1",
    });
    assert.equal(read?.lastSequence, 12);
    const rawKey = opened.database
      .prepare(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'runtime_evidence_cursors'",
      )
      .get();
    assert.ok(rawKey);
    const schema = opened.database
      .prepare("SELECT sql FROM sqlite_master WHERE name = 'runtime_evidence_cursors'")
      .get();
    assert.equal(String(schema?.sql).includes("key_value"), false);
  } finally {
    opened.close();
  }
});
