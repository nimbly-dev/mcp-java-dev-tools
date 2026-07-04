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

test("executeRegressionPlanWorkflow fails closed when wait_for_hit returns probe_selection_failed", async () => {
  const root = createTestTempDir("plan-executor-probe-selection-fail-closed");
  try {
    const projectName = "petclinic-regression";
    const planName = "strict-probe-selection-fail-closed";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [{ projectRoot: root, runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }] }],
    });
    writeJson(path.join(planRoot, "metadata.json"), {
      specVersion: "1.0.0",
      execution: {
        intent: "regression",
        probeVerification: true,
        pinStrictProbeKey: true,
        discoveryPolicy: "allow_discoverable_prerequisites",
      },
    });
    writeJson(path.join(planRoot, "contract.json"), {
      targets: [
        {
          type: "class_method",
          selectors: { fqcn: "org.example.EventsController", method: "create", sourceRoot: "src/main/java" },
          runtimeVerification: {
            strictProbeKey: "org.example.EventsController#create:10",
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
          expect: [{ id: "probe_hit", actualPath: "probe.hit", operator: "probe_line_hit", expected: true }],
        },
      ],
    });

    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          return { structuredContent: { status: "pass", statusCode: 200, durationMs: 8, bodyPreview: "{}" } };
        }
        if (toolName === "probe") {
          if (input.action === "reset") {
            return { structuredContent: { status: "pass", result: { ok: true } } };
          }
          if (input.action === "wait_for_hit") {
            return {
              structuredContent: {
                resultType: "report",
                status: "probe_selection_failed",
                reasonCode: "probe_id_required",
                nextActionCode: "provide_probe_id",
                nextAction: "Provide probeId or baseUrl. Multi-probe profiles require explicit selection.",
              },
            };
          }
        }
        throw new Error(`unexpected tool: ${toolName}`);
      },
    });

    assert.equal(out.status, "executed");
    if (out.status === "executed") {
      assert.equal(out.runStatus, "blocked");
      assert.equal(out.executionResult.status, "blocked");
      assert.equal(out.executionResult.steps[0].status, "blocked_runtime");
      assert.equal(out.executionResult.steps[0].reasonCode, "probe_wait_for_hit_failed");
      assert.deepEqual(out.executionResult.steps[0].reasonMeta, {
        failedStep: "probe_wait_for_hit",
        probeStatus: "probe_selection_failed",
        probeReasonCode: "probe_id_required",
        nextActionCode: "provide_probe_id",
        nextAction: "Provide probeId or baseUrl. Multi-probe profiles require explicit selection.",
      });
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
