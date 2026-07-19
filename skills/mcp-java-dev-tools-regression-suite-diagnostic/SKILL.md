---
name: mcp-java-dev-tools-regression-suite-diagnostic
description: "Read-only diagnosis of Regression Suite plan readiness and execution outcomes using bounded Artifact Management, SQLite projection, and optional live Probe evidence. Use for plan validation, failed/blocked/partial/resumed/degraded execution diagnosis, or suspicious run investigation; never use it to execute, resume, mutate, rebuild, or repair state."
---

# Regression Suite Diagnostic

Produce a deterministic, evidence-backed diagnosis without becoming an executor, planner, state store, or Artifact implementation.

## Routes

Use `plan_validation` with `projectName` and `planName` to determine whether a plan is executable in its selected project context.

Use `execution_diagnosis` with exactly one execution selector:

1. `planName` plus `runId`;
2. `suiteRunId`; or
3. `stateQuery`, which must resolve exactly one execution.

Reject mixed or incomplete selectors with `diagnostic_input_conflict`. Return `diagnostic_run_not_found` for zero state-query matches and `diagnostic_run_ambiguous` for multiple matches.

Read `references/diagnostic-workflow.md` for sequencing and bounded-read rules. Use `references/evidence-model.md` for source-of-truth and security rules, `references/failure-classification.md` for phase classification, `references/mcp-query-playbook.md` for exact MCP request shapes, `references/report-contract.md` for the Markdown report contract and reason codes, and `references/diagnostic-cases.md` for representative outcomes.

## Read-only boundary

Use existing MCP Tools only:

- `artifact_management` for plan validation/read, project-context read, run Artifact read, and applicable SQLite queries;
- `probe` with `action="status"` only for optional current readiness observation.

Never execute triggers, plans, suites, replay exports, or resumes. Never mutate or repair runtimes, Probes, plans, project context, Probe configuration, Artifacts, or SQLite state. Never use deprecated shared JSON indexes as a query fallback.

## Evidence discipline

Keep historical execution evidence separate from optional live runtime evidence. Use canonical per-run Artifacts as historical truth and SQLite only as bounded operational/query projection. Preserve Watcher, external-verification, and Correlation outcomes as separate concerns. Return `blocked` or `inconclusive` when evidence is unavailable, corrupt, ambiguous, or conflicting; do not infer missing proof.

Return concise, human-readable Markdown only. Use exactly these sections, in order:

1. `## Diagnosis` — status, phase, reason code, and plain-language cause;
2. `## Evidence` — bounded Artifact, SQLite projection, project-context, and optional current Sidecar/Probe observations;
3. `## Interpretation` — explain the outcome and clearly separate historical execution evidence from current runtime readiness;
4. `## Next action` — exactly one smallest safe action.

Render deterministic reason codes and bounded evidence references as readable Markdown. Do not generate, persist, or expose `diagnosis.result.json` or any other diagnostic JSON output. Do not expose credentials, authorization headers, raw sensitive correlation values, unbounded response bodies, logs, contracts, or SQL rows.
