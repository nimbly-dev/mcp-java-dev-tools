import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  buildLineKey,
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

test("mcp IT: execution_orchestration preserves probe.hit when manual strict verification resolves via already_hit_since_inline_start", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-event-slow-baseline-probe-line-hit-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "event-slow-baseline-probe-project";
  const projectRootAbs = workspaceRootAbs;

  const producerLine = await findLineNumberBySnippet(
    eventProducerControllerSourceFileAbs,
    "return triggerService.triggerIndex(request, authentication.getName());",
  );
  const producerStrictProbeKey = buildLineKey({
    fqcn: eventProducerControllerFqcn,
    methodName: "triggerIndex",
    line: producerLine,
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
              executionProfile: "slow-baseline-event-run",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "slow-baseline-producer-trigger-plan", onFail: "inherit" }],
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
      "slow-baseline-producer-trigger-plan",
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
            fqcn: eventProducerControllerFqcn,
            method: "triggerIndex",
            sourceRoot: "test/fixtures/spring-apps/social-platform/event-service",
          },
          runtimeVerification: {
            strictProbeKey: producerStrictProbeKey,
            probeId: "event-producer-app",
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
                source: "event-slow-baseline-api",
                dataFormatVersion: 1,
                dataId: "tenant-batch-01",
                data: ["tenant-social-001"],
                notes: "fixture-sleep-ms:2000",
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

    const manualReset = await callTool(mcp, "probe", {
      action: "reset",
      input: {
        key: producerStrictProbeKey,
        probeId: "event-producer-app",
      },
    });
    const manualResetResponse = manualReset.structuredContent?.response as Record<string, unknown> | undefined;
    assert.equal(manualResetResponse?.status, 200);

    const manualResponsePromise = fetch(`${producerRuntime.apiBaseUrl}/api/v1/events/trigger`, {
      method: "POST",
      headers: {
        Authorization: "Bearer alice-token",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        context: "entities",
        type: "TriggerIndex",
        groupId: "group-001",
        source: "event-slow-baseline-api",
        dataFormatVersion: 1,
        dataId: "tenant-batch-01",
        data: ["tenant-social-001"],
        notes: "fixture-sleep-ms:2000",
      }),
    });

    const manualResponse = await manualResponsePromise;
    const manualBody = await manualResponse.text();
    assert.equal(manualResponse.status, 200, manualBody);
    assert.match(manualBody, /evt-/);

    const manualWait = await callTool(mcp, "probe", {
      action: "wait_for_hit",
      input: {
        key: producerStrictProbeKey,
        probeId: "event-producer-app",
        timeoutMs: 5_000,
        pollIntervalMs: 250,
        maxRetries: 1,
      },
    });
    const manualWaitRequest = manualWait.structuredContent?.request as Record<string, unknown> | undefined;
    const manualWaitResult = manualWait.structuredContent?.result as Record<string, unknown> | undefined;
    assert.equal(manualWaitResult?.hit, true, JSON.stringify(manualWait.structuredContent, null, 2));
    assert.equal(manualWaitResult?.source, "already_hit_since_inline_start");
    assert.equal(manualWaitResult?.hitDelta, 0);
    assert.equal(typeof manualWaitRequest?.triggerLeadMs, "number");
    assert.ok(Number(manualWaitRequest?.triggerLeadMs) >= 1_500, JSON.stringify(manualWait.structuredContent, null, 2));

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "slow-baseline-event-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(
      out.structuredContent?.status,
      "pass",
      JSON.stringify(
        {
          orchestration: out.structuredContent,
          manualWait: manualWait.structuredContent,
          producerLogs: producerRuntime.logs(),
          consumerLogs: consumerRuntime.logs(),
        },
        null,
        2,
      ),
    );

    const runRootAbs = path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      "slow-baseline-producer-trigger-plan",
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
        }>;
      }>;
    };

    assert.equal(execution.status, "pass");
    assert.equal(execution.steps[0]?.status, "pass");
    assert.equal(execution.steps[0]?.assertions[2]?.actualPath, "probe.hit");
    assert.equal(execution.steps[0]?.assertions[2]?.status, "pass");
    assert.equal(execution.steps[0]?.assertions[2]?.actual, true);
  } finally {
    await mcp?.close();
    await producerRuntime?.stop();
    await consumerRuntime?.stop();
    if (process.env.KEEP_EVENT_SLOW_BASELINE_TMP !== "1" && fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
