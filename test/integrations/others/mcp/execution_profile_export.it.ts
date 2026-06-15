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

async function writeProjectArtifacts(args: {
  workspaceRootAbs: string;
  projectName: string;
  executionProfiles: string[];
  planNames?: string[];
}): Promise<void> {
  const projectRootAbs = path.join(args.workspaceRootAbs, "app", args.projectName);
  const projectDirAbs = path.join(args.workspaceRootAbs, ".mcpjvm", args.projectName);
  const plansRootAbs = path.join(projectDirAbs, "plans", "regression");
  const scriptsRootAbs = path.join(projectDirAbs, "scripts");
  await fs.mkdir(scriptsRootAbs, { recursive: true });
  await fs.writeFile(path.join(scriptsRootAbs, "setup.js"), "const x = 1;\n", "utf8");

  const profiles = args.executionProfiles.map((executionProfile, index) => ({
    executionProfile,
    executionPolicy: "stop_on_fail",
    scriptRefs: [{ name: "setup-js", phase: "prePlan" }],
    plans: [{ order: 1, planName: args.planNames?.[index] ?? `plan-${index + 1}`, onFail: "inherit" }],
  }));

  await writeJson(path.join(projectDirAbs, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        scripts: [
          {
            name: "setup-js",
            phase: "prePlan",
            command: "node",
            args: [`.mcpjvm/${args.projectName}/scripts/setup.js`],
          },
        ],
        executionProfiles: profiles,
      },
    ],
  });

  for (let index = 0; index < args.executionProfiles.length; index += 1) {
    const planName = args.planNames?.[index] ?? `plan-${index + 1}`;
    const planRootAbs = path.join(plansRootAbs, planName);
    await writeJson(path.join(planRootAbs, "metadata.json"), {
      execution: { intent: "regression" },
    });
    await writeJson(path.join(planRootAbs, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "health_check",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://127.0.0.1:8080/actuator/health" } },
          expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
        },
      ],
    });
    await fs.writeFile(path.join(planRootAbs, "plan.md"), `# ${planName}\n`, "utf8");
  }
}

test("mcp IT: execution_profile_export fails closed when project context is ambiguous", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-profile-export-ambig-project-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: { "gateway-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });
  await writeProjectArtifacts({
    workspaceRootAbs,
    projectName: "entity-regression",
    executionProfiles: ["regression-test-run"],
    planNames: ["data-seed"],
  });
  await writeProjectArtifacts({
    workspaceRootAbs,
    projectName: "configuration-api",
    executionProfiles: ["config-run"],
    planNames: ["config-seed"],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_profile_export", {
      executionProfile: "regression-test-run",
      planName: "data-seed",
      mode: "ps1",
    });

    assert.equal(out.structuredContent?.status, "project_artifact_ambiguous");
    assert.equal(out.structuredContent?.reasonCode, "project_artifact_ambiguous");
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("mcp IT: execution_profile_export honors explicit projectName in multi-project workspace", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-profile-export-project-selector-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: { "gateway-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });
  await writeProjectArtifacts({
    workspaceRootAbs,
    projectName: "entity-regression",
    executionProfiles: ["regression-test-run"],
    planNames: ["data-seed"],
  });
  await writeProjectArtifacts({
    workspaceRootAbs,
    projectName: "configuration-api",
    executionProfiles: ["config-run"],
    planNames: ["config-seed"],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_profile_export", {
      projectName: "entity-regression",
      executionProfile: "regression-test-run",
      planName: "data-seed",
      mode: "ps1",
    });

    assert.equal(out.structuredContent?.status, "ok");
    assert.equal(out.structuredContent?.resultType, "execution_profile_export");
    assert.equal(out.structuredContent?.executionProfile, "regression-test-run");
    assert.notEqual(out.structuredContent?.reasonCode, "project_artifact_ambiguous");
    assert.match(
      String(out.structuredContent?.exportDirAbs ?? "").replaceAll("\\", "/"),
      /\/\.mcpjvm\/entity-regression\/exports\//,
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});
