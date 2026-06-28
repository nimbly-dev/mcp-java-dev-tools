const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { executeRegressionPlanWorkflow } = require("@tools-regression-execution-plan-spec/regression_plan_executor.util");

function createTestTempDir(prefix: string): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, `${prefix}-`));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
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
