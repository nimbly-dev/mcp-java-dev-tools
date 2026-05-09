import assert from "node:assert/strict";
import * as fs from "node:fs/promises";
import * as fssync from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import test from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { startMcpClient } from "@test/integrations/support/spring/social_platform/shared.fixture";

type ToolResult = {
  structuredContent?: Record<string, unknown>;
};

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

async function waitFor(
  check: () => Promise<boolean>,
  args: { timeoutMs: number; intervalMs?: number; failureMessage: string },
): Promise<void> {
  const timeoutAt = Date.now() + args.timeoutMs;
  const intervalMs = args.intervalMs ?? 120;
  while (Date.now() < timeoutAt) {
    if (await check()) return;
    await delay(intervalMs);
  }
  throw new Error(args.failureMessage);
}

function buildFixtureProbeRegistry(workspaceRootAbs: string): Record<string, unknown> {
  return {
    defaultProfile: "dev",
    profiles: {
      dev: {
        defaultProbe: "gateway-service",
        probes: {
          "course-service": {
            baseUrl: "http://127.0.0.1:9193",
            include: ["io.javatab.microservices.core.course.**"],
            exclude: [],
            runtime: { platform: "spring-boot", port: 9001 },
          },
          "gateway-service": {
            baseUrl: "http://127.0.0.1:9196",
            include: ["com.example.springcloud.gateway.**"],
            exclude: [],
            runtime: { platform: "spring-boot", port: 9000 },
          },
        },
      },
    },
    workspaces: [{ root: workspaceRootAbs, profile: "dev" }],
  };
}

test("mcp IT: probe registry auto-reload tracks live edits and fail-closed recovery", async () => {
  const tmpRoot = await fs.mkdtemp(path.join(os.tmpdir(), "mcp-probe-registry-it-"));
  const workspaceRootAbs = path.join(tmpRoot, "workspace");
  const mcpjvmDir = path.join(workspaceRootAbs, ".mcpjvm");
  const configFileAbs = path.join(mcpjvmDir, "probe-config.json");
  await fs.mkdir(mcpjvmDir, { recursive: true });
  await fs.writeFile(
    configFileAbs,
    `${JSON.stringify(buildFixtureProbeRegistry(workspaceRootAbs), null, 2)}\n`,
    "utf8",
  );

  let mcp: Awaited<ReturnType<typeof startMcpClient>> | undefined;
  try {
    mcp = await startMcpClient({
      workspaceRootAbs,
      probeBaseUrl: "http://127.0.0.1:9193",
      extraEnv: {
        MCP_PROBE_CONFIG_FILE: configFileAbs,
      },
    });

    const initial = await callTool(mcp, "probe_registry_list", {});
    assert.equal(initial.structuredContent?.status, "ok");
    assert.equal(initial.structuredContent?.activeProfile, "dev");
    assert.equal(initial.structuredContent?.defaultProbeId, "gateway-service");

    // 1) Valid edit should auto-reload and update defaultProbeId.
    const updated = buildFixtureProbeRegistry(workspaceRootAbs) as {
      profiles: { dev: { defaultProbe: string } };
    };
    updated.profiles.dev.defaultProbe = "course-service";
    await fs.writeFile(configFileAbs, `${JSON.stringify(updated, null, 2)}\n`, "utf8");

    await waitFor(
      async () => {
        const listed = await callTool(mcp!, "probe_registry_list", {});
        return (
          listed.structuredContent?.status === "ok" &&
          listed.structuredContent?.defaultProbeId === "course-service" &&
          listed.structuredContent?.lastReloadStatus === "ok"
        );
      },
      {
        timeoutMs: 8_000,
        failureMessage: "auto-reload did not apply valid registry edit in expected window",
      },
    );

    // 2) Invalid edit should fail closed and keep previous active registry.
    await fs.writeFile(configFileAbs, "{ invalid-json, }\n", "utf8");

    await waitFor(
      async () => {
        const listed = await callTool(mcp!, "probe_registry_list", {});
        return (
          listed.structuredContent?.status === "ok" &&
          listed.structuredContent?.defaultProbeId === "course-service" &&
          listed.structuredContent?.lastReloadStatus === "error" &&
          typeof listed.structuredContent?.lastReloadError === "string"
        );
      },
      {
        timeoutMs: 8_000,
        failureMessage: "auto-reload did not report error state for invalid registry content",
      },
    );

    // 3) Recover with valid content should transition back to ok.
    await fs.writeFile(
      configFileAbs,
      `${JSON.stringify(buildFixtureProbeRegistry(workspaceRootAbs), null, 2)}\n`,
      "utf8",
    );

    await waitFor(
      async () => {
        const listed = await callTool(mcp!, "probe_registry_list", {});
        return (
          listed.structuredContent?.status === "ok" &&
          listed.structuredContent?.defaultProbeId === "gateway-service" &&
          listed.structuredContent?.lastReloadStatus === "ok"
        );
      },
      {
        timeoutMs: 8_000,
        failureMessage: "auto-reload did not recover after valid registry content restored",
      },
    );
  } finally {
    await mcp?.close();
    if (fssync.existsSync(tmpRoot)) {
      await fs.rm(tmpRoot, { recursive: true, force: true });
    }
  }
});

