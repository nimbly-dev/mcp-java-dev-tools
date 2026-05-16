const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function readUtf8(filePath: string): string {
  return fs.readFileSync(filePath, "utf8");
}

function loadRunSessionExportSkill() {
  const skillDir = path.join(process.cwd(), "skills", "mcp-java-dev-tools-run-session-export");
  const skill = readUtf8(path.join(skillDir, "SKILL.md"));
  const specRules = readUtf8(path.join(skillDir, "references", "spec-rules.md"));
  const checklist = readUtf8(path.join(skillDir, "references", "authoring-checklist.md"));
  const templatesIndex = readUtf8(path.join(skillDir, "references", "templates", "index.md"));
  const template = readUtf8(path.join(skillDir, "references", "templates", "run_session_export.md"));
  return { skillDir, skill, specRules, checklist, templatesIndex, template };
}

test("run session export skill is portable with bundled references and templates", () => {
  const loaded = loadRunSessionExportSkill();
  assert.match(loaded.skill, /Portable Source of Truth/);
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "spec-rules.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "authoring-checklist.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "index.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "run_session_export.md")));
});

test("run session export skill remains single-mode and deterministic", () => {
  const loaded = loadRunSessionExportSkill();
  assert.match(loaded.skill, /mode` \(`ps1` \| `sh` \| `postman`\)/);
  assert.match(loaded.skill, /single selected mode/i);
  assert.match(loaded.skill, /Preserve execution order from `planRuns\[\]\.order`/);
  assert.match(loaded.skill, /fail closed/i);
  assert.match(loaded.specRules, /mode must be exactly one of/i);
  assert.match(loaded.checklist, /mode router selected exactly one branch/i);
  assert.match(loaded.templatesIndex, /Default template id: `run_session_export`/);
  assert.match(loaded.template, /Plan Order/);
});
