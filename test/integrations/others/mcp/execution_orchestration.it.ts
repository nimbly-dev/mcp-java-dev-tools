import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import test from "node:test";

import { startMcpClient } from "@test/integrations/support/spring/social_platform/shared.fixture";

type ToolResult = {
  structuredContent?: Record<string, unknown>;
};

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

test("mcp IT: execution_orchestration execute uses runtime suite path and does not create exports", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-performance";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const exportsRootAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "exports");

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        defaultProbe: "gateway-service",
        probes: { "gateway-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        executionProfiles: [
          {
            executionProfile: "test-performance-stress-suite",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "mcp-tool-performance-replay-spec", onFail: "inherit" }],
          },
        ],
      },
    ],
  });
  await writeJson(
    path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      "mcp-tool-performance-replay-spec",
      "metadata.json",
    ),
    {
      execution: { intent: "regression" },
    },
  );
  await writeJson(
    path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      "mcp-tool-performance-replay-spec",
      "contract.json",
    ),
    {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [],
    },
  );

  await fs.mkdir(exportsRootAbs, { recursive: true });
  const beforeExportEntries = await fs.readdir(exportsRootAbs).catch(() => []);

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "test-performance-stress-suite",
      },
    });

    const afterExportEntries = await fs.readdir(exportsRootAbs).catch(() => []);
    assert.deepEqual(afterExportEntries.sort(), beforeExportEntries.sort());
    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.notEqual(out.structuredContent?.reasonCode, "execution_profile_export_failed");
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration execute does not fail with project_artifact_ambiguous when projectName is explicit in multi-project workspace", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-multi-project-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const targetProjectName = "test-project-performance";
  const otherProjectName = "test-project";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        defaultProbe: "gateway-service",
        probes: { "gateway-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", otherProjectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        executionProfiles: [
          {
            executionProfile: "smoke",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "other-smoke-plan", onFail: "inherit" }],
          },
        ],
      },
    ],
  });
  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", targetProjectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        executionProfiles: [
          {
            executionProfile: "test-performance-stress-suite",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: "mcp-tool-performance-replay-spec", onFail: "inherit" }],
          },
        ],
      },
    ],
  });
  await writeJson(
    path.join(
      workspaceRootAbs,
      ".mcpjvm",
      targetProjectName,
      "plans",
      "regression",
      "mcp-tool-performance-replay-spec",
      "metadata.json",
    ),
    {
      execution: { intent: "regression" },
    },
  );
  await writeJson(
    path.join(
      workspaceRootAbs,
      ".mcpjvm",
      targetProjectName,
      "plans",
      "regression",
      "mcp-tool-performance-replay-spec",
      "contract.json",
    ),
    {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [],
    },
  );

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName: targetProjectName,
        executionProfile: "test-performance-stress-suite",
      },
    });

    assert.notEqual(out.structuredContent?.status, "project_artifact_ambiguous");
    assert.notEqual(out.structuredContent?.reasonCode, "project_artifact_ambiguous");
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration fails closed on unsupported transport placeholder syntax before creating a run", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-placeholder-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-performance";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "mcp-tool-performance-replay-spec-temp-repro";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
  );
  const runRootAbs = path.join(planRootAbs, "runs");

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        defaultProbe: "gateway-service",
        probes: { "gateway-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        executionProfiles: [
          {
            executionProfile: "test-performance-contract-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName, onFail: "inherit" }],
          },
        ],
      },
    ],
  });
  await writeJson(path.join(planRootAbs, "metadata.json"), {
    execution: { intent: "regression" },
  });
  await writeJson(path.join(planRootAbs, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [
      {
        key: "targetBaseUrl",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: "http://127.0.0.1:8080",
      },
    ],
    steps: [
      {
        order: 1,
        id: "sample_hello_001",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            url: "{{ targetBaseUrl }}/api/metrics/hello",
            body: {
              nested: ["{{targetBaseUrl}}"],
            },
          },
        },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "test-performance-contract-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "blocked");
    assert.equal(planRuns[0]?.blockedReasonCode, "transport_placeholder_syntax_invalid");
    assert.equal(fssync.existsSync(runRootAbs), false);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration continue_on_fail stops after suite-level env blocker and creates no runs", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-suite-block-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-performance";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const firstPlanName = "producer-endpoint";
  const secondPlanName = "consumer-listener";
  const firstRunRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    firstPlanName,
    "runs",
  );
  const secondRunRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    secondPlanName,
    "runs",
  );

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        defaultProbe: "gateway-service",
        probes: { "gateway-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        variables: {
          bearerTokenEnv: "AUTH_BEARER_TOKEN",
        },
        executionProfiles: [
          {
            executionProfile: "producer-consumer-regression",
            executionPolicy: "continue_on_fail",
            plans: [
              { order: 1, planName: firstPlanName, onFail: "inherit" },
              { order: 2, planName: secondPlanName, onFail: "inherit" },
            ],
          },
        ],
      },
    ],
  });

  for (const planName of [firstPlanName, secondPlanName]) {
    const planRootAbs = path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      planName,
    );
    await writeJson(path.join(planRootAbs, "metadata.json"), {
      execution: { intent: "regression" },
    });
    await writeJson(path.join(planRootAbs, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [
        {
          key: "auth.bearer",
          required: true,
          secret: true,
          provisioning: "user_input",
        },
      ],
      steps: [
        {
          order: 1,
          id: "auth_step",
          targetRef: 0,
          protocol: "http",
          transport: {
            http: {
              method: "GET",
              url: "http://127.0.0.1:8080/secure",
              headers: { Authorization: "Bearer ${auth.bearer}" },
            },
          },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });
  }

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "producer-consumer-regression",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 2);
    assert.equal(planRuns[0]?.status, "blocked");
    assert.equal(planRuns[0]?.blockedReasonCode, "env_key_missing");
    assert.equal(planRuns[1]?.status, "skipped");
    assert.equal(fssync.existsSync(firstRunRootAbs), false);
    assert.equal(fssync.existsSync(secondRunRootAbs), false);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
