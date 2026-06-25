const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const test = require("node:test");

const { executeRegressionRuntimeSuite } = require("@tools-regression-execution-plan-spec/regression_runtime_suite_executor.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

function writePlan(root: string, projectName: string, planName: string, routePath: string): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
    prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
    steps: [
      {
        order: 1,
        id: "step_1",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", pathTemplate: routePath } },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
  });
}

function writeCorrelatedPlan(
  root: string,
  projectName: string,
  planName: string,
  routePath: string,
  args: {
    probeId: string;
    correlationSessionId: string;
    keyValue?: string;
    keySourcePath?: string;
    expectedFlow?: string[];
  },
): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [
      {
        type: "class_method",
        selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" },
        runtimeVerification: { strictProbeKey: "org.example.Controller#call:10", probeId: args.probeId },
      },
    ],
    prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
    steps: [
      {
        order: 1,
        id: "step_1",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", pathTemplate: routePath } },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
    correlation: {
      enabled: true,
      correlationSessionId: args.correlationSessionId,
      key: args.keyValue
        ? { type: "traceId", value: args.keyValue }
        : { type: "traceId", source: { type: "header", path: args.keySourcePath ?? "x-trace-id" } },
      window: { maxWindowMs: 5000 },
      probeIds: [args.probeId],
      ...(args.expectedFlow ? { expectedFlow: args.expectedFlow } : {}),
      matchPolicy: {
        requireExactKeyMatch: true,
        requireWindowMatch: true,
        ambiguityStrategy: "fail_closed",
      },
    },
  });
}

