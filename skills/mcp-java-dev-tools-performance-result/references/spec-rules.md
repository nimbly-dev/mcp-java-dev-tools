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
