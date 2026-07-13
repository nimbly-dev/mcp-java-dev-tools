const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function readUtf8(filePath: string): string {
  return fs.readFileSync(filePath, "utf8");
}

test("project state migration skill uses only maintained Artifact Management actions", () => {
  const base = path.join(process.cwd(), "skills", "mcp-java-dev-tools-project-state-migration");
  const skill = readUtf8(path.join(base, "SKILL.md"));
  assert.ok(fs.existsSync(path.join(base, "agents", "openai.yaml")));
  assert.match(skill, /\"query\": \{ \"select\": \[\"artifact\"\] \}/);
  assert.match(skill, /replace: true/);
  assert.match(skill, /backfill/);
  assert.match(skill, /rebuild/);
  assert.match(skill, /cutover/);
  assert.match(skill, /cleanup/);
  assert.match(skill, /query/);
  assert.doesNotMatch(skill, /DatabaseSync|INSERT INTO|SELECT .* FROM|sqlite3/i);
});

test("project artifact manager hands state migration to the dedicated workflow", () => {
  const skill = readUtf8(
    path.join(process.cwd(), "skills", "mcp-java-dev-tools-project-artifact-manager", "SKILL.md"),
  );
  assert.match(skill, /mcp-java-dev-tools-project-state-migration/);
  assert.match(skill, /replace: true/);
  assert.doesNotMatch(skill, /action=backfill/);
  assert.doesNotMatch(skill, /action=rebuild/);
  assert.doesNotMatch(skill, /action=cutover/);
});
