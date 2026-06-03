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

test("loads explicit probe registry from MCP_PROBE_CONFIG_FILE", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-server-config-file-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const configDir = path.join(tmpRoot, "config");
    fs.mkdirSync(configDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(configDir, "probe-config.json"));
    withEnv(
      {
        [MCP_ENV.WORKSPACE_ROOT]: workspaceRoot,
        [MCP_ENV.PROBE_CONFIG_FILE]: path.join(configDir, "probe-config.json"),
        INIT_CWD: undefined,
        PWD: undefined,
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server"]);
        assert.equal(cfg.workspaceRootSource, "env");
        assert.equal(cfg.probeBaseUrl, "http://127.0.0.1:9190");
      },
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("ignores stale absolute MCP_PROBE_CONFIG_FILE from another workspace when active workspace has probe-config.json", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-server-config-stale-env-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const otherWorkspace = path.join(tmpRoot, "other-workspace");
    const workspaceMcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    const otherMcpjvmDir = path.join(otherWorkspace, ".mcpjvm");
    fs.mkdirSync(workspaceMcpjvmDir, { recursive: true });
    fs.mkdirSync(otherMcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(workspaceMcpjvmDir, "probe-config.json"));
    fs.writeFileSync(
      path.join(otherMcpjvmDir, "probe-config.json"),
      JSON.stringify(
        {
          profiles: [{ name: "dev", probes: [{ id: "wrong-service", baseUrl: "http://127.0.0.1:9292" }] }],
          defaultProfile: "dev",
        },
        null,
        2,
      ),
      "utf8",
    );
    withEnv(
      {
        [MCP_ENV.WORKSPACE_ROOT]: workspaceRoot,
        [MCP_ENV.PROBE_CONFIG_FILE]: path.join(otherMcpjvmDir, "probe-config.json"),
        INIT_CWD: undefined,
        PWD: undefined,
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server"]);
        assert.equal(cfg.probeRegistry?.configFileAbs, path.join(workspaceMcpjvmDir, "probe-config.json"));
        assert.equal(cfg.probeBaseUrl, "http://127.0.0.1:9190");
      },
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("resolves workspace-relative MCP_PROBE_CONFIG_FILE to active workspace probe-config.json", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-server-config-relative-env-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(mcpjvmDir, "probe-config.json"));
    withEnv(
      {
        [MCP_ENV.WORKSPACE_ROOT]: workspaceRoot,
        [MCP_ENV.PROBE_CONFIG_FILE]: "/.mcpjvm/probe-config.json",
        INIT_CWD: undefined,
        PWD: undefined,
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server"]);
        assert.equal(cfg.probeRegistry?.configFileAbs, path.join(mcpjvmDir, "probe-config.json"));
      },
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

