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

test("mcp IT: artifact_management covers probe_config/regression_plan/run_result/execution_export flows", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-management-types-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project";
  const projectRootAbs = path.join(workspaceRootAbs, "app");
  const mcpjvmRoot = path.join(workspaceRootAbs, ".mcpjvm");

  const probeConfigAbs = path.join(mcpjvmRoot, "probe-config.json");
  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        defaultProbe: "gateway-service",
        probes: {
          "gateway-service": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.gateway.**"],
            exclude: [],
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  const projectsFileAbs = path.join(mcpjvmRoot, projectName, "projects.json");
  await writeJson(projectsFileAbs, {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        scripts: [
          {
            name: "setup-js",
            phase: "prePlan",
            command: "node",
            args: [`.mcpjvm/${projectName}/scripts/setup.js`],
          },
        ],
        executionProfiles: [
          {
            executionProfile: "regression-test-run",
            executionPolicy: "stop_on_fail",
            scriptRefs: [{ name: "setup-js", phase: "prePlan" }],
            plans: [{ order: 1, planName: "gateway-route-smoke-spec", onFail: "inherit" }],
          },
        ],
      },
    ],
  });

  const planRoot = path.join(mcpjvmRoot, projectName, "plans", "regression", "gateway-route-smoke-spec");
  await writeJson(path.join(planRoot, "metadata.json"), {
    execution: { intent: "regression" },
  });
  await writeJson(path.join(planRoot, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
    prerequisites: [],
    steps: [
      {
        order: 1,
        id: "health_check",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            url: "http://127.0.0.1:8080/actuator/health",
          },
        },
        expect: [{ id: "e1", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
      },
    ],
  });
  await fs.mkdir(planRoot, { recursive: true });
  await fs.writeFile(path.join(planRoot, "plan.md"), "# Gateway Route Smoke\n", "utf8");

  const runRoot = path.join(planRoot, "runs", "05-28-2026-10-10-10AM");
  await writeJson(path.join(runRoot, "execution.result.json"), {
    status: "ok",
    steps: [{ order: 1, id: "health_check", status: "passed", durationMs: 120 }],
  });
  await writeJson(path.join(runRoot, "evidence.json"), { probe: { status: "verified_line_hit" } });

  const exportsRoot = path.join(mcpjvmRoot, projectName, "exports", "2026-05-28-abcdef");
  await fs.mkdir(exportsRoot, { recursive: true });
  await fs.writeFile(path.join(exportsRoot, "run-execution-profile.sh"), "#!/usr/bin/env sh\n", "utf8");
  await fs.writeFile(path.join(exportsRoot, "project.env"), "A=B\n", "utf8");

  const scriptsRoot = path.join(mcpjvmRoot, projectName, "scripts");
  await fs.mkdir(scriptsRoot, { recursive: true });
  await fs.writeFile(path.join(scriptsRoot, "setup.js"), "const x = 1;\n", "utf8");

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: {
        MCP_PROBE_CONFIG_FILE: probeConfigAbs,
      },
    });

    const probeValidate = await callTool(mcp, "artifact_management", {
      artifactType: "probe_config",
      action: "validate",
      input: {},
    });
    assert.equal(probeValidate.structuredContent?.status, "ok");
    assert.equal(probeValidate.structuredContent?.defaultProbeId, "gateway-service");

    const planReadSummary = await callTool(mcp, "artifact_management", {
      artifactType: "regression_plan",
      action: "read",
      input: { projectName, planName: "gateway-route-smoke-spec" },
    });
    assert.equal(planReadSummary.structuredContent?.status, "ok");
    assert.equal(typeof planReadSummary.structuredContent?.summary, "object");
    assert.equal((planReadSummary.structuredContent?.summary as { stepCount?: unknown })?.stepCount, 1);

    const planReadDetail = await callTool(mcp, "artifact_management", {
      artifactType: "regression_plan",
      action: "read",
      input: { projectName, planName: "gateway-route-smoke-spec", query: { select: ["contract"] } },
    });
    assert.equal(planReadDetail.structuredContent?.status, "ok");
    assert.equal(typeof (planReadDetail.structuredContent?.artifact as { contract?: unknown })?.contract, "object");

    const runList = await callTool(mcp, "artifact_management", {
      artifactType: "run_result",
      action: "list",
      input: { projectName, planName: "gateway-route-smoke-spec" },
    });
    assert.equal(runList.structuredContent?.status, "ok");
    assert.deepEqual(runList.structuredContent?.runIds, ["05-28-2026-10-10-10AM"]);

    const runReadSummary = await callTool(mcp, "artifact_management", {
      artifactType: "run_result",
      action: "read",
      input: { projectName, planName: "gateway-route-smoke-spec", runId: "05-28-2026-10-10-10AM" },
    });
    assert.equal(runReadSummary.structuredContent?.status, "ok");
    assert.equal(typeof runReadSummary.structuredContent?.summary, "object");
    assert.equal((runReadSummary.structuredContent?.summary as { stepCount?: unknown })?.stepCount, 1);

    const exportList = await callTool(mcp, "artifact_management", {
      artifactType: "execution_export",
      action: "list",
      input: { projectName },
    });
    assert.equal(exportList.structuredContent?.status, "ok");
    assert.deepEqual(exportList.structuredContent?.exportFolders, ["2026-05-28-abcdef"]);

    const exportRead = await callTool(mcp, "artifact_management", {
      artifactType: "execution_export",
      action: "read",
      input: { projectName, query: { exportId: "2026-05-28-abcdef" } },
    });
    assert.equal(exportRead.structuredContent?.status, "ok");
    assert.equal(Array.isArray(exportRead.structuredContent?.files), true);

    const exportGenerate = await callTool(mcp, "artifact_management", {
      artifactType: "execution_export",
      action: "generate",
      input: {
        projectName,
        executionProfile: "regression-test-run",
        mode: "sh",
      },
    });
    assert.equal(exportGenerate.structuredContent?.status, "ok");
    assert.equal(exportGenerate.structuredContent?.resultType, "execution_profile_export");
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
