import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import test from "node:test";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

import { mcpServerEntryAbs, repoRootAbs } from "@test/integrations/support/spring/social_platform/shared.fixture";

async function writeJson(filePath: string, payload: Record<string, unknown>): Promise<void> {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("mcp IT: server starts with multi-probe registry and no implicit Probe route", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-multi-probe-startup-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "gateway-service": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.gateway.**"],
            exclude: [],
          },
          "course-composite-service": {
            baseUrl: "http://127.0.0.1:9195",
            include: ["com.example.course.**"],
            exclude: [],
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [mcpServerEntryAbs],
    cwd: repoRootAbs,
    env: Object.fromEntries(
      Object.entries({
        ...process.env,
        MCP_WORKSPACE_ROOT: workspaceRootAbs,
        MCP_PROBE_CONFIG_FILE: probeConfigAbs,
      }).filter((entry): entry is [string, string] => typeof entry[1] === "string"),
    ),
    stderr: "pipe",
  });
  const logBuffer: string[] = [];
  transport.stderr?.on("data", (chunk) => logBuffer.push(String(chunk)));

  const client = new Client({
    name: "mcp-java-dev-tools-it",
    version: "it",
  });

  try {
    await client.connect(transport);
    const tools = await client.listTools();
    const toolNames = tools.tools.map((entry) => entry.name);
    assert.equal(toolNames.includes("probe"), true);
    assert.equal(toolNames.includes("artifact_management"), true);
    assert.equal(toolNames.includes("execution_orchestration"), true);
  } catch (error) {
    throw new Error(
      `Failed to start MCP server with multi-probe registry.\n${logBuffer.join("\n")}\n${String(error)}`,
    );
  } finally {
    await transport.close().catch(() => undefined);
    await fs.rm(tmpRoot, { recursive: true, force: true }).catch(() => undefined);
  }
});
