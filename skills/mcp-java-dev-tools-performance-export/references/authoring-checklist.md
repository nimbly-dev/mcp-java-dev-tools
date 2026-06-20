# Authoring Checklist

1. Confirm `project_name` and `mode` are present.
2. If `mode` is missing, fail closed (`execution_export_mode_required`) and stop. No `ps1` fallback.
3. Confirm selector input is valid (`export_id` or `execution_profile` or `plan_name` or `when`, or default latest).
4. Confirm the selected execution profile resolves from `projects.json`.
5. Confirm the selected execution profile is `suiteType=performance`.
6. Confirm referenced performance plan Artifacts exist under `.mcpjvm/<project>/plans/performance/...`.
7. Confirm mode router selected exactly one branch.
8. Confirm `postman` is rejected deterministically for performance export.
9. Confirm output ordering matches resolved `planRuns[].order`.
10. Confirm redaction or warning policy matches `includeResolvedSecrets`.
11. Confirm each invocation emits a fresh one-off folder under `.mcpjvm/<project>/exports/<yyyy-mm-dd-uuid>/`.
12. Confirm `sh` and `ps1` modes write the workload replay package directly inside that one-off folder (`run-performance-profile.<mode>`, `run-performance-profile.js`, `performance-export.bundle.json`, `project.env`, and `scripts/` when needed).
13. Confirm `sh` and `ps1` modes bundle selected shared `scripts[]` and execute execution profile `scriptRefs[]` by phase.
14. Confirm `includeResolvedSecrets=false` blanks secret-like values in `project.env`.
15. Confirm emitted replay package remains workload-oriented: target preflight, load model execution, threshold evaluation, and required Strict Line Key verification.
16. Confirm blocked replay run Artifacts persist `reasonCode`, `failedStep`, and `reasonMeta` when a runtime leg fails.
17. Confirm fail-closed reason code plus actionable next action on blocked path.
18. Confirm emitted Artifact path comes from MCP Tool output (`output.scriptPathAbs`, `output.readmePathAbs`), not handcrafted path assumptions.
