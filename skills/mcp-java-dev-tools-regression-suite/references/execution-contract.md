# Execution Contract

## Scope Modes

1. `controller`
2. `service`
3. `api`

## Plan Loading

1. Load:
   - `.mcpjvm/<project_name>/plans/regression/<plan>/metadata.json`
   - `.mcpjvm/<project_name>/plans/regression/<plan>/contract.json`
2. Execute/replay via existing MCP flow (no new MCP tool).
3. If artifacts are missing or invalid, fail closed with deterministic nextAction.

## Phase Rules

1. Execute exactly in this order:
   - `phase_0_load_plan`
   - `phase_1_project_context`
   - `phase_2_preflight_and_discovery`
   - `phase_3_strict_probe_gate`
   - `phase_4_step_execution`
   - `phase_5_artifact_persist_and_summary`
2. No phase skipping.
3. Endpoint step execution is forbidden before phase 3 passes when strict probe verification is enabled.
