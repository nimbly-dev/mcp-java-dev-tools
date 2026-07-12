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
