# Authoring Checklist

1. Confirm `project_name` and `mode` are present.
2. Confirm selector input is valid (`export_id` or `execution_profile` or `plan_name` or `when`, or default latest).
3. Confirm execution profile resolves from `projects.json` and referenced plan contracts exist.
4. Confirm mode router selected exactly one branch.
5. Confirm output ordering matches resolved `planRuns[].order`.
6. Confirm redaction/warning policy matches `includeResolvedSecrets`.
7. Confirm each invocation emits a fresh one-off folder under `.mcpjvm/<project>/exports/<yyyy-mm-dd-uuid>/`.
8. Confirm `sh` and `ps1` modes write the replay package directly inside that one-off folder (`run-execution-profile.<mode>`, `project.env`, and `scripts/` when needed).
9. Confirm `sh` and `ps1` modes bundle selected shared `scripts[]` and execute profile `scriptRefs[]` by phase.
10. Confirm `includeResolvedSecrets=false` blanks secret-like values in `project.env`.
11. Confirm fail-closed reason code + actionable next action on blocked path.
12. Confirm emitted artifact path comes from tool response (`output.scriptPathAbs`), not handcrafted path assumptions.
