# Security Suite failure classification

## Reason-code rules

Use one primary deterministic reason code and, when useful, one secondary evidence note. Prefer the earliest failed phase. Do not report "pass" when a required artifact or proof record is missing.

| Phase | Primary reason codes | Interpretation |
|---|---|---|
| `preflight` | `security_diagnostic_input_conflict`, `security_diagnostic_plan_not_found`, `security_diagnostic_run_ambiguous` | Identity, scope, or selector cannot be resolved deterministically |
| `knowledge_pack_selection` | `security_diagnostic_knowledge_pack_unavailable`, `security_diagnostic_knowledge_pack_version_mismatch` | Required security knowledge pack is missing or does not match the plan contract |
| `matrix_generation` | `security_diagnostic_matrix_incomplete`, `security_diagnostic_duplicate_case_id` | Planned case matrix is absent, incomplete, or non-deterministic |
| `authentication_context` | `security_diagnostic_authentication_blocked`, `security_diagnostic_target_boundary_invalid` | Required auth/target context is unavailable or outside the declared boundary |
| `runtime_probe_evidence` | `security_diagnostic_runtime_unavailable`, `security_diagnostic_runtime_identity_mismatch`, `security_diagnostic_runtime_timeout` | Sidecar-assisted runtime evidence cannot be trusted for the selected run |
| `transport_execution` | `security_diagnostic_transport_blocked`, `security_diagnostic_transport_timeout` | The target could not be evaluated or the bounded transport evidence is incomplete |
| `baseline_attack_evaluation` | `security_diagnostic_finding_evidence_missing`, `security_diagnostic_evidence_conflict` | A finding or negative result lacks the proof required by its mode/contract |
| `coverage_persistence` | `security_diagnostic_artifact_unavailable`, `security_diagnostic_artifact_corrupt`, `security_diagnostic_coverage_incomplete` | Persisted matrix, coverage, finding, or evidence records are missing/inconsistent |
| `execution_orchestration` | `security_diagnostic_sqlite_unavailable`, `security_diagnostic_sqlite_corrupt`, `security_diagnostic_execution_stale` | Operational state is missing, stale, or inconsistent with the terminal Artifact |

## Outcome mapping

- `executable`: plan validation succeeds and all required dependencies are present.
- `pass`: all planned cases reached an allowed terminal outcome and no finding violates the plan’s acceptance policy.
- `fail`: one or more findings are supported by the required proof classification.
- `blocked`: execution or diagnosis could not reach a trustworthy evaluation because a required dependency was unavailable.
- `partial_fail`: some cases/findings are terminal but coverage or proof is incomplete.
- `inconclusive`: available records conflict or cannot establish the claimed security result.
- `in_progress`: the run has not reached a terminal Artifact; diagnose the checkpoint without resuming it.

## Disallowed shortcuts

Do not map an HTTP 5xx, a timeout, a Probe line hit, a non-empty finding list, or a healthy current JVM directly to a confirmed vulnerability. These are observations whose meaning depends on the case contract and proof references.
