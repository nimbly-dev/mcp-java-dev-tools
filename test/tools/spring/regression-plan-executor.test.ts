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
