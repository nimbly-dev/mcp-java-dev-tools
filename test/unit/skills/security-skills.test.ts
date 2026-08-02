import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";

const root = path.resolve("skills");

test("[UT][skills] Security foundation registers all three public Skill Workflows", () => {
  const names = [
    "mcp-java-dev-tools-security-plan-crafter",
    "mcp-java-dev-tools-security-suite",
    "mcp-java-dev-tools-security-result",
  ];
  for (const name of names) {
    const skillPath = path.join(root, name, "SKILL.md");
    assert.equal(fs.existsSync(skillPath), true, skillPath);
    const text = fs.readFileSync(skillPath, "utf8");
    assert.match(text, new RegExp(`name: ${name}`));
    assert.match(text, /securityMode/);
    assert.match(text, /fail.?closed/i);
  }
});
