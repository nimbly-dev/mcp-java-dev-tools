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
  return (await mcp.client.callTool({
    name,
    arguments: args,
  })) as ToolResult;
}

async function writeJson(filePath: string, payload: Record<string, unknown>): Promise<void> {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

async function listen(server: http.Server): Promise<number> {
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error("server address unavailable");
  }
  return address.port;
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

test("mcp IT: execution_orchestration accepts compatible {{key}} transport placeholder syntax and reaches plan execution", async () => {
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
  const appServer = http.createServer((req, res) => {
    res.statusCode = req.headers.authorization === "Bearer seeded-token" ? 200 : 401;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ ok: res.statusCode === 200 }));
  });
  const appPort = await listen(appServer);

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
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
        default: `http://127.0.0.1:${appPort}`,
      },
      {
        key: "auth.bearer",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: "seeded-token",
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
            headers: {
              Authorization: "Bearer {{ auth.bearer }}",
            },
          },
        },
        expect: [{ id: "http_ok", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
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
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.notEqual(planRuns[0]?.blockedReasonCode, "transport_placeholder_syntax_invalid");
    assert.equal(typeof planRuns[0]?.runId, "string");
    assert.equal(fssync.existsSync(runRootAbs), true);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration resolves project contextBindings from env-backed workspace mappings", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-context-bindings-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-context-bindings";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "tenant-tags-regression";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
  );
  const runRootAbs = path.join(planRootAbs, "runs");
  const appServer = http.createServer((req, res) => {
    res.statusCode = req.url === "/api/v2/tenant/tenant-social-001/tags" ? 200 : 404;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ ok: res.statusCode === 200 }));
  });
  const appPort = await listen(appServer);

  await fs.mkdir(path.join(workspaceRootAbs, ".mcpjvm", projectName), { recursive: true });
  await fs.writeFile(
    path.join(workspaceRootAbs, ".mcpjvm", projectName, ".env"),
    `BASE_URL=http://127.0.0.1:${appPort}\nTENANT_ID=tenant-social-001\n`,
    "utf8",
  );
  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: { "gateway-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        envFile: `.mcpjvm/${projectName}/.env`,
        variables: {
          contextBindings: {
            apiBaseUrl: "BASE_URL",
            tenantId: "TENANT_ID",
          },
        },
        executionProfiles: [
          {
            executionProfile: "context-binding-run",
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
      { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input" },
      { key: "tenantId", required: true, secret: false, provisioning: "user_input" },
    ],
    steps: [
      {
        order: 1,
        id: "tenant_tags",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            pathTemplate: "/api/v2/tenant/${tenantId}/tags",
          },
        },
        expect: [{ id: "http_ok", actualPath: "response.statusCode", operator: "numeric_gte", expected: 200 }],
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
        executionProfile: "context-binding-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.equal(fssync.existsSync(runRootAbs), true);
    const runIds = await fs.readdir(runRootAbs);
    assert.equal(runIds.length, 1);
    const contextResolved = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runIds[0]!, "context.resolved.json"), "utf8"),
    ) as Record<string, unknown>;
    assert.equal(contextResolved.apiBaseUrl, undefined);
    assert.equal(contextResolved.tenantId, undefined);
  } finally {
    appServer.close();
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

test("mcp IT: execution_orchestration supports resumable in_progress slicing by suiteRunId", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-resume-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-performance";
  const projectRootAbs = workspaceRootAbs;
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

  await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
    workspaces: [
      {
        projectRoot: projectRootAbs,
        executionProfiles: [
          {
            executionProfile: "resumable-suite",
            executionPolicy: "continue_on_fail",
            plans: [
              { order: 1, planName: "plan-a", onFail: "inherit" },
              { order: 2, planName: "plan-b", onFail: "inherit" },
            ],
          },
        ],
      },
    ],
  });

  for (const planName of ["plan-a", "plan-b"]) {
    await writeJson(
      path.join(workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression", planName, "metadata.json"),
      { execution: { intent: "regression" } },
    );
    await writeJson(
      path.join(workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression", planName, "contract.json"),
      {
        targets: [{ type: "class_method", selectors: { fqcn: "x.A", method: "m" } }],
        prerequisites: [],
        steps: [],
      },
    );
  }

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const first = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "resumable-suite",
        maxPlansPerCall: 1,
      },
    });

    assert.equal(first.structuredContent?.resultType, "execution_orchestration");
    assert.equal(first.structuredContent?.status, "in_progress");
    assert.equal(first.structuredContent?.nextPlanOrder, 2);
    const suiteRunId = String(first.structuredContent?.suiteRunId ?? "");
    assert.equal(suiteRunId.length > 0, true);

    const second = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "resumable-suite",
        suiteRunId,
        maxPlansPerCall: 1,
      },
    });

    assert.equal(second.structuredContent?.resultType, "execution_orchestration");
    assert.equal(second.structuredContent?.status, "partial_fail");
    assert.equal(second.structuredContent?.suiteRunId, suiteRunId);
    const planRuns = Array.isArray(second.structuredContent?.planRuns)
      ? (second.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 2);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration executes performance suite profiles through the same MCP Tool", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-performance-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-performance";
  const projectRootAbs = workspaceRootAbs;
  const lineKey = "com.example.catalog.CatalogService#search:42";
  let lineHitCount = 0;

  const appServer = http.createServer((_req, res) => {
    lineHitCount += 1;
    res.statusCode = 200;
    res.setHeader("content-type", "application/json");
    res.end("{\"ok\":true}");
  });
  const probeServer = http.createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1");
    if (req.method === "POST" && url.pathname === "/__probe/reset") {
      lineHitCount = 0;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true, key: lineKey, lineResolvable: true, lineValidation: "resolvable" }));
      return;
    }
    if (req.method === "GET" && url.pathname === "/__probe/status") {
      const key = url.searchParams.get("key") ?? lineKey;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(
        JSON.stringify({
          key,
          hitCount: lineHitCount,
          lastHitEpoch: lineHitCount > 0 ? Date.now() : 0,
          mode: "observe",
          lineResolvable: true,
          lineValidation: "resolvable",
        }),
      );
      return;
    }
    res.statusCode = 404;
    res.end("not found");
  });

  const appPort = await listen(appServer);
  const probePort = await listen(probeServer);
  const probeBaseUrl = `http://127.0.0.1:${probePort}`;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: { "catalog-service": { baseUrl: probeBaseUrl, include: ["com.example.**"], exclude: [] } },
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
            suiteType: "performance",
            executionPolicy: "stop_on_fail",
            runtimeConfig: {
              requestTimeoutMs: 250,
            },
            plans: [{ order: 1, planName: "catalog-search-perf", onFail: "inherit" }],
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
      "performance",
      "catalog-search-perf",
      "metadata.json",
    ),
    {
      specVersion: "0.1.0",
      suiteType: "performance",
      execution: { intent: "performance" },
    },
  );
  await writeJson(
    path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "performance",
      "catalog-search-perf",
      "contract.json",
    ),
    {
      entrypoints: [
        {
          transport: {
            protocol: "http",
            baseUrl: `http://127.0.0.1:${appPort}`,
            wrappedOnly: true,
          },
          request: {
            method: "GET",
            path: "/search",
          },
        },
      ],
      observationTargets: {
        requiredLineHits: [lineKey],
      },
      loadModel: {
        mode: "concurrency",
        concurrency: 1,
        rampUpSeconds: 0,
        durationSeconds: 1,
      },
      successCriteria: {
        maxErrorRatePct: 0,
        minThroughputPerSec: 0.5,
        p95LatencyMs: 500,
      },
    },
  );

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl,
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "test-performance-stress-suite",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    assert.notEqual(out.structuredContent?.reasonCode, "runtime_suite_invalid");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.equal(planRuns[0]?.runStatus, "pass");
    assert.equal(lineHitCount > 0, true);
  } finally {
    appServer.close();
    probeServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
