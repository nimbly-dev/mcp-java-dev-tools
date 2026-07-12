const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { rebuildCorrelationIndex } = require("@tools-feature-regression-suite");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("rebuildCorrelationIndex fails closed without writing the legacy index", async () => {
  const root = createTestTempDir("corr-index-minimal-it");
  try {
    const projectName = "test-project";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root }],
    });
    const plan = "probe-registry-course-service-smoke";
    const runId = "1777691534330";
    const corrPath = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      plan,
      "runs",
      runId,
      "correlation",
      "correlation.json",
    );
    writeJson(corrPath, {
      status: "matched",
      reasonCode: "correlation_event_found",
      matchedEvents: 1,
    });

    const out = await rebuildCorrelationIndex({
      workspaceRootAbs: root,
      now: new Date("2026-05-02T12:00:00.000Z"),
    });
    assert.equal(out.ok, false);
    assert.equal(out.reasonCode, "legacy_write_disabled");
    assert.equal(
      fs.existsSync(path.join(root, ".mcpjvm", projectName, "correlation-index.json")),
      false,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
