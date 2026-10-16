---
name: mcp-java-dev-tools-security-suite
description: "Execute a bounded Security Suite plan through the existing execution orchestration path, with explicit Black-box and Sidecar-assisted mode routing and fail-closed coverage semantics."
---

# MCP JVM Security Suite

## Java CDE invocation

The public MCP Tools are `operation_catalog`, `operation_describe`, and `operation_execute`. Discover supported IDs with Catalog; call Describe with `{"operationId":"<id>"}` before execution. Java Describe owns the current argument schema and safety requirements.

Call `operation_execute` with `{"operationId":"<id>","arguments":{},"confirmed":false}`, replacing `arguments` with the described fields. Set outer `confirmed:true` only for an authorized operation when Describe requires it. Do not send the retired Tool/action/input envelope or an inner lifecycle `confirm`. Nested operation-specific actions remain in `arguments.action` only when Describe declares them. Read the CDE status first, then the capability result in `result`; use structured MCP content and preserve reason codes. Artifact details are under `result.details`; Probe and lifecycle action data are under `result.actionResult`.

This workflow consumes these canonical IDs as needed (not an instruction to execute every operation):

- `artifact_management.project_context.read`
- `artifact_management.security_plan.read`
- `security_suite.execute_plan`
- `execution_orchestration.execute`
- `artifact_management.run_result.read`

This Skill Workflow is the execution router for Security Suite plans.

For one approved plan, invoke `security_suite.execute_plan` with the described `projectName`, `executionProfile`, `planName`, and a fresh `suiteRunId`. For the ordered execution profile, use `execution_orchestration.execute` with bounded `maxPlansPerCall` and continue the same returned suite identity while `result.status=in_progress`. Check CDE status before interpreting the capability result. Java owns HTTP/Probe execution and Artifact persistence in both modes.

## Intent router

1. Load the selected execution profile and require `suiteType=security`.
2. Load and validate the Security plan Artifact through `artifact_management.security_plan.read` and `artifact_management.security_plan.validate`.
3. Route `securityMode=blackbox` to Black-box execution or `securityMode=sidecar_assisted` to Sidecar-assisted execution.
4. For Black-box, resolve the immutable catalog snapshot and execute the finite generated matrix plus bounded custom cases; do not crawl unknown API surface.
5. Treat any required blocked case or incomplete matrix as a non-clean result.

## Outcome and proof semantics

Each case is `passed`, `confirmed`, `not_applicable`, or `blocked`. Finding proof is separately classified as `external`, `internal`, or `corroborated_external`.

Black-box execution must not use source, JAR/classpath, FQCN, Sidecar, Probe, or Strict Line Key information. Sidecar-assisted execution may use those inputs, but must not convert internal analysis into an external-exploitability claim.

## Black-box execution behavior

Black-box execution selects every compatible local knowledge pack by default (or an explicit advanced override), generates finite cases from rule templates × declared HTTP entrypoints × authentication profiles × fixture capabilities, and invokes HTTP cases through `transport_execute.execute` with `wrappedOnly=true`. It persists the resolved knowledge snapshot, matrix, coverage, findings, and redacted HTTP evidence under the canonical Security run Artifact path.

Missing symbolic credentials, missing required fixtures, target-boundary violations, unsupported transport adapters, transport failures, unexpected baseline responses, snapshot mismatch, and incomplete matrix coverage are deterministic blocked outcomes. Safely unsupported rules are `not_applicable`.

## Sidecar-assisted execution behavior

Sidecar-assisted execution first converges and validates every selected Probe/Strict Line Key target, rejecting ambiguous targets, unresolved lines, missing runtime identity, or runtime-instance changes. Before each baseline and attack request it resets the relevant target state, executes the external request through `transport_execute.execute` with `wrappedOnly=true`, and evaluates fresh required and forbidden Line Hits. A required missing/blocked Probe observation is blocked; an external denial bypass remains externally evidenced, while matching Sidecar evidence upgrades the proof classification to `corroborated_external`. A denied request that reaches a forbidden target is an `internal` finding and does not claim remote exploitability.

Runtime targets linked to `instrumentationTargets` are also checked against the selected workspace Probe include/exclude rules. Dependency-scoped targets require an explicit bounded `dependencyRef`; missing Probe configuration, excluded classes, or mismatched class/Strict Line Key declarations fail closed before external execution.
