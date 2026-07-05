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
    assert.deepEqual(contextResolved.redaction, {
      resolvedSecretKeyCount: 2,
      resolvedSecretKeysOmitted: ["apiBaseUrl", "tenantId"],
    });
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

test("mcp IT: execution_orchestration passes transport-failure contract-matched assertions and persists clean step artifacts", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-sad-path-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "petclinic-regression";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "missing-resource-regression";
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
    if (req.url === "/api/missing-resource") {
      res.statusCode = 404;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ reason: "missing resource" }));
      return;
    }
    res.statusCode = 500;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ reason: "unexpected route" }));
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
            executionProfile: "sad-path-run",
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
        id: "missing_resource",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            pathTemplate: "/api/missing-resource",
          },
        },
        expect: [
          { id: "status_not_found", actualPath: "response.statusCode", operator: "field_equals", expected: 404 },
          { id: "body_reason_missing", actualPath: "response.body", operator: "contains", expected: "missing" },
        ],
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
        executionProfile: "sad-path-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.equal(planRuns[0]?.runStatus, "pass");
    assert.equal(fssync.existsSync(runRootAbs), true);

    const runIds = await fs.readdir(runRootAbs);
    assert.equal(runIds.length, 1);
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runIds[0]!, "execution.result.json"), "utf8"),
    ) as { steps?: Array<Record<string, unknown>>; status?: string };
    assert.equal(executionResult.status, "pass");
    assert.equal(Array.isArray(executionResult.steps), true);
    assert.equal(executionResult.steps?.[0]?.status, "pass");
    assert.equal(executionResult.steps?.[0]?.statusCode, 404);
    assert.equal(executionResult.steps?.[0]?.reasonCode, undefined);
    assert.equal(executionResult.steps?.[0]?.reasonMeta, undefined);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration keeps optional-only transport-failure step failures out of overall run status", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-sad-path-optional-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "petclinic-regression";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "missing-resource-optional-regression";
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
    if (req.url === "/api/missing-resource") {
      res.statusCode = 500;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ reason: "unexpected route" }));
      return;
    }
    res.statusCode = 404;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ reason: "missing route" }));
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
            executionProfile: "sad-path-optional-run",
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
        id: "missing_resource_optional",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            pathTemplate: "/api/missing-resource",
          },
        },
        expect: [
          {
            id: "status_not_found_optional",
            actualPath: "response.statusCode",
            operator: "field_equals",
            expected: 404,
            required: false,
          },
          {
            id: "body_reason_missing_optional",
            actualPath: "response.body",
            operator: "contains",
            expected: "missing",
            required: false,
          },
        ],
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
        executionProfile: "sad-path-optional-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.equal(planRuns[0]?.runStatus, "pass");
    assert.equal(fssync.existsSync(runRootAbs), true);

    const runIds = await fs.readdir(runRootAbs);
    assert.equal(runIds.length, 1);
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runIds[0]!, "execution.result.json"), "utf8"),
    ) as { steps?: Array<Record<string, unknown>>; status?: string };
    assert.equal(executionResult.status, "pass");
    assert.equal(Array.isArray(executionResult.steps), true);
    assert.equal(executionResult.steps?.[0]?.status, "fail_assertion");
    assert.equal(executionResult.steps?.[0]?.statusCode, 500);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration supports array index notation in response body assertions", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-array-path-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-array-path";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "array-assertion-regression";
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
    if (req.url === "/api/names") {
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ names: [{ locale: "*", value: "Test" }] }));
      return;
    }
    res.statusCode = 404;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ reason: "missing route" }));
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
            executionProfile: "array-path-run",
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
        id: "array_lookup",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            pathTemplate: "/api/names",
          },
        },
        expect: [
          { id: "first-name-value", actualPath: "response.bodyJson.names[0].value", operator: "field_equals", expected: "Test" },
        ],
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
        executionProfile: "array-path-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.equal(planRuns[0]?.runStatus, "pass");
    assert.equal(fssync.existsSync(runRootAbs), true);

    const runIds = await fs.readdir(runRootAbs);
    assert.equal(runIds.length, 1);
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runIds[0]!, "execution.result.json"), "utf8"),
    ) as { steps?: Array<Record<string, unknown>>; status?: string };
    assert.equal(executionResult.status, "pass");
    assert.equal(executionResult.steps?.[0]?.status, "pass");
    const assertions = Array.isArray(executionResult.steps?.[0]?.assertions)
      ? (executionResult.steps?.[0]?.assertions as Array<Record<string, unknown>>)
      : [];
    assert.equal(assertions[0]?.status, "pass");
    assert.equal(assertions[0]?.actual, "Test");
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration surfaces blockedReasonMeta for http_payload_invalid when apiBaseUrl is missing for pathTemplate synthesis", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-http-payload-meta-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-http-payload-meta";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "tenant-tags-regression";

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
            executionProfile: "http-payload-meta-run",
            executionPolicy: "stop_on_fail",
            plans: [{ order: 1, planName, onFail: "inherit" }],
          },
        ],
      },
    ],
  });

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
    prerequisites: [],
    steps: [
      {
        order: 1,
        id: "tenant_tags",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            pathTemplate: "/api/v2/tenant/tags",
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
        executionProfile: "http-payload-meta-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.equal(planRuns[0]?.runStatus, "blocked");
    assert.equal(planRuns[0]?.blockedReasonCode, "http_payload_invalid");
    assert.equal((planRuns[0]?.blockedReasonMeta as Record<string, unknown> | undefined)?.cause, "api_base_url_missing_for_path_template");
    assert.deepEqual((planRuns[0]?.blockedReasonMeta as Record<string, unknown> | undefined)?.missingFields, ["url"]);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration composes baseUrl prerequisite with transport path when url is absent", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-base-url-path-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "petclinic-regression";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "base-url-path-regression";
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
    res.statusCode = req.url === "/resource/abc-123" ? 200 : 404;
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
            executionProfile: "base-url-path-run",
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
        key: "baseUrl",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: `http://127.0.0.1:${appPort}`,
      },
      {
        key: "id",
        required: true,
        secret: false,
        provisioning: "user_input",
        default: "abc-123",
      },
    ],
    steps: [
      {
        order: 1,
        id: "read_resource",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            path: "/resource/${id}",
          },
        },
        expect: [{ id: "http_ok", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
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
        executionProfile: "base-url-path-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "executed");
    assert.equal(planRuns[0]?.runStatus, "pass");
    assert.equal(fssync.existsSync(runRootAbs), true);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration resolves correlation when json_path source uses response.body.id", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-correlation-json-path-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-correlation";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "correlation-json-path";
  const planRootAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression", planName);
  const runRootAbs = path.join(planRootAbs, "runs");
  const appServer = http.createServer((_req, res) => {
    res.statusCode = 200;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ id: "evt-123", ok: true }));
  });
  const appPort = await listen(appServer);

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: { "event-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
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
            executionProfile: "correlation-json-path-run",
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
    targets: [
      {
        type: "class_method",
        selectors: { fqcn: "x.A", method: "m" },
        runtimeVerification: { strictProbeKey: "x.A#m:10", probeId: "event-service" },
      },
    ],
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
        id: "create_event",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/events",
            body: { kind: "created" },
          },
        },
        expect: [{ id: "http_ok", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
      },
    ],
    correlation: {
      enabled: true,
      key: {
        type: "messageId",
        source: {
          type: "json_path",
          path: "response.body.id",
        },
      },
      window: { maxWindowMs: 60000 },
      probeIds: ["event-service"],
      matchPolicy: {
        requireExactKeyMatch: true,
        requireWindowMatch: true,
        ambiguityStrategy: "fail_closed",
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

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "correlation-json-path-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");

    const runDirs = await fs.readdir(runRootAbs);
    assert.equal(runDirs.length, 1);
    const runDir = runDirs[0];
    if (!runDir) throw new Error("expected one run directory");

    const evidence = JSON.parse(await fs.readFile(path.join(runRootAbs, runDir, "evidence.json"), "utf8")) as Record<string, unknown>;
    const correlation = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runDir, "correlation", "correlation.json"), "utf8"),
    ) as Record<string, unknown>;

    assert.equal(Array.isArray(evidence.correlationEvents), true);
    assert.equal((evidence.correlationPolicy as Record<string, unknown>).keySourceType, "json_path");
    assert.equal((evidence.correlationPolicy as Record<string, unknown>).keySourcePath, "response.body.id");
    assert.equal((evidence.correlationPolicy as Record<string, unknown>).keyValue, "evt-123");
    assert.equal(typeof (evidence.correlationPolicy as Record<string, unknown>).keyExtractionReasonCode, "undefined");
    const correlationEvents = evidence.correlationEvents as Array<Record<string, unknown>>;
    assert.equal(correlationEvents.length, 1);
    assert.equal(correlationEvents[0]?.probeId, "event-service");
    assert.equal(correlationEvents[0]?.keyValue, "evt-123");
    assert.equal(correlation.status, "ok");
    assert.equal(correlation.reasonCode, "ok");
    assert.equal(correlation.keyValue, "evt-123");
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration records unresolved optional extract without blocking the run", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-extract-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-extract";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "extract-optional-miss";
  const planRootAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression", planName);
  const runRootAbs = path.join(planRootAbs, "runs");
  const appServer = http.createServer((_req, res) => {
    res.statusCode = 200;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ ok: true }));
  });
  const appPort = await listen(appServer);

  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: { "event-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] } },
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
            executionProfile: "extract-optional-run",
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
    targets: [
      {
        type: "class_method",
        selectors: { fqcn: "x.A", method: "m" },
      },
    ],
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
        id: "create_event",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/events",
            body: { kind: "created" },
          },
        },
        extract: [{ from: "response.body.id", as: "triggeredEventId" }],
        expect: [{ id: "http_ok", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
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
        executionProfile: "extract-optional-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");

    const runDirs = await fs.readdir(runRootAbs);
    assert.equal(runDirs.length, 1);
    const runDir = runDirs[0];
    if (!runDir) throw new Error("expected one run directory");

    const context = JSON.parse(await fs.readFile(path.join(runRootAbs, runDir, "context.resolved.json"), "utf8")) as Record<string, unknown>;
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runDir, "execution.result.json"), "utf8"),
    ) as Record<string, unknown>;

    assert.equal(typeof context.triggeredEventId, "undefined");
    const steps = executionResult.steps as Array<Record<string, unknown>>;
    assert.equal(Array.isArray(steps), true);
    assert.equal(steps[0]?.status, "pass");
    assert.deepEqual(steps[0]?.extract, [
      {
        from: "response.body.id",
        as: "triggeredEventId",
        required: false,
        status: "unresolved",
        reasonCode: "extract_path_missing",
      },
    ]);
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

