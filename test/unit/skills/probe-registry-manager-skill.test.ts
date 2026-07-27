const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function readUtf8(filePath: string): string {
  return fs.readFileSync(filePath, "utf8");
}

test("[UT][skills][probe_registry_manager_skill] probe registry manager skill enforces strict workspace key and probe baseUrl policy", () => {
  const skillPath = path.join(
    process.cwd(),
    "skills",
    "mcp-java-dev-tools-probe-registry-manager",
    "SKILL.md",
  );
  const text = readUtf8(skillPath);

  assert.match(text, /`workspaces\[\]` entries MUST use `root` \(not `workspaceRoot`\)\./);
  assert.match(text, /"root":/);
  assert.match(text, /Probe `baseUrl` MUST point to probe endpoint mapping/);
  assert.match(text, /not application API `server\.port`/);
  assert.match(text, /\.mcpjvm\/probe-config\.json/);
  assert.doesNotMatch(text, /workspace-local `\.mcp\/probe-config\.json`/);
  assert.match(text, /Deployment Mode Policy \(Strict\)/);
  assert.match(text, /prefer host-published app port for `runtime\.port`/);
  assert.match(text, /Do not use container-internal app port \(`8080`\)/);
  assert.match(text, /Do not assign one shared wildcard include to all services/);
  assert.match(text, /Prefer service-specific include package roots per probeId/);
});
