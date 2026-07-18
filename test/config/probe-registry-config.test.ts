const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { loadConfigFromEnvAndArgs } = require("@/config/server-config");
const { MCP_ENV } = require("@/config/env-vars");

const FIXTURE = path.resolve(__dirname, "fixtures", "probe-config.sample.json");

test("loads empty default probe base URL when active profile has multiple probes", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(mcpjvmDir, "probe-config.json"));
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", workspaceRoot]);
    assert.equal(cfg.probeBaseUrl, "");
    assert.equal(cfg.probeRegistry?.activeProfile, "dev");
    assert.equal(cfg.probeRegistry?.profileSource, "default");
    assert.equal(cfg.probeRegistry?.implicitProbeId, undefined);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("loads implicit probe base URL when active profile has exactly one probe", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-single-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    writeJson(path.join(mcpjvmDir, "probe-config.json"), {
      defaultProfile: "dev",
      profiles: {
        dev: {
          probes: {
            "order-service": {
              baseUrl: "http://127.0.0.1:9190",
              include: ["com.acme.orders.**"],
              exclude: [],
            },
          },
        },
      },
      workspaces: [{ root: workspaceRoot, profile: "dev" }],
    });
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", workspaceRoot]);
    assert.equal(cfg.probeBaseUrl, "http://127.0.0.1:9190");
    assert.equal(cfg.probeRegistry?.implicitProbeId, "order-service");
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("loads with empty default probe base URL when active profile has multiple probes", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-ambiguous-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(mcpjvmDir, "probe-config.json"));
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", workspaceRoot]);
    assert.equal(cfg.probeBaseUrl, "");
    assert.equal(cfg.probeRegistry?.activeProfile, "dev");
    assert.equal(cfg.probeRegistry?.implicitProbeId, undefined);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("keeps the server startable when no probe registry is discoverable", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-missing-"));
  const originalProbeConfigFile = process.env[MCP_ENV.PROBE_CONFIG_FILE];
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    fs.mkdirSync(workspaceRoot, { recursive: true });
    delete process.env[MCP_ENV.PROBE_CONFIG_FILE];
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", workspaceRoot]);
    assert.equal(cfg.workspaceRootAbs, path.resolve(workspaceRoot));
    assert.equal(cfg.probeRegistry, undefined);
  } finally {
    if (typeof originalProbeConfigFile === "string")
      process.env[MCP_ENV.PROBE_CONFIG_FILE] = originalProbeConfigFile;
    else delete process.env[MCP_ENV.PROBE_CONFIG_FILE];
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("fails closed when probe registry still declares defaultProbe", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-defaultprobe-reject-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    writeJson(path.join(mcpjvmDir, "probe-config.json"), {
      defaultProfile: "dev",
      profiles: {
        dev: {
          defaultProbe: "order-service",
          probes: {
            "order-service": {
              baseUrl: "http://127.0.0.1:9190",
              include: ["com.acme.orders.**"],
              exclude: [],
            },
          },
        },
      },
      workspaces: [{ root: workspaceRoot, profile: "dev" }],
    });
    assert.throws(
      () => loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", workspaceRoot]),
      /profiles\.dev\.defaultProbe is not supported/i,
    );
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

function writeJson(filePath: string, payload: Record<string, unknown>): void {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

test("does not auto-discover probe-config.json from parent directories when workspace is nested", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-parent-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const nestedRoot = path.join(workspaceRoot, "services", "visits");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(nestedRoot, { recursive: true });
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(mcpjvmDir, "probe-config.json"));
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", nestedRoot]);
    assert.equal(cfg.probeRegistry, undefined);
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("loads BOM-prefixed probe registry JSON", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-bom-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    const cfgPath = path.join(mcpjvmDir, "probe-config.json");
    const raw = fs.readFileSync(FIXTURE, "utf8");
    fs.writeFileSync(cfgPath, `\ufeff${raw}`, "utf8");
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", workspaceRoot]);
    assert.equal(cfg.probeRegistry?.activeProfile, "dev");
    assert.equal(cfg.probeBaseUrl, "");
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});
