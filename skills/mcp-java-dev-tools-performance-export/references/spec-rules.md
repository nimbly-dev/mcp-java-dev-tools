# Spec Rules

1. Export source is current performance project plan state:
   - `.mcpjvm/<project_name>/projects.json` (`executionProfiles`)
   - `.mcpjvm/<project_name>/plans/performance/<plan>/contract.json`
2. Export manifest is derived metadata and not a required runtime input.
3. If no explicit `export_id` is provided, derive deterministic export label from selected execution profile plus current timestamp.
4. Mode must be exactly one of:
   - `ps1`
   - `sh`
   - missing mode must fail closed (`execution_export_mode_required`)
   - `postman` must fail closed (`performance_export_mode_unsupported`)
   - no implicit default mode is allowed
5. Selected execution profile must be `suiteType=performance`.
6. Preserve execution profile plan order exactly.
7. Do not alter workload contract values from the selected performance plan Artifact.
8. If `includeResolvedSecrets=true`, output must include `SENSITIVE EXPORT` warning.
9. Unknown fields in sources are ignored, not promoted into emitted commands.
10. Each invocation emits a fresh one-off folder under `.mcpjvm/<project_name>/exports/<yyyy-mm-dd-uuid>/`.
11. `sh` and `ps1` exports write a one-off package directly in the one-off folder:
    - `run-performance-profile.sh` or `run-performance-profile.ps1`
    - `run-performance-profile.js`
    - `performance-export.bundle.json`
    - `project.env`
    - `scripts/` when shared execution profile scripts are referenced
12. `sh` and `ps1` exports execute shared `executionProfiles[].scriptRefs[]` by declared phase and use export-local `project.env` for script `envFileArg` values.
13. The replay package must execute workload replay rather than step replay and must evaluate throughput, error rate, latency, and required Strict Line Keys.
14. Exported replay run Artifacts must persist deterministic blocked details when runtime replay cannot complete:
    - `reasonCode`
    - `failedStep`
    - `reasonMeta`
15. Missing required execution profile or plan evidence fails closed with deterministic reason code and actionable next action.
16. Never handcraft replay scripts; only emit artifacts produced by the `execution_profile_export` MCP Tool output paths.
