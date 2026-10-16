---
name: mcp-java-dev-tools-security-result
description: "Render deterministic Security Suite coverage, findings, evidence references, outcomes, and proof classifications from persisted run Artifacts."
---

# MCP JVM Security Result

## Java CDE invocation

The public MCP Tools are `operation_catalog`, `operation_describe`, and `operation_execute`. Discover supported IDs with Catalog; call Describe with `{"operationId":"<id>"}` before execution. Java Describe owns the current argument schema and safety requirements.

Call `operation_execute` with `{"operationId":"<id>","arguments":{},"confirmed":false}`, replacing `arguments` with the described fields. Set outer `confirmed:true` only for an authorized operation when Describe requires it. Do not send the retired Tool/action/input envelope or an inner lifecycle `confirm`. Nested operation-specific actions remain in `arguments.action` only when Describe declares them. Read the CDE status first, then the capability result in `result`; use structured MCP content and preserve reason codes. Artifact details are under `result.details`; Probe and lifecycle action data are under `result.actionResult`.

This workflow consumes these canonical IDs as needed (not an instruction to execute every operation):

- `artifact_management.run_result.list`
- `artifact_management.run_result.read`
- `artifact_management.run_result.query`

This Skill Workflow reports Security Suite results; it does not execute plans or alter findings.

## Report contract

Read the canonical Security run Artifact and report:

- run status and selected `securityMode`;
- planned versus executed finite-matrix coverage;
- resolved knowledge snapshot (selection mode, pack refs, compatibility metadata, and content digests);
- counts for `passed`, `confirmed`, `not_applicable`, and `blocked` cases;
- findings with severity, category, and proof classification;
- redacted evidence references and deterministic reason codes.

## Verdict rules

Never render incomplete required coverage as a clean pass. Preserve the distinction between external proof, internal hardening evidence, and corroborated external proof. If the canonical run Artifact is missing, invalid, or conflicting, fail closed and return a blocked result with the exact missing evidence.

The mode-neutral foundation defines the report shape. Black-box findings use external HTTP request/response evidence; Sidecar-assisted evidence interpretation remains owned by its mode implementation.

For Sidecar-assisted runs, include the fresh Probe evidence references, runtime identity, required/forbidden target interpretation, and proof classification. Distinguish `external`, `internal`, and `corroborated_external`; a Sidecar-only signal must never be rendered as an externally demonstrated exploit.

Render selected application/dependency instrumentation as bounded runtime evidence, including the target scope and dependency reference when present. Do not render raw source, credentials, environment values, or unrestricted classpath contents.

For MCP result reads, set `arguments.suiteType=security` and retain the exact persisted `planName`/`runId`; do not select by directory ordering or confuse identically named runs from different suite types.