function writeAuthPlan(root: string, projectName: string, planName: string, routePath: string): void {
  const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
  writeJson(path.join(planRoot, "metadata.json"), {
    specVersion: "1.0.0",
    execution: {
      intent: "regression",
      probeVerification: false,
      pinStrictProbeKey: false,
      discoveryPolicy: "allow_discoverable_prerequisites",
    },
  });
  writeJson(path.join(planRoot, "contract.json"), {
    targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
    prerequisites: [
      { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" },
      { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
    ],
    steps: [
      {
        order: 1,
        id: "step_1",
        targetRef: 0,
        protocol: "http",
        transport: {
          http: {
            method: "GET",
            pathTemplate: routePath,
            headers: { Authorization: "Bearer ${auth.bearer}" },
          },
        },
        expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
      },
    ],
  });
}

test("executeRegressionRuntimeSuite enforces stop_on_fail and skips remaining plans", async () => {
  const root = createTestTempDir("runtime-suite-stop");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-smoke",
              executionPolicy: "stop_on_fail",
              plans: [
                { order: 1, planName: "plan-pass" },
                { order: 2, planName: "plan-fail" },
                { order: 3, planName: "plan-skipped" },
              ],
            },
          ],
        },
      ],
    });
    writePlan(root, projectName, "plan-pass", "/pass");
    writePlan(root, projectName, "plan-fail", "/fail");
    writePlan(root, projectName, "plan-skipped", "/skipped");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-smoke",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        const url = String(req.url ?? "");
        if (url.includes("/fail")) {
          return { structuredContent: { status: "fail_http", statusCode: 500, durationMs: 9, bodyPreview: "{}" } };
        }
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "fail");
    assert.equal(out.planRuns[0].status, "executed");
    assert.equal(out.planRuns[1].status, "executed");
    assert.equal(out.planRuns[2].status, "skipped");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite continue_on_fail returns partial_fail and continues", async () => {
  const root = createTestTempDir("runtime-suite-continue");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-continue",
              executionPolicy: "continue_on_fail",
              plans: [
                { order: 1, planName: "plan-fail" },
                { order: 2, planName: "plan-pass" },
              ],
            },
          ],
        },
      ],
    });
    writePlan(root, projectName, "plan-pass", "/pass");
    writePlan(root, projectName, "plan-fail", "/fail");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-continue",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        const url = String(req.url ?? "");
        if (url.includes("/fail")) {
          return { structuredContent: { status: "fail_http", statusCode: 500, durationMs: 9, bodyPreview: "{}" } };
        }
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "partial_fail");
    assert.equal(out.planRuns[0].status, "executed");
    assert.equal(out.planRuns[1].status, "executed");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite returns in_progress and resumes from nextPlanOrder at plan boundary", async () => {
  const root = createTestTempDir("runtime-suite-resume");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-resume",
              executionPolicy: "stop_on_fail",
              plans: [
                { order: 1, planName: "plan-a" },
                { order: 2, planName: "plan-b" },
              ],
            },
          ],
        },
      ],
    });
    writePlan(root, projectName, "plan-a", "/a");
    writePlan(root, projectName, "plan-b", "/b");

    const first = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-resume",
      maxPlansPerCall: 1,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(first.status, "in_progress");
    assert.equal(first.planRuns.length, 1);
    assert.equal(first.planRuns[0].planName, "plan-a");
    assert.equal(first.nextPlanOrder, 2);
    assert.equal(typeof first.suiteRunId, "string");

    const second = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-resume",
      suiteRunId: first.suiteRunId,
      startPlanOrder: first.nextPlanOrder,
      priorPlanRuns: first.planRuns,
      maxPlansPerCall: 1,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(second.status, "pass");
    assert.equal(second.planRuns.length, 2);
    assert.equal(second.planRuns[0].planName, "plan-a");
    assert.equal(second.planRuns[1].planName, "plan-b");
    assert.equal(second.suiteRunId, first.suiteRunId);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite continue_on_fail blocks whole suite on shared env/auth non-viability", async () => {
  const root = createTestTempDir("runtime-suite-continue-suite-level-block");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-continue-auth-missing",
              executionPolicy: "continue_on_fail",
              plans: [
                { order: 1, planName: "plan-auth-a" },
                { order: 2, planName: "plan-auth-b" },
              ],
            },
          ],
        },
      ],
    });
    writeAuthPlan(root, projectName, "plan-auth-a", "/auth-a");
    writeAuthPlan(root, projectName, "plan-auth-b", "/auth-b");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-continue-auth-missing",
      mcpInvoke: async () => {
        throw new Error("mcpInvoke should not be called when suite-level preflight blocks");
      },
    });

    assert.equal(out.status, "blocked");
    assert.equal(out.planRuns[0].status, "blocked");
    assert.equal(out.planRuns[0].blockedReasonCode, "env_key_missing");
    assert.equal(out.planRuns[1].status, "skipped");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite surfaces blockedReasonMeta when http payload is invalid from missing synthesized url", async () => {
  const root = createTestTempDir("runtime-suite-http-payload-diagnostics");
  try {
    const projectName = "petclinic-regression";
    const planName = "plan-http-invalid";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-http-invalid",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName }],
            },
          ],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: {
        intent: "regression",
        probeVerification: false,
        pinStrictProbeKey: false,
        discoveryPolicy: "allow_discoverable_prerequisites",
      },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "step_1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/api/v2/tenant/tags" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-http-invalid",
      mcpInvoke: async () => {
        throw new Error("mcpInvoke should not be called when http payload is invalid before network execution");
      },
    });

    assert.equal(out.status, "blocked");
    assert.equal(out.planRuns[0].status, "executed");
    assert.equal(out.planRuns[0].runStatus, "blocked");
    assert.equal(out.planRuns[0].blockedReasonCode, "http_payload_invalid");
    assert.equal(out.planRuns[0].blockedReasonMeta?.cause, "api_base_url_missing_for_path_template");
    assert.deepEqual(out.planRuns[0].blockedReasonMeta?.missingFields, ["url"]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite resolves project contextBindings into transport context and redacts env-sourced values from persisted context", async () => {
  const root = createTestTempDir("runtime-suite-context-bindings");
  try {
    const projectName = "petclinic-regression";
    const envFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(envFile), { recursive: true });
    fs.writeFileSync(envFile, "BASE_URL=http://127.0.0.1:9301\nTENANT_ID=tenant-social-001\n", "utf8");
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: {
            contextBindings: {
              apiBaseUrl: "BASE_URL",
              tenantId: "TENANT_ID",
            },
          },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-context-bindings",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "plan-bound" }],
            },
          ],
        },
      ],
    });
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "plan-bound");
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: {
        intent: "regression",
        probeVerification: false,
        pinStrictProbeKey: false,
        discoveryPolicy: "allow_discoverable_prerequisites",
      },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
      prerequisites: [
        { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input" },
        { key: "tenantId", required: true, secret: false, provisioning: "user_input" },
      ],
      steps: [
        {
          order: 1,
          id: "step_1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/api/v2/tenant/${tenantId}/tags" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-context-bindings",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        assert.equal(req.url, "http://127.0.0.1:9301/api/v2/tenant/tenant-social-001/tags");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "pass");
    const runsRoot = path.join(planRoot, "runs");
    const runIds = fs.readdirSync(runsRoot);
    assert.equal(runIds.length, 1);
    const context = JSON.parse(fs.readFileSync(path.join(runsRoot, runIds[0], "context.resolved.json"), "utf8"));
    assert.equal(context.apiBaseUrl, undefined);
    assert.equal(context.tenantId, undefined);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite does not redact explicit providedContext overrides for env-backed bindings", async () => {
  const root = createTestTempDir("runtime-suite-context-bindings-override");
  try {
    const projectName = "petclinic-regression";
    const envFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(envFile), { recursive: true });
    fs.writeFileSync(envFile, "TENANT_ID=tenant-from-env\n", "utf8");
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", "plan-bound");
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: {
            contextBindings: {
              tenantId: "TENANT_ID",
            },
          },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-context-bindings-override",
              executionPolicy: "stop_on_fail",
              plans: [
                {
                  order: 1,
                  planName: "plan-bound",
                  providedContext: { tenantId: "tenant-override" },
                },
              ],
            },
          ],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: {
        intent: "regression",
        probeVerification: false,
        pinStrictProbeKey: false,
        discoveryPolicy: "allow_discoverable_prerequisites",
      },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "call", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "tenantId", required: true, secret: false, provisioning: "user_input" }],
      steps: [
        {
          order: 1,
          id: "step_1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://127.0.0.1/tenant/${tenantId}" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-context-bindings-override",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        assert.equal(req.url, "http://127.0.0.1/tenant/tenant-override");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "pass");
    const runsRoot = path.join(planRoot, "runs");
    const runIds = fs.readdirSync(runsRoot);
    assert.equal(runIds.length, 1);
    const context = JSON.parse(fs.readFileSync(path.join(runsRoot, runIds[0], "context.resolved.json"), "utf8"));
    assert.equal(context.tenantId, "tenant-override");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite records redaction metadata when secret prerequisites were resolved", async () => {
  const root = createTestTempDir("runtime-suite-secret-redaction-meta");
  try {
    const projectName = "petclinic-regression";
    const planName = "plan-auth";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-secret-redaction-meta",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName, providedContext: { "auth.bearer": "runtime-secret-token" } }],
            },
          ],
        },
      ],
    });
    writeAuthPlan(root, projectName, planName, "/auth");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-secret-redaction-meta",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        const headers = req.headers as Record<string, unknown>;
        assert.equal(headers.Authorization, "Bearer runtime-secret-token");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "pass");
    const runsRoot = path.join(planRoot, "runs");
    const runIds = fs.readdirSync(runsRoot);
    assert.equal(runIds.length, 1);
    const context = JSON.parse(fs.readFileSync(path.join(runsRoot, runIds[0], "context.resolved.json"), "utf8"));
    assert.deepEqual(context.redaction, {
      resolvedSecretKeyCount: 1,
      resolvedSecretKeysOmitted: ["auth.bearer"],
    });
    assert.equal(context["auth.bearer"], undefined);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite applies runtimeConfig retryMax override", async () => {
  const root = createTestTempDir("runtime-suite-runtime-config");
  let attempts = 0;
  const server = http.createServer((_req: any, res: any) => {
    attempts += 1;
    if (attempts === 1) {
      res.statusCode = 503;
      res.end("unavailable");
      return;
    }
    res.statusCode = 200;
    res.end("ok");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  try {
    const addr = server.address();
    if (!addr || typeof addr === "string") throw new Error("addr missing");
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { retryMax: 1, requestTimeoutMs: 200 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-override",
              executionPolicy: "stop_on_fail",
              runtimeConfig: { retryMax: 2 },
              plans: [{ order: 1, planName: "plan-pass" }],
            },
          ],
          externalSystems: [
            {
              name: "api",
              kind: "service",
              host: "127.0.0.1",
              port: addr.port,
              healthChecks: [{ id: "ready", type: "http", url: `http://127.0.0.1:${addr.port}/health`, required: true }],
            },
          ],
        },
      ],
    });
    writePlan(root, projectName, "plan-pass", "/pass");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-override",
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "pass");
    assert.equal(attempts, 2);
  } finally {
    server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite runs shared scriptRefs and reloads env before plan execution", async () => {
  const root = createTestTempDir("runtime-suite-scriptrefs");
  try {
    const projectName = "petclinic-regression";
    const envFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(envFile), { recursive: true });
    fs.writeFileSync(envFile, "AUTH_BEARER_TOKEN=\n", "utf8");
    const scriptFile = path.join(root, "scripts", "write-token.js");
    fs.mkdirSync(path.dirname(scriptFile), { recursive: true });
    fs.writeFileSync(
      scriptFile,
      [
        "const fs = require('node:fs');",
        "const idx = process.argv.indexOf('--env-file');",
        "if (idx < 0 || !process.argv[idx + 1]) process.exit(2);",
        "fs.writeFileSync(process.argv[idx + 1], 'AUTH_BEARER_TOKEN=generated-token\\n', 'utf8');",
      ].join("\n"),
      "utf8",
    );
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          scripts: [
            {
              name: "token-bootstrap",
              phase: "postHealthcheck",
              command: "node",
              args: ["scripts/write-token.js"],
              appdir: ".",
              envFileArg: "--env-file",
            },
          ],
          executionProfiles: [
            {
              executionProfile: "core-scriptrefs",
              executionPolicy: "stop_on_fail",
              scriptRefs: ["token-bootstrap"],
              plans: [{ order: 1, planName: "plan-auth" }],
            },
          ],
        },
      ],
    });
    writeAuthPlan(root, projectName, "plan-auth", "/auth");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-scriptrefs",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        const headers = req.headers as Record<string, unknown>;
        assert.equal(headers.Authorization, "Bearer generated-token");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "pass");
    assert.equal(out.planRuns[0].status, "executed");
    assert.match(fs.readFileSync(envFile, "utf8"), /AUTH_BEARER_TOKEN=generated-token/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite runs postRuntime scriptRefs when health checks are already ready", async () => {
  const root = createTestTempDir("runtime-suite-postruntime-scriptrefs");
  const server = http.createServer((_req: any, res: any) => {
    res.statusCode = 200;
    res.end("ok");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  try {
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("server address unavailable");
    const port = address.port;
    const projectName = "petclinic-regression";
    const envFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(envFile), { recursive: true });
    fs.writeFileSync(envFile, "AUTH_BEARER_TOKEN=\n", "utf8");
    const scriptFile = path.join(root, "scripts", "write-token.js");
    fs.mkdirSync(path.dirname(scriptFile), { recursive: true });
    fs.writeFileSync(
      scriptFile,
      [
        "const fs = require('node:fs');",
        "const idx = process.argv.indexOf('--env-file');",
        "if (idx < 0 || !process.argv[idx + 1]) process.exit(2);",
        "fs.writeFileSync(process.argv[idx + 1], 'AUTH_BEARER_TOKEN=generated-token\\n', 'utf8');",
      ].join("\n"),
      "utf8",
    );
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          externalSystems: [
            {
              name: "customers-api",
              kind: "service",
              host: "127.0.0.1",
              port,
              healthChecks: [
                { id: "http-ready", type: "http", url: `http://127.0.0.1:${port}/health`, required: true },
              ],
            },
          ],
          scripts: [
            {
              name: "token-bootstrap",
              phase: "postRuntime",
              command: "node",
              args: ["scripts/write-token.js"],
              appdir: ".",
              envFileArg: "--env-file",
            },
          ],
          executionProfiles: [
            {
              executionProfile: "core-postruntime-scriptrefs",
              executionPolicy: "stop_on_fail",
              scriptRefs: ["token-bootstrap"],
              plans: [{ order: 1, planName: "plan-auth" }],
            },
          ],
        },
      ],
    });
    writeAuthPlan(root, projectName, "plan-auth", "/auth");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-postruntime-scriptrefs",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        const headers = req.headers as Record<string, unknown>;
        assert.equal(headers.Authorization, "Bearer generated-token");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "pass");
    assert.equal(out.planRuns[0].status, "executed");
    assert.match(fs.readFileSync(envFile, "utf8"), /AUTH_BEARER_TOKEN=generated-token/);
  } finally {
    server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite applies profile runtimeContextName when plan override is absent", async () => {
  const root = createTestTempDir("runtime-suite-profile-runtime-context");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [
            { name: "terminal-cli", mode: "terminal", autoStart: false },
          ],
          executionProfiles: [
            {
              executionProfile: "profile-default-runtime-context",
              runtimeContext: "docker-compose",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "plan-pass" }],
            },
          ],
        },
      ],
    });
    writePlan(root, projectName, "plan-pass", "/pass");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "profile-default-runtime-context",
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
      },
    });

    assert.equal(out.status, "blocked");
    assert.equal("reasonCode" in out, true);
    if ("reasonCode" in out) {
      assert.equal(out.reasonCode, "runtime_context_unknown");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite blocks replay/export script paths in execution profile plans", async () => {
  const root = createTestTempDir("runtime-suite-replay-script-guard");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-smoke",
              executionPolicy: "stop_on_fail",
              plans: [
                {
                  order: 1,
                  planName:
                    ".mcpjvm/test-project/exports/2026-05-23-12345678-1234-1234-1234-123456789abc/run-execution-profile.ps1",
                },
              ],
            },
          ],
        },
      ],
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-smoke",
      mcpInvoke: async () => {
        throw new Error("mcpInvoke should not be called for replay script path");
      },
    });

    assert.equal(out.status, "blocked");
    if ("reasonCode" in out) {
      assert.equal(out.reasonCode, "project_reference_invalid");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite fails closed when execution profile suiteType is performance", async () => {
  const root = createTestTempDir("runtime-suite-performance-profile");
  try {
    const projectName = "petclinic-performance";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "catalog-perf-smoke",
              suiteType: "performance",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "catalog-search-perf" }],
            },
          ],
        },
      ],
    });
    const perfPlanRoot = path.join(root, ".mcpjvm", projectName, "plans", "performance", "catalog-search-perf");
    writeJson(path.join(perfPlanRoot, "metadata.json"), {
      specVersion: "0.1.0",
      suiteType: "performance",
      execution: { intent: "performance" },
    });
    writeJson(path.join(perfPlanRoot, "contract.json"), {
      observationTargets: { requiredLineHits: ["org.example.Service#call:10"] },
      loadModel: { mode: "concurrency", concurrency: 1, rampUpSeconds: 0, durationSeconds: 1 },
      successCriteria: { maxErrorRatePct: 0, minThroughputPerSec: 1, p95LatencyMs: 100 },
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "catalog-perf-smoke",
      mcpInvoke: async () => {
        throw new Error("mcpInvoke should not be called for performance suite profiles");
      },
    });

    assert.equal(out.status, "blocked");
    if ("reasonCode" in out) {
      assert.equal(out.reasonCode, "runtime_suite_invalid");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite annotates plan runs for shared correlation session", async () => {
  const root = createTestTempDir("runtime-suite-correlation");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "async-flow",
              executionPolicy: "stop_on_fail",
              plans: [
                { order: 1, planName: "producer-plan" },
                { order: 2, planName: "consumer-plan" },
              ],
            },
          ],
        },
      ],
    });
    writeCorrelatedPlan(root, projectName, "producer-plan", "/produce", {
      probeId: "producer-service",
      correlationSessionId: "order-flow",
      keyValue: "trace-abc-123",
      expectedFlow: ["producer-service", "consumer-service"],
    });
    writeCorrelatedPlan(root, projectName, "consumer-plan", "/consume", {
      probeId: "consumer-service",
      correlationSessionId: "order-flow",
      keySourcePath: "x-trace-id",
      expectedFlow: ["producer-service", "consumer-service"],
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "async-flow",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        const url = String(req.url ?? "");
        if (url.includes("/consume")) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 8,
              bodyPreview: "{\"ok\":true}",
              headers: { "x-trace-id": "trace-abc-123" },
            },
          };
        }
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 7,
            bodyPreview: "{\"ok\":true}",
          },
        };
      },
    });

    assert.equal(out.status, "pass");
    assert.equal(Array.isArray(out.correlations), true);
    assert.equal(out.correlations?.length, 1);
    assert.equal(out.correlations?.[0].status, "ok");
    assert.equal(out.correlations?.[0].correlationSessionId, "order-flow");

    const producerRunId = out.planRuns.find((entry: { planName: string; runId?: string }) => entry.planName === "producer-plan")?.runId;
    const consumerRunId = out.planRuns.find((entry: { planName: string; runId?: string }) => entry.planName === "consumer-plan")?.runId;
    assert.ok(producerRunId);
    assert.ok(consumerRunId);

    const producerExecution = JSON.parse(
      fs.readFileSync(
        path.join(
          root,
          ".mcpjvm",
          projectName,
          "plans",
          "regression",
          "producer-plan",
          "runs",
          String(producerRunId),
          "execution.result.json",
        ),
        "utf8",
      ),
    );
    const producerCorrelation = JSON.parse(
      fs.readFileSync(
        path.join(
          root,
          ".mcpjvm",
          projectName,
          "plans",
          "regression",
          "producer-plan",
          "runs",
          String(producerRunId),
          "correlation",
          "correlation.json",
        ),
        "utf8",
      ),
    );
    const consumerExecution = JSON.parse(
      fs.readFileSync(
        path.join(
          root,
          ".mcpjvm",
          projectName,
          "plans",
          "regression",
          "consumer-plan",
          "runs",
          String(consumerRunId),
          "execution.result.json",
        ),
        "utf8",
      ),
    );
    const consumerCorrelation = JSON.parse(
      fs.readFileSync(
        path.join(
          root,
          ".mcpjvm",
          projectName,
          "plans",
          "regression",
          "consumer-plan",
          "runs",
          String(consumerRunId),
          "correlation",
          "correlation.json",
        ),
        "utf8",
      ),
    );
    assert.equal(producerExecution.executionProfile, "async-flow");
    assert.equal(producerExecution.suiteRunId, out.suiteRunId);
    assert.equal(producerCorrelation.correlationSessionId, "order-flow");
    assert.equal(consumerExecution.executionProfile, "async-flow");
    assert.equal(consumerExecution.suiteRunId, out.suiteRunId);
    assert.equal(consumerCorrelation.status, "ok");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
