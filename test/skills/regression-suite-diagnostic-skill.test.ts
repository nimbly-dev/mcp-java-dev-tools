const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

function skillPath(...parts: string[]): string {
  return path.join(
    process.cwd(),
    "skills",
    "mcp-java-dev-tools-regression-suite-diagnostic",
    ...parts,
  );
}

function readSkillFile(...parts: string[]): string {
  return fs.readFileSync(skillPath(...parts), "utf8");
}

test("diagnostic skill has the required portable package", () => {
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
  assert.equal(fs.existsSync(skillPath("templates", "diagnosis.result.schema.json")), false);
});

test("diagnostic skill declares both routes and exact selector rules", () => {
  const skill = readSkillFile("SKILL.md");
  const workflow = readSkillFile("references", "diagnostic-workflow.md");
  assert.match(skill, /plan_validation/);
  assert.match(skill, /execution_diagnosis/);
  assert.match(skill, /exactly one execution selector/);
  assert.match(skill, /planName.*runId/);
  assert.match(skill, /suiteRunId/);
  assert.match(skill, /stateQuery/);
  assert.match(skill, /diagnostic_input_conflict/);
  assert.match(skill, /diagnostic_run_not_found/);
  assert.match(skill, /diagnostic_run_ambiguous/);
  assert.match(workflow, /must resolve exactly one execution/);
});

test("diagnostic skill proves the read-only and bounded-read boundary", () => {
  const skill = readSkillFile("SKILL.md");
  const playbook = readSkillFile("references", "mcp-query-playbook.md");
  const evidence = readSkillFile("references", "evidence-model.md");
  assert.match(skill, /Never execute triggers, plans, suites, replay exports, or resumes/);
  assert.match(skill, /Never mutate or repair runtimes, Probes, plans/);
  assert.match(workflowText(), /summary-first, windowed, or cursor-bounded/);
  assert.match(playbook, /"action":"validate"/);
  assert.match(playbook, /"action":"query"/);
  assert.match(playbook, /"stateSurface":"run_state"/);
  assert.match(evidence, /Deprecated shared JSON indexes must never be used/);
});

test("diagnostic skill keeps historical and live evidence separate", () => {
  const skill = readSkillFile("SKILL.md");
  const evidence = readSkillFile("references", "evidence-model.md");
  const playbook = readSkillFile("references", "mcp-query-playbook.md");
  const contract = readSkillFile("references", "report-contract.md");
  assert.match(skill, /historical execution evidence separate from optional live runtime evidence/);
  assert.match(evidence, /Canonical per-run Artifacts are authoritative/);
  assert.match(evidence, /SQLite is an operational\/query projection/);
  assert.match(playbook, /"action": "status"/);
  assert.match(playbook, /"probeId"/);
  assert.match(playbook, /"key": "fully\.qualified\.Class#method:line"/);
  assert.match(playbook, /diagnostic_runtime_unavailable/);
  assert.match(playbook, /diagnostic_runtime_timeout/);
  assert.match(contract, /current Sidecar\/Probe observations/);
  assert.match(contract, /historical execution evidence/);
  assert.match(contract, /## Next action/);
});

test("diagnostic skill requires concise Markdown output and forbids diagnostic JSON", () => {
  const skill = readSkillFile("SKILL.md");
  const contract = readSkillFile("references", "report-contract.md");
  for (const section of ["## Diagnosis", "## Evidence", "## Interpretation", "## Next action"]) {
    assert.match(skill, new RegExp(section.replace(" ", "\\s+")));
    assert.match(contract, new RegExp(section.replace(" ", "\\s+")));
  }
  assert.match(skill, /human-readable Markdown only/);
  assert.match(skill, /Do not generate, persist, or expose `diagnosis\.result\.json`/);
  assert.match(contract, /MUST NOT generate, persist, or expose `diagnosis\.result\.json`/);
});

test("diagnostic skill documents representative diagnosis outcomes", () => {
  const cases = readSkillFile("references", "diagnostic-cases.md");
  for (const expected of [
    "Invalid plan/project compatibility",
    "Current Probe readiness failure",
    "Watcher timeout",
    "External-verification failure",
    "Missing expectedFlow Correlation stage",
    "Async consumer scope/probe failure",
    "Degraded SQLite with terminal Artifact evidence",
    "Active resumable suite checkpoint",
  ]) {
    assert.match(cases, new RegExp(expected));
  }
  assert.match(cases, /one safe action under `## Next action`/);
});

test("diagnostic skill covers all eight phases and base reason codes", () => {
  const classification = readSkillFile("references", "failure-classification.md");
  for (const phase of [
    "preflight",
    "strict_probe_gate",
    "trigger_execution",
    "watchers",
    "external_verification",
    "correlation",
    "artifact_persistence",
    "execution_orchestration",
  ]) {
    assert.match(classification, new RegExp(`diagnostic_phase_${phase}`));
  }
  for (const reasonCode of [
    "diagnostic_artifact_unavailable",
    "diagnostic_artifact_corrupt",
    "diagnostic_sqlite_unavailable",
    "diagnostic_sqlite_corrupt",
    "diagnostic_evidence_conflict",
    "diagnostic_runtime_unavailable",
    "diagnostic_runtime_timeout",
  ]) {
    assert.match(classification, new RegExp(reasonCode));
  }
  assert.match(classification, /underlyingReasonCodes/);
});

function workflowText(): string {
  return readSkillFile("references", "diagnostic-workflow.md");
}
