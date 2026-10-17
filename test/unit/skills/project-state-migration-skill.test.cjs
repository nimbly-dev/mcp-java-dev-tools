const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
function readUtf8(filePath) {
    return fs.readFileSync(filePath, "utf8");
}
test("[UT][skills][project_state_migration_skill] project state migration skill uses only maintained Artifact Management actions", () => {
    const base = path.join(process.cwd(), "skills", "mcp-java-dev-tools-project-state-migration");
    const skill = readUtf8(path.join(base, "SKILL.md"));
    assert.ok(fs.existsSync(path.join(base, "agents", "openai.yaml")));
    const exampleText = skill.match(/```json\s*\n([\s\S]*?)\n\s*```/)?.[1];
    assert.ok(exampleText);
    const example = JSON.parse(exampleText);
    assert.equal(example.operationId, "artifact_management.project_context.read");
    assert.deepEqual(example.arguments.query.select, ["artifact"]);
    assert.equal(example.confirmed, false);
    assert.match(skill, /replace: true/);
    assert.match(skill, /backfill/);
    assert.match(skill, /rebuild/);
    assert.match(skill, /cutover/);
    assert.match(skill, /cleanup/);
    assert.match(skill, /query/);
    assert.doesNotMatch(skill, /DatabaseSync|INSERT INTO|SELECT .* FROM|sqlite3/i);
});
test("[UT][skills][project_state_migration_skill] project artifact manager hands state migration to the dedicated workflow", () => {
    const skill = readUtf8(path.join(process.cwd(), "skills", "mcp-java-dev-tools-project-artifact-manager", "SKILL.md"));
    assert.match(skill, /mcp-java-dev-tools-project-state-migration/);
    assert.match(skill, /replace: true/);
    assert.doesNotMatch(skill, /action=backfill/);
    assert.doesNotMatch(skill, /action=rebuild/);
    assert.doesNotMatch(skill, /action=cutover/);
});
test("[UT][skills][cde_inventory] executable examples reject retired routes without rejecting nested actions or Artifact data", () => {
    const { skillInvocationProblems } = require("../../../mcp-server/test-support/java-it-coverage.mjs");
    const supported = new Set(["probe.actuate", "jvm_lifecycle.attach"]);
    const example = (input) => `\u0060\u0060\u0060json\n${JSON.stringify(input)}\n\u0060\u0060\u0060`;
    assert.deepEqual(skillInvocationProblems(example({
        operationId: "probe.actuate", arguments: { action: "arm" }, confirmed: true,
    }), supported), []);
    assert.deepEqual(skillInvocationProblems(example({ workspaces: [{ projectRoot: "example" }] }), supported), []);
    assert.deepEqual(skillInvocationProblems(example(null), supported), []);
    assert.deepEqual(skillInvocationProblems(example([]), supported), []);
    for (const input of [
        { artifactType: "run_result", action: "read", input: { projectName: "example" } },
        { operationId: "missing.operation", arguments: {}, confirmed: false },
        { operationId: 12, arguments: {}, confirmed: false },
        { operationId: "jvm_lifecycle.attach", arguments: { confirm: true }, confirmed: true },
        { operationId: "probe.actuate", arguments: {}, confirmed: "true" },
        { mcpServers: { example: { command: "node", args: ["dist/server.js"] } } },
    ]) {
        assert.notDeepEqual(skillInvocationProblems(example(input), supported), []);
    }
});
