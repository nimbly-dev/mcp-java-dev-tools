# Artifact Contract

## Required Paths

Persist per run under:

1. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/context.resolved.json`
2. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/execution.result.json`
3. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/evidence.json`
4. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/correlation.json` (required when correlation evidence exists)
5. `.mcpjvm/correlation-index.json` (required when correlation artifact is produced)

## Deterministic Fields

`execution.result.json` step rows MUST include:

1. `order`
2. `id`
3. `status`
4. `durationMs`

`evidence.json` SHOULD include:

1. `correlationPolicy`
2. `correlationEvents[]`

## Correlation Rules

1. Canonical-only support: use `correlationPolicy` + `correlationEvents`.
2. Legacy-only correlation fields are unsupported.
3. Do not author `correlation.json` directly.
4. Persist only through canonical artifact writer flow.
