const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { exportExecutionProfileSh } = require("@tools-export-execution-profile");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  const normalizedPayload =
    path.basename(filePath) === "projects.json" && Array.isArray(payload.workspaces)
      ? {
          ...payload,
          workspaces: payload.workspaces.map((workspace) => {
            const entry = workspace as Record<string, unknown>;
            const defaults =
              entry.defaults && typeof entry.defaults === "object"
                ? (entry.defaults as Record<string, unknown>)
                : {};
            return {
              ...entry,
              defaults: {
                ...defaults,
                orchestrator: {
                  resumePollMax: 30,
                  resumePollIntervalMs: 10000,
                  resumePollTimeoutMs: 300000,
                },
              },
            };
          }),
        }
      : payload;
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(normalizedPayload, null, 2)}\n`, "utf8");
}

function writePlanArtifact(root: string, projectName: string, planName: string): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    execution: { intent: "regression" },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [],
    steps: [],
  });
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
    writePlanArtifact(root, projectName, "course-service-regression-spec");
    writePlanArtifact(root, projectName, "review-service-regression-spec");

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
    assert.match(script, /# SourceRunStatus: pass/);
    assert.match(script, /\[E01\] course-service-regression-spec replay_plan source_status=pass/);
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
    writePlanArtifact(root, projectName, "course-service-regression-spec");

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
    assert.match(script, /# SourceRunStatus: pass/);
    assert.match(script, /\[E01\] course-service-regression-spec replay_plan source_status=pass/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
