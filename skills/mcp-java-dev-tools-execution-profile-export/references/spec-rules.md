# Spec Rules

1. Export source is current project plan state:
   - `.mcpjvm/<project_name>/projects.json` (`executionProfiles`)
   - `.mcpjvm/<project_name>/plans/regression/<plan>/contract.json`
2. export manifest is derived metadata and not a required runtime input.
3. If no explicit `export_id` is provided, derive deterministic export label from selected execution profile + current timestamp.
4. Mode must be exactly one of:
   - `ps1`
   - `sh`
   - `postman`
5. Preserve execution profile plan order and contract step order exactly.
6. Do not add inferred execution steps not present in resolved profile/contract source.
7. If `includeResolvedSecrets=true`, output must include `SENSITIVE EXPORT` warning.
8. Unknown fields in sources are ignored, not promoted into emitted commands.
9. Each invocation emits a fresh one-off folder under `.mcpjvm/<project_name>/exports/<yyyy-mm-dd-uuid>/`.
10. `sh` and `ps1` exports write a one-off package directly in the one-off folder (no nested `exports/<mode>/<uuid>`):
    - `run-execution-profile.sh` or `run-execution-profile.ps1`
    - `project.env`
    - `scripts/` when shared profile scripts are referenced
11. `sh` and `ps1` exports execute shared `executionProfiles[].scriptRefs[]` by declared phase and use export-local `project.env` for script `envFileArg` values.
12. Missing required plan/profile evidence fails closed with deterministic reason code and actionable next action.
13. Never handcraft `replay.sh`; only emit artifacts produced by `execution_profile_export` tool output paths.
