const assert = require("node:assert/strict");
const path = require("node:path");
const http = require("node:http");
const fs = require("node:fs");
const test = require("node:test");

const {
  executeRegressionRuntimeSuite,
  readExecutionOrchestrationSuiteResult,
  writeExecutionOrchestrationSuiteResult,
} = require("@tools-feature-regression-suite");
const {
  createTestTempDir,
  writeJson,
  writePlan,
  writeAuthPlan,
  writeSadPathPlan,
} = require("./regression-runtime-suite-executor.fixture");

test("executeRegressionRuntimeSuite promotes only explicit non-secret suite extracts", async () => {
  const root = createTestTempDir("runtime-suite-context-promotion");
  try {
    const projectName = "petclinic-regression";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression");
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [{
            executionProfile: "suite-context-promotion",
            executionPolicy: "stop_on_fail",
            plans: [
              { order: 1, planName: "produce-context" },
              { order: 2, planName: "consume-context" },
            ],
          }],
        },
      ],
    });
    writeJson(path.join(planRoot, "produce-context", "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "produce-context", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "produce", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [{
        order: 1,
        id: "produce",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "POST", pathTemplate: "/produce" } },
        extract: [{ from: "response.bodyJson.id", as: "output.importJobId", required: true, scope: "suite", secret: false }],
        expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
      }],
    });
    writeJson(path.join(planRoot, "consume-context", "metadata.json"), {
      specVersion: "1.0.0",
      execution: { intent: "regression", probeVerification: false, pinStrictProbeKey: false, discoveryPolicy: "allow_discoverable_prerequisites" },
    });
    writeJson(path.join(planRoot, "consume-context", "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.Controller", method: "consume", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [{
        order: 1,
        id: "consume",
        targetRef: 0,
        protocol: "http",
        transport: { http: { method: "GET", pathTemplate: "/consume/${output.importJobId}" } },
        expect: [{ id: "readable", actualPath: "response.statusCode", operator: "field_equals", expected: 200 }],
      }],
    });

    const first = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "suite-context-promotion",
      maxPlansPerCall: 1,
      mcpInvoke: async ({ input }: { toolName: string; input: Record<string, unknown> }) => {
        const request = input.request as Record<string, unknown>;
        assert.equal(request.url, "http://localhost:8082/produce");
        return { structuredContent: { status: "pass", statusCode: 202, durationMs: 1, body: "{\"id\":\"job-42\"}", bodyPreview: "{\"id\":\"job-42\"}" } };
      },
    });
    assert.equal(first.status, "in_progress");
    assert.equal(first.suiteContext?.["output.importJobId"], "job-42");

    const second = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "suite-context-promotion",
      suiteRunId: first.suiteRunId,
      startPlanOrder: first.nextPlanOrder,
      priorPlanRuns: first.planRuns,
      priorSuiteContext: first.suiteContext,
      mcpInvoke: async ({ input }: { toolName: string; input: Record<string, unknown> }) => {
        const request = input.request as Record<string, unknown>;
        assert.equal(request.url, "http://localhost:8082/consume/job-42");
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 1, bodyPreview: "{}" } };
      },
    });
    assert.equal(second.status, "pass");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

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

