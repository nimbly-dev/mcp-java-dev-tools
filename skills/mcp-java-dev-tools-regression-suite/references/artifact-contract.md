# Artifact Contract

## Required Paths

Persist per run under:

1. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/context.resolved.json`
2. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/execution.result.json`
3. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/evidence.json`
4. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/correlation.json` (required when correlation evidence exists)
5. `.mcpjvm/correlation-index.json` (required when correlation artifact is produced)

Runtime suite manifest path:

1. `.mcpjvm/<project_name>/projects.json`
2. Suite manifest MUST include:
   - `executionProfile`
   - `executionPolicy`
   - ordered `plans[]`
3. Suite manifest is stored under the matching workspace entry:
   - `workspaces[].executionProfiles[]`

## Run ID Contract

1. Canonical run ID format is mandatory:
   - `MM-DD-YYYY-hh-mm-ssAM`
2. Example:
   - `05-09-2026-08-33-41PM`
3. Non-canonical run IDs MUST fail closed before artifact persistence.
4. Ad-hoc IDs are forbidden (for example `20260509T134827387Z-customers`).

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
