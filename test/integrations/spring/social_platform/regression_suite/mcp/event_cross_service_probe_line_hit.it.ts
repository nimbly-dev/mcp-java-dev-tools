import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import path from "node:path";
import test from "node:test";

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

type ToolResult = {
  structuredContent?: Record<string, unknown>;
};

async function writeJson(filePath: string, payload: Record<string, unknown>): Promise<void> {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
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

test("mcp IT: execution_orchestration preserves probe.hit across cross-service event trigger and downstream listener verification", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-event-cross-service-probe-line-hit-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "event-cross-service-probe-project";
  const projectRootAbs = workspaceRootAbs;

  const producerLine = await findLineNumberBySnippet(
    eventProducerControllerSourceFileAbs,
    "return triggerService.triggerIndex(request, authentication.getName());",
  );
  const consumerListenerLine = await findLineNumberBySnippet(
    eventConsumerListenerSourceFileAbs,
    "processingStore.markProcessed(event.eventId());",
  );
  const producerStrictProbeKey = buildLineKey({
    fqcn: eventProducerControllerFqcn,
    methodName: "triggerIndex",
    line: producerLine,
  });
  const consumerStrictProbeKey = buildLineKey({
    fqcn: eventConsumerListenerFqcn,
    methodName: "receiveEvent",
    line: consumerListenerLine,
  });

  let consumerRuntime: Awaited<ReturnType<typeof startEventConsumerAppWithAgent>> | undefined;
  let producerRuntime: Awaited<ReturnType<typeof startEventProducerAppWithAgent>> | undefined;
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

  try {
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

    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: projectRootAbs,
          executionProfiles: [
            {
              executionProfile: "cross-service-event-run",
              executionPolicy: "stop_on_fail",
              plans: [
                { order: 1, planName: "producer-trigger-plan", onFail: "inherit" },
                { order: 2, planName: "consumer-listener-plan", onFail: "inherit" },
              ],
            },
          ],
        },
      ],
    });

    async function writePlan(args: {
      planName: string;
      strictProbeKey: string;
      probeId: string;
      fqcn: string;
      method: string;
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
              fqcn: args.fqcn,
              method: args.method,
              sourceRoot: "test/fixtures/spring-apps/social-platform/event-service",
            },
            runtimeVerification: {
              strictProbeKey: args.strictProbeKey,
              probeId: args.probeId,
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
      planName: "producer-trigger-plan",
      strictProbeKey: producerStrictProbeKey,
      probeId: "event-producer-app",
      fqcn: eventProducerControllerFqcn,
      method: "triggerIndex",
    });
    await writePlan({
      planName: "consumer-listener-plan",
      strictProbeKey: consumerStrictProbeKey,
      probeId: "event-consumer-app",
      fqcn: eventConsumerListenerFqcn,
      method: "receiveEvent",
    });

    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: producerRuntime.probeBaseUrl,
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "cross-service-event-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(
      out.structuredContent?.status,
      "pass",
      JSON.stringify(
        {
          orchestration: out.structuredContent,
          producerLogs: producerRuntime.logs(),
          consumerLogs: consumerRuntime.logs(),
        },
        null,
        2,
      ),
    );

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
          assertions: Array<{
            actualPath: string;
            status: string;
            actual?: unknown;
          }>;
        }>;
      };
    }

    const producerExecution = await readLatestExecutionResult("producer-trigger-plan");
    const consumerExecution = await readLatestExecutionResult("consumer-listener-plan");

    assert.equal(producerExecution.status, "pass");
    assert.equal(producerExecution.steps[0]?.status, "pass");
    assert.equal(producerExecution.steps[0]?.assertions[2]?.actualPath, "probe.hit");
    assert.equal(producerExecution.steps[0]?.assertions[2]?.status, "pass");
    assert.equal(producerExecution.steps[0]?.assertions[2]?.actual, true);

    assert.equal(consumerExecution.status, "pass");
    assert.equal(consumerExecution.steps[0]?.status, "pass");
    assert.equal(consumerExecution.steps[0]?.assertions[2]?.actualPath, "probe.hit");
    assert.equal(consumerExecution.steps[0]?.assertions[2]?.status, "pass");
    assert.equal(consumerExecution.steps[0]?.assertions[2]?.actual, true);
  } finally {
    await mcp?.close();
    await producerRuntime?.stop();
    await consumerRuntime?.stop();
    if (process.env.KEEP_EVENT_CROSS_SERVICE_TMP !== "1" && fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
