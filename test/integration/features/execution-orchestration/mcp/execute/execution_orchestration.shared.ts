import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as http from "node:http";
import * as os from "node:os";
import * as path from "node:path";
import test from "node:test";

import { startMcpClient } from "@test/support/spring/social-platform/shared.fixture";
import { renderPerformanceResultFromArtifacts } from "@tools-feature-performance-suite";

export type ToolResult = {
  structuredContent?: Record<string, unknown>;
};

export async function callTool(
  mcp: Awaited<ReturnType<typeof startMcpClient>>,
  name: string,
  args: Record<string, unknown>,
): Promise<ToolResult> {
  return (await mcp.client.callTool({
    name,
    arguments: args,
  })) as ToolResult;
}

export async function writeJson(filePath: string, payload: Record<string, unknown>): Promise<void> {
  const nextPayload =
    path.basename(filePath) === "projects.json" && Array.isArray(payload.workspaces)
      ? {
          ...payload,
          workspaces: payload.workspaces.map((workspace) => {
            if (!workspace || typeof workspace !== "object" || Array.isArray(workspace))
              return workspace;
            const defaults =
              "defaults" in workspace &&
              workspace.defaults &&
              typeof workspace.defaults === "object"
                ? workspace.defaults
                : {};
            const orchestrator =
              "orchestrator" in defaults &&
              defaults.orchestrator &&
              typeof defaults.orchestrator === "object"
                ? defaults.orchestrator
                : {
                    resumePollMax: 30,
                    resumePollIntervalMs: 10000,
                    resumePollTimeoutMs: 300000,
                  };
            return {
              ...workspace,
              defaults: {
                ...defaults,
                orchestrator,
              },
            };
          }),
        }
      : payload;
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(nextPayload, null, 2)}\n`, "utf8");
}

export async function listen(server: http.Server): Promise<number> {
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("server address unavailable");
  }
  return address.port;
}

async function cleanupTemporaryWorkspace(tmpRoot: string): Promise<void> {
  await fs.rm(tmpRoot, {
    recursive: true,
    force: true,
    maxRetries: 5,
    retryDelay: 100,
  });
}

export {
  assert,
  fs,
  fssync,
  http,
  os,
  path,
  test,
  cleanupTemporaryWorkspace,
  renderPerformanceResultFromArtifacts,
  startMcpClient,
};
