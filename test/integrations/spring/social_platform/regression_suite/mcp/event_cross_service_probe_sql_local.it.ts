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
                    resumePollIntervalMs: 10_000,
                    resumePollTimeoutMs: 300_000,
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

test("mcp IT: local cross-service regression verifies producer and consumer strict probes plus DB persistence", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-event-cross-service-probe-sql-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "event-cross-service-probe-sql-project";
  const projectRootAbs = workspaceRootAbs;
  const sqliteFileAbs = path.join(tmpRoot, "event-processing.sqlite");
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

  try {
    consumerRuntime = await startEventConsumerAppWithAgent({ sqliteFileAbs });
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

    const sharedPlanContext = {
      consumerApiBaseUrl: activeConsumerRuntime.apiBaseUrl,
      "sql.connection.fixtureDb.kind": "sqlite",
      "sql.connection.fixtureDb.sqlite.filePath": sqliteFileAbs,
    };

    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: projectRootAbs,
          defaults: { requestTimeoutMs: 250, retryMax: 20 },
          executionProfiles: [
            {
              executionProfile: "probe-sql-run",
              executionPolicy: "stop_on_fail",
              plans: [
                {
                  order: 1,
                  planName: "producer-plan",
                  onFail: "inherit",
                  providedContext: sharedPlanContext,
                },
                {
                  order: 2,
                  planName: "consumer-plan",
                  onFail: "inherit",
                  providedContext: sharedPlanContext,
                },
              ],
            },
          ],
        },
      ],
    });

    const requestBody = {
      context: "entities",
      type: "TriggerIndex",
      groupId: "group-probe",
      source: "event-cross-service-probe-sql-it",
      dataFormatVersion: 1,
      dataId: "tenant-batch-probe",
      data: ["tenant-social-001", "doc-1", "doc-2"],
      notes: "fixture-listener-delay-ms:800",
    };

    async function writePlan(args: {
      planName: string;
      targetFqcn: string;
      targetMethod: string;
      strictProbeKey: string;
      probeId: string;
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
              fqcn: args.targetFqcn,
              method: args.targetMethod,
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
                  "x-tenant-id": "tenant-social-001",
                },
                body: requestBody,
                timeoutMs: 10_000,
              },
            },
            extract: [{ from: "response.bodyJson.eventId", as: "eventId", required: true }],
            expect: [
              {
                id: "http_ok",
                actualPath: "response.statusCode",
                operator: "field_equals",
                expected: 200,
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
        watchers: [
          {
            id: "consumer_processed",
            dependency: { stepOrder: 1 },
            provider: {
              type: "http",
              transport: {
                request: {
                  method: "GET",
                  url: "${consumerApiBaseUrl}/internal/events/${eventId}",
                },
              },
            },
            waitPolicy: {
              timeoutMs: 15_000,
              retryMax: 20,
            },
            expect: [
              {
                id: "status_processed",
                actualPath: "response.bodyJson.status",
                operator: "field_equals",
                expected: "processed",
              },
            ],
          },
        ],
        externalVerification: [
          {
            id: "verify_sqlite_event_row",
            provider: { type: "sql" },
            request: {
              sql: {
                connectionRef: "fixtureDb",
                statement: `
                  SELECT status, indexed_count, tenant, event_type
                  FROM event_processing_audit
                  WHERE event_id = :eventId
                `,
                parameters: [{ name: "eventId", valueFromContext: "eventId" }],
              },
            },
            expect: [
              {
                id: "sql_row_found",
                actualPath: "sql.rowCount",
                operator: "field_equals",
                expected: 1,
              },
              {
                id: "sql_status_processed",
                actualPath: "sql.firstRow.status",
                operator: "field_equals",
                expected: "processed",
              },
              {
                id: "sql_indexed_count_written",
                actualPath: "sql.firstRow.indexed_count",
                operator: "field_equals",
                expected: 3,
              },
            ],
          },
        ],
      });
    }

    await writePlan({
      planName: "producer-plan",
      targetFqcn: eventProducerControllerFqcn,
      targetMethod: "triggerIndex",
      strictProbeKey: producerStrictProbeKey,
      probeId: "event-producer-app",
    });
    await writePlan({
      planName: "consumer-plan",
      targetFqcn: eventConsumerListenerFqcn,
      targetMethod: "receiveEvent",
      strictProbeKey: consumerStrictProbeKey,
      probeId: "event-consumer-app",
    });

    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: activeProducerRuntime.probeBaseUrl,
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });

    const out = await callTool(mcp, "execution_orchestration", {
      action: "execute",
      input: {
        projectName,
        executionProfile: "probe-sql-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");

    async function readExecution(planName: string): Promise<Record<string, unknown>> {
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
      ) as Record<string, unknown>;
    }

    const producerExecution = await readExecution("producer-plan");
    const consumerExecution = await readExecution("consumer-plan");

    for (const execution of [producerExecution, consumerExecution]) {
      const steps = execution.steps as Array<Record<string, unknown>>;
      const stepAssertions = (steps[0]?.assertions as Array<Record<string, unknown>>) ?? [];
      const watcherRows = execution.watchers as Array<Record<string, unknown>>;
      const externalVerification = execution.externalVerification as Array<Record<string, unknown>>;

      assert.equal(execution.status, "pass");
      assert.equal(execution.watcherStatus, "pass");
      assert.equal(execution.externalVerificationStatus, "pass");
      assert.equal(stepAssertions[1]?.actualPath, "probe.hit");
      assert.equal(stepAssertions[1]?.actual, true);
      assert.equal(watcherRows[0]?.status, "pass");
      assert.equal(externalVerification[0]?.status, "pass");
      assert.equal(
        ((externalVerification[0]?.sql as Record<string, unknown>)?.firstRow as Record<string, unknown>)
          ?.status,
        "processed",
      );
    }
  } finally {
    await mcp?.close();
    await producerRuntime?.stop();
    await consumerRuntime?.stop();
    if (process.env.KEEP_EVENT_CROSS_SERVICE_PROBE_SQL_TMP !== "1" && fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
