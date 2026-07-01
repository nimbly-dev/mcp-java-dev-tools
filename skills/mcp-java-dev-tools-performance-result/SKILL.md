---
name: mcp-java-dev-tools-performance-result
description: "Render performance run results from Artifacts under `.mcpjvm/.../plans/performance/.../runs` into deterministic summaries and threshold tables. Use when summarizing completed Java performance-suite runs."
---

# MCP JVM Performance Result

Use this workflow to generate performance run summaries from persisted Artifacts under `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/`.

## Execution Mode

This skill runs in two phases:

1. `Read`
2. `Render`

Do not render from transient logs when Artifacts are available.

## Portable Source of Truth

Always align with:

1. `references/spec-rules.md`
2. `references/authoring-checklist.md`
3. `references/templates/index.md`
4. `references/templates/performance_summary_result.md`

If user request conflicts with these rules, fail closed and return deterministic blocked guidance.

## Source of Truth

Operational source for Artifact reads:

1. `artifact_management` MCP Tool when performance result reads are exposed canonically

Artifact semantics/reference paths:

1. `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/execution.result.json`
2. `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/evidence.json`
3. optional `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/context.resolved.json`
4. optional `.mcpjvm/<project_name>/plans/performance/<plan>/runs/<run_id>/execution-timing.msta.json`

## Template Routing

1. default template: `performance_summary_result`
2. when user asks for `table`, `show thresholds`, or equivalent, route to `performance_summary_result`
3. future template IDs must be documented in `references/templates/index.md`

## Extensible Presentation Modes

Support user-driven display formats while preserving deterministic field mapping:

1. `table` (default)
2. `compact`
3. `narrative`
4. `debug`

## Strict Line Verification Rendering

Use only these deterministic values for required line-hit coverage:

1. `verified_line_hit`
2. `required_line_missed`
3. `unknown`
4. `n/a`

## MSTA Rendering

Render only persisted states:

1. `available`
2. `not_configured`
3. `disabled`
4. `jfr_missing`
5. `jfr_parse_failed`
6. `no_anchor_samples`

When `status=not_configured`, render `MSTA: n/a (not configured)` and add a one-line note pointing to `analysis.msta`.

When `status=disabled`, render `MSTA: n/a (disabled)`.

Optional guidance for `not_configured` and `disabled`:

- `To enable MSTA for a future run, set analysis.msta.enabled=true and provide a valid analysis.executionTiming block.`

Do not infer MSTA availability from a missing Artifact file or from the presence of a raw JFR file alone.

## Governance and Redaction

1. Never render secret values from Artifacts.
2. Respect Artifact redaction as-is.
3. Do not reconstruct secret material from surrounding fields.

## Fail-Closed Conditions

Return deterministic blocked guidance when:

1. one or more required Artifact files are missing
2. Artifact JSON is invalid
3. required threshold/result fields are absent and cannot be deterministically mapped
4. requested template is not registered

Blocked response must include:

1. exact missing/invalid Artifact or template id
2. deterministic `reasonCode`
3. single `nextAction`

When one or more required Artifact files are missing for the requested `run_id`, return:

- `status=blocked`
- `reasonCode=artifact_files_missing`
- `runId=<run_id>`
- `missing=[...]` containing only missing required Artifact filenames
- `nextAction=Re-run the plan to regenerate Artifacts, or provide the run ID of an existing persisted run.`

Optional Artifact files such as `context.resolved.json` and `execution-timing.msta.json` MUST NOT trigger blocked output by themselves when every required output field remains deterministically mappable from persisted Artifacts. If a missing optional Artifact prevents deterministic rendering of a required field such as `MSTA`, the skill MUST fail closed and MUST NOT reconstruct that field from transient logs.
