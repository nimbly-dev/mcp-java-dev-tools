const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { artifactManagementDomain } = require("@/tools/core/artifact_management/domain");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("artifact_management blocks disallowed action by artifactType", async () => {
  const out = await artifactManagementDomain({
    workspaceRootAbs: process.cwd(),
    request: {
      artifactType: "run_result",
      action: "upsert",
      input: {},
    } as any,
  });
  assert.equal(out.structuredContent.status, "artifact_action_not_allowed");
});

test("artifact_management project_context list returns deterministic project names", async () => {
  const root = createTestTempDir("artifact-management-list");
  try {
    writeJson(path.join(root, ".mcpjvm", "zeta", "projects.json"), { workspaces: [{ projectRoot: root }] });
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), { workspaces: [{ projectRoot: root }] });
    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "project_context",
        action: "list",
        input: {},
      },
    });
    assert.equal(out.structuredContent.status, "ok");
    assert.deepEqual(out.structuredContent.projectNames, ["alpha", "zeta"]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("artifact_management project_context read supports structured query projection", async () => {
  const root = createTestTempDir("artifact-management-query");
  try {
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [{ executionProfile: "smoke", executionPolicy: "stop_on_fail", plans: [{ order: 1, planName: "p1" }] }],
          scripts: [{ name: "prep", command: "node" }],
        },
      ],
    });
    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "project_context",
        action: "read",
        input: {
          projectName: "alpha",
          query: { select: ["summary", "executionProfiles"], executionProfile: "smoke" },
        },
      },
    });
    assert.equal(out.structuredContent.status, "ok");
    assert.equal(typeof out.structuredContent.artifact, "undefined");
    assert.equal(out.structuredContent.summary.workspaceCount, 1);
    assert.equal(out.structuredContent.executionProfiles.length, 1);
    assert.equal(out.structuredContent.executionProfiles[0].executionProfile, "smoke");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("artifact_management project_context read returns summary by default", async () => {
  const root = createTestTempDir("artifact-management-default-summary");
  try {
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", startups: [{ name: "app", command: "java" }] }],
          executionProfiles: [{ executionProfile: "smoke", executionPolicy: "stop_on_fail", plans: [{ order: 1, planName: "p1" }] }],
        },
      ],
    });
    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "project_context",
        action: "read",
        input: { projectName: "alpha" },
      },
    });
    assert.equal(out.structuredContent.status, "ok");
    assert.equal(typeof out.structuredContent.artifact, "undefined");
    assert.equal(out.structuredContent.summary.workspaceCount, 1);
    assert.deepEqual(out.structuredContent.summary.executionProfileNames, ["smoke"]);
    assert.deepEqual(out.structuredContent.summary.runtimeContextNames, ["terminal-cli"]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("artifact_management run_result rejects invalid action with deterministic fail-closed output", async () => {
  const out = await artifactManagementDomain({
    workspaceRootAbs: process.cwd(),
    request: {
      artifactType: "run_result",
      action: "validate",
      input: {},
    } as any,
  });
  assert.equal(out.structuredContent.status, "artifact_action_not_allowed");
});
