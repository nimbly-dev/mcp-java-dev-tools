# Project Context and Preflight

## Project Context Integration

1. Resolve `.mcpjvm/<project-name>/projects.json` before endpoint execution.
2. Apply env-key interpolation only by key reference (never persist secret values).
3. Run required external health checks (`tcp`/`http`).
4. Fail closed for:
   - `project_artifact_missing`
   - `project_artifact_invalid`
   - `workspace_root_invalid`
   - `env_key_missing`
   - `runtime_context_unknown`
   - `external_system_invalid`
   - `external_healthcheck_failed`
5. Runtime startup authority:
   - when `projects.json` defines `runtimeContexts`, startup/restart must follow that context contract.
   - do not bypass with ad-hoc terminal commands outside `runtimeContexts` guidance.
   - if startup contract is incomplete for the selected context, fail closed and return `needs_user_input`.
6. HTTP context compatibility boundary:
   - `apiBaseUrl` is the canonical HTTP base-URL context key.
   - initial prerequisite/project-context `baseUrl` may be bridged into `apiBaseUrl` for legacy compatibility.
   - do not author new plans against `baseUrl`; prefer `apiBaseUrl`.
   - do not treat extracted runtime fields named `baseUrl` as transport configuration.

## Discovery-First Orchestration

1. Build initial preflight from plan + provided inputs.
2. If discoverable prerequisites are pending, run discovery resolver first.
3. Merge context precedence: user-provided > discovered > non-secret defaults.
4. Re-run preflight.
5. Prompt user only for remaining unresolved required fields.
6. For HTTP steps with no explicit `transport.http.url`, compose from canonical base URL plus `pathTemplate` or `path` only when canonical base-URL context is present; otherwise fail closed with transport diagnostics.

## Needs User Input Contract

1. `status=needs_user_input`
2. `missing[]`
3. `checks[]`
4. `nextAction`

Keep output minimal, deterministic, and resumable.
