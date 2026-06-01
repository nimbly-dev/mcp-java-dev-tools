# Authoring Checklist

1. Confirm `project_name` and `mode` are present.
2. If `mode` is missing, fail closed (`execution_export_mode_required`) and stop (no `ps1` fallback).
3. Confirm selector input is valid (`export_id` or `execution_profile` or `plan_name` or `when`, or default latest).
4. Confirm execution profile resolves from `projects.json` and referenced plan contracts exist.
5. Confirm mode router selected exactly one branch.
6. Confirm output ordering matches resolved `planRuns[].order`.
7. Confirm redaction/warning policy matches `includeResolvedSecrets`.
8. Confirm each invocation emits a fresh one-off folder under `.mcpjvm/<project>/exports/<yyyy-mm-dd-uuid>/`.
9. Confirm `sh` and `ps1` modes write the replay package directly inside that one-off folder (`run-execution-profile.<mode>`, `project.env`, and `scripts/` when needed).
10. Confirm `sh` and `ps1` modes bundle selected shared `scripts[]` and execute profile `scriptRefs[]` by phase.
11. Confirm `includeResolvedSecrets=false` blanks secret-like values in `project.env`.
12. Confirm fail-closed reason code + actionable next action on blocked path.
13. Confirm emitted artifact path comes from tool response (`output.scriptPathAbs`), not handcrafted path assumptions.
