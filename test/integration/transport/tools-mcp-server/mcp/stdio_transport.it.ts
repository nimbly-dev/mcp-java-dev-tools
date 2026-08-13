import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import * as fs from "node:fs/promises";
import * as http from "node:http";
import * as os from "node:os";
import * as path from "node:path";
import { pathToFileURL } from "node:url";
import test from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import {
  mcpServerEntryAbs,
  repoRootAbs,
} from "@test/support/spring/social-platform/shared.fixture";

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

test("[IT][tools-mcp-server][stdio] stdio transport keeps stdout protocol-only and writes diagnostics to stderr", async () => {
  const stdoutChunks: string[] = [];
  const stderrChunks: string[] = [];
  let authorizedFailureAnalysisRequests = 0;
  const sidecar = http.createServer((request, response) => {
    if (request.url !== "/__probe/failure/analyze") {
      response.statusCode = 404;
      response.end();
      return;
    }
    if (request.headers.authorization !== "Bearer test-observe-token") {
      response.statusCode = 401;
      response.end();
      return;
    }
    authorizedFailureAnalysisRequests += 1;
    response.setHeader("content-type", "application/json");
    response.end(
      JSON.stringify({
        fingerprint: {
          exceptionType: "com.example.OrderFailure",
          rootCauseType: "java.lang.IllegalStateException",
          nearestApplicationFrame: { className: "com.example.OrderService", methodName: "submit" },
          complete: true,
          incompletenessReasons: [],
        },
        investigationCandidates: [],
        reasons: [],
      }),
    );
  });
  await new Promise<void>((resolve) => sidecar.listen(0, "127.0.0.1", resolve));
  const sidecarAddress = sidecar.address();
  if (!sidecarAddress || typeof sidecarAddress === "string")
    throw new Error("sidecar address unavailable");
  const sidecarBaseUrl = `http://127.0.0.1:${sidecarAddress.port}`;
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-stdio-transport-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  await fs.mkdir(path.dirname(probeConfigAbs), { recursive: true });
  await fs.writeFile(
    probeConfigAbs,
    `${JSON.stringify(
      {
        defaultProfile: "dev",
        profiles: {
          dev: {
            probes: {
              "post-app": {
                baseUrl: sidecarBaseUrl,
                include: ["com.example.social.**"],
                exclude: [],
              },
            },
          },
        },
        workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
      },
      null,
      2,
    )}\n`,
    "utf8",
  );

  const child = spawn(process.execPath, [mcpServerEntryAbs], {
    cwd: repoRootAbs,
    env: {
      ...process.env,
      MCP_WORKSPACE_ROOT: workspaceRootAbs,
      MCP_PROBE_CONFIG_FILE: probeConfigAbs,
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
    const analyzeTrace = JSON.stringify({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: {
        name: "failure_analysis",
        arguments: {
          action: "analyze_trace",
          input: {
            trace: "com.example.OrderFailure: failed",
            sidecarBaseUrl,
            sidecarAuthorization: "Bearer test-observe-token",
          },
        },
      },
    });
    const cancelledInvestigation = JSON.stringify({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: {
        name: "failure_analysis",
        arguments: {
          action: "verify_reproduction",
          input: {
            terminalState: {
              outcome: "CANCELLED",
              reasonCode: "user_cancelled",
              cleanupStatus: "cleanup_confirmed",
              attemptCount: 1,
            },
          },
        },
      },
    });

    child.stdin?.write(`${initialize}\n`);
    child.stdin?.write(`${initialized}\n`);
    child.stdin?.write(`${listTools}\n`);
    child.stdin?.write(`${analyzeTrace}\n`);
    child.stdin?.write(`${cancelledInvestigation}\n`);

    await waitFor(
      () =>
        getNonEmptyLines(stdoutChunks.join("")).length >= 4 &&
        stderrChunks.join("").includes("running (stdio)"),
      {
        timeoutMs: 10_000,
        failureMessage:
          `server did not emit expected stdout JSON-RPC responses or stderr startup diagnostics.\n` +
          `STDOUT:\n${stdoutChunks.join("")}\nSTDERR:\n${stderrChunks.join("")}`,
      },
    );

    const stdoutLines = getNonEmptyLines(stdoutChunks.join(""));
    assert.equal(stdoutLines.length >= 4, true);

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
    assert.equal(toolNames.has("probe"), true);
    assert.equal(toolNames.has("route_synthesis"), true);
    assert.equal(toolNames.has("failure_analysis"), true);

    const analysisResponse = parsedLines.find((message) => message.id === 3);
    const analysisResult = (analysisResponse?.result ?? {}) as {
      structuredContent?: { outcome?: string };
    };
    const analysisStructuredContent = analysisResult.structuredContent;
    assert.equal(analysisStructuredContent?.outcome, "ANALYZED");
    assert.equal(authorizedFailureAnalysisRequests, 1);
    const cancellationResponse = parsedLines.find((message) => message.id === 4);
    const cancellationResult = (cancellationResponse?.result ?? {}) as {
      structuredContent?: { outcome?: string; cleanupStatus?: string; diagnosisClaimed?: boolean };
    };
    assert.equal(cancellationResult.structuredContent?.outcome, "CANCELLED");
    assert.equal(cancellationResult.structuredContent?.cleanupStatus, "cleanup_confirmed");
    assert.equal(cancellationResult.structuredContent?.diagnosisClaimed, false);
    assert.equal(toolNames.has("probe_check"), false);
    assert.equal(toolNames.has("probe_enable"), false);
    assert.equal(toolNames.has("probe_get_capture"), false);
    assert.equal(toolNames.has("probe_get_status"), false);
    assert.equal(toolNames.has("probe_reset"), false);
    assert.equal(toolNames.has("probe_wait_for_hit"), false);
    assert.equal(toolNames.has("probe_registry_list"), false);
    assert.equal(toolNames.has("probe_registry_reload"), false);
    assert.equal(toolNames.has("artifact_management"), true);
    assert.equal(toolNames.has("execution_orchestration"), true);
    assert.equal(toolNames.has("project_context_validate"), false);
    assert.equal(toolNames.has("probe_target_infer"), false);
    assert.equal(toolNames.has("probe_recipe_create"), false);

    const joinedStdout = stdoutChunks.join("");
    const joinedStderr = stderrChunks.join("");

    assert.equal(joinedStdout.includes("running (stdio)"), false);
    assert.equal(joinedStderr.includes("running (stdio)"), true);
  } finally {
    child.stdin?.end();
    await forceStop(child);
    await new Promise<void>((resolve) => sidecar.close(() => resolve()));
    await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("[IT][tools-mcp-server][stdio] stdio server exits after stdin closes", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-stdio-shutdown-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  await fs.mkdir(path.dirname(probeConfigAbs), { recursive: true });
  await fs.writeFile(
    probeConfigAbs,
    `${JSON.stringify(
      {
        defaultProfile: "dev",
        profiles: {
          dev: {
            probes: {
              "post-app": {
                baseUrl: "http://127.0.0.1:9191",
                include: ["com.example.social.**"],
                exclude: [],
              },
            },
          },
        },
        workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
      },
      null,
      2,
    )}\n`,
    "utf8",
  );

  const child = spawn(process.execPath, [mcpServerEntryAbs], {
    cwd: repoRootAbs,
    env: {
      ...process.env,
      MCP_WORKSPACE_ROOT: workspaceRootAbs,
      MCP_PROBE_CONFIG_FILE: probeConfigAbs,
    },
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });

  const stderrChunks: string[] = [];
  child.stderr?.on("data", (chunk) => {
    stderrChunks.push(String(chunk));
  });

  try {
    await waitFor(() => stderrChunks.join("").includes("running (stdio)"), {
      timeoutMs: 10_000,
      failureMessage: `server did not start.\n${stderrChunks.join("")}`,
    });

    child.stdin?.end();

    await waitFor(() => child.exitCode !== null, {
      timeoutMs: 10_000,
      failureMessage: `server did not exit after stdin closed.\n${stderrChunks.join("")}`,
    });

    assert.equal(child.exitCode, 0);
    assert.equal(stderrChunks.join("").includes("shutdown: stdin_"), true);
  } finally {
    await forceStop(child);
    await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("[IT][tools-mcp-server][stdio] reports oversized frames on stderr without contaminating stdout", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-stdio-frame-limit-it-"));
  const stdoutChunks: string[] = [];
  const stderrChunks: string[] = [];
  const child = spawn(process.execPath, [mcpServerEntryAbs], {
    cwd: repoRootAbs,
    env: {
      ...process.env,
      MCP_WORKSPACE_ROOT: tmpRoot,
      MCP_STDIO_MAX_BUFFER_SIZE: String(1 * 1024 * 1024),
    },
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });

  child.stdin?.on("error", () => undefined);
  child.stdout?.on("data", (chunk) => stdoutChunks.push(String(chunk)));
  child.stderr?.on("data", (chunk) => stderrChunks.push(String(chunk)));

  try {
    const initialize = JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-03-26",
        capabilities: {},
        clientInfo: { name: "stdio-frame-limit-it", version: "1.0.0" },
      },
    });
    child.stdin?.write(`${initialize}\n`);

    await waitFor(
      () =>
        getNonEmptyLines(stdoutChunks.join("")).some((line) => {
          try {
            return (JSON.parse(line) as { id?: number }).id === 1;
          } catch {
            return false;
          }
        }),
      {
        timeoutMs: 10_000,
        failureMessage: `server did not acknowledge initialize.\nSTDOUT:\n${stdoutChunks.join("")}\nSTDERR:\n${stderrChunks.join("")}`,
      },
    );

    const oversizedRequest =
      JSON.stringify({
        jsonrpc: "2.0",
        id: 2,
        method: "tools/call",
        params: {
          name: "transport_execute",
          arguments: {
            protocol: "http",
            request: {
              method: "GET",
              url: "http://127.0.0.1:1/oversized-frame",
              headers: { Authorization: `Bearer ${"x".repeat(1_100_000)}` },
            },
            options: { wrappedOnly: true },
          },
        },
      }) + "\n";
    assert.equal(Buffer.byteLength(oversizedRequest) > 1 * 1024 * 1024, true);
    child.stdin?.write(oversizedRequest);

    await waitFor(
      () =>
        stderrChunks
          .join("")
          .includes(
            "mcp-java-dev-tools stdio transport error: ReadBuffer exceeded maximum size of 1048576 bytes",
          ),
      {
        timeoutMs: 10_000,
        failureMessage: `oversized frame diagnostic missing.\nSTDERR:\n${stderrChunks.join("")}`,
      },
    );

    for (const line of getNonEmptyLines(stdoutChunks.join(""))) {
      assert.equal((JSON.parse(line) as { jsonrpc?: string }).jsonrpc, "2.0");
    }

    await waitFor(() => child.exitCode !== null, {
      timeoutMs: 10_000,
      failureMessage: `server did not terminate after oversized frame.\nSTDERR:\n${stderrChunks.join("")}`,
    });
    assert.equal(child.exitCode, 1);
    assert.equal(stderrChunks.join("").includes("shutdown: stdio_transport_error"), true);
  } finally {
    child.stdin?.end();
    await forceStop(child);
    await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("[IT][tools-mcp-server][stdio] Roots bind workspace context and expose ambiguity across Roots changes", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-roots-context-it-"));
  const firstRoot = path.join(tmpRoot, "first");
  const secondRoot = path.join(tmpRoot, "second");
  for (const root of [firstRoot, secondRoot]) {
    const probeConfigAbs = path.join(root, ".mcpjvm", "probe-config.json");
    await fs.mkdir(path.dirname(probeConfigAbs), { recursive: true });
    await fs.writeFile(probeConfigAbs, "{}\n", "utf8");
  }

  const env = { ...process.env };
  delete env.MCP_WORKSPACE_ROOT;
  delete env.MCP_PROBE_CONFIG_FILE;
  delete env.INIT_CWD;
  delete env.PWD;
  const child = spawn(process.execPath, [mcpServerEntryAbs], {
    cwd: repoRootAbs,
    env,
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });
  const messages: Array<Record<string, unknown>> = [];
  let stdoutBuffer = "";
  child.stdout?.on("data", (chunk) => {
    stdoutBuffer += String(chunk);
    const lines = stdoutBuffer.split(/\r?\n/);
    stdoutBuffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line.trim()) continue;
      messages.push(JSON.parse(line) as Record<string, unknown>);
    }
  });

  const send = (message: Record<string, unknown>) => {
    child.stdin?.write(`${JSON.stringify(message)}\n`);
  };
  const waitForMessage = async (
    predicate: (message: Record<string, unknown>) => boolean,
    failureMessage: string,
  ): Promise<Record<string, unknown>> => {
    await waitFor(() => messages.some(predicate), {
      timeoutMs: 10_000,
      failureMessage: `${failureMessage}\nMessages: ${JSON.stringify(messages)}`,
    });
    return messages.find(predicate) as Record<string, unknown>;
  };
  const rootUris = [pathToFileURL(firstRoot).toString(), pathToFileURL(secondRoot).toString()];

  try {
    send({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-03-26",
        capabilities: { roots: { listChanged: true } },
        clientInfo: { name: "roots-context-it", version: "1.0.0" },
      },
    });
    await waitForMessage((message) => message.id === 1, "initialize response missing");
    send({ jsonrpc: "2.0", method: "notifications/initialized", params: {} });

    const initialRootsRequest = await waitForMessage(
      (message) => message.method === "roots/list",
      "initial roots/list request missing",
    );
    send({
      jsonrpc: "2.0",
      id: initialRootsRequest.id,
      result: { roots: rootUris.map((uri) => ({ uri })) },
    });

    send({ jsonrpc: "2.0", id: 2, method: "tools/list", params: {} });
    const toolsList = await waitForMessage(
      (message) => message.id === 2,
      "tools/list response missing",
    );
    const toolsResult = toolsList.result as { tools?: unknown[] };
    assert.equal(Array.isArray(toolsResult.tools), true);

    send({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: { name: "probe", arguments: { action: "status", input: {} } },
    });
    const ambiguousCall = await waitForMessage(
      (message) => message.id === 3,
      "ambiguous action response missing",
    );
    const ambiguousText =
      (ambiguousCall.result as { content?: Array<{ text?: string }> }).content?.[0]?.text ?? "";
    assert.equal(ambiguousText.includes("workspace_context_ambiguous"), true);

    send({ jsonrpc: "2.0", method: "notifications/roots/list_changed", params: {} });
    const changedRootsRequest = await waitForMessage(
      (message) => message.method === "roots/list" && message.id !== initialRootsRequest.id,
      "Roots-change roots/list request missing",
    );
    send({ jsonrpc: "2.0", id: changedRootsRequest.id, result: { roots: [] } });

    send({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: { name: "probe", arguments: { action: "status", input: {} } },
    });
    const missingCall = await waitForMessage(
      (message) => message.id === 4,
      "post-removal action response missing",
    );
    const missingText =
      (missingCall.result as { content?: Array<{ text?: string }> }).content?.[0]?.text ?? "";
    assert.equal(missingText.includes("workspace_context_missing"), true);
  } finally {
    child.stdin?.end();
    await forceStop(child);
    await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});
