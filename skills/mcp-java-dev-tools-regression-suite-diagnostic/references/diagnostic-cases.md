# Representative Diagnostic Cases

Static-contract coverage must retain these expected outcomes:

| Case | Route | Expected result |
| --- | --- | --- |
| Invalid plan/project compatibility | `plan_validation` | `invalid`, `diagnostic_phase_preflight` |
| Current Probe readiness failure | `plan_validation` or `execution_diagnosis` | `blocked`, `diagnostic_runtime_unavailable` or `diagnostic_runtime_timeout` in `liveRuntime` |
| Watcher timeout | `execution_diagnosis` | `diagnosed`, `diagnostic_phase_watchers` |
| External-verification failure | `execution_diagnosis` | `diagnosed`, `diagnostic_phase_external_verification` |
| Missing expectedFlow Correlation stage | `execution_diagnosis` | `diagnosed`, `diagnostic_phase_correlation` |
| Async consumer scope/probe failure | `execution_diagnosis` | `diagnosed`, `diagnostic_phase_correlation` |
| Degraded SQLite with terminal Artifact evidence | `execution_diagnosis` | `inconclusive` or `blocked`, `diagnostic_sqlite_unavailable`, `diagnostic_sqlite_corrupt`, or `diagnostic_evidence_conflict` |
| Active resumable suite checkpoint | `execution_diagnosis` | `diagnosed`, `diagnostic_phase_execution_orchestration` |

Each case must preserve bounded historical evidence references, keep live runtime separate, and render exactly one safe action under `## Next action`.
