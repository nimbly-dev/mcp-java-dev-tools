# Security Suite diagnostic workflow

## Routes

### `plan_validation`

Use this route for "can this Security plan run?", "why is this plan blocked?", or contract/readiness questions. Validate the `security_plan`, inspect only the bounded metadata and contract summary needed for diagnosis, and check the referenced project context and execution profile. Do not execute the plan.

At minimum establish:

- `suiteType=security` and a valid plan identity;
- `securityMode=blackbox` or `securityMode=sidecar_assisted`;
- a finite, deterministic case matrix with unique case IDs;
- target/entrypoint and authentication references that stay within the plan boundary;
- knowledge-pack references that resolve to supported packs and versions;
- mode-appropriate evidence requirements; and
- a compatible project execution profile/runtime context when Sidecar-assisted evidence is required.

Return `executable` only when all required inputs are present and validation is successful. Return `blocked` for unavailable dependencies and `invalid` for contract violations. A plan that is merely readable is not necessarily executable.

### `execution_diagnosis`

Require exactly one selector:

1. `planName` plus `runId` for one canonical run;
2. `suiteRunId` for one correlated execution; or
3. a bounded `stateQuery` with explicit project/plan/suite filters and a small page size.

Reject a selector that combines these forms or can return several candidate runs without a deterministic tie-breaker. Query the selected run, not an inferred "latest" run.

## Ordered phases

Classify the earliest failed or incomplete phase from this ordered list:

1. `preflight`
2. `knowledge_pack_selection`
3. `matrix_generation`
4. `authentication_context`
5. `transport_execution`
6. `baseline_attack_evaluation`
7. `coverage_persistence`
8. `execution_orchestration`

For Sidecar-assisted runs, `runtime_probe_evidence` is a supporting phase between `authentication_context` and `transport_execution` when the persisted run records a Probe dependency. Do not invent a phase from an absent field; report `security_diagnostic_artifact_corrupt` or `security_diagnostic_evidence_unavailable` when the phase cannot be determined.

## Phase procedure

### 1. Preflight

Confirm project, plan, selector, and scope. Check for conflicting inputs and redaction requirements. Never broaden a query because the initial identity is missing.

### 2. Plan and knowledge-pack readiness

Read the plan validation result and bounded matrix/knowledge-pack references. A missing, unsupported, or mismatched pack blocks deterministic diagnosis. Version selection is interpreted according to the persisted plan contract; do not silently substitute another pack version.

### 3. Matrix and authentication readiness

Compare planned case count and unique case IDs with the persisted matrix. Check that target/entrypoint references and authentication profiles are resolved without exposing their values. Missing authentication context blocks cases that cannot be safely evaluated.

### 4. Runtime and transport evidence

For Black-box mode, correlate redacted request/response references and transport outcome. For Sidecar-assisted mode, correlate the configured runtime identity and bounded Probe status with persisted evidence. A current runtime check cannot prove a historical response.

### 5. Evaluation, findings, and coverage

Compare planned, executed, passed, confirmed, not-applicable, and blocked counts. For each finding, retain its case ID, finding ID, severity/category, proof classification, and redacted evidence reference IDs. Missing evidence for a claimed external finding is inconclusive, not a pass.

### 6. Persistence and orchestration

Check whether the canonical run Artifact is terminal and internally consistent. Use SQLite `run_state` only to explain active, resumed, stale, or degraded operational state. If the Artifact is terminal and SQLite is stale, report the projection discrepancy without modifying either source.

## Diagnostic invariants

- One selected run produces one diagnosis; do not merge unrelated runs.
- Historical Artifact evidence is authoritative for completed case outcomes and coverage.
- SQLite projection is authoritative only for bounded operational lookup/status.
- Live Probe evidence is authoritative only for current runtime observations.
- Missing evidence fails closed and never becomes an inferred security pass.
- The workflow ends after the Markdown report; it does not hand off to an executor.
