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

test("[UT][skills][regression_result_skill][ok] regression result skill is portable with bundled references and templates", () => {
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

test("[UT][skills][regression_result_skill][ok] result template index defines endpoint_table_result as default template", () => {
  const { templatesIndex } = loadResultSkill();
  assert.match(templatesIndex, /Default template id: `endpoint_table_result`/);
  assert.match(templatesIndex, /1\. `endpoint_table_result`/);
  assert.match(templatesIndex, /http_result_table\/endpoint_table_result\.md/);
});

test("[UT][skills][regression_result_skill][ok] endpoint table template defines required columns and memory gate rule", () => {
  const { endpointTemplate, specRules } = loadResultSkill();
  assert.match(
    endpointTemplate,
    /\| Endpoint \| Status \| HTTP Code \| Duration \(ms\) \| Probe Coverage \|/,
  );
  assert.match(endpointTemplate, /verified_line_hit/);
  assert.match(endpointTemplate, /http_only_unverified_line/);
  assert.match(endpointTemplate, /Memory \(bytes\)/);
  assert.match(specRules, /contract-defined/);
  assert.match(specRules, /verified_line_hit/);
  assert.match(specRules, /http_only_unverified_line/);
});
