import assert from "node:assert/strict";
import { execFile as execFileCallback } from "node:child_process";
import * as fs from "node:fs/promises";
import * as http from "node:http";
import * as os from "node:os";
import * as path from "node:path";
import { promisify } from "node:util";
import test from "node:test";

import { startMcpClient } from "@test/integrations/support/spring/social_platform/shared.fixture";

const execFile = promisify(execFileCallback);

async function docker(args: string[]): Promise<string> {
  const result = await execFile("docker", args, { windowsHide: true, maxBuffer: 64 * 1024 });
  return result.stdout.trim();
}

async function dockerAvailable(): Promise<boolean> {
  try {
    await docker(["version", "--format", "{{.Server.Version}}"]).then(() => undefined);
    return true;
  } catch {
    return false;
  }
}

async function writeJson(filePath: string, value: Record<string, unknown>): Promise<void> {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

test("mcp IT: PostgreSQL external verification uses native pg with bound values", async () => {
  if (!(await dockerAvailable())) {
    assert.fail("postgresql_it_docker_unavailable");
  }

  const tmpRoot = await fs.mkdtemp(
    path.join(os.tmpdir(), "mcp-postgresql-external-verification-it-"),
  );
  const containerName = `mcp-pg-it-${process.pid}-${Date.now()}`;
  const projectName = "test-project-postgresql";
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const probeConfigAbs = path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const planRootAbs = path.join(
    workspaceRootAbs,
    ".mcpjvm",
    projectName,
    "plans",
    "regression",
    "postgresql-verification",
  );
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  let appServer: http.Server | undefined;
  try {
    await docker([
      "run",
      "-d",
      "--name",
      containerName,
      "-e",
      "POSTGRES_PASSWORD=it_password",
      "-e",
      "POSTGRES_USER=it_user",
      "-e",
      "POSTGRES_DB=it_database",
      "-p",
      "127.0.0.1::5432",
      "postgres:16-alpine",
    ]);
    for (let attempt = 0; attempt < 30; attempt += 1) {
      try {
        await docker(["exec", containerName, "pg_isready", "-U", "it_user", "-d", "it_database"]);
        break;
      } catch {
        if (attempt === 29) throw new Error("postgresql_it_not_ready");
        await new Promise((resolve) => setTimeout(resolve, 1_000));
      }
    }
    const mappedPort = Number(
      (await docker(["port", containerName, "5432/tcp"])).match(/:(\d+)$/)?.[1],
    );
    assert.ok(Number.isInteger(mappedPort) && mappedPort > 0, "postgresql_it_port_unresolved");
    await docker([
      "exec",
      containerName,
      "psql",
      "-v",
      "ON_ERROR_STOP=1",
      "-U",
      "it_user",
      "-d",
      "it_database",
      "-c",
      "CREATE TABLE verification_rows (tenant_id text primary key, indexed_count integer not null, pending_count integer not null); INSERT INTO verification_rows VALUES ('tenant-it', 500, 0);",
    ]);

    await writeJson(probeConfigAbs, {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            "postgresql-it-probe": {
              baseUrl: "http://127.0.0.1:9196",
              include: ["test.**"],
              exclude: [],
            },
          },
        },
      },
      workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
    });
    await fs.mkdir(path.join(workspaceRootAbs, ".mcpjvm", projectName), { recursive: true });
    await fs.writeFile(
      path.join(workspaceRootAbs, ".env"),
      [
        "PG_KIND=postgresql",
        `PG_HOST=127.0.0.1`,
        `PG_PORT=${mappedPort}`,
        "PG_DATABASE=it_database",
        "PG_USERNAME=it_user",
        "PG_PASSWORD=it_password",
        "PG_TLS=disable",
      ].join("\n") + "\n",
      "utf8",
    );
    await writeJson(path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"), {
      workspaces: [
        {
          projectRoot: workspaceRootAbs,
          defaults: {
            requestTimeoutMs: 2_000,
            retryMax: 0,
            orchestrator: {
              resumePollMax: 1,
              resumePollIntervalMs: 10,
              resumePollTimeoutMs: 2_000,
            },
          },
          envFile: ".env",
          variables: {
            contextBindings: {
              "sql.connection.catalog.kind": "PG_KIND",
              "sql.connection.catalog.host": "PG_HOST",
              "sql.connection.catalog.port": "PG_PORT",
              "sql.connection.catalog.database": "PG_DATABASE",
              "sql.connection.catalog.username": "PG_USERNAME",
              "sql.connection.catalog.password": "PG_PASSWORD",
              "sql.connection.catalog.tls.mode": "PG_TLS",
            },
          },
          executionProfiles: [
            {
              executionProfile: "postgresql-it",
              suiteType: "regression",
              executionPolicy: "stop_on_fail",
              plans: [{ order: 1, planName: "postgresql-verification" }],
            },
          ],
        },
      ],
    });
    await writeJson(path.join(planRootAbs, "metadata.json"), {
      execution: { intent: "regression" },
    });
    await writeJson(path.join(planRootAbs, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "test.Target", method: "run" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "trigger",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", url: "http://127.0.0.1:0/trigger" } },
          expect: [
            {
              id: "accepted",
              actualPath: "response.statusCode",
              operator: "field_equals",
              expected: 200,
            },
          ],
        },
      ],
      externalVerification: [
        {
          id: "verify_catalog",
          provider: { type: "sql" },
          request: {
            sql: {
              connectionRef: "catalog",
              statement: "SELECT indexed_count, pending_count FROM verification_rows WHERE tenant_id = :tenant",
              parameters: [{ name: "tenant", value: "tenant-it" }],
            },
          },
          expect: [
            {
              id: "count",
              actualPath: "sql.firstRow.indexed_count",
              operator: "field_equals",
              expected: 500,
            },
            {
              id: "pending_count_zero",
              actualPath: "sql.firstRow.pending_count",
              operator: "field_equals",
              expected: 0,
            },
          ],
        },
      ],
    });

    appServer = http.createServer((_request, response) => {
      response.statusCode = 200;
      response.setHeader("content-type", "application/json");
      response.end(JSON.stringify({ accepted: true }));
    });
    await new Promise<void>((resolve) => appServer!.listen(0, "127.0.0.1", resolve));
    const address = appServer.address();
    if (!address || typeof address === "string")
      throw new Error("postgresql_it_http_port_unresolved");
    const contractPath = path.join(planRootAbs, "contract.json");
    const contract = JSON.parse(await fs.readFile(contractPath, "utf8")) as Record<string, unknown>;
    const steps = contract.steps as Array<Record<string, unknown>>;
    const firstStep = steps[0]!;
    firstStep.transport = {
      http: { method: "POST", url: `http://127.0.0.1:${address.port}/trigger` },
    };
    await writeJson(contractPath, contract);

    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
      extraEnv: { MCP_PROBE_CONFIG_FILE: probeConfigAbs },
    });
    const out = (await mcp.client.callTool({
      name: "execution_orchestration",
      arguments: { action: "execute", input: { projectName, executionProfile: "postgresql-it" } },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(
      out.structuredContent?.resultType,
      "execution_orchestration",
      JSON.stringify(out.structuredContent),
    );
    assert.equal(out.structuredContent?.status, "pass", JSON.stringify(out.structuredContent));
    const serialized = JSON.stringify(out.structuredContent);
    assert.equal(serialized.includes("it_password"), false);
    assert.equal(serialized.includes("jdbc:"), false);
    const statusArtifactPath = out.structuredContent?.statusArtifactPath;
    assert.equal(typeof statusArtifactPath, "string");
    const persisted = JSON.parse(
      await fs.readFile(path.join(workspaceRootAbs, statusArtifactPath as string), "utf8"),
    ) as Record<string, unknown>;
    const persistedPlanRuns = Array.isArray(persisted.planRuns)
      ? (persisted.planRuns as Array<Record<string, unknown>>)
      : [];
    assert.equal(persistedPlanRuns[0]?.status, "executed");
    assert.equal(persistedPlanRuns[0]?.runStatus, "pass");
    const runId = persistedPlanRuns[0]?.runId;
    assert.equal(typeof runId, "string");
    const persistedRun = JSON.parse(
      await fs.readFile(
        path.join(planRootAbs, "runs", runId as string, "execution.result.json"),
        "utf8",
      ),
    ) as Record<string, unknown>;
    const persistedExternalVerification = Array.isArray(persistedRun.externalVerification)
      ? (persistedRun.externalVerification as Array<Record<string, unknown>>)
      : [];
    assert.equal(persistedExternalVerification[0]?.status, "pass", JSON.stringify(persistedRun));
    assert.equal(JSON.stringify(persistedRun).includes("it_password"), false);

    const limitPlanRootAbs = path.join(
      workspaceRootAbs,
      ".mcpjvm",
      projectName,
      "plans",
      "regression",
      "postgresql-result-limit",
    );
    await writeJson(path.join(limitPlanRootAbs, "metadata.json"), {
      execution: { intent: "regression" },
    });
    await writeJson(path.join(limitPlanRootAbs, "contract.json"), {
      targets: [{ type: "class_method", selectors: { fqcn: "test.Target", method: "run" } }],
      prerequisites: [],
      steps: [
        {
          order: 1,
          id: "trigger",
          targetRef: 0,
          protocol: "http",
          transport: { http: { method: "POST", url: `http://127.0.0.1:${address.port}/trigger` } },
          expect: [
            {
              id: "accepted",
              actualPath: "response.statusCode",
              operator: "field_equals",
              expected: 200,
            },
          ],
        },
      ],
      externalVerification: [
        {
          id: "verify_result_limit",
          provider: { type: "sql" },
          request: {
            sql: {
              connectionRef: "catalog",
              statement: "SELECT generate_series(1, 1001) AS indexed_count",
            },
          },
          expect: [
            {
              id: "row_count",
              actualPath: "sql.rowCount",
              operator: "field_equals",
              expected: 1001,
            },
          ],
        },
      ],
    });
    const projectsPath = path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
    const projectArtifact = JSON.parse(await fs.readFile(projectsPath, "utf8")) as {
      workspaces: Array<{ executionProfiles: Array<Record<string, unknown>> }>;
    };
    projectArtifact.workspaces[0]!.executionProfiles.push({
      executionProfile: "postgresql-limit-it",
      suiteType: "regression",
      executionPolicy: "stop_on_fail",
      plans: [{ order: 1, planName: "postgresql-result-limit" }],
    });
    await writeJson(projectsPath, projectArtifact);
    const limitOut = (await mcp.client.callTool({
      name: "execution_orchestration",
      arguments: {
        action: "execute",
        input: { projectName, executionProfile: "postgresql-limit-it" },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(
      limitOut.structuredContent?.status,
      "blocked",
      JSON.stringify(limitOut.structuredContent),
    );
    const limitPlanRun = Array.isArray(limitOut.structuredContent?.planRuns)
      ? (limitOut.structuredContent?.planRuns as Array<Record<string, unknown>>)[0]
      : undefined;
    assert.equal(typeof limitPlanRun?.runId, "string", JSON.stringify(limitOut.structuredContent));
    const limitedRun = JSON.parse(
      await fs.readFile(
        path.join(limitPlanRootAbs, "runs", limitPlanRun!.runId as string, "execution.result.json"),
        "utf8",
      ),
    ) as Record<string, unknown>;
    const limitedVerification = Array.isArray(limitedRun.externalVerification)
      ? (limitedRun.externalVerification as Array<Record<string, unknown>>)[0]
      : undefined;
    assert.equal(limitedVerification?.reasonCode, "external_verification_execution_failed");
    assert.equal(
      (limitedVerification?.reasonMeta as Record<string, unknown>)?.cause,
      "result_limit_exceeded",
    );
  } finally {
    await mcp?.close();
    await new Promise<void>((resolve) =>
      appServer ? appServer.close(() => resolve()) : resolve(),
    );
    await docker(["rm", "-f", containerName]).catch(() => undefined);
    await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});
