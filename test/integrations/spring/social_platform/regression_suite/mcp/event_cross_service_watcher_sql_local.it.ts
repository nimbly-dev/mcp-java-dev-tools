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

test("mcp IT: local cross-service regression waits on downstream processing and verifies indexed_count=500 via SQL", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-event-cross-service-watcher-sql-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "event-cross-service-watcher-sql-project";
  const projectRootAbs = workspaceRootAbs;
  const sqliteFileAbs = path.join(tmpRoot, "event-processing.sqlite");
  const producerLine = await findLineNumberBySnippet(
    eventProducerControllerSourceFileAbs,
    "return triggerService.triggerIndex(request, authentication.getName());",
  );
  const producerStrictProbeKey = buildLineKey({
    fqcn: eventProducerControllerFqcn,
    methodName: "triggerIndex",
    line: producerLine,
  });
  const payloadData = Array.from({ length: 500 }, (_value, index) =>
    index === 0 ? "tenant-social-001" : `doc-${index}`,
  );

  let consumerRuntime: Awaited<ReturnType<typeof startEventConsumerAppWithAgent>> | undefined;
  let producerRuntime: Awaited<ReturnType<typeof startEventProducerAppWithAgent>> | undefined;
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;

  try {
    consumerRuntime = await startEventConsumerAppWithAgent({ sqliteFileAbs });
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
          defaults: {
            requestTimeoutMs: 250,
            retryMax: 20,
            orchestrator: {
              resumePollMax: 30,
              resumePollIntervalMs: 100,
              resumePollTimeoutMs: 300000,
            },
          },
          executionProfiles: [
            {
              executionProfile: "watcher-sql-run",
              executionPolicy: "stop_on_fail",
              plans: [
                {
                  order: 1,
                  planName: "producer-trigger-plan",
                  onFail: "inherit",
                  providedContext: {
                    consumerApiBaseUrl: consumerRuntime.apiBaseUrl,
                    "sql.connection.fixtureDb.kind": "sqlite",
                    "sql.connection.fixtureDb.sqlite.filePath": sqliteFileAbs,
                  },
                },
              ],
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
      "producer-trigger-plan",
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
          id: "trigger_bulk_reindex",
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
                groupId: "group-500",
                source: "event-cross-service-local-sql-it",
                dataFormatVersion: 1,
                dataId: "tenant-batch-500",
                data: payloadData,
                notes: "fixture-listener-delay-ms:1200",
              },
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
            {
              id: "indexed_count_ready",
              actualPath: "response.bodyJson.indexedCount",
              operator: "field_equals",
              expected: 500,
            },
          ],
        },
      ],
      externalVerification: [
        {
          id: "verify_sqlite_processing_row",
          provider: { type: "sql" },
          request: {
            sql: {
              connectionRef: "fixtureDb",
              statement: `
                SELECT status, indexed_count, tenant
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
              id: "sql_indexed_count_500",
              actualPath: "sql.firstRow.indexed_count",
              operator: "field_equals",
              expected: 500,
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
        executionProfile: "watcher-sql-run",
      },
    });

    assert.equal(out.structuredContent?.resultType, "execution_orchestration");
    assert.equal(out.structuredContent?.status, "pass");

    const runRootAbs = path.join(planRootAbs, "runs");
    const runIds = await fs.readdir(runRootAbs);
    assert.equal(runIds.length, 1);
    const runDirAbs = path.join(runRootAbs, runIds[0]!);
    const execution = JSON.parse(
      await fs.readFile(path.join(runDirAbs, "execution.result.json"), "utf8"),
    ) as Record<string, unknown>;

    const watchers = execution.watchers as Array<Record<string, unknown>>;
    const externalVerification = execution.externalVerification as Array<Record<string, unknown>>;
    assert.equal(execution.status, "pass");
    assert.equal(execution.watcherStatus, "pass");
    assert.equal(execution.externalVerificationStatus, "pass");
    assert.equal(Array.isArray(watchers), true);
    assert.equal(watchers[0]?.status, "pass");
    assert.equal((watchers[0]?.attemptCount as number) >= 2, true);
    assert.equal(Array.isArray(externalVerification), true);
    assert.equal(externalVerification[0]?.status, "pass");
    assert.equal(
      ((externalVerification[0]?.sql as Record<string, unknown>)?.firstRow as Record<string, unknown>)
        ?.indexed_count,
      500,
    );
  } finally {
    await mcp?.close();
    await producerRuntime?.stop();
    await consumerRuntime?.stop();
    if (process.env.KEEP_EVENT_CROSS_SERVICE_WATCHER_SQL_TMP !== "1" && fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});
