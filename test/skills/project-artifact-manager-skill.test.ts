const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function readUtf8(filePath: string): string {
  return fs.readFileSync(filePath, "utf8");
}

test("project artifact manager skill enforces separation, prompt, and secret rules", () => {
  const skillPath = path.join(
    process.cwd(),
    "skills",
    "mcp-java-dev-tools-project-artifact-manager",
    "SKILL.md",
  );
  const text = readUtf8(skillPath);

  assert.match(text, /\.mcpjvm\/<project-name>\/projects\.json/);
  assert.match(text, /If project name is missing, ask the user first/);
  assert.match(text, /`probe-config\.json` remains authoritative/);
  assert.match(text, /MUST NOT duplicate probe endpoint config/);
  assert.match(text, /never resolved token values/i);
  assert.match(text, /mode` is restricted to `terminal` and `docker`/);
  assert.match(text, /autoStart/);
  assert.match(text, /autoStopOnFinish/);
  assert.match(text, /startups\[]/);
  assert.match(text, /appdir/);
  assert.match(text, /external_healthcheck_failed/);
  assert.match(text, /references\/postgres\.md/);
  assert.match(text, /references\/dynamodb\.md/);
  assert.match(text, /references\/keycloak\.md/);
});

test("project artifact manager references are present", () => {
  const base = path.join(
    process.cwd(),
    "skills",
    "mcp-java-dev-tools-project-artifact-manager",
  );
  assert.ok(fs.existsSync(path.join(base, "README.md")));
  assert.ok(fs.existsSync(path.join(base, "references", "postgres.md")));
  assert.ok(fs.existsSync(path.join(base, "references", "dynamodb.md")));
  assert.ok(fs.existsSync(path.join(base, "references", "keycloak.md")));
  assert.ok(fs.existsSync(path.join(base, "references", "validation-rules.md")));
  assert.ok(fs.existsSync(path.join(base, "templates", "projects.terminal.example.json")));
});
