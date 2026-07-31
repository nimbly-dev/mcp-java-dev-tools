const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function readUtf8(filePath: string): string {
  return fs.readFileSync(filePath, "utf8");
}

function loadResultSkill() {
  const skillDir = path.join(process.cwd(), "skills", "mcp-java-dev-tools-regression-result");
  const skill = readUtf8(path.join(skillDir, "SKILL.md"));
  const specRules = readUtf8(path.join(skillDir, "references", "spec-rules.md"));
  const checklist = readUtf8(path.join(skillDir, "references", "authoring-checklist.md"));
  const templatesIndex = readUtf8(path.join(skillDir, "references", "templates", "index.md"));
  const endpointTemplate = readUtf8(
    path.join(skillDir, "references", "templates", "http_result_table", "endpoint_table_result.md"),
  );
  return { skillDir, skill, specRules, checklist, templatesIndex, endpointTemplate };
}

test("[UT][skills][regression_result_skill] regression result skill is portable with bundled references and templates", () => {
  const loaded = loadResultSkill();
  assert.match(loaded.skill, /Portable Source of Truth/);
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "spec-rules.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "authoring-checklist.md")));
  assert.ok(fs.existsSync(path.join(loaded.skillDir, "references", "templates", "index.md")));
  assert.ok(
    fs.existsSync(
      path.join(
        loaded.skillDir,
        "references",
        "templates",
        "http_result_table",
        "endpoint_table_result.md",
      ),
    ),
  );
});

test("[UT][skills][regression_result_skill] result template index defines endpoint_table_result as default template", () => {
  const { templatesIndex } = loadResultSkill();
  assert.match(templatesIndex, /Default template id: `endpoint_table_result`/);
  assert.match(templatesIndex, /1\. `endpoint_table_result`/);
  assert.match(templatesIndex, /http_result_table\/endpoint_table_result\.md/);
});

test("[UT][skills][regression_result_skill] endpoint table template defines required columns and memory gate rule", () => {
  const { endpointTemplate, specRules } = loadResultSkill();
  assert.match(
    endpointTemplate,
    /\|\s*Endpoint\s*\|\s*Status\s*\|\s*HTTP Code\s*\|\s*Duration \(ms\)\s*\|\s*Probe Coverage\s*\|/,
  );
  assert.match(endpointTemplate, /verified_line_hit/);
  assert.match(endpointTemplate, /http_only_unverified_line/);
  assert.match(endpointTemplate, /Memory \(bytes\)/);
  assert.match(specRules, /contract-defined/);
  assert.match(specRules, /verified_line_hit/);
  assert.match(specRules, /http_only_unverified_line/);
});

test("[UT][skills][regression_result_skill] failed assertion diagnostics are documented as persisted and redacted", () => {
  const { skill, specRules, endpointTemplate, templatesIndex } = loadResultSkill();
  assert.match(skill, /Failed-Assertion Diagnostics/);
  assert.match(skill, /\[not persisted\]/);
  assert.match(specRules, /skipped_optional/);
  assert.match(specRules, /256 characters/);
  assert.match(endpointTemplate, /\|\s*Step\s*\|\s*Endpoint\s*\|\s*Assertion\s*\|/);
  assert.match(templatesIndex, /failed_assertions/);
});
