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
  options?: { timeoutMs?: number },
): Promise<ToolResult> {
  const request = {
    name,
    arguments: args,
  };
  if (typeof options?.timeoutMs === "number") {
    return (await mcp.client.callTool(request, undefined, { timeout: options.timeoutMs })) as ToolResult;
  }
  return (await mcp.client.callTool(request)) as ToolResult;
}

test(
  "mcp IT: execution_orchestration honors extended strict probe wait budget for long-running listener processing",
  { timeout: 240_000 },
  async () => {
    const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-event-long-listener-probe-late-hit-it-"));
    const workspaceRootAbs = path.join(tmpRoot, "workspace");
    const projectName = "event-long-listener-probe-project";
    const projectRootAbs = workspaceRootAbs;

    const consumerListenerLine = await findLineNumberBySnippet(
      eventConsumerListenerSourceFileAbs,
      "processingStore.markProcessed(event.eventId());",
    );
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

      const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
      await writeJson(probeConfigAbs, {
        defaultProfile: "dev",
        profiles: {
          dev: {
            probes: {
              "event-producer-app": {
                baseUrl: producerRuntime.probeBaseUrl,
                include: ["com.example.social.**"],
                exclude: ["**.config.**"],
              },
              "event-consumer-app": {
                baseUrl: consumerRuntime.probeBaseUrl,
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
                executionProfile: "long-listener-event-run",
                executionPolicy: "stop_on_fail",
                plans: [{ order: 1, planName: "long-listener-plan", onFail: "inherit" }],
              },
            ],
          },
        ],
      });

      const planRootAbs = path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        "long-listener-plan",
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
              fqcn: eventConsumerListenerFqcn,
              method: "receiveEvent",
              sourceRoot: "test/fixtures/spring-apps/social-platform/event-service",
            },
            runtimeVerification: {
              strictProbeKey: consumerStrictProbeKey,
              probeId: "event-consumer-app",
              waitForHit: {
                timeoutMs: 60_000,
                pollIntervalMs: 500,
                maxRetries: 4,
              },
            },
          },
        ],
        prerequisites: [
          {
            key: "apiBaseUrl",
            required: true,
            secret: false,
            provisioning: "user_input",
            default: producerRuntime.apiBaseUrl,
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
                  source: "event-long-listener-api",
                  dataFormatVersion: 1,
                  dataId: "tenant-batch-01",
                  data: ["tenant-social-001"],
                  notes: "fixture-listener-delay-ms:90000",
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

      mcp = await startMcpClient({
        workspaceRootAbs,
        probeBaseUrl: producerRuntime.probeBaseUrl,
        extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
      });

      const out = await callTool(mcp, "execution_orchestration", {
        action: "execute",
        input: {
          projectName,
          executionProfile: "long-listener-event-run",
        },
      }, { timeoutMs: 240_000 });

      const runRootAbs = path.join(
        workspaceRootAbs,
        ".mcpjvm",
        projectName,
        "plans",
        "regression",
        "long-listener-plan",
        "runs",
      );
      const runIds = await fs.readdir(runRootAbs);
      assert.equal(runIds.length, 1);
      const execution = JSON.parse(
        await fs.readFile(path.join(runRootAbs, runIds[0]!, "execution.result.json"), "utf8"),
      ) as {
        status: string;
        steps: Array<{
          status: string;
          assertions: Array<{
            actualPath: string;
            status: string;
            actual?: unknown;
            expected?: unknown;
          }>;
        }>;
      };

      assert.equal(execution.status, "pass");
      assert.equal(execution.steps[0]?.assertions[0]?.status, "pass");
      assert.equal(execution.steps[0]?.assertions[1]?.status, "pass");
      assert.equal(execution.steps[0]?.assertions[2]?.actualPath, "probe.hit");
      assert.equal(execution.steps[0]?.assertions[2]?.status, "pass");
      assert.equal(execution.steps[0]?.assertions[2]?.actual, true);

      assert.equal(out.structuredContent?.resultType, "execution_orchestration");
      assert.equal(
        out.structuredContent?.status,
        "pass",
        JSON.stringify(
          {
            orchestration: out.structuredContent,
            execution,
          },
          null,
          2,
        ),
      );
    } finally {
      await mcp?.close();
      await producerRuntime?.stop();
      await consumerRuntime?.stop();
      if (process.env.KEEP_EVENT_LONG_LISTENER_TMP !== "1" && fssync.existsSync(tmpRoot)) {
        await fs.rm(tmpRoot, { recursive: true, force: true });
      }
    }
  },
);
