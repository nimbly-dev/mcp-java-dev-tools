import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import test from "node:test";

import { startMcpClient } from "@test/integrations/support/spring/social_platform/shared.fixture";

type ToolResult = {
  content?: Array<{ type?: string; text?: string }>;
  structuredContent?: Record<string, unknown>;
};

const REQUIRED_ORCHESTRATOR_DEFAULTS = {
  resumePollMax: 30,
  resumePollIntervalMs: 10000,
  resumePollTimeoutMs: 300000,
};

function withRequiredDefaults(workspace: Record<string, unknown>): Record<string, unknown> {
  return {
    ...workspace,
    defaults: {
      ...(workspace.defaults && typeof workspace.defaults === "object" ? workspace.defaults : {}),
      orchestrator: REQUIRED_ORCHESTRATOR_DEFAULTS,
    },
  };
}

async function callTool(
  mcp: Awaited<ReturnType<typeof startMcpClient>>,
  name: string,
  args: Record<string, unknown>,
): Promise<ToolResult> {
  return (await mcp.client.callTool({
    name,
    arguments: args,
  })) as ToolResult;
}

async function writeJson(filePath: string, payload: Record<string, unknown>): Promise<void> {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

async function writeRegressionPlan(args: {
  workspaceRootAbs: string;
  projectName: string;
  planName: string;
}): Promise<void> {
  const planRootAbs = path.join(
    args.workspaceRootAbs,
    ".mcpjvm",
    args.projectName,
    "plans",
    "regression",
    args.planName,
  );
  await writeJson(path.join(planRootAbs, "metadata.json"), {
    execution: { intent: "regression" },
  });
  await writeJson(path.join(planRootAbs, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [],
    steps: [],
  });
}

test("mcp IT: artifact_management enforces typed envelope and returns project_context summary by default", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-management-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project";
  const projectsFileAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  await writeJson(projectsFileAbs, {
    workspaces: [
      withRequiredDefaults({
        projectRoot: workspaceRootAbs,
        runtimeContexts: [
          {
            name: "terminal-cli",
            mode: "terminal",
            autoStart: true,
            startups: [{ name: "app", command: "java" }],
          },
        ],
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "gateway-route-smoke-spec" }],
          },
        ],
      }),
    ],
  });
  await writeRegressionPlan({
    workspaceRootAbs,
    projectName,
    planName: "gateway-route-smoke-spec",
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9191",
    });

    const validRead = await callTool(mcp, "artifact_management", {
      artifactType: "project_context",
      action: "read",
      input: {
        projectName,
      },
    });
    assert.equal(validRead.structuredContent?.status, "ok");
    assert.equal(validRead.structuredContent?.artifactType, "project_context");
    assert.equal(validRead.structuredContent?.action, "read");
    assert.equal(typeof validRead.structuredContent?.artifact, "undefined");
    assert.equal(typeof validRead.structuredContent?.summary, "object");
    assert.equal(
      (validRead.structuredContent?.summary as { workspaceCount?: unknown })?.workspaceCount,
      1,
    );

    const legacyFlat = await callTool(mcp, "artifact_management", {
      artifactType: "project_context",
      action: "read",
      projectName,
    });
    assert.equal(typeof legacyFlat.structuredContent, "undefined");
    const maybeText = legacyFlat.content?.[0]?.text;
    assert.match(String(maybeText ?? ""), /-32602|invalid params|MCP error/i);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: artifact_management project_context creation provisions the run-state store", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-management-provision-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({ workspaceRootAbs, probeBaseUrl: "http://127.0.0.1:9191" });
    const out = await callTool(mcp, "artifact_management", {
      artifactType: "project_context",
      action: "upsert",
      input: {
        projectName: "new-project",
        payload: {
          workspaces: [withRequiredDefaults({ projectRoot: workspaceRootAbs })],
        },
      },
    });
    assert.equal(out.structuredContent?.status, "ok");
    assert.deepEqual(out.structuredContent?.stateStore, {
      provisioned: true,
      databasePathRel: ".mcpjvm/new-project/run-state.sqlite",
      schemaVersion: 9,
    });
    assert.equal(
      await fs
        .stat(path.join(workspaceRootAbs, ".mcpjvm", "new-project", "projects.json"))
        .then(() => true)
        .catch(() => false),
      true,
    );
    assert.equal(
      await fs
        .stat(path.join(workspaceRootAbs, ".mcpjvm", "new-project", "run-state.sqlite"))
        .then(() => true)
        .catch(() => false),
      true,
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: artifact_management project_context validate rejects missing orchestrator defaults", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-artifact-management-required-orchestrator-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project";
  const projectsFileAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  await writeJson(projectsFileAbs, {
    workspaces: [{ projectRoot: workspaceRootAbs }],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({ workspaceRootAbs, probeBaseUrl: "http://127.0.0.1:9191" });
    const out = await callTool(mcp, "artifact_management", {
      artifactType: "project_context",
      action: "validate",
      input: { projectName },
    });
    assert.equal(out.structuredContent?.status, "project_artifact_invalid");
    assert.equal(out.structuredContent?.reasonCode, "project_artifact_invalid");
    assert.match(
      String(out.structuredContent?.reason ?? ""),
      /workspaces\[0\]\.defaults is required/i,
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("mcp IT: artifact_management project_context validate fails closed on absolute envFile", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-artifact-management-invalid-envfile-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project";
  const projectsFileAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  await writeJson(projectsFileAbs, {
    workspaces: [
      withRequiredDefaults({
        projectRoot: workspaceRootAbs,
        envFile: "C:\\workspace\\spring\\.env",
      }),
    ],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9191",
    });

    const out = await callTool(mcp, "artifact_management", {
      artifactType: "project_context",
      action: "validate",
      input: {
        projectName,
      },
    });
    assert.equal(out.structuredContent?.status, "project_artifact_invalid");
    assert.equal(out.structuredContent?.reasonCode, "project_artifact_invalid");
    assert.match(
      String(out.structuredContent?.reason ?? ""),
      /envFile must be relative\/replayable/i,
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: artifact_management project_context validate fails closed on missing execution profile plan artifact", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-artifact-management-missing-plan-ref-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project";
  const projectsFileAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  await writeJson(projectsFileAbs, {
    workspaces: [
      withRequiredDefaults({
        projectRoot: workspaceRootAbs,
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "missing-regression-plan" }],
          },
        ],
      }),
    ],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9191",
    });

    const out = await callTool(mcp, "artifact_management", {
      artifactType: "project_context",
      action: "validate",
      input: {
        projectName,
      },
    });
    assert.equal(out.structuredContent?.status, "project_reference_invalid");
    assert.equal(out.structuredContent?.reasonCode, "project_reference_invalid");
    assert.match(
      String(out.structuredContent?.reason ?? ""),
      /planName must match an existing regression plan artifact/i,
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: artifact_management project_context upsert fails closed on unsupported workspace variable mappings and does not persist artifact", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-artifact-management-unsupported-vars-upsert-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project";
  const projectsFileAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9191",
    });

    const out = await callTool(mcp, "artifact_management", {
      artifactType: "project_context",
      action: "upsert",
      input: {
        projectName,
        payload: {
          workspaces: [
            withRequiredDefaults({
              projectRoot: workspaceRootAbs,
              variables: {
                bearerTokenEnv: "AUTH_BEARER_TOKEN",
                tenantIdEnv: "TENANT_ID",
                baseUrlEnv: "BASE_URL",
              },
            }),
          ],
        },
      },
    });
    assert.equal(out.structuredContent?.status, "project_artifact_invalid");
    assert.equal(out.structuredContent?.reasonCode, "project_artifact_invalid");
    assert.match(
      String(out.structuredContent?.reason ?? ""),
      /variables\.tenantIdEnv is unsupported/i,
    );
    assert.equal(fssync.existsSync(projectsFileAbs), false);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: artifact_management project_context validate returns root inspection for canonical selector inputs", async () => {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-artifact-management-root-validate-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "post-service";
  const projectRootAbs = path.join(workspaceRootAbs, "post-service", "post-app");
  const projectsFileAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  await fs.mkdir(path.join(projectRootAbs, "src", "main", "java"), { recursive: true });
  await fs.writeFile(path.join(projectRootAbs, "pom.xml"), "<project/>", "utf8");
  await writeJson(projectsFileAbs, {
    workspaces: [
      withRequiredDefaults({
        projectRoot: projectRootAbs,
      }),
    ],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9191",
    });

    const out = await callTool(mcp, "artifact_management", {
      artifactType: "project_context",
      action: "validate",
      input: {
        projectName,
        projectRootAbs,
      },
    });
    assert.equal(out.structuredContent?.status, "ok");
    assert.equal(out.structuredContent?.projectName, projectName);
    assert.equal(out.structuredContent?.projectRootAbs, projectRootAbs);
    assert.deepEqual(out.structuredContent?.buildMarkers, ["pom.xml"]);
    assert.equal(out.structuredContent?.hasBuildMarker, true);
    assert.equal(Array.isArray(out.structuredContent?.javaSourceRoots), true);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
