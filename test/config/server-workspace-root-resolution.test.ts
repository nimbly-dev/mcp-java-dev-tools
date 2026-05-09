const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

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

function writeProbeConfig(root: string): void {
  const dir = path.join(root, ".mcpjvm");
  fs.mkdirSync(dir, { recursive: true });
  fs.copyFileSync(FIXTURE, path.join(dir, "probe-config.json"));
}

test("workspace root resolution prefers --workspace-root over session/cwd", () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "workspace-root-arg-"));
  try {
    const argRoot = path.join(tmp, "arg");
    const sessionRoot = path.join(tmp, "session");
    writeProbeConfig(argRoot);
    writeProbeConfig(sessionRoot);
    withEnv(
      {
        INIT_CWD: sessionRoot,
        PWD: sessionRoot,
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server", "--workspace-root", argRoot]);
        assert.equal(cfg.workspaceRootSource, "arg");
        assert.equal(cfg.workspaceRootAbs, path.resolve(argRoot));
      },
    );
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("workspace root resolution uses INIT_CWD then PWD when CLI arg is absent", () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "workspace-root-session-"));
  try {
    const sessionRoot = path.join(tmp, "session");
    writeProbeConfig(sessionRoot);
    withEnv(
      {
        INIT_CWD: sessionRoot,
        PWD: undefined,
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server"]);
        assert.equal(cfg.workspaceRootSource, "session");
        assert.equal(cfg.workspaceRootAbs, path.resolve(sessionRoot));
      },
    );
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test("workspace root resolution falls back to cwd when session vars are absent", () => {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "workspace-root-cwd-"));
  const original = process.cwd();
  try {
    writeProbeConfig(tempRoot);
    process.chdir(tempRoot);
    withEnv(
      {
        INIT_CWD: undefined,
        PWD: undefined,
        CODEX_WORKSPACE_ROOT: "C:\\repo\\codex-workspace",
        CODEX_CWD: "C:\\repo\\codex-cwd",
      },
      () => {
        const cfg = loadConfigFromEnvAndArgs(["node", "server"]);
        assert.equal(cfg.workspaceRootSource, "cwd");
        assert.equal(cfg.workspaceRootAbs, path.resolve(tempRoot));
      },
    );
  } finally {
    process.chdir(original);
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});

