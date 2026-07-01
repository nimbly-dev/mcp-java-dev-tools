# Performance Result Spec Rules

This file defines normative rules used by the result skill.

## Artifact Inputs

Required:

1. `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/execution.result.json`
2. `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/evidence.json`

Optional:

1. `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/context.resolved.json`
2. `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/execution-timing.msta.json`

## Template Contract

1. Every template id MUST be documented in `references/templates/index.md`.
2. Default template id MUST be `performance_summary_result`.
3. Unknown template ids MUST fail closed.

## Performance Summary Result

Required rows or fields:

1. `Status`
2. `Duration (ms)`
3. `Error Rate (%)`
4. `Throughput (/sec)`
5. `P95 Latency (ms)`
6. `Required Line Hits`
7. `MSTA`

## Deterministic Rendering

1. Missing optional fields render stable placeholders.
2. No secret values may be rendered.
3. Required line-hit status must map from Artifact evidence, not inference.
4. MSTA status must map from persisted Artifact evidence, not profiler log inference.
5. `not_configured` maps to `MSTA: n/a (not configured)`.
6. `disabled` maps to `MSTA: n/a (disabled)`.
7. Enablement guidance MAY be added only for `not_configured` and `disabled` states.
8. Missing required Artifact files MUST fail closed; rendering from transient logs or session memory is not allowed.
9. Missing optional Artifact files MUST render stable placeholders or omit optional sections only when every required output field can still be deterministically mapped from persisted Artifacts.
10. If a missing optional Artifact file prevents deterministic mapping of a required output field such as `MSTA`, the renderer MUST fail closed under the existing missing-required-fields rule and MUST NOT fall back to transient logs.

## Blocked Output Contract

When required Artifact files are missing for a requested `run_id`, the blocked output MUST include:

1. `status=blocked`
2. `reasonCode=artifact_files_missing`
3. `runId=<run_id>`
4. `missing=[...]` containing only missing required Artifact filenames
5. `nextAction=Re-run the plan to regenerate Artifacts, or provide the run ID of an existing persisted run.`
