# Failure Classification

Classify the earliest terminal or blocking phase using this ordered vocabulary:

1. `preflight` → `diagnostic_phase_preflight`
2. `strict_probe_gate` → `diagnostic_phase_strict_probe_gate`
3. `trigger_execution` → `diagnostic_phase_trigger_execution`
4. `watchers` → `diagnostic_phase_watchers`
5. `external_verification` → `diagnostic_phase_external_verification`
6. `correlation` → `diagnostic_phase_correlation`
7. `artifact_persistence` → `diagnostic_phase_artifact_persistence`
8. `execution_orchestration` → `diagnostic_phase_execution_orchestration`

Base diagnostic reason codes:

- `diagnostic_input_conflict`
- `diagnostic_run_not_found`
- `diagnostic_run_ambiguous`
- `diagnostic_artifact_unavailable`
- `diagnostic_artifact_corrupt`
- `diagnostic_sqlite_unavailable`
- `diagnostic_sqlite_corrupt`
- `diagnostic_evidence_conflict`
- `diagnostic_runtime_unavailable`
- `diagnostic_runtime_timeout`

The diagnostic reason code is authoritative. Preserve any underlying MCP Tool reason codes in bounded `underlyingReasonCodes`; do not replace or discard them.
