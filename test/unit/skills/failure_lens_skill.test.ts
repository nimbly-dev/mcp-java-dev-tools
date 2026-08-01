const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const skillPath = path.join(process.cwd(), "skills", "mcp-java-dev-tools-failure-lens", "SKILL.md");

function readSkill(): string {
  return fs.readFileSync(skillPath, "utf8");
}

test("[UT][skills][failure_lens] documents public MCP composition and bounded authorization", () => {
  const skill = readSkill();

  for (const phrase of [
    "public `failure_analysis`, `jvm_lifecycle`, Route Synthesis, and Probe MCP Tools",
    "guided",
    "hands_off",
    "attemptLimit",
    "elapsedTimeLimitMs",
    "Do not call MCP transport",
  ]) {
    assert.match(skill, new RegExp(phrase.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});

test("[UT][skills][failure_lens] documents every protected terminal path and cleanup", () => {
  const skill = readSkill();

  for (const outcome of [
    "BLOCKED_AMBIGUOUS_JVM",
    "BLOCKED_MISSING_AUTH",
    "BLOCKED_MISSING_TRIGGER",
    "BLOCKED_USER_ACTION_REQUIRED",
    "BLOCKED_UNSAFE_OPERATION",
    "ENVIRONMENT_MISMATCH",
    "CANCELLED",
    "cleanupStatus",
    "ttlMs",
  ]) {
    assert.match(skill, new RegExp(outcome));
  }
  assert.match(skill, /do not inspect files or environment values/);
  assert.match(skill, /Failure Lens Artifact or physical Markdown report/);
});
