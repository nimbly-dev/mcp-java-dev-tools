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

function writeRegressionPlan(root: string, projectName: string, planName: string): void {
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "metadata.json"), {
    execution: { intent: "regression" },
  });
  writeJson(path.join(root, ".mcpjvm", projectName, "plans", "regression", planName, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [],
    steps: [
      {
        order: 1,
        id: "health",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", url: "http://localhost/health" } },
        expect: [{ id: "status-200", actualPath: "$.statusCode", operator: "field_equals", expected: 200 }],
      },
    ],
  });
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
    writeRegressionPlan(root, "alpha", "p1");
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
    writeRegressionPlan(root, "alpha", "p1");
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

test("artifact_management project_context validate fails closed when execution profile references missing plan artifact", async () => {
  const root = createTestTempDir("artifact-management-project-missing-plan");
  try {
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          executionProfiles: [{ executionProfile: "smoke", executionPolicy: "stop_on_fail", plans: [{ order: 1, planName: "missing-plan" }] }],
        },
      ],
    });
    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "project_context",
        action: "validate",
        input: { projectName: "alpha" },
      },
    });
    assert.equal(out.structuredContent.status, "project_reference_invalid");
    assert.equal(out.structuredContent.reasonCode, "project_reference_invalid");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("artifact_management project_context validate returns root inspection for matching projectName and projectRootAbs", async () => {
  const root = createTestTempDir("artifact-management-project-validate-root");
  try {
    fs.mkdirSync(path.join(root, "src", "main", "java"), { recursive: true });
    writeJson(path.join(root, "pom.xml"), { modelVersion: "4.0.0" });
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), {
      workspaces: [{ projectRoot: root }],
    });
    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "project_context",
        action: "validate",
        input: { projectName: "alpha", projectRootAbs: root },
      },
    });
    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.projectName, "alpha");
    assert.equal(out.structuredContent.projectRootAbs, root);
    assert.deepEqual(out.structuredContent.buildMarkers, ["pom.xml"]);
    assert.equal(out.structuredContent.hasBuildMarker, true);
    assert.deepEqual(out.structuredContent.javaSourceRoots, [path.join(root, "src", "main", "java")]);
    assert.equal(out.structuredContent.hasJavaSourceRoot, true);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("artifact_management project_context validate resolves unique project from projectRootAbs", async () => {
  const root = createTestTempDir("artifact-management-project-validate-resolve-root");
  const appRoot = path.join(root, "apps", "post-app");
  try {
    fs.mkdirSync(path.join(appRoot, "src", "main", "java"), { recursive: true });
    writeJson(path.join(appRoot, "pom.xml"), { modelVersion: "4.0.0" });
    writeJson(path.join(root, ".mcpjvm", "post-service", "projects.json"), {
      workspaces: [{ projectRoot: appRoot }],
    });
    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "project_context",
        action: "validate",
        input: { projectRootAbs: appRoot },
      },
    });
    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.projectName, "post-service");
    assert.equal(out.structuredContent.projectRootAbs, appRoot);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("artifact_management project_context validate fails closed when projectName and projectRootAbs mismatch", async () => {
  const root = createTestTempDir("artifact-management-project-validate-mismatch");
  const alphaRoot = path.join(root, "apps", "alpha");
  const betaRoot = path.join(root, "apps", "beta");
  try {
    fs.mkdirSync(alphaRoot, { recursive: true });
    fs.mkdirSync(betaRoot, { recursive: true });
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), {
      workspaces: [{ projectRoot: alphaRoot }],
    });
    writeJson(path.join(root, ".mcpjvm", "beta", "projects.json"), {
      workspaces: [{ projectRoot: betaRoot }],
    });
    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "project_context",
        action: "validate",
        input: { projectName: "alpha", projectRootAbs: betaRoot },
      },
    });
    assert.equal(out.structuredContent.status, "project_scope_mismatch");
    assert.equal(out.structuredContent.reasonCode, "project_scope_mismatch");
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

test("artifact_management run_result blocks generate action after orchestration extraction", async () => {
  const out = await artifactManagementDomain({
    workspaceRootAbs: process.cwd(),
    request: {
      artifactType: "run_result",
      action: "generate",
      input: {},
    } as any,
  });
  assert.equal(out.structuredContent.status, "artifact_action_not_allowed");
});

