const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const test = require("node:test");

const { executeRegressionRuntimeSuite } = require("@tools-feature-regression-suite");
const {
  createTestTempDir,
  writeJson,
  writePlan,
  writeAuthPlan,
} = require("./regression-runtime-suite-executor.fixture");

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
              env: {
                PATH: path.join(root, "missing-path"),
              },
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

test("executeRegressionRuntimeSuite reruns postHealthcheck scriptRefs on a fresh run after prior external_healthcheck_failed", async () => {
  const root = createTestTempDir("runtime-suite-posthealthcheck-rerun");
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
        "const current = fs.existsSync(process.argv[idx + 1]) ? fs.readFileSync(process.argv[idx + 1], 'utf8') : '';",
        "const match = current.match(/SCRIPT_RUN_COUNT=(\\d+)/);",
        "const count = match ? Number(match[1]) + 1 : 1;",
        "fs.writeFileSync(process.argv[idx + 1], `AUTH_BEARER_TOKEN=generated-token-${count}\\nSCRIPT_RUN_COUNT=${count}\\n`, 'utf8');",
      ].join("\n"),
      "utf8",
    );
    const healthPort = 1;
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
              port: healthPort,
              healthChecks: [
                { id: "http-ready", type: "http", url: `http://127.0.0.1:${healthPort}/health`, required: true },
              ],
            },
          ],
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
              executionProfile: "core-scriptrefs-rerun",
              executionPolicy: "stop_on_fail",
              scriptRefs: ["token-bootstrap"],
              plans: [{ order: 1, planName: "plan-auth" }],
            },
          ],
        },
      ],
    });
    writeAuthPlan(root, projectName, "plan-auth", "/auth");

    const first = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-scriptrefs-rerun",
      mcpInvoke: async () => {
        throw new Error("mcpInvoke should not be called when suite-level preflight blocks");
      },
    });

    assert.equal(first.status, "blocked");
    assert.equal(first.planRuns[0].blockedReasonCode, "external_healthcheck_failed");
    assert.match(fs.readFileSync(envFile, "utf8"), /^AUTH_BEARER_TOKEN=\s*$/m);

    const server = http.createServer((_req: any, res: any) => {
      res.statusCode = 200;
      res.end("ok");
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("server address unavailable");
    const liveHealthPort = address.port;
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
              port: liveHealthPort,
              healthChecks: [
                { id: "http-ready", type: "http", url: `http://127.0.0.1:${liveHealthPort}/health`, required: true },
              ],
            },
          ],
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
              executionProfile: "core-scriptrefs-rerun",
              executionPolicy: "stop_on_fail",
              scriptRefs: ["token-bootstrap"],
              plans: [{ order: 1, planName: "plan-auth" }],
            },
          ],
        },
      ],
    });
    try {
      const second = await executeRegressionRuntimeSuite({
        workspaceRootAbs: root,
        executionProfile: "core-scriptrefs-rerun",
        mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
          assert.equal(toolName, "transport_execute");
          const req = input.request as Record<string, unknown>;
          const headers = req.headers as Record<string, unknown>;
          assert.equal(headers.Authorization, "Bearer generated-token-1");
          return { structuredContent: { status: "pass", statusCode: 200, durationMs: 7, bodyPreview: "{}" } };
        },
      });

      assert.equal(second.status, "pass");
      assert.equal(second.planRuns[0].status, "executed");
      assert.match(fs.readFileSync(envFile, "utf8"), /AUTH_BEARER_TOKEN=generated-token-1/);
      assert.match(fs.readFileSync(envFile, "utf8"), /SCRIPT_RUN_COUNT=1/);
    } finally {
      server.close();
    }
  } finally {
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
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
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
