const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function readUtf8(filePath: string): string {
  return fs.readFileSync(filePath, "utf8");
}

function loadBundledSkill(skillName: string) {
  const skillDir = path.join(process.cwd(), "skills", skillName);
  const skill = readUtf8(path.join(skillDir, "SKILL.md"));
  const specRules = readUtf8(path.join(skillDir, "references", "spec-rules.md"));
  const checklist = readUtf8(path.join(skillDir, "references", "authoring-checklist.md"));
  const templatesIndex = readUtf8(path.join(skillDir, "references", "templates", "index.md"));
  const template = readUtf8(path.join(skillDir, "references", "templates", "execution_profile_export.md"));
  return { skillDir, skill, specRules, checklist, templatesIndex, template };
}

test("Regression Export Skill Workflow is portable with bundled references and templates", () => {
  const loaded = loadBundledSkill("mcp-java-dev-tools-regression-export");
  assert.match(loaded.skill, /Regression Export/);
  assert.match(loaded.skill, /suiteType=regression/);
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "spec-rules.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "authoring-checklist.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "index.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "execution_profile_export.md")));
});

test("Regression Export Skill Workflow remains single-mode and deterministic", () => {
  const loaded = loadBundledSkill("mcp-java-dev-tools-regression-export");
  assert.match(loaded.skill, /mode` \(`ps1` \| `sh` \| `postman`\)/);
  assert.match(loaded.skill, /do not default to `ps1`/i);
  assert.match(loaded.skill, /single selected mode/i);
  assert.match(loaded.skill, /suiteType=regression/);
  assert.match(loaded.specRules, /Selected execution profile must be `suiteType=regression`/);
  assert.match(loaded.checklist, /mode router selected exactly one branch/i);
  assert.match(loaded.templatesIndex, /Default template id: `regression_execution_profile_export`/);
  assert.match(loaded.template, /Replay Package Type/);
});

test("Performance Export Skill Workflow is portable with bundled references and templates", () => {
  const loaded = loadBundledSkill("mcp-java-dev-tools-performance-export");
  assert.match(loaded.skill, /Performance Export/);
  assert.match(loaded.skill, /suiteType=performance/);
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "spec-rules.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "authoring-checklist.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "index.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "execution_profile_export.md")));
});

test("Performance Export Skill Workflow remains single-mode and deterministic", () => {
  const loaded = loadBundledSkill("mcp-java-dev-tools-performance-export");
  assert.match(loaded.skill, /mode` \(`ps1` \| `sh`\)/);
  assert.match(loaded.skill, /do not default to `ps1`/i);
  assert.match(loaded.skill, /single selected mode/i);
  assert.match(loaded.skill, /mode=postman` => fail closed/i);
  assert.match(loaded.specRules, /`postman` must fail closed \(`performance_export_mode_unsupported`\)/);
  assert.match(loaded.checklist, /`postman` is rejected deterministically/i);
  assert.match(loaded.templatesIndex, /Default template id: `performance_execution_profile_export`/);
  assert.match(loaded.template, /replays workload execution/i);
});