test("executeRegressionRuntimeSuite resumes the same in_progress plan without duplicating planRuns", async () => {
  const root = createTestTempDir("runtime-suite-same-plan-resume");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-resume-plan";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 100, retryMax: 3 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "watcher-resume-suite",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName }],
            },
          ],
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
          extract: [{ from: "response.bodyJson.eventId", as: "eventId", required: true }],
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
              },
            },
          },
          waitPolicy: { timeoutMs: 1_000, retryMax: 3 },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    let stepCalls = 0;
    let watcherCalls = 0;
    const first = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "watcher-resume-suite",
      orchestrationTimeoutBudgetMs: 10,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          stepCalls += 1;
          return { structuredContent: { status: "pass", statusCode: 202, durationMs: 1, body: "{\"eventId\":\"evt-1\"}", bodyPreview: "{\"eventId\":\"evt-1\"}" } };
        }
        watcherCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 15));
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 1, body: "{\"state\":\"pending\"}", bodyPreview: "{\"state\":\"pending\"}" } };
      },
    });

    assert.equal(first.status, "in_progress");
    assert.equal(first.nextPlanOrder, 1);
    assert.equal(first.planRuns.length, 1);
    assert.equal(first.planRuns[0].runStatus, "in_progress");

    const second = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "watcher-resume-suite",
      suiteRunId: first.suiteRunId,
      startPlanOrder: first.nextPlanOrder,
      priorPlanRuns: first.planRuns,
      orchestrationTimeoutBudgetMs: 1_000,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          stepCalls += 1;
          return { structuredContent: { status: "pass", statusCode: 202, durationMs: 1, body: "{\"eventId\":\"evt-1\"}", bodyPreview: "{\"eventId\":\"evt-1\"}" } };
        }
        watcherCalls += 1;
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 1, body: "{\"state\":\"ready\"}", bodyPreview: "{\"state\":\"ready\"}" } };
      },
    });

    assert.equal(second.status, "pass");
    assert.equal(second.planRuns.length, 1);
    assert.equal(second.planRuns[0].runStatus, "pass");
    assert.equal(second.suiteRunId, first.suiteRunId);
    assert.equal(stepCalls, 1);
    assert.equal(watcherCalls, 2);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite builds bounded watcher progressSummary for in_progress plan waits", async () => {
  const root = createTestTempDir("runtime-suite-progress-summary-watcher");
  try {
    const projectName = "petclinic-regression";
    const planName = "watcher-progress-plan";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 100, retryMax: 3 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "watcher-progress-suite",
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
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.EventsController", method: "trigger", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
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
          waitPolicy: { timeoutMs: 1_000, retryMax: 3 },
          expect: [{ id: "indexed", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "watcher-progress-suite",
      orchestrationTimeoutBudgetMs: 10,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          return { structuredContent: { status: "pass", statusCode: 202, durationMs: 1, body: "{\"eventId\":\"evt-700\"}", bodyPreview: "{\"eventId\":\"evt-700\"}" } };
        }
        await new Promise((resolve) => setTimeout(resolve, 15));
        return { structuredContent: { status: "pass", statusCode: 200, durationMs: 1, body: "{\"state\":\"pending\"}", bodyPreview: "{\"state\":\"pending\"}" } };
      },
    });

    assert.equal(out.status, "in_progress");
    assert.equal(out.progressSummary?.progressState, "waiting_in_active_plan");
    assert.equal(out.progressSummary?.totalPlanCount, 1);
    assert.equal(out.progressSummary?.completedPlanCount, 0);
    assert.equal(out.progressSummary?.remainingPlanCount, 0);
    assert.equal(out.progressSummary?.activePlan?.planName, planName);
    assert.equal(out.progressSummary?.activePlan?.phase, "watchers");
    assert.equal(out.progressSummary?.activePlan?.triggerStatus, "pass");
    assert.equal(out.progressSummary?.activePlan?.watcherStatus, "in_progress");
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.targetType, "watcher");
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.targetId, "search_indexed");
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.currentIndex, 1);
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.totalCount, 1);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("executeRegressionRuntimeSuite builds bounded external verification progressSummary for in_progress plan waits", async () => {
  const root = createTestTempDir("runtime-suite-progress-summary-external-verification");
  try {
    const projectName = "petclinic-regression";
    const planName = "external-verification-progress-plan";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 100, retryMax: 2 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "external-verification-progress-suite",
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
      targets: [{ type: "class_method", selectors: { fqcn: "org.example.TasksController", method: "submit", sourceRoot: "src/main/java" } }],
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "submit_task",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/tasks" } },
          extract: [{ from: "response.bodyJson.taskId", as: "taskId", required: true }],
          expect: [{ id: "accepted", actualPath: "response.statusCode", operator: "field_equals", expected: 202 }],
        },
      ],
      externalVerification: [
        {
          id: "verify_task_completed",
          provider: { type: "http" },
          request: { http: { method: "GET", url: "http://localhost:8082/tasks/${taskId}" } },
          expect: [{ id: "completed", actualPath: "response.bodyJson.completed", operator: "field_equals", expected: true }],
        },
      ],
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "external-verification-progress-suite",
      orchestrationTimeoutBudgetMs: 10,
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const request = input.request as Record<string, unknown>;
        if (String(request.method) === "POST") {
          await new Promise((resolve) => setTimeout(resolve, 15));
          return { structuredContent: { status: "pass", statusCode: 202, durationMs: 1, body: "{\"taskId\":\"task-700\"}", bodyPreview: "{\"taskId\":\"task-700\"}" } };
        }
        throw new Error("external verification should not execute before bounded resume");
      },
    });

    assert.equal(out.status, "in_progress");
    assert.equal(out.progressSummary?.progressState, "waiting_in_active_plan");
    assert.equal(out.progressSummary?.activePlan?.planName, planName);
    assert.equal(out.progressSummary?.activePlan?.phase, "external_verification");
    assert.equal(out.progressSummary?.activePlan?.triggerStatus, "pass");
    assert.equal(out.progressSummary?.activePlan?.watcherStatus, "not_configured");
    assert.equal(out.progressSummary?.activePlan?.externalVerificationStatus, "in_progress");
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.targetType, "external_verification");
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.targetId, "verify_task_completed");
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.providerType, "http");
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.currentIndex, 1);
    assert.equal(out.progressSummary?.activePlan?.waitingOn?.totalCount, 1);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("writeExecutionOrchestrationSuiteResult persists and reloads bounded progressSummary", async () => {
  const root = createTestTempDir("runtime-suite-progress-summary-persist");
  try {
    const projectName = "petclinic-regression";
    const suiteRunId = "suite-progress-001";
    await writeExecutionOrchestrationSuiteResult({
      workspaceRootAbs: root,
      projectName,
      suite: {
        executionProfile: "watcher-progress-suite",
        executionPolicy: "stop_on_fail",
        status: "in_progress",
        suiteRunId,
        planRuns: [
          {
            order: 1,
            planName: "watcher-progress-plan",
            status: "executed",
            runStatus: "in_progress",
            runId: "run-001",
          },
        ],
        completedPlanCount: 0,
        progressSummary: {
          progressState: "waiting_in_active_plan",
          totalPlanCount: 1,
          completedPlanCount: 0,
          remainingPlanCount: 0,
          activePlan: {
            order: 1,
            planName: "watcher-progress-plan",
            runId: "run-001",
            phase: "watchers",
            phaseStartedAt: "2026-07-08T00:00:00.000Z",
            lastUpdatedAt: "2026-07-08T00:00:05.000Z",
            triggerStatus: "pass",
            watcherStatus: "in_progress",
            waitingOn: {
              targetType: "watcher",
              targetId: "search_indexed",
              providerType: "http",
              currentIndex: 1,
              totalCount: 1,
            },
          },
        },
      },
    });

    const persisted = await readExecutionOrchestrationSuiteResult({
      workspaceRootAbs: root,
      projectName,
      suiteRunId,
    });

    assert.equal(persisted?.progressSummary?.progressState, "waiting_in_active_plan");
    assert.equal(persisted?.progressSummary?.activePlan?.planName, "watcher-progress-plan");
    assert.equal(persisted?.progressSummary?.activePlan?.waitingOn?.targetId, "search_indexed");
    assert.equal(persisted?.progressSummary?.activePlan?.waitingOn?.providerType, "http");
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

test("executeRegressionRuntimeSuite surfaces watcher-only blocked reason details in planRuns", async () => {
  const root = createTestTempDir("runtime-suite-watcher-blocked-reason");
  try {
    const projectName = "petclinic-regression";
    const planName = "plan-watcher-block";
    const planRoot = path.join(root, ".mcpjvm", projectName, "plans", "regression", planName);
    writeJson(path.join(root, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: root,
          defaults: { requestTimeoutMs: 100, retryMax: 2 },
          runtimeContexts: [{ name: "terminal-cli", mode: "terminal", autoStart: false }],
          executionProfiles: [
            {
              executionProfile: "core-watcher-blocked",
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
      prerequisites: [{ key: "apiBaseUrl", required: true, secret: false, provisioning: "user_input", default: "http://localhost:8082" }],
      steps: [
        {
          order: 1,
          id: "step_1",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", pathTemplate: "/events" } },
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
                url: "http://127.0.0.1:1/index/status",
              },
            },
          },
          waitPolicy: { timeoutMs: 80, retryMax: 2 },
          expect: [{ id: "ready", actualPath: "response.bodyJson.state", operator: "field_equals", expected: "ready" }],
        },
      ],
    });

    const out = await executeRegressionRuntimeSuite({
      workspaceRootAbs: root,
      executionProfile: "core-watcher-blocked",
      mcpInvoke: async ({ toolName, input }: { toolName: string; input: Record<string, unknown> }) => {
        assert.equal(toolName, "transport_execute");
        const req = input.request as Record<string, unknown>;
        if (String(req.method) === "POST") {
          return { structuredContent: { status: "pass", statusCode: 202, durationMs: 7, bodyPreview: "{\"ok\":true}" } };
        }
        return { structuredContent: { status: "blocked_runtime", durationMs: 5, reasonCode: "connect_failed" } };
      },
    });

    assert.equal(out.status, "blocked");
    assert.equal(out.planRuns[0].status, "executed");
    assert.equal(out.planRuns[0].runStatus, "blocked");
    assert.equal(out.planRuns[0].blockedReasonCode, "watcher_target_unreachable");
    assert.equal(out.planRuns[0].blockedReasonMeta?.transportReasonCode, "connect_failed");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
