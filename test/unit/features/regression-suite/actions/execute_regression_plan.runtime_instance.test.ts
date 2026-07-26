const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { executeRegressionPlanWorkflow } = require("@tools-feature-regression-suite");

function createTempDir(): string {
  const base = path.join(process.cwd(), "test", ".tmp");
  fs.mkdirSync(base, { recursive: true });
  return fs.mkdtempSync(path.join(base, "plan-executor-runtime-instance-"));
}

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("[UT][regression-suite][execute_regression_plan_runtime_instance][ok] strict line correlation uses Probe runtimeInstanceId in observe mode", async () => {
  const root = createTempDir();
  try {
    const projectName = "runtime-instance-regression";
    const planName = "strict-line-correlation";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    const strictLineKey = "org.example.EventsController#create:10";

    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: {
            requestTimeoutMs: 10_000,
            retryMax: 1,
            orchestrator: {
              resumePollMax: 30,
              resumePollIntervalMs: 10_000,
              resumePollTimeoutMs: 300_000,
            },
          },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
        },
      ],
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
          selectors: {
            fqcn: "org.example.EventsController",
            method: "create",
            sourceRoot: "src/main/java",
          },
          runtimeVerification: { strictProbeKey: strictLineKey, probeId: "event-service" },
        },
      ],
      prerequisites: [
        {
          key: "apiBaseUrl",
          required: true,
          secret: false,
          provisioning: "user_input",
          default: "http://localhost:8080",
        },
      ],
      steps: [
        {
          order: 1,
          id: "create_event",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
          expect: [
            {
              id: "outcome_ok",
              actualPath: "status",
              operator: "outcome_status",
              expected: "pass",
            },
          ],
        },
      ],
      correlation: {
        enabled: true,
        key: { type: "messageId", value: "event-1" },
        window: { maxWindowMs: 60_000 },
        probeIds: ["event-service"],
        strictLineExpectations: [
          {
            sequenceOrder: 1,
            strictLineKey,
            selectorPolicy: "exact_instance",
            operator: "exact",
            expectedHitDelta: 1,
          },
        ],
        matchPolicy: {
          requireExactKeyMatch: true,
          requireWindowMatch: true,
          ambiguityStrategy: "fail_closed",
        },
      },
    });

    let transportCalls = 0;
    let statusCalls = 0;
    const out = await executeRegressionPlanWorkflow({
      workspaceRootAbs: root,
      planName,
      mcpInvoke: async ({
        toolName,
        input,
      }: {
        toolName: string;
        input: Record<string, unknown>;
      }) => {
        if (toolName === "transport_execute") {
          transportCalls += 1;
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 8,
              body: '{"ok":true}',
              bodyPreview: '{"ok":true}',
            },
          };
        }
        assert.equal(toolName, "probe");
        if (input.action === "reset") {
          return { structuredContent: { ok: true } };
        }
        if (input.action === "status") {
          statusCalls += 1;
          return {
            structuredContent: {
              response: {
                status: 200,
                json: {
                  hitCount: statusCalls === 1 ? 0 : 1,
                  runtime: { sessionId: "", runtimeInstanceId: "runtime-1" },
                },
              },
            },
          };
        }
        if (input.action === "wait_for_hit") {
          return { structuredContent: { result: { hit: true } } };
        }
        throw new Error(`unexpected probe action: ${String(input.action)}`);
      },
    });

    assert.equal(out.status, "executed");
    assert.equal(transportCalls, 1);
    assert.equal(statusCalls, 2);
    if (out.status === "executed") {
      assert.equal(out.runStatus, "pass");
      assert.equal(out.executionResult.steps[0].status, "pass");
    }
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
