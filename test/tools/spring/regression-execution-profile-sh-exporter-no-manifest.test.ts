const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { exportExecutionProfileSh } = require("@tools-export-execution-profile/index");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("exportExecutionProfileSh synthesizes export context from projects.json", async () => {
  const root = createTestTempDir("execution-profile-sh-no-manifest");
  try {
    const projectName = "test-project";
    const exportId = "manual-export-001";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              plans: [
                { order: 1, planName: "course-service-regression-spec" },
                { order: 2, planName: "review-service-regression-spec" },
              ],
            },
          ],
        },
      ],
    });

    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      "course-service-regression-spec",
      "runs",
      "05-17-2026-11-11-11AM",
    );
    fs.mkdirSync(runDir, { recursive: true });
    writeJson(path.join(runDir, "execution.result.json"), { status: "pass" });

    const out = await exportExecutionProfileSh({
      workspaceRootAbs: root,
      exportId,
      includeResolvedSecrets: false,
    });

    assert.equal(fs.existsSync(out.scriptPathAbs), true);
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /course-service-regression-spec/);
    assert.match(script, /review-service-regression-spec/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("exportExecutionProfileSh does not require a persisted export manifest", async () => {
  const root = createTestTempDir("execution-profile-sh-invalid-manifest");
  try {
    const projectName = "test-project";
    const exportId = "manual-export-002";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [
            {
              executionProfile: "regression-test-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "course-service-regression-spec" }],
            },
          ],
        },
      ],
    });

    const runDir = path.join(
      root,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      "course-service-regression-spec",
      "runs",
      "05-17-2026-11-11-12AM",
    );
    fs.mkdirSync(runDir, { recursive: true });
    writeJson(path.join(runDir, "execution.result.json"), { status: "pass" });

    const out = await exportExecutionProfileSh({
      workspaceRootAbs: root,
      exportId,
      includeResolvedSecrets: false,
    });

    assert.equal(fs.existsSync(out.scriptPathAbs), true);
    const script = fs.readFileSync(out.scriptPathAbs, "utf8");
    assert.match(script, /course-service-regression-spec/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
