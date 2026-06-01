const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function readUtf8(filePath: string): string {
  return fs.readFileSync(filePath, "utf8");
}

function loadExecutionProfileExportSkill() {
  const skillDir = path.join(process.cwd(), "skills", "mcp-java-dev-tools-execution-profile-export");
  const skill = readUtf8(path.join(skillDir, "SKILL.md"));
  const specRules = readUtf8(path.join(skillDir, "references", "spec-rules.md"));
  const checklist = readUtf8(path.join(skillDir, "references", "authoring-checklist.md"));
  const templatesIndex = readUtf8(path.join(skillDir, "references", "templates", "index.md"));
  const template = readUtf8(path.join(skillDir, "references", "templates", "execution_profile_export.md"));
  return { skillDir, skill, specRules, checklist, templatesIndex, template };
}

test("Execution Profile Export skill is portable with bundled references and templates", () => {
  const loaded = loadExecutionProfileExportSkill();
  assert.match(loaded.skill, /Portable Source of Truth/);
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "spec-rules.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "authoring-checklist.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "index.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "execution_profile_export.md")));
});

test("Execution Profile Export skill remains single-mode and deterministic", () => {
  const loaded = loadExecutionProfileExportSkill();
  assert.match(loaded.skill, /mode` \(`ps1` \| `sh` \| `postman`\)/);
  assert.match(loaded.skill, /do not default to `ps1`/i);
  assert.match(loaded.skill, /single selected mode/i);
  assert.match(loaded.skill, /Preserve execution order from profile plan order and contract step order/);
  assert.match(loaded.skill, /fail closed/i);
  assert.match(loaded.specRules, /mode must be exactly one of/i);
  assert.match(loaded.specRules, /missing mode must fail closed/i);
  assert.match(loaded.checklist, /mode router selected exactly one branch/i);
  assert.match(loaded.checklist, /no `ps1` fallback/i);
  assert.match(loaded.templatesIndex, /Default template id: `execution_profile_export`/);
  assert.match(loaded.template, /Plan Order/);
});
