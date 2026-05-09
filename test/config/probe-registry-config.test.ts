const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const { loadConfigFromEnvAndArgs } = require("@/config/server-config");

const FIXTURE = path.resolve(__dirname, "fixtures", "probe-config.sample.json");

test("loads probe base URL from configured default probe in registry", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(mcpjvmDir, "probe-config.json"));
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", workspaceRoot]);
    assert.equal(cfg.probeBaseUrl, "http://127.0.0.1:9190");
    assert.equal(cfg.probeRegistry?.activeProfile, "dev");
    assert.equal(cfg.probeRegistry?.profileSource, "default");
    assert.equal(cfg.probeRegistry?.defaultProbeId, "order-service");
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});

test("auto-discovers probe-config.json from parent directories when workspace is nested", () => {
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), "mcp-probe-registry-parent-"));
  try {
    const workspaceRoot = path.join(tmpRoot, "workspace");
    const nestedRoot = path.join(workspaceRoot, "services", "visits");
    const mcpjvmDir = path.join(workspaceRoot, ".mcpjvm");
    fs.mkdirSync(nestedRoot, { recursive: true });
    fs.mkdirSync(mcpjvmDir, { recursive: true });
    fs.copyFileSync(FIXTURE, path.join(mcpjvmDir, "probe-config.json"));
    const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", nestedRoot]);
    assert.equal(
      cfg.probeRegistry?.configFileAbs,
      path.join(workspaceRoot, ".mcpjvm", "probe-config.json"),
    );
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
    assert.equal(cfg.probeBaseUrl, "http://127.0.0.1:9190");
  } finally {
    fs.rmSync(tmpRoot, { recursive: true, force: true });
  }
});
