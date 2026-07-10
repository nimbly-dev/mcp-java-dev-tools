const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const { executeRegressionRuntimeSuite } = require("@tools-feature-regression-suite");
const {
  createTestTempDir,
  writeJson,
  writeCorrelatedPlan,
  writeAuthenticatedStrictProbeCorrelatedPlan,
} = require("./regression-runtime-suite-executor.fixture");

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
        path.join(root, ".mcpjvm", projectName, "plans", "regression", "producer-plan", "runs", String(producerRunId), "execution.result.json"),
        "utf8",
      ),
    );
    const producerCorrelation = JSON.parse(
      fs.readFileSync(
        path.join(root, ".mcpjvm", projectName, "plans", "regression", "producer-plan", "runs", String(producerRunId), "correlation", "correlation.json"),
        "utf8",
      ),
    );
    const consumerExecution = JSON.parse(
      fs.readFileSync(
        path.join(root, ".mcpjvm", projectName, "plans", "regression", "consumer-plan", "runs", String(consumerRunId), "execution.result.json"),
        "utf8",
      ),
    );
    const consumerCorrelation = JSON.parse(
      fs.readFileSync(
        path.join(root, ".mcpjvm", projectName, "plans", "regression", "consumer-plan", "runs", String(consumerRunId), "correlation", "correlation.json"),
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

test("executeRegressionRuntimeSuite reproduces ordered cross-service flow where trigger plan passes and second plan fails probe verification", async () => {
  const root = createTestTempDir("runtime-suite-auth-cross-service-ordered-fail");
  try {
    const projectName = "petclinic-regression";
    const executionProfile = "authenticated-cross-service-ordered-flow";
    const correlationSessionId = "cross-service-ordered-flow";
    const authEnvFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(authEnvFile), { recursive: true });
    fs.writeFileSync(authEnvFile, "AUTH_BEARER_TOKEN=alice-token\n", "utf8");
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile,
              executionPolicy: "continue_on_fail",
              plans: [
                { order: 1, planName: "trigger-plan" },
                { order: 2, planName: "consumer-plan" },
              ],
            },
          ],
        },
      ],
    });
    writeAuthenticatedStrictProbeCorrelatedPlan(root, projectName, "trigger-plan", "/produce", {
      method: "POST",
      probeId: "producer-service",
      strictProbeKey: "org.example.ProducerController#create:42",
      correlationSessionId,
      expectedFlow: ["producer-service", "consumer-service"],
      body: { kind: "created" },
      verifyProbe: false,
    });
    writeAuthenticatedStrictProbeCorrelatedPlan(root, projectName, "consumer-plan", "/consume", {
      method: "GET",
      probeId: "consumer-service",
      strictProbeKey: "org.example.ConsumerListener#accept:88",
      correlationSessionId,
      expectedFlow: ["producer-service", "consumer-service"],
    });

    const probeWaits: Array<{ key: string; probeId: string }> = [];

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          const req = input.request as Record<string, unknown>;
          const url = String(req.url ?? "");
          const headers = req.headers as Record<string, unknown>;
          assert.equal(headers.Authorization, "Bearer alice-token");
          if (url.includes("/produce")) {
            return {
              structuredContent: {
                status: "pass",
                statusCode: 200,
                durationMs: 7,
                body: "{\"id\":\"evt-123\",\"ok\":true}",
                bodyPreview: "{\"id\":\"evt-123\",\"ok\":true}",
              },
            };
          }
          if (url.includes("/consume")) {
            return {
              structuredContent: {
                status: "pass",
                statusCode: 200,
                durationMs: 8,
                body: "{\"processed\":true}",
                bodyPreview: "{\"processed\":true}",
              },
            };
          }
          throw new Error(`unexpected transport url: ${url}`);
        }
        if (toolName === "probe") {
          const action = input.action;
          const probeInput = input.input as Record<string, unknown>;
          const key = String(probeInput.key ?? "");
          const probeId = String(probeInput.probeId ?? "");
          if (action === "reset") {
            return { structuredContent: { status: "pass", result: { ok: true } } };
          }
          if (action === "wait_for_hit") {
            probeWaits.push({ key, probeId });
            return {
              structuredContent: {
                status: "pass",
                result: {
                  hit: false,
                  reasonCode: "timeout_no_inline_hit",
                  nextAction: "verify_trigger_path_or_branch_then_rerun_probe_wait_for_hit",
                },
              },
            };
          }
          if (action === "check") {
            return { structuredContent: { status: "pass", result: { ok: true } } };
          }
          throw new Error(`unexpected probe action: ${String(action)}`);
        }
        throw new Error(`unexpected tool: ${toolName}`);
      },
    });

    assert.equal(out.status, "partial_fail");
    assert.equal(out.planRuns.length, 2);
    assert.equal(out.planRuns[0].planName, "trigger-plan");
    assert.equal(out.planRuns[0].status, "executed");
    assert.equal(out.planRuns[0].runStatus, "pass");
    assert.equal(out.planRuns[1].planName, "consumer-plan");
    assert.equal(out.planRuns[1].status, "executed");
    assert.equal(out.planRuns[1].runStatus, "fail");
    assert.deepEqual(probeWaits, [{ key: "org.example.ConsumerListener#accept:88", probeId: "consumer-service" }]);
    assert.equal(Array.isArray(out.correlations), true);
    assert.equal(out.correlations?.length, 1);
    assert.equal(out.correlations?.[0].status, "ok");
    assert.equal(out.correlations?.[0].reasonCode, "ok");
    assert.equal(out.correlations?.[0].correlationSessionId, correlationSessionId);

    const triggerRunId = out.planRuns.find((entry: { planName: string; runId?: string }) => entry.planName === "trigger-plan")?.runId;
    const consumerRunId = out.planRuns.find((entry: { planName: string; runId?: string }) => entry.planName === "consumer-plan")?.runId;
    assert.ok(triggerRunId);
    assert.ok(consumerRunId);

    const triggerExecution = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "trigger-plan", "runs", String(triggerRunId), "execution.result.json"), "utf8"),
    );
    const triggerEvidence = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "trigger-plan", "runs", String(triggerRunId), "evidence.json"), "utf8"),
    );
    const consumerExecution = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "consumer-plan", "runs", String(consumerRunId), "execution.result.json"), "utf8"),
    );
    const consumerEvidence = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "consumer-plan", "runs", String(consumerRunId), "evidence.json"), "utf8"),
    );

    assert.equal(triggerExecution.steps[0].status, "pass");
    assert.equal(triggerExecution.steps[0].statusCode, 200);
    assert.equal(triggerExecution.steps[0].assertions.length, 1);
    assert.equal(triggerExecution.steps[0].assertions[0].status, "pass");
    assert.equal(triggerEvidence.correlationPolicy.keyValue, "evt-123");
    assert.equal(typeof triggerEvidence.correlationPolicy.keyExtractionReasonCode, "undefined");
    assert.equal(Array.isArray(triggerEvidence.correlationEvents), true);
    assert.equal(triggerEvidence.correlationEvents.length, 1);
    assert.equal(triggerEvidence.correlationEvents[0].probeId, "producer-service");
    assert.equal(triggerEvidence.correlationEvents[0].keyValue, "evt-123");

    assert.equal(consumerExecution.steps[0].status, "fail_assertion");
    assert.equal(consumerExecution.steps[0].statusCode, 200);
    assert.equal(consumerExecution.steps[0].assertions[1].actualPath, "probe.hit");
    assert.equal(consumerExecution.steps[0].assertions[1].status, "fail");
    assert.equal(consumerExecution.steps[0].assertions[1].actual, false);
    assert.equal(typeof consumerEvidence.correlationPolicy.keyValue, "undefined");
    assert.equal(consumerEvidence.correlationPolicy.keyExtractionReasonCode, "correlation_key_extraction_failed");
    assert.equal(Array.isArray(consumerEvidence.correlationEvents), true);
    assert.equal(consumerEvidence.correlationEvents.length, 1);
    assert.equal(consumerEvidence.correlationEvents[0].probeId, "consumer-service");
    assert.equal(typeof consumerEvidence.correlationEvents[0].keyValue, "undefined");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite carries forward a resolved cross-plan correlation key into later plan context", async () => {
  const root = createTestTempDir("runtime-suite-auth-cross-service-dynamic-correlation-key");
  try {
    const projectName = "petclinic-regression";
    const executionProfile = "authenticated-cross-service-dynamic-correlation-key";
    const correlationSessionId = "cross-service-dynamic-key-flow";
    const authEnvFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(authEnvFile), { recursive: true });
    fs.writeFileSync(authEnvFile, "AUTH_BEARER_TOKEN=alice-token\n", "utf8");
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile,
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
    writeAuthenticatedStrictProbeCorrelatedPlan(root, projectName, "producer-plan", "/produce", {
      method: "POST",
      probeId: "producer-service",
      strictProbeKey: "org.example.ProducerController#create:42",
      correlationSessionId,
      expectedFlow: ["producer-service", "consumer-service"],
      body: { kind: "created" },
      verifyProbe: false,
      correlationSourcePath: "response.body.id",
    });
    writeAuthenticatedStrictProbeCorrelatedPlan(root, projectName, "consumer-plan", "/consume", {
      method: "GET",
      probeId: "consumer-service",
      strictProbeKey: "org.example.ConsumerListener#accept:88",
      correlationSessionId,
      expectedFlow: ["producer-service", "consumer-service"],
      verifyProbe: false,
      correlationKeyValue: `\${suite.correlation.${correlationSessionId}.keyValue}`,
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        const url = String(req.url ?? "");
        const headers = req.headers as Record<string, unknown>;
        assert.equal(headers.Authorization, "Bearer alice-token");
        if (url.includes("/produce")) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 7,
              body: "{\"id\":\"evt-123\",\"ok\":true}",
              bodyPreview: "{\"id\":\"evt-123\",\"ok\":true}",
            },
          };
        }
        if (url.includes("/consume")) {
          return {
            structuredContent: {
              status: "pass",
              statusCode: 200,
              durationMs: 8,
              body: "{\"processed\":true}",
              bodyPreview: "{\"processed\":true}",
            },
          };
        }
        throw new Error(`unexpected transport url: ${url}`);
      },
    });

    assert.equal(out.status, "pass");
    assert.equal(out.correlations?.length, 1);
    assert.equal(out.correlations?.[0].status, "ok");
    assert.equal(out.correlations?.[0].keyValue, "evt-123");

    const producerRunId = out.planRuns.find((entry: { planName: string; runId?: string }) => entry.planName === "producer-plan")?.runId;
    const consumerRunId = out.planRuns.find((entry: { planName: string; runId?: string }) => entry.planName === "consumer-plan")?.runId;
    assert.ok(producerRunId);
    assert.ok(consumerRunId);

    const consumerEvidence = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "consumer-plan", "runs", String(consumerRunId), "evidence.json"), "utf8"),
    );

    assert.equal(consumerEvidence.correlationPolicy.keyValue, "evt-123");
    assert.equal(typeof consumerEvidence.correlationPolicy.keyExtractionReasonCode, "undefined");
    assert.equal(consumerEvidence.correlationEvents[0].keyValue, "evt-123");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite reproduces authenticated cross-service probe misses with empty correlation evidence", async () => {
  const root = createTestTempDir("runtime-suite-auth-cross-service-probe-miss");
  try {
    const projectName = "petclinic-regression";
    const executionProfile = "authenticated-cross-service-flow";
    const correlationSessionId = "cross-service-order-flow";
    const authEnvFile = path.join(root, ".mcpjvm", projectName, ".env");
    fs.mkdirSync(path.dirname(authEnvFile), { recursive: true });
    fs.writeFileSync(authEnvFile, "AUTH_BEARER_TOKEN=alice-token\n", "utf8");
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          envFile: `.mcpjvm/${projectName}/.env`,
          variables: { bearerTokenEnv: "AUTH_BEARER_TOKEN" },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile,
              executionPolicy: "continue_on_fail",
              plans: [
                { order: 1, planName: "producer-plan" },
                { order: 2, planName: "consumer-plan" },
              ],
            },
          ],
        },
      ],
    });
    writeAuthenticatedStrictProbeCorrelatedPlan(root, projectName, "producer-plan", "/produce", {
      method: "POST",
      probeId: "producer-service",
      strictProbeKey: "org.example.ProducerController#create:42",
      correlationSessionId,
      expectedFlow: ["producer-service", "consumer-service"],
      body: { kind: "created" },
    });
    writeAuthenticatedStrictProbeCorrelatedPlan(root, projectName, "consumer-plan", "/consume", {
      method: "GET",
      probeId: "consumer-service",
      strictProbeKey: "org.example.ConsumerListener#accept:88",
      correlationSessionId,
      expectedFlow: ["producer-service", "consumer-service"],
    });

    const probeResets: Array<{ key: string; probeId: string }> = [];
    const probeWaits: Array<{ key: string; probeId: string }> = [];

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        if (toolName === "transport_execute") {
          const req = input.request as Record<string, unknown>;
          const url = String(req.url ?? "");
          const headers = req.headers as Record<string, unknown>;
          assert.equal(headers.Authorization, "Bearer alice-token");
          if (url.includes("/produce")) {
            return {
              structuredContent: {
                status: "pass",
                statusCode: 200,
                durationMs: 7,
                body: "{\"id\":\"evt-123\",\"ok\":true}",
                bodyPreview: "{\"id\":\"evt-123\",\"ok\":true}",
              },
            };
          }
          if (url.includes("/consume")) {
            return {
              structuredContent: {
                status: "pass",
                statusCode: 200,
                durationMs: 8,
                body: "{\"processed\":true}",
                bodyPreview: "{\"processed\":true}",
              },
            };
          }
          throw new Error(`unexpected transport url: ${url}`);
        }
        if (toolName === "probe") {
          const action = input.action;
          const probeInput = input.input as Record<string, unknown>;
          const key = String(probeInput.key ?? "");
          const probeId = String(probeInput.probeId ?? "");
          if (action === "reset") {
            probeResets.push({ key, probeId });
            return { structuredContent: { status: "pass", result: { ok: true } } };
          }
          if (action === "wait_for_hit") {
            probeWaits.push({ key, probeId });
            return {
              structuredContent: {
                status: "pass",
                result: {
                  hit: false,
                  reasonCode: "timeout_no_inline_hit",
                  nextAction: "verify_trigger_path_or_branch_then_rerun_probe_wait_for_hit",
                },
              },
            };
          }
          if (action === "check") {
            return { structuredContent: { status: "pass", result: { ok: true } } };
          }
          throw new Error(`unexpected probe action: ${String(action)}`);
        }
        throw new Error(`unexpected tool: ${toolName}`);
      },
    });

    assert.equal(out.status, "partial_fail");
    assert.equal(out.planRuns.length, 2);
    assert.equal(out.planRuns[0].status, "executed");
    assert.equal(out.planRuns[0].runStatus, "fail");
    assert.equal(out.planRuns[1].status, "executed");
    assert.equal(out.planRuns[1].runStatus, "fail");
    assert.equal(Array.isArray(out.correlations), true);
    assert.equal(out.correlations?.length, 1);
    assert.equal(out.correlations?.[0].status, "ok");
    assert.equal(out.correlations?.[0].reasonCode, "ok");
    assert.equal(out.correlations?.[0].correlationSessionId, correlationSessionId);
    assert.deepEqual(probeResets, [
      { key: "org.example.ProducerController#create:42", probeId: "producer-service" },
      { key: "org.example.ConsumerListener#accept:88", probeId: "consumer-service" },
    ]);
    assert.deepEqual(probeWaits, [
      { key: "org.example.ProducerController#create:42", probeId: "producer-service" },
      { key: "org.example.ConsumerListener#accept:88", probeId: "consumer-service" },
    ]);

    const producerRunId = out.planRuns.find((entry: { planName: string; runId?: string }) => entry.planName === "producer-plan")?.runId;
    const consumerRunId = out.planRuns.find((entry: { planName: string; runId?: string }) => entry.planName === "consumer-plan")?.runId;
    assert.ok(producerRunId);
    assert.ok(consumerRunId);

    const producerExecution = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "producer-plan", "runs", String(producerRunId), "execution.result.json"), "utf8"),
    );
    const producerEvidence = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "producer-plan", "runs", String(producerRunId), "evidence.json"), "utf8"),
    );
    const producerCorrelation = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "producer-plan", "runs", String(producerRunId), "correlation", "correlation.json"), "utf8"),
    );
    const consumerExecution = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "consumer-plan", "runs", String(consumerRunId), "execution.result.json"), "utf8"),
    );
    const consumerEvidence = JSON.parse(
      fs.readFileSync(path.join(root, ".mcpjvm", projectName, "plans", "regression", "consumer-plan", "runs", String(consumerRunId), "evidence.json"), "utf8"),
    );

    assert.equal(producerExecution.steps[0].status, "fail_assertion");
    assert.equal(producerExecution.steps[0].statusCode, 200);
    assert.equal(producerExecution.steps[0].assertions[1].actualPath, "probe.hit");
    assert.equal(producerExecution.steps[0].assertions[1].status, "fail");
    assert.equal(producerExecution.steps[0].assertions[1].actual, false);
    assert.equal(consumerExecution.steps[0].status, "fail_assertion");
    assert.equal(consumerExecution.steps[0].statusCode, 200);
    assert.equal(consumerExecution.steps[0].assertions[1].actualPath, "probe.hit");
    assert.equal(consumerExecution.steps[0].assertions[1].status, "fail");
    assert.equal(consumerExecution.steps[0].assertions[1].actual, false);

    assert.equal(producerEvidence.correlationPolicy.correlationSessionId, correlationSessionId);
    assert.equal(producerEvidence.correlationPolicy.keySourceType, "json_path");
    assert.equal(producerEvidence.correlationPolicy.keySourcePath, "response.body.id");
    assert.equal(producerEvidence.correlationPolicy.keyValue, "evt-123");
    assert.equal(typeof producerEvidence.correlationPolicy.keyExtractionReasonCode, "undefined");
    assert.equal(Array.isArray(producerEvidence.correlationEvents), true);
    assert.equal(producerEvidence.correlationEvents.length, 1);
    assert.equal(producerEvidence.correlationEvents[0].probeId, "producer-service");
    assert.equal(producerEvidence.correlationEvents[0].keyValue, "evt-123");
    assert.equal(producerCorrelation.status, "ok");
    assert.equal(producerCorrelation.reasonCode, "ok");

    assert.equal(consumerEvidence.correlationPolicy.correlationSessionId, correlationSessionId);
    assert.equal(consumerEvidence.correlationPolicy.keySourceType, "json_path");
    assert.equal(consumerEvidence.correlationPolicy.keySourcePath, "response.body.id");
    assert.equal(Array.isArray(consumerEvidence.correlationEvents), true);
    assert.equal(consumerEvidence.correlationEvents.length, 1);
    assert.equal(consumerEvidence.correlationEvents[0].probeId, "consumer-service");
    assert.equal(typeof consumerEvidence.correlationEvents[0].keyValue, "undefined");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
