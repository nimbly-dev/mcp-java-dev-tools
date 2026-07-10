import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  buildLineKey,
  eventControllerFqcn,
  eventControllerSourceFileAbs,
  eventListenerFqcn,
  eventListenerSourceFileAbs,
  findLineNumberBySnippet,
  startEventAppWithAgent,
  startMcpClient,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

type ToolResult = {
  structuredContent?: Record<string, unknown>;
};

async function writeJson(filePath: string, payload: Record<string, unknown>): Promise<void> {
  const nextPayload =
    path.basename(filePath) === "projects.json" && Array.isArray(payload.workspaces)
      ? {
          ...payload,
          workspaces: payload.workspaces.map((workspace) => {
            if (!workspace || typeof workspace !== "object" || Array.isArray(workspace)) return workspace;
            const defaults =
              "defaults" in workspace && workspace.defaults && typeof workspace.defaults === "object"
                ? workspace.defaults
                : {};
            const orchestrator =
              "orchestrator" in defaults && defaults.orchestrator && typeof defaults.orchestrator === "object"
                ? defaults.orchestrator
                : {
                    resumePollMax: 30,
                    resumePollIntervalMs: 10000,
                    resumePollTimeoutMs: 300000,
                  };
            return {
              ...workspace,
              defaults: {
                ...defaults,
                orchestrator,
              },
            };
          }),
        }
      : payload;
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(nextPayload, null, 2)}\n`, "utf8");
}

async function callTool(
  mcp: Awaited<ReturnType<typeof startMcpClient>>,
  name: string,
  args: Record<string, unknown>,
): Promise<ToolResult> {
  return (await mcp.client.callTool({
    name,
    arguments: args,
  })) as ToolResult;
}

test("mcp IT: execution_orchestration preserves probe.hit for authenticated async event trigger on controller and listener targets", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-event-async-probe-line-hit-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "event-async-probe-project";
  const projectRootAbs = workspaceRootAbs;

  const controllerLine = await findLineNumberBySnippet(
    eventControllerSourceFileAbs,
    "return triggerService.triggerIndex(request, authentication.getName());",
  );
  const listenerLine = await findLineNumberBySnippet(
    eventListenerSourceFileAbs,
    'processingStore.markProcessed(',
  );
  const controllerStrictProbeKey = buildLineKey({
    fqcn: eventControllerFqcn,
    methodName: "triggerIndex",
    line: controllerLine,
  });
  const listenerStrictProbeKey = buildLineKey({
    fqcn: eventListenerFqcn,
    methodName: "receiveEvent",
    line: listenerLine,
  });

  let runtime: Awaited<ReturnType<typeof startEventAppWithAgent>> | undefined;
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

  try {
    runtime = await startEventAppWithAgent();
    const activeRuntime = runtime;
    const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
    await writeJson(probeConfigAbs, {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            "event-app": {
              baseUrl: activeRuntime.probeBaseUrl,
              include: ["com.example.social.**"],
              exclude: ["**.config.**"],
            },
          },
        },
      },
      workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
    });

    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: projectRootAbs,
          executionProfiles: [
            {
              executionProfile: "controller-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "controller-plan", onFail: "inherit" }],
            },
            {
              executionProfile: "listener-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "listener-plan", onFail: "inherit" }],
            },
          ],
        },
      ],
    });

    async function writePlan(args: {
      planName: string;
      strictProbeKey: string;
      expectedStatusCode: number;
    }): Promise<void> {
      const planRootAbs = path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        args.planName,
      );
      await writeJson(path.join(planRootAbs, "metadata.json"), {
        specVersion: "1.0.0",
        execution: {
          intent: "regression",
          probeVerification: true,
          pinStrictProbeKey: true,
          discoveryPolicy: "disabled",
        },
      });
      await writeJson(path.join(planRootAbs, "contract.json"), {
        targets: [
          {
            type: "class_method",
            selectors: {
              fqcn: args.strictProbeKey.startsWith(eventControllerFqcn) ? eventControllerFqcn : eventListenerFqcn,
              method: args.strictProbeKey.startsWith(eventControllerFqcn) ? "triggerIndex" : "receiveEvent",
              sourceRoot: "test/fixtures/spring-apps/social-platform/event-service/event-app",
            },
            runtimeVerification: {
              strictProbeKey: args.strictProbeKey,
              probeId: "event-app",
            },
          },
        ],
        prerequisites: [
          {
            key: "apiBaseUrl",
            required: true,
            secret: false,
            provisioning: "user_input",
            default: activeRuntime.apiBaseUrl,
          },
        ],
        steps: [
          {
            order: 1,
            id: "trigger_event",
            targetRef: 0,
            protocol: "http",
            transport: {
              http: {
                method: "POST",
                pathTemplate: "/api/v1/events/trigger",
                headers: {
                  Authorization: "Bearer alice-token",
                },
                body: {
                  context: "entities",
                  type: "TriggerIndex",
                  groupId: "group-001",
                  source: "event-fixture-api",
                  dataFormatVersion: 1,
                  dataId: "tenant-batch-01",
                  data: ["tenant-social-001"],
                  notes: "Trigger reindex per tenant",
                },
                timeoutMs: 5_000,
              },
            },
            expect: [
              {
                id: "http_ok",
                actualPath: "response.statusCode",
                operator: "field_equals",
                expected: args.expectedStatusCode,
              },
              {
                id: "response_event_id",
                actualPath: "response.body",
                operator: "contains",
                expected: "evt-",
              },
              {
                id: "probe_hit",
                actualPath: "probe.hit",
                operator: "probe_line_hit",
                expected: true,
              },
            ],
          },
        ],
      });
    }

    await writePlan({
      planName: "controller-plan",
      strictProbeKey: controllerStrictProbeKey,
      expectedStatusCode: 200,
    });
    await writePlan({
      planName: "listener-plan",
      strictProbeKey: listenerStrictProbeKey,
      expectedStatusCode: 200,
    });

    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: activeRuntime.probeBaseUrl,
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const controllerOut = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "controller-run",
      },
    });
    const listenerOut = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "listener-run",
      },
    });

    assert.equal(controllerOut.structuredContent?.resultType, "execution_orchestration");
    assert.equal(listenerOut.structuredContent?.resultType, "execution_orchestration");

    async function readLatestExecutionResult(planName: string) {
      const runRootAbs = path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        planName,
        "runs",
      );
      const runIds = await fs.readdir(runRootAbs);
      assert.equal(runIds.length, 1);
      return JSON.parse(
        await fs.readFile(path.join(runRootAbs, runIds[0]!, "execution.result.json"), "utf8"),
      ) as {
        status: string;
        steps: Array<{
          status: string;
          statusCode: number;
          assertions: Array<{
            actualPath: string;
            status: string;
            actual?: unknown;
          }>;
        }>;
      };
    }

    const controllerExecution = await readLatestExecutionResult("controller-plan");
    const listenerExecution = await readLatestExecutionResult("listener-plan");

    assert.equal(controllerOut.structuredContent?.status, "pass");
    assert.equal(controllerExecution.status, "pass");
    assert.equal(controllerExecution.steps[0]?.status, "pass");
    assert.equal(controllerExecution.steps[0]?.assertions[2]?.actualPath, "probe.hit");
    assert.equal(controllerExecution.steps[0]?.assertions[2]?.status, "pass");
    assert.equal(controllerExecution.steps[0]?.assertions[2]?.actual, true);

    assert.equal(listenerOut.structuredContent?.status, "pass");
    assert.equal(listenerExecution.status, "pass");
    assert.equal(listenerExecution.steps[0]?.status, "pass");
    assert.equal(listenerExecution.steps[0]?.assertions[2]?.actualPath, "probe.hit");
    assert.equal(listenerExecution.steps[0]?.assertions[2]?.status, "pass");
    assert.equal(listenerExecution.steps[0]?.assertions[2]?.actual, true);
  } finally {
    await mcp?.close();
    await runtime?.stop();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
