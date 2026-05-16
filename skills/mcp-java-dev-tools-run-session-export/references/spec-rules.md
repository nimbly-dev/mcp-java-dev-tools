# Spec Rules

1. Export source must be session manifest only:
   - `.mcpjvm/<project_name>/exports/session-runs-exports/<session_id>/session-manifest.json`
2. Mode must be exactly one of:
   - `ps1`
   - `sh`
   - `postman`
3. Preserve `planRuns[].order` exactly.
4. Do not add inferred execution steps not present in manifest.
5. If `includeResolvedSecrets=true`, output must include `SENSITIVE EXPORT` warning.
6. Unknown fields in manifest are ignored, not promoted into emitted commands.
7. Missing required fields fail closed.
