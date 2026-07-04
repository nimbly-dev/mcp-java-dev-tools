const assert = require("node:assert/strict");
const path = require("node:path");
const http = require("node:http");
const fs = require("node:fs");
const test = require("node:test");

const { executeRegressionRuntimeSuite } = require("@tools-regression-execution-plan-spec/regression_runtime_suite_executor.util");
const {
  createTestTempDir,
  writeJson,
  writePlan,
  writeAuthPlan,
  writeSadPathPlan,
} = require("./regression-runtime-suite-executor.fixture");

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

test("executeRegressionRuntimeSuite passes plan with intentional non-2xx sad-path assertions", async () => {
  const root = createTestTempDir("runtime-suite-sad-path-pass");
  try {
    const projectName = "petclinic-regression";
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "sad-path-check",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "plan-not-found" }],
            },
          ],
        },
      ],
    });
    writeSadPathPlan(root, projectName, "plan-not-found", "/missing");

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "sad-path-check",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        const url = String(req.url ?? "");
        assert.equal(url.includes("/missing"), true);
        return {
          structuredContent: { status: "fail_http", statusCode: 404, durationMs: 9, bodyPreview: "{\"reason\":\"missing\"}" },
        };
      },
    });

    assert.equal(out.status, "pass");
    assert.equal(out.planRuns[0].status, "executed");
    assert.equal(out.planRuns[0].runStatus, "pass");
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
