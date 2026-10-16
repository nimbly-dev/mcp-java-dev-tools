---
name: mcp-java-dev-tools-regression-suite-diagnostic
description: "Read-only diagnosis of Regression Suite plan readiness and execution outcomes using bounded Artifact Management, SQLite projection, and optional live Probe evidence. Use for plan validation, failed/blocked/partial/resumed/degraded execution diagnosis, or suspicious run investigation; never use it to execute, resume, mutate, rebuild, or repair state."
---

# Regression Suite Diagnostic

## Java CDE invocation

The public MCP Tools are `operation_catalog`, `operation_describe`, and `operation_execute`. Discover supported IDs with Catalog; call Describe with `{"operationId":"<id>"}` before execution. Java Describe owns the current argument schema and safety requirements.

Call `operation_execute` with `{"operationId":"<id>","arguments":{},"confirmed":false}`, replacing `arguments` with the described fields. Set outer `confirmed:true` only for an authorized operation when Describe requires it. Do not send the retired Tool/action/input envelope or an inner lifecycle `confirm`. Nested operation-specific actions remain in `arguments.action` only when Describe declares them. Read the CDE status first, then the capability result in `result`; use structured MCP content and preserve reason codes. Artifact details are under `result.details`; Probe and lifecycle action data are under `result.actionResult`.

This workflow consumes these canonical IDs as needed (not an instruction to execute every operation):

- `artifact_management.run_result.list`
- `artifact_management.run_result.read`
- `artifact_management.run_result.query`

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

- Artifact Management operations for plan validation/read, project-context read, run Artifact read, and applicable SQLite queries;
- `probe.status` only for optional current readiness observation.

Never execute triggers, plans, suites, replay exports, or resumes. Never mutate or repair runtimes, Probes, plans, project context, Probe configuration, Artifacts, or SQLite state. Never use deprecated shared JSON indexes as a query fallback.

## Evidence discipline

Keep historical execution evidence separate from optional live runtime evidence. Use canonical per-run Artifacts as historical truth and SQLite only as bounded operational/query projection. Preserve Watcher, external-verification, and Correlation outcomes as separate concerns. Return `blocked` or `inconclusive` when evidence is unavailable, corrupt, ambiguous, or conflicting; do not infer missing proof.

Return concise, human-readable Markdown only. Use exactly these sections, in order:

1. `## Diagnosis` — status, phase, reason code, and plain-language cause;
2. `## Evidence` — bounded Artifact, SQLite projection, project-context, and optional current Sidecar/Probe observations;
3. `## Interpretation` — explain the outcome and clearly separate historical execution evidence from current runtime readiness;
4. `## Next action` — exactly one smallest safe action.

Render deterministic reason codes and bounded evidence references as readable Markdown. Do not generate, persist, or expose `diagnosis.result.json` or any other diagnostic JSON output. Do not expose credentials, authorization headers, raw sensitive correlation values, unbounded response bodies, logs, contracts, or SQL rows.
