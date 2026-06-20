# Authoring Checklist

1. Confirm `project_name` and `mode` are present.
2. If `mode` is missing, fail closed (`execution_export_mode_required`) and stop. No `ps1` fallback.
3. Confirm selector input is valid (`export_id` or `execution_profile` or `plan_name` or `when`, or default latest).
4. Confirm the selected execution profile resolves from `projects.json`.
5. Confirm the selected execution profile is `suiteType=regression`, or that backward-compatible implicit regression handling applies.
6. Confirm referenced regression plan Artifacts exist under `.mcpjvm/<project>/plans/regression/...`.
7. Confirm mode router selected exactly one branch.
8. Confirm output ordering matches resolved `planRuns[].order`.
9. Confirm redaction or warning policy matches `includeResolvedSecrets`.
10. Confirm each invocation emits a fresh one-off folder under `.mcpjvm/<project>/exports/<yyyy-mm-dd-uuid>/`.
11. Confirm `sh` and `ps1` modes write the replay package directly inside that one-off folder (`run-execution-profile.<mode>`, `project.env`, and `scripts/` when needed).
12. Confirm `sh` and `ps1` modes bundle selected shared `scripts[]` and execute execution profile `scriptRefs[]` by phase.
13. Confirm `includeResolvedSecrets=false` blanks secret-like values in `project.env`.
14. Confirm fail-closed reason code plus actionable next action on blocked path.
15. Confirm emitted Artifact path comes from MCP Tool output (`output.scriptPathAbs`, `output.collectionPathAbs`, `output.environmentPathAbs`), not handcrafted path assumptions.
