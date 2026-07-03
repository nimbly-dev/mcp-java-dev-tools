# Reason Codes

Canonical fail/blocked reason codes:

1. `toolchain_unavailable`
2. `project_artifact_missing`
3. `project_artifact_invalid`
4. `workspace_root_invalid`
5. `runtime_context_unknown`
6. `env_key_missing`
7. `script_execution_failed`
8. `external_system_invalid`
9. `external_healthcheck_failed`
10. `runtime_auto_replace_required`
11. `runtime_start_failed`
12. `runtime_probe_unreachable_after_start`
13. `probe_gate_failed`
14. `needs_user_input`

Usage:

1. Emit exactly one primary reason code per blocked run.
2. Keep `checks[]` concise and machine-readable.
3. Keep `nextAction` deterministic and single-step.