test("mcp IT: execution_orchestration blocks non-canonical env-style prerequisite keys and placeholders", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-env-prereq-alias-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-env-prereq-alias";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "secure-env-alias-regression";
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    planName,
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
        variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
        executionProfiles: [
          {
            executionProfile: "env-prereq-alias-run",
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
        key: "AUTH_BEARER_TOKEN",
        required: true,
        secret: true,
        provisioning: "user_input",
      },
    ],
    steps: [
      {
        order: 1,
        id: "secure_call",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            url: "http://127.0.0.1:8080/secure",
            headers: { Authorization: "Bearer {{AUTH_BEARER_TOKEN}}" },
          },
        },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: {
        MCP_PROBE_CONFIG_FILE: probeConfigAbs,
        AUTH_BEARER_TOKEN: "runtime-token-from-env",
      },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "env-prereq-alias-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.status, "blocked");
    assert.equal(planRuns[0]?.blockedReasonCode, "plan_context_key_noncanonical");
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

test("mcp IT: execution_orchestration executes watcher polling after trigger success and persists watcher Artifact state", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-watcher-success-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-watchers";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "watcher-success";
  const planRootAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression", planName);
  const runRootAbs = path.join(planRootAbs, "runs");
  let stateChecks = 0;

  const appServer = http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/events") {
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ id: "evt-123" }));
      return;
    }
    if (req.method === "GET" && req.url === "/index/evt-123") {
      stateChecks += 1;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ state: stateChecks >= 3 ? "ready" : "pending" }));
      return;
    }
    res.statusCode = 404;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ reason: "missing" }));
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
        defaults: { requestTimeoutMs: 120, retryMax: 3 },
        executionProfiles: [
          {
            executionProfile: "watcher-success-run",
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
        id: "trigger_event",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/events",
          },
        },
        extract: [{ from: "response.bodyJson.id", as: "eventId", required: true }],
        expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
      },
    ],
    watchers: [
      {
        id: "indexed_ready",
        dependency: { stepOrder: 1 },
        provider: {
          type: "http",
          transport: {
            request: {
              method: "GET",
              url: `http://127.0.0.1:${appPort}/index/\${eventId}`,
            },
          },
        },
        expect: [{ id: "ready", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
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
        executionProfile: "watcher-success-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");
    assert.equal(stateChecks, 3);
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.runStatus, "pass");

    const runDirs = await fs.readdir(runRootAbs);
    assert.equal(runDirs.length, 1);
    const runDir = runDirs[0];
    if (!runDir) throw new Error("expected one watcher run directory");
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runDir, "execution.result.json"), "utf8"),
    ) as Record<string, unknown>;
    const evidence = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runDir, "evidence.json"), "utf8"),
    ) as Record<string, unknown>;
    const watchers = executionResult.watchers as Array<Record<string, unknown>>;
    assert.equal(executionResult.triggerStatus, "pass");
    assert.equal(executionResult.watcherStatus, "pass");
    assert.equal(Array.isArray(watchers), true);
    assert.equal(watchers[0]?.status, "pass");
    assert.equal(watchers[0]?.attemptCount, 3);
    assert.equal(Array.isArray(evidence.watcherExecutions), true);
    assert.equal((evidence.watcherExecutions as Array<Record<string, unknown>>)[0]?.status, "pass");
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration fails closed when watcher response normalization fails", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-watcher-normalization-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-watchers";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "watcher-normalization-failure";
  const planRootAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression", planName);
  const runRootAbs = path.join(planRootAbs, "runs");

  let watcherCalls = 0;
  const appServer = http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/events") {
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ eventId: "evt-401" }));
      return;
    }
    if (req.method === "GET" && req.url === "/index/evt-401") {
      watcherCalls += 1;
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end("not-json");
      return;
    }
    res.statusCode = 404;
    res.end();
  });

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    const appPort = await listen(appServer);
    await fs.mkdir(runRootAbs, { recursive: true });
    await writeJson(probeConfigAbs, {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            "gateway-service": { baseUrl: "http://127.0.0.1:9196", include: ["com.example.**"], exclude: [] },
          },
        },
      },
      workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
    });

    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: projectRootAbs,
          defaults: { requestTimeoutMs: 100, retryMax: 2 },
          executionProfiles: [
            {
              executionProfile: "watcher-normalization-run",
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
        { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: `http://127.0.0.1:${appPort}` },
      ],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.eventId", as: "eventId", required: true }],
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "indexed_ready",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: { http: { method: "GET", pathTemplate: "/index/${eventId}" } },
            config: {
              response: {
                bodyFormat: "json",
              },
            },
          },
          expect: [{ id: "ready", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "watcher-normalization-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.blockedReasonCode, "watcher_response_normalization_failed");
    assert.equal(watcherCalls, 1);
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

test("mcp IT: execution_orchestration fails closed when watcher target stays unreachable", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-execution-orchestration-watcher-unreachable-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "test-project-watchers";
  const projectRootAbs = workspaceRootAbs;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planName = "watcher-unreachable";
  const planRootAbs = path.join(workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression", planName);
  const runRootAbs = path.join(planRootAbs, "runs");
  const appServer = http.createServer((req, res) => {
    if (req.method === "POST" && req.url === "/events") {
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    res.statusCode = 404;
    res.end("not found");
  });
  const appPort = await listen(appServer);

  const unreachableServer = http.createServer((_req, res) => {
    res.statusCode = 200;
    res.end("unused");
  });
  const unreachablePort = await listen(unreachableServer);
  await new Promise<void>((resolve, reject) => unreachableServer.close((error) => error ? reject(error) : resolve()));

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
        defaults: { requestTimeoutMs: 100, retryMax: 2 },
        executionProfiles: [
          {
            executionProfile: "watcher-unreachable-run",
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
        id: "trigger_event",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "POST",
            pathTemplate: "/events",
          },
        },
        expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
      },
    ],
    watchers: [
      {
        id: "indexed_ready",
        dependency: { stepOrder: 1 },
        provider: {
          type: "http",
          transport: {
            request: {
              method: "GET",
              url: `http://127.0.0.1:${unreachablePort}/index/evt-123`,
            },
          },
        },
        waitPolicy: { timeoutMs: 80, retryMax: 2 },
        expect: [{ id: "ready", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
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
        executionProfile: "watcher-unreachable-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "blocked");
    const planRuns = Array.isArray(out.structuredContent?.planRuns)
      ? (out.structuredContent?.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(planRuns.length, 1);
    assert.equal(planRuns[0]?.runStatus, "blocked");

    const runDirs = await fs.readdir(runRootAbs);
    assert.equal(runDirs.length, 1);
    const runDir = runDirs[0];
    if (!runDir) throw new Error("expected one watcher run directory");
    const executionResult = JSON.parse(
      await fs.readFile(path.join(runRootAbs, runDir, "execution.result.json"), "utf8"),
    ) as Record<string, unknown>;
    const watchers = executionResult.watchers as Array<Record<string, unknown>>;
    assert.equal(executionResult.triggerStatus, "pass");
    assert.equal(executionResult.watcherStatus, "blocked");
    assert.equal(watchers[0]?.status, "blocked_runtime");
    assert.equal(watchers[0]?.reasonCode, "watcher_target_unreachable");
  } finally {
    appServer.close();
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
