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
