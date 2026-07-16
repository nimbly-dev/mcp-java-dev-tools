import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as http from "node:http";
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
  return (await mcp.client.callTool({ name, arguments: args })) as ToolResult;
}

async function writeJson(filePath: string, payload: Record<string, unknown>): Promise<void> {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

async function listen(server: http.Server): Promise<number> {
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("server address unavailable");
  return address.port;
}

async function readLatestEvidence(runRootAbs: string): Promise<Record<string, unknown>> {
  const runDirs = await fs.readdir(runRootAbs);
  assert.equal(runDirs.length, 1);
  const runDir = runDirs[0];
  if (!runDir) throw new Error("expected one run directory");
  return JSON.parse(
    await fs.readFile(path.join(runRootAbs, runDir, "evidence.json"), "utf8"),
  ) as Record<string, unknown>;
}

test("mcp IT: execution_orchestration persists capture_field and producer-selected json_path correlation", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-correlation-persistence-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-correlation-persistence";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const capturePlanName = "capture-field";
  const jsonPathPlanName = "producer-selected-json-path";
  const capturePlanRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    capturePlanName,
  );
  const jsonPathPlanRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    jsonPathPlanName,
  );
  let postCount = 0;
  const appServer = http.createServer((req, res) => {
    res.setHeader("content-type", "application/json");
    if (req.method === "POST" && req.url === "/imports") {
      postCount += 1;
      res.statusCode = 201;
      res.end(JSON.stringify({ id: "job-123" }));
      return;
    }
    if (req.method === "GET" && req.url === "/entities/job-123") {
      res.statusCode = 200;
      res.end(JSON.stringify({ id: "entity-999" }));
      return;
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ reason: "missing" }));
  });
  const appPort = await listen(appServer);

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "fixture-probe": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.**"],
            exclude: [],
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        defaults: {
          requestTimeoutMs: 10000,
          retryMax: 1,
          orchestrator: {
            resumePollMax: 10,
            resumePollIntervalMs: 1000,
            resumePollTimeoutMs: 60000,
          },
        },
        executionProfiles: [
          {
            executionProfile: "capture-field-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: capturePlanName, onFail: "inherit" }],
          },
          {
            executionProfile: "json-path-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName: jsonPathPlanName, onFail: "inherit" }],
          },
        ],
      },
    ],
  });

  const target = {
    type: "class_method",
    selectors: { fqcn: "example.ImportController", method: "create" },
    runtimeVerification: { probeId: "fixture-probe" },
  };
  const correlation = (source: Record<string, unknown>) => ({
    enabled: true,
    key: { type: "requestId", source },
    window: { maxWindowMs: 60000 },
    probeIds: ["fixture-probe"],
    matchPolicy: {
      requireExactKeyMatch: true,
      requireWindowMatch: true,
      ambiguityStrategy: "fail_closed",
    },
  });

  await writeJson(path.join(capturePlanRootAbs, "metadata.json"), {
    execution: { intent: "regression" },
  });
  await writeJson(path.join(capturePlanRootAbs, "contract.json"), {
    targets: [target],
    prerequisites: [
      {
        key: "apiBaseUrl",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: `http://127.0.0.1:${appPort}`,
      },
    ],
    steps: [
      {
        order: 1,
        id: "create_import",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "POST", pathTemplate: "/imports" } },
        extract: [
          {
            from: "response.bodyJson.id",
            as: "validImportJobId",
            required: true,
            scope: "suite",
            secret: false,
          },
        ],
        expect: [
          {
            id: "created",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 201,
          },
        ],
      },
    ],
    correlation: correlation({ type: "capture_field", path: "validImportJobId" }),
  });

  await writeJson(path.join(jsonPathPlanRootAbs, "metadata.json"), {
    execution: { intent: "regression" },
  });
  await writeJson(path.join(jsonPathPlanRootAbs, "contract.json"), {
    targets: [target],
    prerequisites: [
      {
        key: "apiBaseUrl",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: `http://127.0.0.1:${appPort}`,
      },
    ],
    steps: [
      {
        order: 1,
        id: "create_import",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "POST", pathTemplate: "/imports" } },
        extract: [
          {
            from: "response.bodyJson.id",
            as: "validImportJobId",
            required: true,
            scope: "suite",
            secret: false,
          },
        ],
        expect: [
          {
            id: "created",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 201,
          },
        ],
      },
      {
        order: 2,
        id: "read_entity",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", pathTemplate: "/entities/${validImportJobId}" } },
        expect: [
          {
            id: "found",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 200,
          },
        ],
      },
    ],
    correlation: {
      ...correlation({ type: "json_path", path: "response.body.id" }),
      key: {
        type: "requestId",
        source: { type: "json_path", path: "response.body.id", stepOrder: 1 },
      },
    },
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    for (const executionProfile of ["capture-field-run", "json-path-run"]) {
      const out = await callTool(mcp, "execution_orchestration", {
        action: "execute",
        input: { projectName, executionProfile },
      });
      assert.equal(
        out.structuredContent?.resultType,
        "execution_orchestration",
        JSON.stringify(out.structuredContent),
      );
      assert.equal(out.structuredContent?.status, "pass");
    }

    assert.equal(postCount, 2);
    const captureEvidence = await readLatestEvidence(path.join(capturePlanRootAbs, "runs"));
    const jsonPathEvidence = await readLatestEvidence(path.join(jsonPathPlanRootAbs, "runs"));
    const correlationFailures: string[] = [];
    for (const [label, evidence] of [
      ["capture_field", captureEvidence],
      ["json_path", jsonPathEvidence],
    ] as const) {
      const policy = evidence.correlationPolicy as Record<string, unknown>;
      if (policy.keyValue !== "job-123") {
        correlationFailures.push(`${label}: policy.keyValue=${String(policy.keyValue)}`);
      }
      if (label === "json_path" && policy.keySourceStepOrder !== 1) {
        correlationFailures.push(
          `${label}: keySourceStepOrder=${String(policy.keySourceStepOrder)}`,
        );
      }
      const events = Array.isArray(evidence.correlationEvents)
        ? (evidence.correlationEvents as Array<Record<string, unknown>>)
        : [];
      if (events.length === 0) correlationFailures.push(`${label}: no correlation events`);
      if (!events.every((event) => event.keyValue === "job-123")) {
        correlationFailures.push(
          `${label}: event.keyValues=${JSON.stringify(events.map((event) => event.keyValue))}`,
        );
      }
    }
    assert.deepEqual(correlationFailures, [], correlationFailures.join("; "));
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});
