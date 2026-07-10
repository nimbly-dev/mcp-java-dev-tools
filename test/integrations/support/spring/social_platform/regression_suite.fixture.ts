import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import path from "node:path";

import {
  buildLineKey,
  eventConsumerListenerFqcn,
  eventConsumerListenerSourceFileAbs,
  eventProducerControllerFqcn,
  eventProducerControllerSourceFileAbs,
  findLineNumberBySnippet,
  startEventConsumerAppWithAgent,
  startEventProducerAppWithAgent,
  startMcpClient,
} from "@test/integrations/support/spring/social_platform/shared.fixture";

export type ToolResult = {
  structuredContent?: Record<string, unknown>;
};

type TriggerTarget = "producer" | "consumer";

type ExecutionResultShape = {
  status: string;
  steps: Array<{
    status: string;
    statusCode?: number;
    reasonCode?: string;
    reasonMeta?: Record<string, unknown>;
    assertions?: Array<{
      actualPath: string;
      status: string;
      actual?: unknown;
      expected?: unknown;
    }>;
  }>;
};

type EvidenceShape = Record<string, unknown>;
type CorrelationShape = Record<string, unknown>;

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

async function readJson<T>(filePath: string): Promise<T> {
  return JSON.parse(await fs.readFile(filePath, "utf8")) as T;
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

export async function startEventCrossServiceRegressionSuiteFixture(args?: {
  projectName?: string;
  tmpPrefix?: string;
  keepTmpEnvVar?: string;
}) {
  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), args?.tmpPrefix ?? "mcp-event-cross-service-regression-suite-it-"),
  );
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = args?.projectName ?? "event-cross-service-regression-project";
  const keepTmpEnvVar = args?.keepTmpEnvVar ?? "KEEP_EVENT_CROSS_SERVICE_REGRESSION_TMP";

  const producerLine = await findLineNumberBySnippet(
    eventProducerControllerSourceFileAbs,
    "return triggerService.triggerIndex(request, authentication.getName());",
  );
  const consumerLine = await findLineNumberBySnippet(
    eventConsumerListenerSourceFileAbs,
    "processingStore.markProcessed(",
  );
  const producerStrictProbeKey = buildLineKey({
    fqcn: eventProducerControllerFqcn,
    methodName: "triggerIndex",
    line: producerLine,
  });
  const consumerStrictProbeKey = buildLineKey({
    fqcn: eventConsumerListenerFqcn,
    methodName: "receiveEvent",
    line: consumerLine,
  });

  let consumerRuntime: Awaited<ReturnType<typeof startEventConsumerAppWithAgent>> | undefined;
  let producerRuntime: Awaited<ReturnType<typeof startEventProducerAppWithAgent>> | undefined;
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

  consumerRuntime = await startEventConsumerAppWithAgent();
  producerRuntime = await startEventProducerAppWithAgent({
    consumerBaseUrl: consumerRuntime.apiBaseUrl,
  });

  const activeConsumerRuntime = consumerRuntime;
  const activeProducerRuntime = producerRuntime;
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  await writeJson(probeConfigAbs, {
    defaultProfile: "dev",
    profiles: {
      dev: {
        probes: {
          "event-producer-app": {
            baseUrl: activeProducerRuntime.probeBaseUrl,
            include: ["com.example.social.**"],
            exclude: ["**.config.**"],
          },
          "event-consumer-app": {
            baseUrl: activeConsumerRuntime.probeBaseUrl,
            include: ["com.example.social.**"],
            exclude: ["**.config.**"],
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  });

  mcp = await startMcpClient({
    workspaceRootAbs,
    probeBaseUrl: activeProducerRuntime.probeBaseUrl,
    extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
  });

  async function writeExecutionProfile(args: {
    executionProfile: string;
    executionPolicy: "stop_on_fail" | "continue_on_fail";
    plans: Array<{ order: number; planName: string; onFail?: "inherit" | "stop" | "continue" }>;
  }): Promise<void> {
    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: workspaceRootAbs,
          executionProfiles: [
            {
              executionProfile: args.executionProfile,
              executionPolicy: args.executionPolicy,
              plans: args.plans.map((plan) => ({
                order: plan.order,
                planName: plan.planName,
                onFail: plan.onFail ?? "inherit",
              })),
            },
          ],
        },
      ],
    });
  }

  async function writeTriggerPlan(args: {
    planName: string;
    target: TriggerTarget;
    probeId?: string;
    includeProbeExpectation?: boolean;
    correlation?: {
      correlationSessionId: string;
      expectedFlow: string[];
      keyMode: "explicit_message_id" | "response_body_id" | "suite_session_message_id" | "suite_last_message_id";
      explicitKeyValue?: string;
      responseJsonPath?: string;
    };
  }): Promise<void> {
    const planRootAbs = path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      args.planName,
    );
    const targetConfig =
      args.target === "producer"
        ? {
            fqcn: eventProducerControllerFqcn,
            method: "triggerIndex",
            sourceRoot: "test/fixtures/spring-apps/social-platform/event-service",
            strictProbeKey: producerStrictProbeKey,
          }
        : {
            fqcn: eventConsumerListenerFqcn,
            method: "receiveEvent",
            sourceRoot: "test/fixtures/spring-apps/social-platform/event-service",
            strictProbeKey: consumerStrictProbeKey,
          };

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
            fqcn: targetConfig.fqcn,
            method: targetConfig.method,
            sourceRoot: targetConfig.sourceRoot,
          },
          runtimeVerification: {
            strictProbeKey: targetConfig.strictProbeKey,
            ...(typeof args.probeId === "string" ? { probeId: args.probeId } : {}),
          },
        },
      ],
      prerequisites: [
        {
          key: "apiBaseUrl",
          required: true,
          secret: false,
          provisioning: "user_input",
          default: activeProducerRuntime.apiBaseUrl,
        },
      ],
      steps: [
        {
          order: 1,
          id: args.target === "producer" ? "producer_trigger_event" : "consumer_trigger_event",
          targetRef: 0,
          protocol: "http",
          transport: {
            http: {
              method: "POST",
              pathTemplate: "/api/v1/events/trigger",
              headers: {
                Authorization: "Bearer alice-token",
                "x-tenant-id": "tenant-social-001",
              },
              body: {
                context: "entities",
                type: "TriggerIndex",
                groupId: "group-001",
                source: "event-cross-service-api",
                dataFormatVersion: 1,
                dataId: "tenant-batch-01",
                data: ["tenant-social-001"],
                notes: "Trigger reindex per tenant",
              },
              timeoutMs: 10_000,
            },
          },
          expect: [
            {
              id: "http_ok",
              actualPath: "response.statusCode",
              operator: "field_equals",
              expected: 200,
            },
            {
              id: "response_event_id",
              actualPath: "response.body",
              operator: "contains",
              expected: "evt-",
            },
            ...(args.includeProbeExpectation === false
              ? []
              : [
                  {
                    id: "probe_hit",
                    actualPath: "probe.hit",
                    operator: "probe_line_hit",
                    expected: true,
                  },
                ]),
          ],
        },
      ],
      ...(args.correlation
        ? {
            correlation: {
              enabled: true,
              crossPlan: true,
                correlationSessionId: args.correlation.correlationSessionId,
              key:
                args.correlation.keyMode === "explicit_message_id"
                  ? { type: "messageId", value: args.correlation.explicitKeyValue ?? "evt-unknown" }
                  : args.correlation.keyMode === "suite_session_message_id"
                    ? {
                        type: "messageId",
                        value: `\${suite.correlation.${args.correlation.correlationSessionId}.keyValue}`,
                      }
                    : args.correlation.keyMode === "suite_last_message_id"
                      ? {
                          type: "messageId",
                          value: "${suite.correlation.last.keyValue}",
                        }
                  : {
                      type: "messageId",
                      source: {
                        type: "json_path",
                        path: args.correlation.responseJsonPath ?? "response.bodyJson.id",
                      },
                    },
              window: { maxWindowMs: 60_000 },
              probeIds:
                typeof args.probeId === "string"
                  ? [args.probeId]
                  : [],
              expectedFlow: args.correlation.expectedFlow,
              matchPolicy: {
                requireExactKeyMatch: true,
                requireWindowMatch: true,
                ambiguityStrategy: "fail_closed",
              },
            },
          }
        : {}),
    });
  }

  async function callExecutionOrchestration(executionProfile: string): Promise<ToolResult> {
    assert.ok(mcp);
    return await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile,
      },
    });
  }

  async function callProbe(action: string, input: Record<string, unknown>): Promise<ToolResult> {
    assert.ok(mcp);
    return await callTool(mcp, "probe", { action, input });
  }

  async function readLatestExecutionResult(planName: string): Promise<ExecutionResultShape> {
    const runId = await readLatestRunId(planName);
    return await readJson<ExecutionResultShape>(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        planName,
        "runs",
        runId,
        "execution.result.json",
      ),
    );
  }

  async function readLatestRunId(planName: string): Promise<string> {
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
    return runIds[0]!;
  }

  async function readLatestEvidence(planName: string): Promise<EvidenceShape> {
    const runId = await readLatestRunId(planName);
    return await readJson<EvidenceShape>(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        planName,
        "runs",
        runId,
        "evidence.json",
      ),
    );
  }

  async function readLatestCorrelation(planName: string): Promise<CorrelationShape> {
    const runId = await readLatestRunId(planName);
    return await readJson<CorrelationShape>(
      path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        planName,
        "runs",
        runId,
        "correlation",
        "correlation.json",
      ),
    );
  }

  async function executeProducerTriggerRequest() {
    const response = await fetch(`${activeProducerRuntime.apiBaseUrl}/api/v1/events/trigger`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: "Bearer alice-token",
        "x-tenant-id": "tenant-social-001",
      },
      body: JSON.stringify({
        context: "entities",
        type: "TriggerIndex",
        groupId: "group-001",
        source: "event-cross-service-api",
        dataFormatVersion: 1,
        dataId: "tenant-batch-01",
        data: ["tenant-social-001"],
        notes: "Trigger reindex per tenant",
      }),
    });
    const bodyText = await response.text();
    return {
      status: response.status,
      bodyText,
    };
  }

  async function stop(): Promise<void> {
    await mcp?.close();
    await producerRuntime?.stop();
    await consumerRuntime?.stop();
    if (process.env[keepTmpEnvVar] !== "1" && fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }

  return {
    workspaceRootAbs,
    projectName,
    producerStrictProbeKey,
    consumerStrictProbeKey,
    producerProbeId: "event-producer-app",
    consumerProbeId: "event-consumer-app",
    writeExecutionProfile,
    writeTriggerPlan,
    callExecutionOrchestration,
    callProbe,
    readLatestRunId,
    readLatestExecutionResult,
    readLatestEvidence,
    readLatestCorrelation,
    executeProducerTriggerRequest,
    stop,
  };
}
