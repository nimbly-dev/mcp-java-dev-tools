const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function skillPath(...parts: string[]): string {
  return path.join(
    process.cwd(),
    "skills",
    "mcp-java-dev-tools-security-suite-diagnostic",
    ...parts,
  );
}

function readSkillFile(...parts: string[]): string {
  return fs.readFileSync(skillPath(...parts), "utf8");
}

test("[UT][skills][security_suite_diagnostic_skill] diagnostic skill has the required portable package", () => {
  assert.ok(fs.existsSync(skillPath("SKILL.md")));
  assert.ok(fs.existsSync(skillPath("agents", "openai.yaml")));
  for (const reference of [
    "diagnostic-workflow.md",
    "evidence-model.md",
    "failure-classification.md",
    "mcp-query-playbook.md",
    "report-contract.md",
    "diagnostic-cases.md",
  ]) {
    assert.ok(fs.existsSync(skillPath("references", reference)), reference);
  }
  assert.equal(fs.existsSync(skillPath("templates", "diagnosis.result.json")), false);
});

test("[UT][skills][security_suite_diagnostic_skill] declares Security routes and exact selector rules", () => {
  const skill = readSkillFile("SKILL.md");
  const workflow = readSkillFile("references", "diagnostic-workflow.md");
  assert.match(skill, /plan_validation/);
  assert.match(skill, /execution_diagnosis/);
  assert.match(skill, /exactly one execution selector/);
  assert.match(skill, /`planName` plus `runId`/);
  assert.match(skill, /`suiteRunId`/);
  assert.match(skill, /`stateQuery`/);
  assert.match(skill, /security_diagnostic_input_conflict/);
  assert.match(workflow, /Require exactly one selector/);
  assert.match(workflow, /knowledge_pack_selection/);
});

test("[UT][skills][security_suite_diagnostic_skill] preserves read-only and bounded evidence boundaries", () => {
  const skill = readSkillFile("SKILL.md");
  const playbook = readSkillFile("references", "mcp-query-playbook.md");
  const evidence = readSkillFile("references", "evidence-model.md");
  assert.match(skill, /never executes, resumes, mutates, rebuilds, repairs, or cleans up/);
  assert.match(skill, /Never return credentials, tokens, cookies/);
  assert.match(playbook, /"artifactType":"security_plan"/);
  assert.match(playbook, /"artifactType":"run_result"/);
  assert.match(playbook, /"stateSurface":"run_state"/);
  assert.match(playbook, /Forbidden operations/);
  assert.match(evidence, /Canonical run Artifact/);
  assert.match(evidence, /SQLite projection/);
  assert.match(evidence, /Live Probe status/);
});

test("[UT][skills][security_suite_diagnostic_skill] distinguishes Security proof classifications and live evidence", () => {
  const skill = readSkillFile("SKILL.md");
  const evidence = readSkillFile("references", "evidence-model.md");
  const contract = readSkillFile("references", "report-contract.md");
  for (const classification of ["external", "internal", "corroborated_external"]) {
    assert.match(evidence, new RegExp(`\\b${classification}\\b`));
  }
  assert.match(skill, /live Probe evidence can explain current availability/);
  assert.match(evidence, /cannot change a persisted terminal result/);
  assert.match(contract, /historical Artifact truth, SQLite operational state, and live Probe truth/);
});

test("[UT][skills][security_suite_diagnostic_skill] requires the four-section Markdown report", () => {
  const skill = readSkillFile("SKILL.md");
  const contract = readSkillFile("references", "report-contract.md");
  for (const section of ["## Diagnosis", "## Evidence", "## Interpretation", "## Next action"]) {
    assert.match(skill, new RegExp(section.replace(" ", "\\s+")));
    assert.match(contract, new RegExp(section.replace(" ", "\\s+")));
  }
  assert.match(skill, /Produce exactly the four sections/);
  assert.match(contract, /Return Markdown only/);
  assert.match(contract, /no raw JSON dump/);
});

test("[UT][skills][security_suite_diagnostic_skill] covers Security phases, reason codes, and representative cases", () => {
  const classification = readSkillFile("references", "failure-classification.md");
  const cases = readSkillFile("references", "diagnostic-cases.md");
  for (const phase of [
    "preflight",
    "knowledge_pack_selection",
    "matrix_generation",
    "authentication_context",
    "transport_execution",
    "baseline_attack_evaluation",
    "coverage_persistence",
    "execution_orchestration",
  ]) {
    assert.match(classification, new RegExp("\\`" + phase + "\\`"));
  }
  for (const reasonCode of [
    "security_diagnostic_artifact_unavailable",
    "security_diagnostic_artifact_corrupt",
    "security_diagnostic_sqlite_unavailable",
    "security_diagnostic_evidence_conflict",
    "security_diagnostic_runtime_unavailable",
    "security_diagnostic_finding_evidence_missing",
  ]) {
    assert.match(classification, new RegExp(reasonCode));
  }
  for (const scenario of [
    "Invalid mode or contract",
    "Knowledge pack unavailable or mismatched",
    "Complete Black-box run",
    "Finding without required external proof",
    "Sidecar runtime identity mismatch",
    "Terminal Artifact with stale SQLite projection",
  ]) {
    assert.match(cases, new RegExp(scenario));
  }
});
