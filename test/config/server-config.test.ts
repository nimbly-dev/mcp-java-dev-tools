const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { CONFIG_DEFAULTS } = require("@tools-core/probe_defaults");
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
    assert.equal(cfg.probeBaseUrl, "");
    assert.equal(cfg.probeStatusPath, CONFIG_DEFAULTS.PROBE_STATUS_PATH);
    assert.equal(cfg.probeResetPath, CONFIG_DEFAULTS.PROBE_RESET_PATH);
    assert.equal(cfg.probeCapturePath, CONFIG_DEFAULTS.PROBE_CAPTURE_PATH);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("missing probe-config keeps the server startable without environment probe routing", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-server-config-no-probe-"));
  try {
    withEnv(
      {
        [MCP_ENV.PROBE_CONFIG_FILE]: undefined,
        [MCP_ENV.WORKSPACE_ROOT]: undefined,
        INIT_CWD: undefined,
        PWD: undefined,
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", tmpRoot]);
        assert.equal(cfg.probeRegistry, undefined);
        assert.equal(cfg.workspaceRootSource, "arg");
        assert.equal(cfg.workspaceRootAbs, path.resolve(tmpRoot));
      },
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("preserves an absolute MCP_PROBE_CONFIG_FILE override outside the canonical location", () => {
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
        assert.equal(cfg.workspaceRootAbs, path.resolve(workspaceRoot));
        assert.equal(cfg.probeRegistry?.configFileAbs, path.join(configDir, "probe-config.json"));
      },
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("derives workspace from an explicit canonical probe-config in another workspace", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-server-config-stale-env-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const otherWorkspace = path.join(tmpRoot, "other-workspace");
    const workspaceMcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    const otherMcpjvmDir = path.join(otherWorkspace, ".mcpjvm");
    fs.mkdirSync(workspaceMcpjvmDir, { recursive: true });
    fs.mkdirSync(otherMcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(workspaceMcpjvmDir, "probe-config.json"));
    fs.copyFileSync(FIXTURE, path.join(otherMcpjvmDir, "probe-config.json"));
    withEnv(
      {
        [MCP_ENV.WORKSPACE_ROOT]: workspaceRoot,
        [MCP_ENV.PROBE_CONFIG_FILE]: path.join(otherMcpjvmDir, "probe-config.json"),
        INIT_CWD: undefined,
        PWD: undefined,
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server"]);
        assert.equal(cfg.workspaceRootSource, "probe-config");
        assert.equal(cfg.workspaceRootAbs, path.resolve(otherWorkspace));
        assert.equal(
          cfg.probeRegistry?.configFileAbs,
          path.join(otherMcpjvmDir, "probe-config.json"),
        );
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

test("preserves an explicit noncanonical Probe-config override with an explicit workspace", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-server-config-noncanonical-env-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const probeConfigAbs = path.join(tmpRoot, "shared", "probe-config.json");
    fs.mkdirSync(workspaceRoot, { recursive: true });
    fs.mkdirSync(path.dirname(probeConfigAbs), { recursive: true });
    fs.copyFileSync(FIXTURE, probeConfigAbs);
    withEnv(
      {
        [MCP_ENV.WORKSPACE_ROOT]: workspaceRoot,
        [MCP_ENV.PROBE_CONFIG_FILE]: probeConfigAbs,
        INIT_CWD: undefined,
        PWD: undefined,
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server"]);
        assert.equal(cfg.workspaceRootAbs, path.resolve(workspaceRoot));
        assert.equal(cfg.probeRegistry?.configFileAbs, path.resolve(probeConfigAbs));
      },
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});
