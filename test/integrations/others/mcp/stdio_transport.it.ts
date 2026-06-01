import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import test from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { mcpServerEntryAbs, repoRootAbs } from "@test/integrations/support/spring/social_platform/shared.fixture";

async function waitFor(
  check: () => boolean,
  args: { timeoutMs: number; intervalMs?: number; failureMessage: string },
): Promise<void> {
  const timeoutAt = Date.now() + args.timeoutMs;
  const intervalMs = args.intervalMs ?? 50;

  while (Date.now() < timeoutAt) {
    if (check()) {
      return;
    }
    await delay(intervalMs);
  }

  throw new Error(args.failureMessage);
}

async function forceStop(child: ChildProcess): Promise<void> {
  if (!child.pid || child.exitCode !== null) {
    return;
  }

  if (process.platform === "win32") {
    await new Promise<void>((resolve) => {
      const killer = spawn("taskkill", ["/pid", String(child.pid), "/t", "/f"], {
        stdio: "ignore",
        windowsHide: true,
      });
      killer.once("close", () => resolve());
      killer.once("error", () => resolve());
    });
    return;
  }

  try {
    child.kill("SIGTERM");
  } catch {
    return;
  }
  await delay(1_000);
  if (child.exitCode === null) {
    try {
      child.kill("SIGKILL");
    } catch {
      // Ignore cleanup failures during forced shutdown.
    }
  }
}

function getNonEmptyLines(buffer: string): string[] {
  return buffer
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

test("mcp IT: stdio transport keeps stdout protocol-only and writes diagnostics to stderr", async () => {
  const stdoutChunks: string[] = [];
  const stderrChunks: string[] = [];

  const child = spawn(process.execPath, [mcpServerEntryAbs], {
    cwd: repoRootAbs,
    env: {
      ...process.env,
      MCP_WORKSPACE_ROOT: repoRootAbs,
      MCP_PROBE_BASE_URL: "http://127.0.0.1:9191",
    },
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });

  child.stdout?.on("data", (chunk) => {
    stdoutChunks.push(String(chunk));
  });
  child.stderr?.on("data", (chunk) => {
    stderrChunks.push(String(chunk));
  });

  try {
    await waitFor(
      () => stderrChunks.join("").includes("running (stdio)"),
      {
        timeoutMs: 10_000,
        failureMessage: `server did not write startup diagnostics to stderr.\n${stderrChunks.join("")}`,
      },
    );

    const initialize = JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-03-26",
        capabilities: {},
        clientInfo: {
          name: "stdio-transport-it",
          version: "1.0.0",
        },
      },
    });
    const initialized = JSON.stringify({
      jsonrpc: "2.0",
      method: "notifications/initialized",
      params: {},
    });
    const listTools = JSON.stringify({
      jsonrpc: "2.0",
      id: 2,
      method: "tools/list",
      params: {},
    });

    child.stdin?.write(`${initialize}\n`);
    child.stdin?.write(`${initialized}\n`);
    child.stdin?.write(`${listTools}\n`);

    await waitFor(
      () => getNonEmptyLines(stdoutChunks.join("")).length >= 2,
      {
        timeoutMs: 10_000,
        failureMessage:
          `server did not emit expected stdout JSON-RPC responses.\n` +
          `STDOUT:\n${stdoutChunks.join("")}\nSTDERR:\n${stderrChunks.join("")}`,
      },
    );

    const stdoutLines = getNonEmptyLines(stdoutChunks.join(""));
    assert.equal(stdoutLines.length >= 2, true);

    const parsedLines = stdoutLines.map((line) => {
      try {
        return JSON.parse(line) as {
          jsonrpc?: string;
          id?: number;
          result?: {
            tools?: unknown[];
          };
        };
      } catch {
        assert.fail(`stdout line is not valid JSON-RPC payload: ${line}`);
      }
    });

    for (const message of parsedLines) {
      assert.equal(message.jsonrpc, "2.0");
    }

    const toolsListResponse = parsedLines.find((message) => message.id === 2);
    assert.ok(toolsListResponse);
    assert.equal(Array.isArray(toolsListResponse.result?.tools), true);
    const tools = (toolsListResponse.result?.tools ?? []) as Array<{ name?: string }>;
    const toolNames = new Set(
      tools.map((tool) => tool.name).filter((name): name is string => typeof name === "string"),
    );
    assert.equal(toolNames.has("probe_registry_list"), true);
    assert.equal(toolNames.has("probe_registry_reload"), true);
    assert.equal(toolNames.has("artifact_management"), true);
    assert.equal(toolNames.has("execution_orchestration"), true);

    const joinedStdout = stdoutChunks.join("");
    const joinedStderr = stderrChunks.join("");

    assert.equal(joinedStdout.includes("running (stdio)"), false);
    assert.equal(joinedStderr.includes("running (stdio)"), true);
  } finally {
    child.stdin?.end();
    await forceStop(child);
  }
});

