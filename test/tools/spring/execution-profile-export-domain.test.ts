const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { executionProfileExportDomain } = require("@/tools/core/execution_profile_export/domain");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

function writeProject(root: string): void {
  const projectName = "test-project";
  writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: root,
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "gateway-route-smoke-spec" }],
          },
          {
            executionProfile: "alternate-run",
            executionPolicy: "continue_on_fail",
            plans: [{ order: 1, planName: "alternate-spec" }],
          },
        ],
      },
    ],
  });
}

test("executionProfileExportDomain resolves executionProfile and creates a fresh sh export", async () => {
  const root = createTestTempDir("execution-profile-export-domain-sh");
  try {
    writeProject(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      executionProfile: "regression-test-run",
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.mode, "sh");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-regression-test-run$/);
    assert.match(
      String(out.structuredContent.output?.scriptPathAbs ?? ""),
      /exports[\\/]\d{4}-\d{2}-\d{2}-[0-9a-f-]+[\\/]run-execution-profile\.sh$/,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain derives export id from current profile when no selector is provided", async () => {
  const root = createTestTempDir("execution-profile-export-domain-default");
  try {
    writeProject(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      mode: "sh",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-regression-test-run$/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executionProfileExportDomain resolves containing execution profile by planName selector", async () => {
  const root = createTestTempDir("execution-profile-export-domain-plan-selector");
  try {
    writeProject(root);

    const out = await executionProfileExportDomain({
      workspaceRootAbs: root,
      mode: "sh",
      planName: "alternate-spec",
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.match(String(out.structuredContent.exportId ?? ""), /^20\d{6}-\d{6}-alternate-run$/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
