---
name: mcp-java-dev-tools-regression-result
description: "Render regression run results from artifacts into deterministic summaries and tables."
---

# MCP JVM Regression Result

Use this workflow to generate run summaries from persisted artifacts under `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/`.

## Execution Mode

This skill runs in two phases:

1. `Read`
2. `Render`

Do not render from transient logs when artifacts are available.

## Portable Source of Truth

Always align with:

1. `references/spec-rules.md`
2. `references/authoring-checklist.md`
3. `references/templates/index.md`
4. `references/templates/http_result_table/endpoint_table_result.md`

These references are bundled with the skill so it remains installable and usable across repositories.
If user request conflicts with these rules, fail closed and return deterministic blocked guidance.

## Source of Truth

Operational source for artifact reads:

1. `artifact_management` MCP Tool:
   - `artifactType=run_result`, `action=read|list|rebuild`

Artifact semantics/reference paths:

1. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/execution.result.json`
2. `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/evidence.json`
3. optional `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/correlation.json` for distributed correlation verdict/timeline summary
4. optional `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/context.resolved.json` for non-secret context display

`run-state.sqlite` may supply bounded operational projections, including Watcher checkpoints and attempt progress, but canonical run Artifacts remain the rendering evidence. Do not merge a legacy `correlation-index.json` into a fresh result. Preserve the distinction between trigger success, Watcher success, and checkpoint/projection failure; a terminal Watcher result is immutable through ordinary runtime writes.

For state-store recovery, render the rebuild response from its bounded summary and reason rows. Do not dump rebuilt SQLite rows or canonical Artifact payloads into MCP output.

## Template Routing

1. default template: `endpoint_table_result`
2. when user asks to "tablize", "show as table", or equivalent, route to `endpoint_table_result`
3. future template IDs must be documented in `references/templates/index.md`

## Extensible Presentation Modes

Support user-driven display formats while preserving deterministic field mapping:

1. `table` (default)
2. `compact` (one-line per endpoint)
3. `narrative` (short summary + top-level metrics)
4. `debug` (includes deterministic diagnostics and reason codes)

## Probe Coverage Enum

For rendered endpoint results, use only these deterministic values:

1. `verified_line_hit`: strict `Class#method:line` probe verification confirmed
2. `http_only_unverified_line`: HTTP-level assertion passed without strict line verification
3. `unknown`: coverage could not be deterministically mapped from artifacts
4. `n/a`: placeholder only for blocked/no-step rows

## Governance and Redaction

1. Never render secret values from artifacts.
2. Respect artifact redaction (`[REDACTED]`) as-is.
3. Do not reconstruct secret material from surrounding fields.
4. If requested output would expose secrets, fail closed and explain blocked field.

## Fail-Closed Conditions

Return deterministic blocked guidance when:

1. required artifact files are missing
2. artifact JSON is invalid
3. required result fields are absent and cannot be deterministically mapped
4. requested template is not registered

Blocked response must include:

1. exact missing/invalid artifact or template id
2. deterministic reason code
3. single next action