test("artifact_management regression_plan validate fails closed when step protocol is unsupported by execution_orchestration", async () => {
  const root = createTestTempDir("artifact-management-regression-validate-protocol");
  try {
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), {
      workspaces: [{ projectRoot: root }],
    });
    writeJson(path.join(root, ".mcpjvm", "alpha", "plans", "regression", "p1", "metadata.json"), {
      execution: { intent: "regression" },
    });
    writeJson(path.join(root, ".mcpjvm", "alpha", "plans", "regression", "p1", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "artifact_read_full",
          targetRef: 0,
          protocol: "artifact_management",
          transport: {
            artifact_management: {
              action: "read",
              artifactType: "regression_plan",
            },
          },
          expect: [{ id: "e1", actualPath: "status", operator: "field_equals", expected: "ok" }],
        },
      ],
    });

    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "regression_plan",
        action: "validate",
        input: { projectName: "alpha", planName: "p1" },
      },
    });

    assert.equal(out.structuredContent.status, "transport_protocol_mismatch");
    assert.equal(out.structuredContent.reasonCode, "transport_protocol_mismatch");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("artifact_management regression_plan read supports windowable prerequisites and steps sections", async () => {
  const root = createTestTempDir("artifact-management-regression-windowed-read");
  try {
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), {
      workspaces: [{ projectRoot: root }],
    });
    writeJson(path.join(root, ".mcpjvm", "alpha", "plans", "regression", "p1", "metadata.json"), {
      execution: { intent: "regression" },
    });
    writeJson(path.join(root, ".mcpjvm", "alpha", "plans", "regression", "p1", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: Array.from({ length: 4 }, (_, index) => ({
        key: `ctx-${index + 1}`,
        source: "provided",
      })),
      steps: Array.from({ length: 3 }, (_, index) => ({
        order: index + 1,
        id: `step-${index + 1}`,
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", url: `http://localhost/${index + 1}` } },
        expect: [{ id: `e-${index + 1}`, actualPath: "$.statusCode", operator: "numeric_gte", expected: 200 }],
      })),
    });

    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "regression_plan",
        action: "read",
        input: {
          projectName: "alpha",
          planName: "p1",
          query: {
            select: ["summary", "targets", "prerequisites", "steps"],
            prerequisites: { offset: 1, limit: 2 },
            steps: { offset: 1, limit: 1 },
          },
        },
      },
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.equal(out.structuredContent.summary.stepCount, 3);
    assert.equal(out.structuredContent.summary.prerequisiteCount, 4);
    assert.equal(out.structuredContent.targets.length, 1);
    assert.equal(out.structuredContent.prerequisites.offset, 1);
    assert.equal(out.structuredContent.prerequisites.returned, 2);
    assert.equal(out.structuredContent.prerequisites.total, 4);
    assert.equal(out.structuredContent.prerequisites.items[0].key, "ctx-2");
    assert.equal(out.structuredContent.steps.offset, 1);
    assert.equal(out.structuredContent.steps.returned, 1);
    assert.equal(out.structuredContent.steps.total, 3);
    assert.equal(out.structuredContent.steps.items[0].id, "step-2");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("artifact_management run_result read uses explicit projectName in multi-project workspace", async () => {
  const root = createTestTempDir("artifact-management-run-read-project");
  try {
    writeJson(path.join(root, ".mcpjvm", "alpha", "projects.json"), {
      workspaces: [{ projectRoot: root }],
    });
    writeJson(path.join(root, ".mcpjvm", "beta", "projects.json"), {
      workspaces: [{ projectRoot: root }],
    });
    writeJson(
      path.join(root, ".mcpjvm", "beta", "plans", "regression", "misc-controllers", "runs", "06-05-2026-07-27-58AM", "execution.result.json"),
      {
        status: "pass",
        steps: [{ order: 1, id: "health_check", status: "pass" }],
      },
    );
    writeJson(
      path.join(root, ".mcpjvm", "beta", "plans", "regression", "misc-controllers", "runs", "06-05-2026-07-27-58AM", "evidence.json"),
      { targetResolution: [] },
    );

    const out = await artifactManagementDomain({
      workspaceRootAbs: root,
      request: {
        artifactType: "run_result",
        action: "read",
        input: {
          projectName: "beta",
          planName: "misc-controllers",
          runId: "06-05-2026-07-27-58AM",
        },
      },
    });

    assert.equal(out.structuredContent.status, "ok");
    assert.match(String(out.structuredContent.runDirAbs).replaceAll("\\", "/"), /\.mcpjvm\/beta\/plans\/regression\/misc-controllers\/runs\/06-05-2026-07-27-58AM$/);
    assert.notEqual(out.structuredContent.reasonCode, "project_artifact_ambiguous");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
