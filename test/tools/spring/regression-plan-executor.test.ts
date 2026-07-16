import type { IncomingMessage, ServerResponse } from "node:http";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const test = require("node:test");

const { executeRegressionPlanWorkflow } = require("@tools-feature-regression-suite");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  const normalizedPayload =
    path.basename(filePath) === "projects.json" && Array.isArray(payload.workspaces)
      ? {
          ...payload,
          workspaces: payload.workspaces.map((workspace) => {
            const entry = workspace as Record<string, unknown>;
            const defaults =
              entry.defaults && typeof entry.defaults === "object"
                ? (entry.defaults as Record<string, unknown>)
                : {};
            return {
              ...entry,
              defaults: {
                ...defaults,
                orchestrator: {
                  resumePollMax: 30,
                  resumePollIntervalMs: 10000,
                  resumePollTimeoutMs: 300000,
                },
              },
            };
          }),
        }
      : payload;
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(normalizedPayload, null, 2)}\n`, "utf8");
}

test("executeRegressionPlanWorkflow runs plan and writes artifacts without regression-specific MCP tool", async () => {
  const root = createTestTempDir("plan-executor");
  try {
    const projectName = "petclinic-regression";
    const planName = "visits-service-endpoints";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
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
      targets: [
        {
          type: "class_method",
          selectors: { fqcn: "org.example.VisitsController", method: "listVisits", sourceRoot: "src/main/java" },
        },
      ],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "list_visits",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 22,
            bodyPreview: '{"ok":true}',
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(fs.existsSync(out.artifacts.contextResolvedPathAbs), true);
      assert.equal(fs.existsSync(out.artifacts.executionResultPathAbs), true);
      assert.equal(fs.existsSync(out.artifacts.evidencePathAbs), true);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow serializes object HTTP body for wrapped transport", async () => {
  const root = createTestTempDir("plan-executor-body");
  try {
    const projectName = "petclinic-regression";
    const planName = "visits-service-endpoints";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.VisitsController", method: "create", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "create_visit",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/owners/1/pets/1/visits", body: { date: "2026-01-01", description: "regression visit" } } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    let capturedBody;
    await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        capturedBody = (input.request as Record<string, unknown>).body;
        return { structuredContent: { status: "pass", statusCode: 201, durationMs: 10, bodyPreview: "{\"id\":1}" } };
      },
    });

    assert.equal(typeof capturedBody, "string");
    assert.equal(
      capturedBody,
      JSON.stringify({ date: "2026-01-01", description: "regression visit" }),
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow extracts response.bodyJson.id from full response body beyond preview length", async () => {
  const root = createTestTempDir("plan-executor-full-body-extract");
  try {
    const projectName = "petclinic-regression";
    const planName = "favorites-lifecycle-full-body-extract";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.FavoritesController", method: "create", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "favoriteId", required: false, secret: false, provisioning: "user_input", default: "_pending_extract_" }],
      steps: [
        {
          order: 1,
          id: "create_favorite",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", url: "http://localhost:8082/favorites", body: { name: "alpha" } } },
          extract: [{ from: "response.bodyJson.id", as: "favoriteId" }],
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 201 }],
        },
        {
          order: 2,
          id: "read_favorite",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://localhost:8082/favorites/${favoriteId}" } },
          expect: [{ id: "ok", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    const largeBody = JSON.stringify({
      id: "5a4ddf25-ea48-4e46-828b-bbc79ab6da7f",
      filler: "x".repeat(3000),
    });
    const seenUrls: string[] = [];
    let calls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        seenUrls.push(String(request.url));
        calls += 1;
        if (calls === 1) {
          return { structuredContent: { status: "pass", statusCode: 201, durationMs: 10, body: largeBody, bodyPreview: largeBody.slice(0, 2048) } };
        }
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 8, body: "{\"ok\":true}", bodyPreview: "{\"ok\":true}" } };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.deepEqual(seenUrls, [
        "http://localhost:8082/favorites",
        "http://localhost:8082/favorites/5a4ddf25-ea48-4e46-828b-bbc79ab6da7f",
      ]);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow records unresolved optional extract and continues", async () => {
  const root = createTestTempDir("plan-executor-optional-extract-miss");
  try {
    const projectName = "petclinic-regression";
    const planName = "optional-extract-miss";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "create", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "create_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events", body: { kind: "created" } } },
          extract: [{ from: "response.body.id", as: "triggeredEventId" }],
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 10, body: "{\"ok\":true}", bodyPreview: "{\"ok\":true}" } };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.deepEqual(out.executionResult.steps[0].extract, [
        {
          from: "response.body.id",
          as: "triggeredEventId",
          required: false,
          status: "unresolved",
          reasonCode: "extract_path_missing",
        },
      ]);
      const resolved = JSON.parse(fs.readFileSync(out.artifacts.contextResolvedPathAbs, "utf8"));
      assert.equal(typeof resolved.triggeredEventId, "undefined");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow blocks when required extract path is unresolved", async () => {
  const root = createTestTempDir("plan-executor-required-extract-miss");
  try {
    const projectName = "petclinic-regression";
    const planName = "required-extract-miss";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "create", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "create_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events", body: { kind: "created" } } },
          extract: [{ from: "response.body.id", as: "triggeredEventId", required: true }],
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
        {
          order: 2,
          id: "consume_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://localhost:8082/events/${triggeredEventId}" } },
          expect: [{ id: "ok", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    let calls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        calls += 1;
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 10, body: "{\"ok\":true}", bodyPreview: "{\"ok\":true}" } };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(calls, 1);
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.steps[0].status, "blocked_runtime");
      assert.equal(out.executionResult.steps[0].reasonCode, "extract_path_missing");
      assert.deepEqual(out.executionResult.steps[0].extract, [
        {
          from: "response.body.id",
          as: "triggeredEventId",
          required: true,
          status: "unresolved",
          reasonCode: "extract_path_missing",
        },
      ]);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow preserves primary step failure when required extract is also unresolved", async () => {
  const root = createTestTempDir("plan-executor-required-extract-with-failure");
  try {
    const projectName = "petclinic-regression";
    const planName = "required-extract-with-failure";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "create", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "create_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events", body: { kind: "created" } } },
          extract: [{ from: "response.body.id", as: "triggeredEventId", required: true }],
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
        {
          order: 2,
          id: "consume_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://localhost:8082/events/${triggeredEventId}" } },
          expect: [{ id: "ok", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    let calls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        calls += 1;
        return { structuredContent: { status: "pass", statusCode: 500, durationMs: 10, body: "{\"ok\":true}", bodyPreview: "{\"ok\":true}" } };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(calls, 1);
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.steps[0].status, "fail_assertion");
      assert.equal(typeof out.executionResult.steps[0].reasonCode, "undefined");
      assert.deepEqual(out.executionResult.steps[0].reasonMeta, {
        extract: [
          {
            from: "response.body.id",
            as: "triggeredEventId",
            required: true,
            status: "unresolved",
            reasonCode: "extract_path_missing",
          },
        ],
      });
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow applies workspace requestTimeoutMs to wrapped transport when step timeout is absent", async () => {
  const root = createTestTempDir("plan-executor-timeout-default");
  try {
    const projectName = "petclinic-regression";
    const planName = "visits-service-endpoints";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 250000 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
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
      targets: [
        {
          type: "class_method",
          selectors: { fqcn: "org.example.VisitsController", method: "listVisits", sourceRoot: "src/main/java" },
        },
      ],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "list_visits",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    let capturedTimeoutMs;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        capturedTimeoutMs = (input.request as Record<string, unknown>).timeoutMs;
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 22,
            bodyPreview: '{"ok":true}',
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    assert.equal(capturedTimeoutMs, 250000);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow stops step iteration on runtime block", async () => {
  const root = createTestTempDir("plan-executor-blocked");
  try {
    const projectName = "petclinic-regression";
    const planName = "visits-service-endpoints";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.VisitsController", method: "read", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "step_1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits/1" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
        {
          order: 2,
          id: "step_2",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits/2" } },
          expect: [{ id: "outcome_ok_2", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    let calls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        calls += 1;
        return {
          structuredContent: {
            status: "blocked_runtime",
            reasonCode: "transport_request_failed",
            durationMs: 7,
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(calls, 1);
      assert.equal(out.executionResult.steps.length, 1);
      assert.equal(out.executionResult.steps[0].id, "step_1");
      assert.equal(out.runStatus, "blocked");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow routes strict probe verification per target probeId", async () => {
  const root = createTestTempDir("plan-executor-probe-route");
  try {
    const projectName = "petclinic-regression";
    const planName = "multi-service-endpoints";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: true, pinStrictProbeKey: true, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [
        {
          type: "class_method",
          selectors: { fqcn: "org.example.CourseController", method: "list", sourceRoot: "src/main/java" },
          runtimeVerification: { strictProbeKey: "org.example.CourseController#list:10", probeId: "course-service" },
        },
        {
          type: "class_method",
          selectors: { fqcn: "org.example.ReviewController", method: "list", sourceRoot: "src/main/java" },
          runtimeVerification: { strictProbeKey: "org.example.ReviewController#list:20", probeId: "review-service" },
        },
      ],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8080" }],
      steps: [
        {
          order: 1,
          id: "course_step",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/courses" } },
          expect: [{ id: "outcome_ok_1", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
        {
          order: 2,
          id: "review_step",
          targetRef: 1,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/reviews" } },
          expect: [{ id: "outcome_ok_2", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    const probeWaitCalls: Array<Record<string, unknown>> = [];
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          return { structuredContent: { status: "pass", statusCode: 200, durationMs: 8, bodyPreview: "[]" } };
        }
        if (toolName === "probe") {
          assert.equal(typeof input.action, "string");
          if (input.action === "reset") {
            return { structuredContent: { ok: true } };
          }
          if (input.action === "wait_for_hit") {
            probeWaitCalls.push(input.input as Record<string, unknown>);
            return { structuredContent: { result: { hit: true } } };
          }
        }
        throw new Error(`unexpected tool: ${toolName}`);
      },
    });

    assert.equal(out.status, "executed");
    assert.equal(probeWaitCalls.length, 2);
    assert.equal(probeWaitCalls[0]!.probeId, "course-service");
    assert.equal(probeWaitCalls[1]!.probeId, "review-service");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow propagates runtimeVerification.waitForHit overrides to probe wait_for_hit", async () => {
  const root = createTestTempDir("plan-executor-probe-wait-overrides");
  try {
    const projectName = "petclinic-regression";
    const planName = "strict-probe-wait-overrides";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: true, pinStrictProbeKey: true, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [
        {
          type: "class_method",
          selectors: { fqcn: "org.example.EventsController", method: "create", sourceRoot: "src/main/java" },
          runtimeVerification: {
            strictProbeKey: "org.example.EventsController#create:10",
            probeId: "event-service",
            waitForHit: {
              timeoutMs: 60_000,
              pollIntervalMs: 750,
              maxRetries: 9,
            },
          },
        },
      ],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8080" }],
      steps: [
        {
          order: 1,
          id: "event_step",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    let waitInput: Record<string, unknown> | undefined;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          return { structuredContent: { status: "pass", statusCode: 200, durationMs: 8, bodyPreview: "{}" } };
        }
        if (toolName === "probe") {
          if (input.action === "reset") {
            return { structuredContent: { ok: true } };
          }
          if (input.action === "wait_for_hit") {
            waitInput = input.input as Record<string, unknown>;
            return { structuredContent: { result: { hit: true } } };
          }
        }
        throw new Error(`unexpected tool: ${toolName}`);
      },
    });

    assert.equal(out.status, "executed");
    assert.equal(waitInput?.probeId, "event-service");
    assert.equal(waitInput?.timeoutMs, 60_000);
    assert.equal(waitInput?.pollIntervalMs, 750);
    assert.equal(waitInput?.maxRetries, 9);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow infers apiBaseUrl from probe-config runtime.port when plan context omits it", async () => {
  const root = createTestTempDir("plan-executor-probe-config-api-base");
  try {
    const projectName = "petclinic-regression";
    const planName = "course-service-regression-spec";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", "probe-config.json"), {
      defaultProfile: "dev",
      workspaces: [{ root, profile: "dev" }],
      profiles: {
        dev: {
          probes: {
            "course-service": {
              baseUrl: "http://127.0.0.1:9193",
              include: ["x.**"],
              exclude: [],
              runtime: { platform: "spring-boot", port: 9101 },
            },
          },
        },
      },
    });
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.CourseController", method: "create", sourceRoot: "src/main/java" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "create_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/api/courses", body: { title: "Regression Course" } } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    let capturedUrl;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        capturedUrl = (input.request as Record<string, unknown>).url;
        return {
          structuredContent: {
            status: "pass",
            statusCode: 201,
            durationMs: 12,
            bodyPreview: '{"id":123}',
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    assert.equal(capturedUrl, "http://127.0.0.1:9101/api/courses");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow composes url from baseUrl prerequisite and transport path", async () => {
  const root = createTestTempDir("plan-executor-base-url-path");
  try {
    const projectName = "petclinic-regression";
    const planName = "base-url-path-regression";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.CourseController", method: "read", sourceRoot: "src/main/java" } }],
      prerequisites: [
        { key: "baseUrl", required: true, secret: false, provisioning: "user_input", default: "http://127.0.0.1:8082" },
        { key: "resourceId", required: true, secret: false, provisioning: "user_input", default: "42" },
      ],
      steps: [
        {
          order: 1,
          id: "read_course",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", path: "/api/courses/${resourceId}" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    let capturedUrl;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        capturedUrl = (input.request as Record<string, unknown>).url;
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 8,
            bodyPreview: "{\"ok\":true}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    assert.equal(capturedUrl, "http://127.0.0.1:8082/api/courses/42");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow passes transport-failure step when authored assertions match", async () => {
  const root = createTestTempDir("plan-executor-sad-path-http");
  try {
    const projectName = "petclinic-regression";
    const planName = "missing-entity-check";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.VisitsController", method: "readMissing", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "read_missing_visit",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits/404" } },
          expect: [
            { id: "http-not-found", actualPath: "response.statusCode", operator: "field_equals", expected: 404 },
            { id: "body-reason", actualPath: "response.body", operator: "contains", expected: "missing" },
          ],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return {
          structuredContent: {
            status: "fail_http",
            statusCode: 404,
            durationMs: 11,
            bodyPreview: "{\"reason\":\"missing\"}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(out.executionResult.steps[0].status, "pass");
      assert.equal(out.executionResult.steps[0].statusCode, 404);
      assert.equal(out.executionResult.steps[0].reasonCode, undefined);
      assert.equal(out.executionResult.steps[0].reasonMeta, undefined);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow treats optional-only transport-failure step as non-required in run status", async () => {
  const root = createTestTempDir("plan-executor-sad-path-optional");
  try {
    const projectName = "petclinic-regression";
    const planName = "missing-entity-optional-check";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.VisitsController", method: "readMissing", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "read_missing_visit_optional",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits/404" } },
          expect: [
            { id: "http-not-found", actualPath: "response.statusCode", operator: "field_equals", expected: 404, required: false },
            { id: "body-reason", actualPath: "response.body", operator: "contains", expected: "missing", required: false },
          ],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return {
          structuredContent: {
            status: "fail_http",
            statusCode: 404,
            durationMs: 9,
            bodyPreview: "{\"reason\":\"missing\"}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(out.executionResult.steps[0].status, "pass");
      assert.equal(out.executionResult.steps[0].assertions.every((entry: { status: string }) => entry.status === "pass"), true);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow does not fail overall run for optional-only transport-failure mismatch", async () => {
  const root = createTestTempDir("plan-executor-sad-path-optional-mismatch");
  try {
    const projectName = "petclinic-regression";
    const planName = "missing-entity-optional-mismatch";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.VisitsController", method: "readMissing", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "read_missing_visit_optional_mismatch",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits/404" } },
          expect: [
            { id: "http-not-found", actualPath: "response.statusCode", operator: "field_equals", expected: 404, required: false },
            { id: "body-reason", actualPath: "response.body", operator: "contains", expected: "missing", required: false },
          ],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return {
          structuredContent: {
            status: "fail_http",
            statusCode: 500,
            durationMs: 9,
            bodyPreview: "{\"reason\":\"unexpected\"}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(out.executionResult.steps[0].status, "fail_assertion");
      assert.equal(out.executionResult.steps[0].assertions.some((entry: { status: string }) => entry.status === "fail"), true);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow fails overall run when a required step has any authored expectation mismatch", async () => {
  const root = createTestTempDir("plan-executor-transport-failure-mixed-mismatch");
  try {
    const projectName = "petclinic-regression";
    const planName = "missing-entity-mixed-mismatch";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.VisitsController", method: "readMissing", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "read_missing_visit_mixed_mismatch",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits/404" } },
          expect: [
            { id: "http-not-found", actualPath: "response.statusCode", operator: "field_equals", expected: 404 },
            { id: "body-reason", actualPath: "response.body", operator: "contains", expected: "missing", required: false },
          ],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return {
          structuredContent: {
            status: "fail_http",
            statusCode: 404,
            durationMs: 9,
            bodyPreview: "{\"reason\":\"unexpected\"}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "fail");
      assert.equal(out.executionResult.steps[0].status, "fail_assertion");
      assert.equal(out.executionResult.steps[0].assertions[0].status, "pass");
      assert.equal(out.executionResult.steps[0].assertions[1].status, "fail");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow supports array index notation in expectations and extracts", async () => {
  const root = createTestTempDir("plan-executor-array-paths");
  try {
    const projectName = "petclinic-regression";
    const planName = "array-body-regression";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.CourseController", method: "list", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "read_names",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/names" } },
          extract: [{ from: "response.bodyJson.names[0].value", as: "primaryName" }],
          expect: [
            { id: "first-value", actualPath: "response.bodyJson.names[0].value", operator: "field_equals", expected: "Test" },
          ],
        },
        {
          order: 2,
          id: "verify_extracted_name",
          targetRef: 0,
          protocol: "http",
          when: { left: "context.primaryName", op: "equals", right: "Test" },
          transport: { http: { method: "GET", pathTemplate: "/verify" } },
          expect: [{ id: "outcome_ok_2", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    let calls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        calls += 1;
        if (calls === 1) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 8,
              bodyPreview: "{\"names\":[{\"locale\":\"*\",\"value\":\"Test\"},{\"locale\":\"en\",\"value\":\"Test EN\"}]}",
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

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(calls, 2);
      assert.equal(out.runStatus, "pass");
      assert.equal(out.executionResult.steps[0].status, "pass");
      assert.equal(out.executionResult.steps[1].status, "pass");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow does not promote extracted baseUrl into apiBaseUrl", async () => {
  const root = createTestTempDir("plan-executor-extracted-base-url-no-promotion");
  try {
    const projectName = "petclinic-regression";
    const planName = "extracted-base-url-no-promotion";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.CourseController", method: "list", sourceRoot: "src/main/java" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "discover_business_base_url",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", url: "http://localhost:8082/bootstrap" } },
          extract: [{ from: "response.bodyJson.baseUrl", as: "baseUrl" }],
          expect: [{ id: "outcome_ok_1", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
        {
          order: 2,
          id: "must_fail_closed_without_canonical_api_base_url",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", path: "/verify" } },
          expect: [{ id: "outcome_fail", actualPath: "status", operator: "outcome_status", expected: "fail" }],
        },
      ],
    });

    const seenRequests: string[] = [];
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        seenRequests.push(String(request.url));
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 8,
            bodyPreview: "{\"baseUrl\":\"https://business.example.test\"}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.deepEqual(seenRequests, ["http://localhost:8082/bootstrap"]);
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.steps[0].status, "pass");
      assert.equal(out.executionResult.steps[1].status, "blocked_dependency");
      assert.equal(out.executionResult.steps[1].reasonCode, "http_payload_invalid");
      assert.deepEqual(out.executionResult.steps[1].reasonMeta?.missingFields, ["url"]);
      assert.equal(out.executionResult.steps[1].reasonMeta?.cause, "api_base_url_missing_for_path");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow resolves correlation key when json_path source uses response.body.id", async () => {
  const root = createTestTempDir("plan-executor-correlation-json-path");
  try {
    const projectName = "petclinic-regression";
    const planName = "correlation-json-path-fail-closed";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [
        {
          type: "class_method",
          selectors: { fqcn: "org.example.EventsController", method: "create", sourceRoot: "src/main/java" },
          runtimeVerification: { strictProbeKey: "org.example.EventsController#create:10", probeId: "event-service" },
        },
      ],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "create_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events", body: { kind: "created" } } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
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

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 11,
            body: "{\"id\":\"evt-123\",\"ok\":true}",
            bodyPreview: "{\"id\":\"evt-123\",\"ok\":true}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(typeof out.artifacts.correlationPathAbs, "string");
      const evidence = JSON.parse(fs.readFileSync(out.artifacts.evidencePathAbs, "utf8"));
      const correlation = JSON.parse(fs.readFileSync(String(out.artifacts.correlationPathAbs), "utf8"));

      assert.equal(evidence.correlationPolicy.keyType, "messageId");
      assert.equal(evidence.correlationPolicy.keySourceType, "json_path");
      assert.equal(evidence.correlationPolicy.keySourcePath, "response.body.id");
      assert.equal(evidence.correlationPolicy.keyValue, "evt-123");
      assert.equal(typeof evidence.correlationPolicy.keyExtractionReasonCode, "undefined");
      assert.equal(Array.isArray(evidence.correlationEvents), true);
      assert.equal(evidence.correlationEvents.length, 1);
      assert.equal(evidence.correlationEvents[0].probeId, "event-service");
      assert.equal(evidence.correlationEvents[0].keyValue, "evt-123");

      assert.equal(correlation.status, "ok");
      assert.equal(correlation.reasonCode, "ok");
      assert.equal(correlation.keyValue, "evt-123");
      assert.equal(Array.isArray(correlation.timeline), true);
      assert.equal(correlation.timeline.length, 1);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow skips step when condition evaluates false", async () => {
  const root = createTestTempDir("plan-executor-condition-skip");
  try {
    const projectName = "petclinic-regression";
    const planName = "conditional-skip";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.VisitsController", method: "read", sourceRoot: "src/main/java" } }],
      prerequisites: [
        { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" },
        { key: "verifyEnabled", required: false, secret: false, provisioning: "user_input", default: false },
      ],
      steps: [
        {
          order: 1,
          id: "step_1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "GET", pathTemplate: "/visits/1" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
        {
          order: 2,
          id: "step_2",
          targetRef: 0,
          protocol: "http",
          when: {
            all: [
              { left: "step[1].status", op: "equals", right: "pass" },
              { left: "context.verifyEnabled", op: "equals", right: true },
            ],
          },
          transport: { http: { method: "GET", pathTemplate: "/visits/2" } },
          expect: [{ id: "outcome_ok_2", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    let calls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        calls += 1;
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 7,
            bodyPreview: "{}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(calls, 1);
      assert.equal(out.executionResult.steps[1].status, "skipped_condition_false");
      assert.equal(out.executionResult.steps[1].conditionEvaluation.status, false);
      assert.equal(out.runStatus, "pass");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow blocks when condition path is invalid at runtime", async () => {
  const root = createTestTempDir("plan-executor-condition-block");
  try {
    const projectName = "petclinic-regression";
    const planName = "conditional-block";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.VisitsController", method: "read", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "step_1",
          targetRef: 0,
          protocol: "http",
          when: { left: "step[0].status", op: "equals", right: "pass" },
          transport: { http: { method: "GET", pathTemplate: "/visits/1" } },
          expect: [{ id: "outcome_ok", actualPath: "status", operator: "outcome_status", expected: "pass" }],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async () => {
        throw new Error("transport should not execute");
      },
    });

    assert.equal(out.status, "blocked");
    if (out.status === "blocked") {
      assert.equal(out.preflight.reasonCode, "step_condition_forward_reference");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow executes watcher as bounded post-step verification and persists watcher success", async () => {
  const root = createTestTempDir("plan-executor-watcher-success");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-success";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: {
            requestTimeoutMs: 120,
            retryMax: 3,
            orchestrator: {
              resumePollMax: 30,
              resumePollIntervalMs: 10000,
              resumePollTimeoutMs: 300000,
            },
          },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.id", as: "eventId", required: true }],
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "search_indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                url: "http://localhost:8082/index/${eventId}",
              },
            },
          },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    let watcherCalls = 0;
    const seenUrls: string[] = [];
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        seenUrls.push(String(request.url));
        if (String(request.method) === "POST") {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"id\":\"evt-001\"}",
              bodyPreview: "{\"id\":\"evt-001\"}",
            },
          };
        }
        watcherCalls += 1;
        return {
          structuredContent: watcherCalls < 3
            ? {
                status: "pass",
                statusCode: 200,
                durationMs: 7,
                body: "{\"state\":\"pending\"}",
                bodyPreview: "{\"state\":\"pending\"}",
              }
            : {
                status: "pass",
                statusCode: 200,
                durationMs: 7,
                body: "{\"state\":\"ready\"}",
                bodyPreview: "{\"state\":\"ready\"}",
              },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(out.executionResult.triggerStatus, "pass");
      assert.equal(out.executionResult.watcherStatus, "pass");
      assert.equal(out.executionResult.watchers?.length, 1);
      assert.equal(out.executionResult.watchers?.[0]?.status, "pass");
      assert.equal(out.executionResult.watchers?.[0]?.outcome, "verified");
      assert.equal(out.executionResult.watchers?.[0]?.attemptCount, 3);
      assert.equal(out.executionResult.watchers?.[0]?.waitPolicy.timeoutSource, "project_default");
      assert.equal(out.executionResult.watchers?.[0]?.waitPolicy.retrySource, "project_default");
      assert.equal(Array.isArray(out.executionResult.watchers?.[0]?.attempts), true);
      assert.deepEqual(seenUrls, [
        "http://localhost:8082/events",
        "http://localhost:8082/index/evt-001",
        "http://localhost:8082/index/evt-001",
        "http://localhost:8082/index/evt-001",
      ]);
      const evidence = JSON.parse(fs.readFileSync(out.artifacts.evidencePathAbs, "utf8"));
      assert.equal(Array.isArray(evidence.watcherExecutions), true);
      assert.equal(evidence.watcherExecutions.length, 1);
      assert.equal(evidence.watcherExecutions[0].status, "ok");
      assert.equal(evidence.watcherExecutions[0].attemptCount, 3);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow reuses HTTP payload normalization for watcher requests", async () => {
  const root = createTestTempDir("plan-executor-watcher-http-normalization");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-http-normalization";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.id", as: "eventId", required: true }],
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "index_event",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "POST",
                pathTemplate: "/index/${eventId}",
                body: { state: "ready" },
              },
            },
          },
          expect: [{ id: "indexed", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    const capturedRequests: Record<string, unknown>[] = [];
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        capturedRequests.push(request);
        if (capturedRequests.length === 1) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"id\":\"evt-200\"}",
              bodyPreview: "{\"id\":\"evt-200\"}",
            },
          };
        }
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 8,
            body: "{\"ok\":true}",
            bodyPreview: "{\"ok\":true}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(capturedRequests.length, 2);
      const watcherRequest = capturedRequests[1]!;
      assert.equal(watcherRequest.url, "http://localhost:8082/index/evt-200");
      assert.equal(watcherRequest.body, JSON.stringify({ state: "ready" }));
      assert.deepEqual(watcherRequest.headers, { "Content-Type": "application/json" });
      assert.equal(watcherRequest.timeoutMs, 20_000);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow fails closed when watcher response cannot be normalized", async () => {
  const root = createTestTempDir("plan-executor-watcher-normalization-failure");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-normalization-failure";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 100, retryMax: 2 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.eventId", as: "eventId", required: true, scope: "suite", secret: false }],
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

    let callCount = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        callCount += 1;
        if (callCount === 1) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: JSON.stringify({ eventId: "evt-300" }),
              bodyPreview: "{\"eventId\":\"evt-300\"}",
            },
          };
        }
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 7,
            body: "not-json",
            bodyPreview: "not-json",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.watcherStatus, "blocked");
      assert.equal(out.executionResult.watchers?.[0]?.status, "blocked_runtime");
      assert.equal(out.executionResult.watchers?.[0]?.reasonCode, "watcher_configuration_invalid");
      assert.equal(out.executionResult.watchers?.[0]?.reasonMeta?.providerReasonCode, "watcher_response_normalization_failed");
      assert.equal(out.executionResult.watchers?.[0]?.reasonMeta?.cause, "response_body_json_invalid");
      assert.equal(out.executionResult.watchers?.[0]?.attemptCount, 1);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow fails closed when watcher timeout is exceeded before expectations converge", async () => {
  const root = createTestTempDir("plan-executor-watcher-timeout");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-defaults-unresolved";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "search_indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                url: "http://localhost:8082/index/status",
              },
            },
          },
          waitPolicy: { timeoutMs: 30, retryMax: 5 },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    let watcherCalls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "GET") {
          watcherCalls += 1;
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 5,
              body: "{\"state\":\"pending\"}",
              bodyPreview: "{\"state\":\"pending\"}",
            },
          };
        }
        return {
          structuredContent: {
            status: "pass",
            statusCode: 202,
            durationMs: 8,
            body: "{\"ok\":true}",
            bodyPreview: "{\"ok\":true}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(watcherCalls >= 1, true);
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.triggerStatus, "pass");
      assert.equal(out.executionResult.watcherStatus, "blocked");
      assert.equal(out.executionResult.watchers?.[0]?.status, "blocked_runtime");
      assert.equal(out.executionResult.watchers?.[0]?.outcome, "timed_out");
      assert.equal(out.executionResult.watchers?.[0]?.reasonCode, "watcher_timeout");
      assert.equal(out.executionResult.watchers?.[0]?.attemptCount >= 1, true);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow returns in_progress during watcher polling and resumes the same runId without rerunning steps", async () => {
  const root = createTestTempDir("plan-executor-watcher-resume");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-resume";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    const envFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(envFile), { recursive: true });
    fs.writeFileSync(envFile, "MCP_JVM_TEST_RESUME_AUTH_BEARER_TOKEN=resume-token-v1\n", "utf8");
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { bearerTokenEnv: "MCP_JVM_TEST_RESUME_AUTH_BEARER_TOKEN" },
          defaults: { requestTimeoutMs: 100, retryMax: 3 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [
        { key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" },
        { key: "auth.bearer", required: true, secret: true, provisioning: "user_input" },
      ],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events", headers: { Authorization: "Bearer ${auth.bearer}" } } },
          extract: [{ from: "response.bodyJson.eventId", as: "eventId", required: true, scope: "suite", secret: false }],
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "search_indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                url: "http://localhost:8082/index/${eventId}",
                headers: { Authorization: "Bearer ${auth.bearer}" },
              },
            },
          },
          waitPolicy: { timeoutMs: 1000, retryMax: 3 },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    let stepCalls = 0;
    let watcherCalls = 0;
    const first = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      orchestrationTimeoutBudgetMs: 10,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          assert.equal((request.headers as Record<string, unknown>).Authorization, "Bearer resume-token-v1");
          stepCalls += 1;
          return { structuredContent: { status: "pass", statusCode: 202, durationMs: 1, body: "{\"eventId\":\"evt-500\"}", bodyPreview: "{\"eventId\":\"evt-500\"}" } };
        }
        watcherCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 15));
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 1, body: "{\"state\":\"pending\"}", bodyPreview: "{\"state\":\"pending\"}" } };
      },
    });

    assert.equal(first.status, "executed");
    if (first.status !== "executed") {
      throw new Error("expected executed result");
    }
    assert.equal(first.runStatus, "in_progress");
    assert.equal(first.executionResult.watcherStatus, "in_progress");
    assert.equal(first.executionResult.continuation?.phase, "watchers");
    assert.equal(stepCalls, 1);
    assert.equal(watcherCalls, 1);

    const resumeState = {
      resolvedContext: JSON.parse(fs.readFileSync(first.artifacts.contextResolvedPathAbs, "utf8")),
      executionResult: JSON.parse(fs.readFileSync(first.artifacts.executionResultPathAbs, "utf8")),
      evidence: JSON.parse(fs.readFileSync(first.artifacts.evidencePathAbs, "utf8")),
    };
    assert.equal(resumeState.resolvedContext["auth.bearer"], undefined);
    fs.rmSync(envFile);
    const failedResume = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      runId: first.runId,
      orchestrationTimeoutBudgetMs: 1000,
      resumeState,
      mcpInvoke: async () => {
        throw new Error("resume must fail before transport execution");
      },
    });
    assert.equal(failedResume.status, "blocked");
    if (failedResume.status === "blocked") {
      assert.equal(failedResume.preflight.reasonCode, "env_key_missing");
    }

    fs.writeFileSync(envFile, "MCP_JVM_TEST_RESUME_AUTH_BEARER_TOKEN=resume-token-v2\n", "utf8");
    const second = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      runId: first.runId,
      orchestrationTimeoutBudgetMs: 1000,
      resumeState,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          stepCalls += 1;
          return { structuredContent: { status: "pass", statusCode: 202, durationMs: 1, body: "{\"eventId\":\"evt-500\"}", bodyPreview: "{\"eventId\":\"evt-500\"}" } };
        }
        assert.equal((request.headers as Record<string, unknown>).Authorization, "Bearer resume-token-v2");
        watcherCalls += 1;
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 1, body: "{\"state\":\"ready\"}", bodyPreview: "{\"state\":\"ready\"}" } };
      },
    });

    assert.equal(second.status, "executed");
    if (second.status === "executed") {
      assert.equal(second.runId, first.runId);
      assert.equal(second.runStatus, "pass");
      assert.equal(second.executionResult.watcherStatus, "pass");
      assert.equal(second.executionResult.continuation, undefined);
      assert.equal(second.suiteContext?.eventId, "evt-500");
    }
    assert.equal(stepCalls, 1);
    assert.equal(watcherCalls, 2);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow marks watcher dependency blocked when dependent step does not pass", async () => {
  const root = createTestTempDir("plan-executor-watcher-dependency");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-dependency";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 80, retryMax: 2 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "search_indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                url: "http://localhost:8082/index/status",
              },
            },
          },
          waitPolicy: { timeoutMs: 50, retryMax: 2 },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    let calls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        calls += 1;
        return {
          structuredContent: {
            status: "pass",
            statusCode: 500,
            durationMs: 8,
            body: "{\"ok\":false}",
            bodyPreview: "{\"ok\":false}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(calls, 1);
      assert.equal(out.runStatus, "fail");
      assert.equal(out.executionResult.watchers?.[0]?.status, "blocked_dependency");
      assert.equal(out.executionResult.watchers?.[0]?.reasonCode, "watcher_dependency_invalid");
      assert.equal(out.executionResult.watchers?.[0]?.reasonMeta?.dependencyStatus, "fail_assertion");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow fails closed when watcher target is unreachable and when expectation stays unsatisfied", async () => {
  const root = createTestTempDir("plan-executor-watcher-terminal-failures");
  try {
    const projectName = "petclinic-regression";
    const unreachablePlanName = "watcher-unreachable";
    const unreachablePlanRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", unreachablePlanName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 80, retryMax: 2 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });
    writeJson(path.join(unreachablePlanRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(unreachablePlanRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "search_indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                url: "http://localhost:8082/index/status",
              },
            },
          },
          waitPolicy: { timeoutMs: 60, retryMax: 2 },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    let watcherCalls = 0;
    const unreachableOut = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName: unreachablePlanName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"ok\":true}",
              bodyPreview: "{\"ok\":true}",
            },
          };
        }
        watcherCalls += 1;
        return {
          structuredContent: {
            status: "blocked_runtime",
            durationMs: 5,
            reasonCode: "connect_failed",
          },
        };
      },
    });

    assert.equal(unreachableOut.status, "executed");
    if (unreachableOut.status === "executed") {
      assert.equal(watcherCalls, 1);
      assert.equal(unreachableOut.runStatus, "blocked");
      assert.equal(unreachableOut.executionResult.watchers?.[0]?.status, "blocked_runtime");
      assert.equal(unreachableOut.executionResult.watchers?.[0]?.outcome, "blocked");
      assert.equal(unreachableOut.executionResult.watchers?.[0]?.reasonCode, "watcher_target_unreachable");
    }

    const expectationPlanName = "watcher-failed-expectation";
    const expectationPlanRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", expectationPlanName);
    writeJson(path.join(expectationPlanRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(expectationPlanRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "search_indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                url: "http://localhost:8082/index/status",
              },
            },
          },
          waitPolicy: { timeoutMs: 500, retryMax: 2 },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    let expectationWatcherCalls = 0;
    const expectationOut = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName: expectationPlanName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"ok\":true}",
              bodyPreview: "{\"ok\":true}",
            },
          };
        }
        expectationWatcherCalls += 1;
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 7,
            body: "{\"state\":\"pending\"}",
            bodyPreview: "{\"state\":\"pending\"}",
          },
        };
      },
    });

    assert.equal(expectationOut.status, "executed");
    if (expectationOut.status === "executed") {
      assert.equal(expectationWatcherCalls, 2);
      assert.equal(expectationOut.runStatus, "fail");
      assert.equal(expectationOut.executionResult.watchers?.[0]?.status, "fail_assertion");
      assert.equal(expectationOut.executionResult.watchers?.[0]?.outcome, "failed_expectation");
      assert.equal(expectationOut.executionResult.watchers?.[0]?.reasonCode, "watcher_expectation_failed");
      assert.equal(expectationOut.executionResult.watchers?.[0]?.attemptCount, 2);
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow retries watcher polls when the expected field is temporarily absent", async () => {
  const root = createTestTempDir("plan-executor-watcher-missing-path-retry");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-missing-path-retry";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 240, retryMax: 4 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "search_indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                url: "http://localhost:8082/index/status",
              },
            },
          },
          waitPolicy: { timeoutMs: 240, retryMax: 4 },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    let watcherCalls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"ok\":true}",
              bodyPreview: "{\"ok\":true}",
            },
          };
        }
        watcherCalls += 1;
        return {
          structuredContent: watcherCalls < 4
            ? {
                status: "pass",
                statusCode: 200,
                durationMs: 5,
                body: "{\"pending\":true}",
                bodyPreview: "{\"pending\":true}",
              }
            : {
                status: "pass",
                statusCode: 200,
                durationMs: 5,
                body: "{\"state\":\"ready\"}",
                bodyPreview: "{\"state\":\"ready\"}",
              },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(watcherCalls, 4);
      assert.equal(out.executionResult.watchers?.[0]?.status, "pass");
      assert.equal(out.executionResult.watchers?.[0]?.attemptCount, 4);
      assert.equal(out.executionResult.watchers?.[0]?.assertions[0]?.status, "pass");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow binds watcher placeholders to the dependency step context snapshot", async () => {
  const root = createTestTempDir("plan-executor-watcher-dependency-context");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-dependency-context";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 120, retryMax: 2 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_primary_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events/primary" } },
          extract: [{ from: "response.bodyJson.id", as: "eventId", required: true }],
          expect: [{ id: "created", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
        {
          order: 2,
          id: "trigger_secondary_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events/secondary" } },
          extract: [{ from: "response.bodyJson.id", as: "eventId", required: true }],
          expect: [{ id: "created_secondary", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "primary_indexed",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                pathTemplate: "/index/${eventId}",
              },
            },
          },
          expect: [{ id: "indexed", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    const seenUrls: string[] = [];
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        seenUrls.push(String(request.url));
        if (String(request.url).endsWith("/events/primary")) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"id\":\"evt-primary\"}",
              bodyPreview: "{\"id\":\"evt-primary\"}",
            },
          };
        }
        if (String(request.url).endsWith("/events/secondary")) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"id\":\"evt-secondary\"}",
              bodyPreview: "{\"id\":\"evt-secondary\"}",
            },
          };
        }
        return {
          structuredContent: {
            status: "pass",
            statusCode: 200,
            durationMs: 8,
            body: "{\"ok\":true}",
            bodyPreview: "{\"ok\":true}",
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.deepEqual(seenUrls, [
        "http://localhost:8082/events/primary",
        "http://localhost:8082/events/secondary",
        "http://localhost:8082/index/evt-primary",
      ]);
      assert.equal(out.executionResult.watchers?.[0]?.status, "pass");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow executes HTTP external verification against a concrete local target", async () => {
  const root = createTestTempDir("plan-executor-external-verification-http-pass");
  const server = http.createServer((req: IncomingMessage, res: ServerResponse) => {
    if (req.url === "/events" && req.method === "POST") {
      res.statusCode = 202;
      res.setHeader("content-type", "application/json");
      res.end("{\"taskId\":\"task-123\"}");
      return;
    }
    if (req.url === "/tasks/task-123" && req.method === "GET") {
      res.statusCode = 200;
      res.setHeader("content-type", "application/json");
      res.end("{\"completed\":true,\"task\":{\"status\":\"completed\"}}");
      return;
    }
    res.statusCode = 404;
    res.end("{\"error\":\"not_found\"}");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  try {
    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("expected concrete server address");
    }
    const projectName = "petclinic-regression";
    const planName = "external-verification-http-pass";
    const apiBaseUrl = `http://127.0.0.1:${address.port}`;
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: apiBaseUrl }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.taskId", as: "taskId", required: true }],
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      externalVerification: [
        {
          id: "verify_reindex_task_status",
          provider: { type: "http" },
          request: {
            http: {
              method: "GET",
              pathTemplate: "/tasks/${taskId}",
              timeoutMs: 5000,
            },
          },
          expect: [
            { id: "task_completed", actualPath: "response.bodyJson.completed", operator: "field_equals", expected: true },
            { id: "task_status_completed", actualPath: "response.bodyJson.task.status", operator: "field_equals", expected: "completed" },
          ],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        const response = await fetch(String(request.url), {
          method: String(request.method),
          headers: (request.headers as Record<string, string> | undefined) ?? {},
          ...(typeof request.body === "string" ? { body: request.body } : {}),
        });
        const body = await response.text();
        return {
          structuredContent: {
            status: response.status >= 200 && response.status < 400 ? "pass" : "fail_http",
            statusCode: response.status,
            durationMs: 5,
            headers: Object.fromEntries(response.headers.entries()),
            body,
            bodyPreview: body,
          },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(out.executionResult.externalVerificationStatus, "pass");
      assert.equal(out.executionResult.externalVerification?.length, 1);
      assert.equal(out.executionResult.externalVerification?.[0]?.status, "pass");
      assert.equal(out.executionResult.externalVerification?.[0]?.response?.statusCode, 200);
      assert.equal(out.executionResult.externalVerification?.[0]?.assertions?.[0]?.status, "pass");
    }
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow fails external verification deterministically when expectations are unmet", async () => {
  const root = createTestTempDir("plan-executor-external-verification-fail");
  try {
    const projectName = "petclinic-regression";
    const planName = "external-verification-http-fail";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.taskId", as: "taskId", required: true }],
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      externalVerification: [
        {
          id: "verify_reindex_task_status",
          provider: { type: "http" },
          request: { http: { method: "GET", pathTemplate: "/tasks/${taskId}" } },
          expect: [{ id: "task_completed", actualPath: "response.bodyJson.completed", operator: "field_equals", expected: true }],
        },
      ],
    });

    let callCount = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        callCount += 1;
        return {
          structuredContent: callCount === 1
            ? { status: "pass", statusCode: 202, durationMs: 8, body: "{\"taskId\":\"task-123\"}", bodyPreview: "{\"taskId\":\"task-123\"}" }
            : { status: "pass", statusCode: 200, durationMs: 6, body: "{\"completed\":false}", bodyPreview: "{\"completed\":false}" },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "fail");
      assert.equal(out.executionResult.externalVerificationStatus, "fail");
      assert.equal(out.executionResult.externalVerification?.[0]?.status, "fail_assertion");
      assert.equal(out.executionResult.externalVerification?.[0]?.reasonCode, "external_verification_expectation_failed");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow blocks external verification deterministically on unresolved placeholders", async () => {
  const root = createTestTempDir("plan-executor-external-verification-unresolved");
  try {
    const projectName = "petclinic-regression";
    const planName = "external-verification-http-unresolved";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      externalVerification: [
        {
          id: "verify_reindex_task_status",
          provider: { type: "http" },
          request: { http: { method: "GET", pathTemplate: "/tasks/${taskId}" } },
          expect: [{ id: "task_completed", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        return { structuredContent: { status: "pass", statusCode: 202, durationMs: 8, body: "{\"ok\":true}", bodyPreview: "{\"ok\":true}" } };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.externalVerificationStatus, "blocked");
      assert.equal(out.executionResult.externalVerification?.[0]?.status, "blocked_runtime");
      assert.equal(out.executionResult.externalVerification?.[0]?.reasonCode, "external_verification_request_unresolved");
      assert.equal(out.executionResult.externalVerification?.[0]?.reasonMeta?.missingContextKey, "taskId");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow blocks external verification deterministically when target is unreachable", async () => {
  const root = createTestTempDir("plan-executor-external-verification-unreachable");
  try {
    const projectName = "petclinic-regression";
    const planName = "external-verification-http-unreachable";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.taskId", as: "taskId", required: true }],
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      externalVerification: [
        {
          id: "verify_reindex_task_status",
          provider: { type: "http" },
          request: { http: { method: "GET", pathTemplate: "/tasks/${taskId}" } },
          expect: [{ id: "task_completed", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    let callCount = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        callCount += 1;
        return {
          structuredContent: callCount === 1
            ? { status: "pass", statusCode: 202, durationMs: 8, body: "{\"taskId\":\"task-123\"}", bodyPreview: "{\"taskId\":\"task-123\"}" }
            : { status: "blocked_runtime", durationMs: 5, reasonCode: "connect_failed", reasonMeta: { url: "http://localhost:8082/tasks/task-123" } },
        };
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.externalVerificationStatus, "blocked");
      assert.equal(out.executionResult.externalVerification?.[0]?.status, "blocked_runtime");
      assert.equal(out.executionResult.externalVerification?.[0]?.reasonCode, "external_verification_target_unreachable");
      assert.equal(out.executionResult.externalVerification?.[0]?.reasonMeta?.transportReasonCode, "connect_failed");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow treats thrown external verification transport errors as runtime failures", async () => {
  const root = createTestTempDir("plan-executor-external-verification-transport-throws");
  try {
    const projectName = "petclinic-regression";
    const planName = "external-verification-http-transport-throws";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.taskId", as: "taskId", required: true }],
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      externalVerification: [
        {
          id: "verify_reindex_task_status",
          provider: { type: "http" },
          request: { http: { method: "GET", pathTemplate: "/tasks/${taskId}" } },
          expect: [{ id: "task_completed", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
        },
      ],
    });

    let callCount = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        callCount += 1;
        if (callCount === 1) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"taskId\":\"task-123\"}",
              bodyPreview: "{\"taskId\":\"task-123\"}",
            },
          };
        }
        throw new Error("socket hang up");
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.externalVerificationStatus, "blocked");
      assert.equal(out.executionResult.externalVerification?.[0]?.status, "blocked_runtime");
      assert.equal(out.executionResult.externalVerification?.[0]?.reasonCode, "external_verification_target_unreachable");
      assert.equal(out.executionResult.externalVerification?.[0]?.reasonMeta?.errorMessage, "socket hang up");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionPlanWorkflow still runs external verification when watcher phase fails", async () => {
  const root = createTestTempDir("plan-executor-external-verification-after-watcher-fail");
  try {
    const projectName = "petclinic-regression";
    const planName = "external-verification-after-watcher-fail";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 80, retryMax: 1 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          extract: [{ from: "response.bodyJson.taskId", as: "taskId", required: true }],
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      watchers: [
        {
          id: "watcher_fail",
          dependency: { stepOrder: 1 },
          provider: {
            type: "http",
            transport: {
              request: {
                method: "GET",
                pathTemplate: "/watchers/${taskId}",
              },
            },
          },
          waitPolicy: { timeoutMs: 50, retryMax: 1 },
          expect: [{ id: "watch_ready", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
      externalVerification: [
        {
          id: "verify_reindex_task_status",
          provider: { type: "http" },
          request: { http: { method: "GET", pathTemplate: "/tasks/${taskId}" } },
          expect: [{ id: "task_completed", actualPath: "response.bodyJson.completed", operator: "field_equals", expected: true }],
        },
      ],
    });

    let watcherCalls = 0;
    let verificationCalls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        const url = String(request.url);
        if (url.endsWith("/events")) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 202,
              durationMs: 8,
              body: "{\"taskId\":\"task-123\"}",
              bodyPreview: "{\"taskId\":\"task-123\"}",
            },
          };
        }
        if (url.endsWith("/watchers/task-123")) {
          watcherCalls += 1;
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 5,
              body: "{\"state\":\"pending\"}",
              bodyPreview: "{\"state\":\"pending\"}",
            },
          };
        }
        if (url.endsWith("/tasks/task-123")) {
          verificationCalls += 1;
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 5,
              body: "{\"completed\":true}",
              bodyPreview: "{\"completed\":true}",
            },
          };
        }
        throw new Error(`unexpected request url: ${url}`);
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(watcherCalls, 1);
      assert.equal(verificationCalls, 1);
      assert.equal(out.runStatus, "fail");
      assert.equal(out.executionResult.watcherStatus, "fail");
      assert.equal(out.executionResult.externalVerificationStatus, "pass");
      assert.equal(out.executionResult.externalVerification?.[0]?.status, "pass");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
