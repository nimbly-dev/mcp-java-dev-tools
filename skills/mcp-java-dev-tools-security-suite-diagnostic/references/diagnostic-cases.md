# Security Suite diagnostic cases

These cases guide classification; they do not authorize execution or repair.

## Plan cases

### Invalid mode or contract

**Evidence:** validation reports an unsupported `securityMode`, missing finite matrix, duplicate case IDs, or missing mode-specific proof requirement.

**Diagnosis:** `invalid`, phase `preflight`/`matrix_generation`, with the validator's reason and `security_diagnostic_matrix_incomplete` or `security_diagnostic_input_conflict` when applicable.

### Knowledge pack unavailable or mismatched

**Evidence:** the plan names a pack/version that cannot be resolved or does not satisfy the pinned contract.

**Diagnosis:** `blocked`, phase `knowledge_pack_selection`, reason `security_diagnostic_knowledge_pack_unavailable` or `security_diagnostic_knowledge_pack_version_mismatch`. Do not substitute a nearby version silently.

### Missing target or authentication context

**Evidence:** target/entrypoint is outside the declared boundary, or required auth profile cannot be resolved without exposing credentials.

**Diagnosis:** `blocked`, phase `authentication_context`, reason `security_diagnostic_target_boundary_invalid` or `security_diagnostic_authentication_blocked`.

## Run cases

### Complete Black-box run

**Evidence:** terminal Artifact, reconciled finite matrix, redacted external evidence for applicable cases, and findings with required `external` or `corroborated_external` proof.

**Diagnosis:** `pass` or `fail` according to the persisted acceptance policy. Include the finding IDs and proof classifications, never payloads.

### Finding without required external proof

**Evidence:** internal observation, attack request, or runtime line hit exists, but the required externally observable evidence reference is absent.

**Diagnosis:** `inconclusive` or `partial_fail`, phase `baseline_attack_evaluation`, reason `security_diagnostic_finding_evidence_missing`. Do not call the finding externally confirmed.

### Incomplete matrix or blocked cases

**Evidence:** planned count exceeds terminal classifications, or cases are `blocked` with no allowed not-applicable rationale.

**Diagnosis:** `partial_fail`/`blocked`, phase `matrix_generation` or `coverage_persistence`, reason `security_diagnostic_matrix_incomplete` or `security_diagnostic_coverage_incomplete`.

### Sidecar runtime identity mismatch

**Evidence:** persisted runtime identity differs from current Probe status or current Probe is unavailable for a Sidecar-dependent case.

**Diagnosis:** `blocked` or `inconclusive`, phase `runtime_probe_evidence`, reason `security_diagnostic_runtime_identity_mismatch` or `security_diagnostic_runtime_unavailable`. Current status does not rewrite historical evidence.

### Terminal Artifact with stale SQLite projection

**Evidence:** canonical Artifact is terminal and internally consistent, while `run_state` is stale, absent, or reports an older lifecycle state.

**Diagnosis:** preserve the Artifact outcome; report operational degradation with `security_diagnostic_sqlite_unavailable`, `security_diagnostic_sqlite_corrupt`, or `security_diagnostic_execution_stale` as appropriate. Do not rebuild or repair.

### In-progress or resumable run

**Evidence:** state indicates an active checkpoint and no terminal canonical Artifact exists.

**Diagnosis:** `in_progress` or `blocked`, with the earliest known phase and `security_diagnostic_execution_stale` when the checkpoint is stale. Explain that resumption is outside this diagnostic skill.
