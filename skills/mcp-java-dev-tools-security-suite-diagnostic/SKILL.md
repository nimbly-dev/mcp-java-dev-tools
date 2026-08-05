---
name: mcp-java-dev-tools-security-suite-diagnostic
description: >-
  Diagnose Security Suite plan readiness and persisted execution outcomes using bounded Artifact Management, SQLite projection, and optional live Probe evidence. Use when a Security Suite plan is invalid, blocked, partial, failed, resumed, degraded, or suspicious, or when the user asks why a security run did not produce complete coverage or trustworthy findings. This skill is read-only: it never executes, resumes, mutates, rebuilds, repairs, or cleans up suite state.
---

# MCP Java Dev Tools Security Suite Diagnostic

## Overview

Use this workflow to explain whether a Security Suite plan is executable and what caused a persisted run to pass, fail, block, partially fail, remain in progress, or become inconclusive. It applies the Security Suite contract for `securityMode=blackbox` and `securityMode=sidecar_assisted` while preserving the separation between canonical Artifact history, the SQLite operational projection, and current live Probe truth.

Load the relevant reference files before diagnosing:

- [diagnostic-workflow.md](references/diagnostic-workflow.md) for routing and bounded phases.
- [evidence-model.md](references/evidence-model.md) for historical, operational, and live evidence precedence.
- [failure-classification.md](references/failure-classification.md) for deterministic Security phases and reason codes.
- [mcp-query-playbook.md](references/mcp-query-playbook.md) for bounded MCP queries.
- [diagnostic-cases.md](references/diagnostic-cases.md) for common Security-specific cases.
- [report-contract.md](references/report-contract.md) for the required Markdown result shape.

## Scope and safety boundary

This is a diagnostic workflow, not a Security Suite executor or repair tool. Use only existing read-oriented MCP Tool actions such as `artifact_management` reads/queries and, when the plan is Sidecar-assisted, a bounded `probe` status check. Do not invoke suite execution, resume an interrupted run, mutate plans or Artifacts, rebuild or backfill SQLite, perform cutover/retention, or clean up state.

Never return credentials, tokens, cookies, raw request or response bodies, unredacted headers, exploit payloads, source fragments, or secrets. Report redacted evidence references, case IDs, finding IDs, severity/category, proof classification, phase, and reason code only.

## Workflow

1. **Route the request.** Use `plan_validation` when the user asks whether a Security plan can run. Use `execution_diagnosis` when a plan or run outcome needs explanation. If the request asks to execute, resume, repair, rebuild, or mutate state, state that this skill is read-only and stop without performing that action.
2. **Require exactly one execution selector** for `execution_diagnosis`: `planName` plus `runId`, `suiteRunId`, or a bounded `stateQuery`. Reject conflicting or missing selectors as `security_diagnostic_input_conflict`; do not guess a run.
3. **Establish project context.** Resolve the project, plan, suite type, execution profile, target boundary, security mode, optional knowledge-pack override, persisted knowledge snapshot, and whether Sidecar evidence is required. Treat an ambiguous project or plan as blocked.
4. **Read bounded evidence.** Validate/read the `security_plan`; read the selected `run_result` or `run_state` projection; read the canonical Security run Artifact summary and its bounded coverage, findings, and evidence references when available. For Sidecar-assisted plans, optionally read current Probe status only after historical evidence is loaded.
5. **Classify the failure or outcome.** Identify the earliest failed or incomplete Security phase, distinguish configuration/contract problems from runtime/transport problems, and distinguish missing evidence from a negative security result. Apply the reason-code rules in [failure-classification.md](references/failure-classification.md).
6. **Correlate without rewriting.** Compare canonical Artifact history with SQLite state and current Probe identity/timestamp. Artifact history wins for terminal outcome and coverage; SQLite explains operational state; live Probe evidence can explain current availability but cannot rewrite a completed run.
7. **Return the fixed report.** Produce exactly the four sections in [report-contract.md](references/report-contract.md): `## Diagnosis`, `## Evidence`, `## Interpretation`, and `## Next action`. Keep the next action singular and safe.

## Mode-specific rules

### Black-box

Require a finite matrix of planned cases, target/entrypoint resolution, authentication context, and redacted HTTP request/response evidence where the contract requires external proof. Treat missing external evidence as `inconclusive` or `blocked` according to the persisted case outcome; do not relabel internal observations as externally confirmed findings.

### Sidecar-assisted

Require the configured runtime context and Sidecar/Probe identity for cases that rely on runtime evidence. A live Probe check is current evidence only. It may explain `security_diagnostic_runtime_unavailable`, identity mismatch, or a stale execution, but it cannot replace persisted case coverage or prove an historical result by itself.

### Findings and proof

Preserve the persisted proof classification: `external`, `internal`, or `corroborated_external`. A finding is not "confirmed" merely because an attack request was sent or a Probe line was hit. Require the evidence references and case outcome that the Security contract specifies.

## Fail-closed outcomes

Use `blocked` when required plan, Artifact, matrix, authentication, target, or runtime evidence is unavailable. Use `inconclusive` when evidence conflicts or is insufficient to classify a finding or historical outcome. Do not infer pass, complete coverage, or confirmed external impact from missing records, a healthy current Probe, or an operational SQLite row alone.
