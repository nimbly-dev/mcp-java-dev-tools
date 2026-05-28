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

test("mcp IT: artifact_management enforces typed envelope and returns project_context summary by default", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-management-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project";
  const projectsFileAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  await writeJson(projectsFileAbs, {
    workspaces: [
      {
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
      },
    ],
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
    assert.equal((validRead.structuredContent?.summary as { workspaceCount?: unknown })?.workspaceCount, 1);

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
