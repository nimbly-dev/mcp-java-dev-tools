const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const test = require("node:test");

const {
  resolveProbeConfigFileForWorkspace,
  resolveWorkspaceFromRoots,
} = require("@/config/workspace-context");

function fileUri(root: string): string {
  return pathToFileURL(root).toString();
}

test("normalizes a root pointing at .mcpjvm", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mcpjvm-root-"));
  try {
    fs.mkdirSync(path.join(root, ".mcpjvm"), { recursive: true });
    const result = resolveWorkspaceFromRoots([{ uri: fileUri(path.join(root, ".mcpjvm")) }]);
    assert.equal(result.workspaceRootAbs, root);
    assert.equal(result.source, "roots");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("fails closed for multiple canonical workspace roots", () => {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), "mcpjvm-roots-"));
  try {
    const first = path.join(parent, "first");
    const second = path.join(parent, "second");
    for (const root of [first, second]) {
      fs.mkdirSync(path.join(root, ".mcpjvm"), { recursive: true });
      fs.writeFileSync(path.join(root, ".mcpjvm", "probe-config.json"), "{}");
    }
    const result = resolveWorkspaceFromRoots([{ uri: fileUri(first) }, { uri: fileUri(second) }]);
    assert.equal(result.reasonCode, "workspace_context_ambiguous");
  } finally {
    fs.rmSync(parent, { recursive: true, force: true });
  }
});

test("returns missing when Roots are removed", () => {
  const result = resolveWorkspaceFromRoots([]);
  assert.equal(result.workspaceRootAbs, undefined);
  assert.equal(result.reasonCode, "workspace_context_missing");
});

test("preserves explicit Probe-config overrides across workspace rebinding", () => {
  const workspace = path.join(os.tmpdir(), "mcpjvm-rebound");
  assert.equal(
    resolveProbeConfigFileForWorkspace(workspace, ".mcpjvm/probe-config.json"),
    path.join(workspace, ".mcpjvm", "probe-config.json"),
  );
  const absoluteOverride = path.join(os.tmpdir(), "shared-probe-config.json");
  assert.equal(
    resolveProbeConfigFileForWorkspace(workspace, absoluteOverride),
    path.resolve(absoluteOverride),
  );
});

test("preserves UNC workspace Roots on Windows", () => {
  if (process.platform !== "win32") return;
  const result = resolveWorkspaceFromRoots([{ uri: "file://server/share/workspace" }]);
  assert.equal(result.workspaceRootAbs, path.resolve("\\\\server\\share\\workspace"));
});
