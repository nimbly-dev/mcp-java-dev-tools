# Artifact Contract

## Required Paths

Persist per run under:

1. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/context.resolved.json`
2. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/execution.result.json`
3. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/evidence.json`
4. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/correlation.json` (required when correlation evidence exists)
5. `.mcpjvm/<project_name>/correlation-index.json` is legacy migration input only and MUST NOT be written by runtime or rebuild flows.

Runtime suite manifest path:

1. `.mcpjvm/<project_name>/projects.json`
2. Suite manifest MUST include:
   - `executionProfile`
   - `executionPolicy`
   - ordered `plans[]`
3. Suite manifest is stored under the matching workspace entry:
   - `workspaces[].executionProfiles[]`

## Run ID Contract

1. Supply the described `suiteRunId` to Java execution and retain the returned/persisted `runId` for result reads and continuation.
2. Java owns run identity and persistence validation. Direct plan execution can persist `<suiteRunId>-1`; do not reject that identity using a former timestamp-only convention.
3. Never rename run directories, manufacture a result identity, or rewrite a terminal run. Reuse the exact suite identity only for supported continuation; a fresh run needs a fresh identity.

## Deterministic Fields

`execution.result.json` step rows MUST include:

1. `order`
2. `id`
3. `status`
4. `durationMs`

`evidence.json` SHOULD include:

1. `correlationPolicy`
2. `correlationEvents[]`

Runtime suite summary SHOULD include:

1. `executionProfile`
2. `executionPolicy`
3. ordered `planRuns[]` entries:
   - `order`
   - `planName`
   - `status` (`executed` | `blocked` | `skipped`)
   - `runStatus` when executed

## Correlation Rules

1. Canonical-only support: use `correlationPolicy` + `correlationEvents`.
2. Legacy-only correlation fields are unsupported.
3. Do not author `correlation.json` directly.
4. Persist only through canonical artifact writer flow.
5. SQLite is the only operational/query state store after explicit cutover.
