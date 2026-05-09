const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { CONFIG_DEFAULTS } = require("@/config/defaults");
const { MCP_ENV } = require("@/config/env-vars");
const { loadConfigFromEnvAndArgs } = require("@/config/server-config");

const FIXTURE = path.resolve(__dirname, "fixtures", "probe-config.sample.json");

function withEnv(overrides: Record<string, string | undefined>, run: () => void): void {
  const keys = Object.keys(overrides);
  const before: Record<string, string | undefined> = {};
  for (const key of keys) before[key] = process.env[key];
  for (const key of keys) {
    const next = overrides[key];
    if (typeof next === "undefined") delete process.env[key];
    else process.env[key] = next;
  }
  try {
    run();
  } finally {
    for (const key of keys) {
      const prev = before[key];
      if (typeof prev === "undefined") delete process.env[key];
      else process.env[key] = prev;
    }
  }
}

test("loads from probe-config.json and applies fixed probe path defaults", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-server-config-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(mcpjvmDir, "probe-config.json"));
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", workspaceRoot]);
    assert.equal(cfg.probeBaseUrl, "http://127.0.0.1:9190");
    assert.equal(cfg.probeStatusPath, CONFIG_DEFAULTS.PROBE_STATUS_PATH);
    assert.equal(cfg.probeResetPath, CONFIG_DEFAULTS.PROBE_RESET_PATH);
    assert.equal(cfg.probeCapturePath, CONFIG_DEFAULTS.PROBE_CAPTURE_PATH);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("missing probe-config fails closed and does not rely on MCP_PROBE_BASE_URL", () => {
  withEnv(
    {
      [MCP_ENV.PROBE_BASE_URL]: "http://127.0.0.1:9193",
      [MCP_ENV.PROBE_CONFIG_FILE]: undefined,
      [MCP_ENV.WORKSPACE_ROOT]: undefined,
    },
    () => {
      assert.throws(
        () => loadConfigFromEnvAndArgs(["node", "server"]),
        (err: any) => {
          assert.ok(err instanceof Error);
          assert.match(err.message, /probe-config\.json/i);
          assert.doesNotMatch(err.message, /MCP_PROBE_BASE_URL/);
          return true;
        },
      );
    },
  );
});

