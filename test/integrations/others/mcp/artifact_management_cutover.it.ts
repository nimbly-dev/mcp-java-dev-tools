import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import test from "node:test";

import { writeRegressionRunArtifacts } from "@tools-feature-regression-suite";
import { startMcpClient } from "@test/integrations/support/spring/social_platform/shared.fixture";

test("mcp IT: artifact_management run_result cutover is idempotent", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-cutover-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "cutover-project";
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9196",
    });
    const first = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "cutover",
        input: { projectName },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(first.structuredContent?.status, "ok");
    assert.equal(
      (first.structuredContent?.summary as { status?: unknown } | undefined)?.status,
      "cutover_complete",
    );

    const second = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "cutover",
        input: { projectName },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(second.structuredContent?.status, "ok");
    assert.equal(second.structuredContent?.idempotent, true);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("mcp IT: artifact_management exposes bounded read-only run_state queries", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-run-state-query-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "query-project";
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({ workspaceRootAbs, probeBaseUrl: "http://127.0.0.1:9197" });
    const missingProject = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "query",
        input: { stateSurface: "run_state", query: { pageSize: 25 } },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(missingProject.structuredContent?.reasonCode, "run_state_query_invalid");

    const cutover = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: { artifactType: "run_result", action: "cutover", input: { projectName } },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(cutover.structuredContent?.status, "ok");

    const { DatabaseSync } = require("node:sqlite") as {
      DatabaseSync: new (location: string) => {
        prepare(sql: string): { run(...parameters: unknown[]): void };
        close(): void;
      };
    };
    const database = new DatabaseSync(
      path.join(workspaceRootAbs, ".mcpjvm", projectName, "run-state.sqlite"),
    );
    database
      .prepare(
        `INSERT INTO suite_runs (project_name, suite_run_id, execution_profile, status, active_phase, revision, started_at_epoch_ms, updated_at_epoch_ms)
       VALUES (?, 'suite-1', 'test', 'in_progress', 'trigger', 1, 10, 20)`,
      )
      .run(projectName);
    database.close();

    const query = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "query",
        input: { projectName, stateSurface: "run_state", query: { pageSize: 25 } },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(query.structuredContent?.status, "ok");
    assert.equal(query.structuredContent?.stateSurface, "run_state");
    assert.deepEqual((query.structuredContent?.items as Array<Record<string, unknown>>)?.[0], {
      stateKind: "suite",
      projectName,
      suiteRunId: "suite-1",
      executionProfile: "test",
      status: "in_progress",
      activePhase: "trigger",
      startedAtEpochMs: 10,
      updatedAtEpochMs: 20,
      revision: 1,
      resumable: true,
      artifactReferences: [],
    });
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("mcp IT: artifact_management exposes bounded correlation_state summaries", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-correlation-state-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "correlation-query-project";
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({ workspaceRootAbs, probeBaseUrl: "http://127.0.0.1:9198" });
    const cutover = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: { artifactType: "run_result", action: "cutover", input: { projectName } },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(cutover.structuredContent?.status, "ok");
    const { DatabaseSync } = require("node:sqlite") as {
      DatabaseSync: new (location: string) => {
        prepare(sql: string): { run(...parameters: unknown[]): void };
        close(): void;
      };
    };
    const database = new DatabaseSync(
      path.join(workspaceRootAbs, ".mcpjvm", projectName, "run-state.sqlite"),
    );
    database
      .prepare(
        `INSERT INTO correlation_runs (project_name, plan_name, run_id, correlation_session_id, status, reason_code, expected_line_count, matched_line_count, max_window_ms, started_at_epoch_ms, revision)
         VALUES (?, 'p1', 'r1', 'session-1', 'collecting', 'collecting', 1, 0, 60000, 10, 1)`,
      )
      .run(projectName);
    database.close();

    const query = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "query",
        input: {
          projectName,
          stateSurface: "correlation_state",
          query: { filters: { correlationSessionId: "session-1" } },
        },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(query.structuredContent?.status, "ok");
    assert.equal(query.structuredContent?.stateSurface, "correlation_state");
    assert.equal(
      (query.structuredContent?.items as Array<Record<string, unknown>>)?.[0]?.isCorrelated,
      false,
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("mcp IT: artifact_management exposes active watcher_state progress", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-watcher-state-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "watcher-query-project";
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({ workspaceRootAbs, probeBaseUrl: "http://127.0.0.1:9199" });
    await mcp.client.callTool({
      name: "artifact_management",
      arguments: { artifactType: "run_result", action: "cutover", input: { projectName } },
    });
    const { DatabaseSync } = require("node:sqlite") as {
      DatabaseSync: new (location: string) => {
        prepare(sql: string): {
          run(...parameters: unknown[]): void;
          get(...parameters: unknown[]): Record<string, unknown> | undefined;
        };
        close(): void;
      };
    };
    const database = new DatabaseSync(
      path.join(workspaceRootAbs, ".mcpjvm", projectName, "run-state.sqlite"),
    );
    database
      .prepare(
        `INSERT INTO plan_runs (project_name, plan_name, run_id, status, run_dir_path_rel)
       VALUES (?, 'p1', 'r1', 'executed', '.mcpjvm/${projectName}/plans/regression/p1/runs/r1')`,
      )
      .run(projectName);
    const planPk = database
      .prepare(
        "SELECT plan_run_pk FROM plan_runs WHERE project_name = ? AND plan_name = 'p1' AND run_id = 'r1'",
      )
      .get(projectName)?.plan_run_pk;
    database
      .prepare(
        `INSERT INTO watcher_runs (plan_run_pk, project_name, plan_name, run_id, watcher_name, dependency_step_order, watcher_index, provider_type, status, outcome, started_at_epoch_ms, deadline_at_epoch_ms, timeout_ms, poll_interval_ms, retry_max, attempt_count, revision)
       VALUES (?, ?, 'p1', 'r1', 'health-check', 1, 0, 'http', 'in_progress', 'blocked', 10, 1000, 60000, 1000, 3, 1, 1)`,
      )
      .run(planPk, projectName);
    database.close();

    const query = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "query",
        input: {
          projectName,
          stateSurface: "watcher_state",
          query: { filters: { status: "in_progress" } },
        },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(query.structuredContent?.status, "ok");
    assert.equal(query.structuredContent?.stateSurface, "watcher_state");
    assert.equal(
      (query.structuredContent?.items as Array<Record<string, unknown>>)?.[0]?.active,
      true,
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("mcp IT: artifact_management rebuild returns bounded recovery metadata", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-rebuild-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "rebuild-project";
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    await fs.mkdir(path.join(workspaceRootAbs, ".mcpjvm", projectName), { recursive: true });
    await fs.writeFile(
      path.join(workspaceRootAbs, ".mcpjvm", projectName, "projects.json"),
      JSON.stringify({ workspaces: [{ projectRoot: workspaceRootAbs }] }),
      "utf8",
    );
    await writeRegressionRunArtifacts({
      workspaceRootAbs,
      projectName,
      runId: "2026-04-19T08-01-22Z_01",
      planRef: { name: "rebuild-plan" },
      resolvedContext: {},
      executionResult: {
        status: "pass",
        preflight: {
          status: "ready",
          reasonCode: "ok",
          missing: [],
          discoverablePending: [],
          prerequisiteResolution: [],
          requiredUserAction: [],
        },
        startedAt: "2026-04-19T08:01:22.000Z",
        endedAt: "2026-04-19T08:01:23.000Z",
        steps: [{ order: 1, id: "trigger", status: "pass" }],
      },
      evidence: { targetResolution: [] },
      now: new Date("2026-04-19T08:01:24.000Z"),
    });
    mcp = await startMcpClient({ workspaceRootAbs, probeBaseUrl: "http://127.0.0.1:9200" });
    const rebuilt = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "rebuild",
        input: {
          projectName,
          strict: false,
          scope: { stateSurfaces: ["run_state", "watcher_state"] },
        },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(rebuilt.structuredContent?.status, "ok");
    assert.deepEqual(rebuilt.structuredContent?.scope, {
      stateSurfaces: ["run_state", "watcher_state"],
    });
    assert.equal(rebuilt.structuredContent?.strict, false);
    assert.equal(
      (rebuilt.structuredContent?.summary as Record<string, unknown>)?.recoveryStatus,
      "complete",
    );
    assert.equal(
      rebuilt.structuredContent?.databasePathRel,
      `.mcpjvm/${projectName}/run-state.sqlite`,
    );
    assert.equal("databasePathAbs" in (rebuilt.structuredContent ?? {}), false);
    assert.equal("quarantinePathAbs" in (rebuilt.structuredContent ?? {}), false);
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("mcp IT: artifact_management rebuild fails closed without canonical sources", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-rebuild-empty-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({ workspaceRootAbs, probeBaseUrl: "http://127.0.0.1:9201" });
    const rebuilt = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "rebuild",
        input: { projectName: "empty-rebuild-project" },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(rebuilt.structuredContent?.status, "state_store_rebuild_source_invalid");
    assert.equal(rebuilt.structuredContent?.reasonCode, "state_store_rebuild_source_invalid");
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});

test("mcp IT: artifact_management exposes transitional correlation backfill metadata", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-artifact-backfill-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const projectName = "backfill-project";
  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    const projectDir = path.join(workspaceRootAbs, ".mcpjvm", projectName);
    await fs.mkdir(projectDir, { recursive: true });
    await fs.writeFile(
      path.join(projectDir, "projects.json"),
      JSON.stringify({ workspaces: [{ projectRoot: workspaceRootAbs }] }),
      "utf8",
    );
    await fs.writeFile(
      path.join(projectDir, "correlation-index.json"),
      JSON.stringify({
        version: 1,
        entries: [
          {
            runId: "2026-07-12T00-00-00Z_01",
            planName: "legacy-plan",
            runPath: `.mcpjvm/${projectName}/plans/regression/legacy-plan/runs/2026-07-12T00-00-00Z_01`,
            generatedAtEpochMs: 1000,
            status: "ok",
            reasonCode: "ok",
            keyType: "traceId",
            keyValue: "trace-1",
            correlationSessionId: "legacy-session-1",
            window: { startEpochMs: 900, endEpochMs: 1100, maxWindowMs: 60000 },
            probeIds: [],
          },
        ],
      }),
      "utf8",
    );
    mcp = await startMcpClient({ workspaceRootAbs, probeBaseUrl: "http://127.0.0.1:9202" });
    const result = (await mcp.client.callTool({
      name: "artifact_management",
      arguments: {
        artifactType: "run_result",
        action: "backfill",
        input: { projectName, stateSurface: "correlation_state" },
      },
    })) as { structuredContent?: Record<string, unknown> };
    assert.equal(result.structuredContent?.status, "ok");
    assert.deepEqual(result.structuredContent?.transitional, {
      kind: "legacy_correlation_index_backfill",
      preCutoverOnly: true,
      sourcePathRel: `.mcpjvm/${projectName}/correlation-index.json`,
    });
    assert.match(
      ((result.structuredContent?.summary as Record<string, unknown>)?.sourceChecksum ??
        "") as string,
      /^[a-f0-9]{64}$/,
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) await fs.rm(tmpRoot, { recursive: true, force: true });
  }
});
