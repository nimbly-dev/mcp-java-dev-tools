# Spec Rules

1. Export source is current regression project plan state:
   - `.mcpjvm/<project_name>/projects.json` (`executionProfiles`)
   - `.mcpjvm/<project_name>/plans/regression/<plan>/contract.json`
2. Export manifest is derived metadata and not a required runtime input.
3. If no explicit `export_id` is provided, derive deterministic export label from selected execution profile plus current timestamp.
4. Mode must be exactly one of:
   - `ps1`
   - `sh`
   - `postman`
   - missing mode must fail closed (`execution_export_mode_required`)
   - no implicit default mode is allowed
5. Selected execution profile must be `suiteType=regression` or backward-compatible implicit regression.
6. Preserve execution profile plan order and contract step order exactly.
7. Do not add inferred execution steps not present in resolved execution profile or contract source.
8. If `includeResolvedSecrets=true`, output must include `SENSITIVE EXPORT` warning.
9. Unknown fields in sources are ignored, not promoted into emitted commands.
10. Each invocation emits a fresh one-off folder under `.mcpjvm/<project_name>/exports/<yyyy-mm-dd-uuid>/`.
11. `sh` and `ps1` exports write a one-off package directly in the one-off folder:
    - `run-execution-profile.sh` or `run-execution-profile.ps1`
    - `project.env`
    - `scripts/` when shared execution profile scripts are referenced
12. `sh` and `ps1` exports execute shared `executionProfiles[].scriptRefs[]` by declared phase and use export-local `project.env` for script `envFileArg` values.
13. Missing required execution profile or plan evidence fails closed with deterministic reason code and actionable next action.
14. Never handcraft `replay.sh`; only emit artifacts produced by the `execution_profile_export` MCP Tool output paths.
