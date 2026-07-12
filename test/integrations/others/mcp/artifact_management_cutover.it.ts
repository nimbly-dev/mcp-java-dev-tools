import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import test from "node:test";

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
