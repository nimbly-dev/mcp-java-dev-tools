---
name: mcp-java-dev-tools-line-probe-run
description: "Run strict single-line JVM probe verification. Use for line-hit checks, route validation, and fail-closed probe execution."
---

# MCP JVM Line Probe Run

Use this workflow only for strict one-line verification runs.

## Contract

1. This skill runs in `single_line_probe` semantics only.
2. Strict line target is mandatory: `Class#method:line` (or inferable via `lineHint` from `classHint` + `methodHint`).
3. Never downgrade to regression-only in this skill.

## MCP-First Requirement

1. Mandatory tools: `probe`, `artifact_management`, `probe_recipe_create`.
2. If MCP toolchain is unavailable, stop immediately and return:
   - `reasonCode=toolchain_unavailable`
   - `nextAction=enable_mcp_jvm_debugger_tools_then_rerun`
3. Never fallback to direct `curl`/raw HTTP-only execution.
4. If recipe synthesis fails but MCP probe tools are reachable, do not stop immediately:
   - gather only missing execution inputs
   - continue with manual probe-verified execution flow
   - preserve fail-closed reporting fields.

## Execution Sequence

0. Discover execution environment once per run (orchestrator-owned):
   - API base URL (or API port)
   - probe base URL (or probe port)
   - optional `apiBasePath`
   - auth requirement/token only if needed.
1. Validate probe connectivity first with `probe` using `action=check` on the selected probe base URL.
2. Call `artifact_management` with `artifactType=project_context`, `action=validate`, and typed `input`.
3. Prefer explicit `projectName` for multi-project or microservice workspaces.
4. Include `projectRootAbs` when the orchestrator needs deterministic root validation or scope cross-checking.
5. Call `probe_recipe_create` with intentMode=line_probe, strict line target context, and exact FQCN in `classHint`.
6. Provide `apiBasePath` when runtime is deployed with a context path (for example `/api/v1`).
7. Ask for context path at most once per run; reuse the same `apiBasePath` for subsequent attempts in that run.
8. Runtime synthesis scope is runtime-only (`src/main/java` + generated-main roots); test sources are excluded.
9. If `probe_recipe_create` returns `resultType=report`, read compact execution metadata:
   - `executionPlan.routingReason` (code)
   - `executionPlan.steps[].actionCode` (code)
   - avoid depending on verbose instruction text.
10. Route only by deterministic contract fields (`resultType`, `status`, `reasonCode`, `failedStep`).
11. Never use or request confidence/heuristic scoring for routing decisions.
12. Probe tool outputs use compact text summaries; treat `structuredContent` as canonical for full payload details.
13. On report outputs, always capture synthesis diagnostics:
   - `reasonCode`
   - `failedStep`
   - `evidence`
   - `attemptedStrategies`
   - `synthesizerUsed`
14. If report indicates unresolved request inputs (for example `target_not_inferred`/`api_request_not_inferred`/`execution_input_required`):
   - gather missing request/auth input once
   - continue with manual probe-verified flow (see fallback section).
15. Resolve route dynamically from runtime candidates.
16. Validate exactly one route using:
   - probe reachability
   - API reachability
   - strict target alignment (`Class#method:line` resolvability or class-scoped line discovery)
17. Treat `probe_target_infer.firstExecutableLine` as runtime-validated only; when unresolved, do not guess a line and fail closed.
18. When capture preview is available, use `capturePreview.executionPaths` as runtime evidence; do not re-derive call paths heuristically.
19. Execute probe flow:
   - `probe` with `action=reset` -> trigger HTTP request -> `probe` with `action=wait_for_hit` / `action=status`
20. Cleanup (disable actuation when used).

## Prerequisites

Before starting execution:

1. Probe endpoint must be reachable (`probe` with `action=check` succeeds for selected probe base URL).
2. API and probe endpoints must be explicitly known and must not be assumed identical unless validated.
3. Strict target must be provided (`Class#method:line` or inferable from exact `classHint` + `methodHint` + `lineHint`).
4. Auth input must be present only when required by synthesis/auth diagnostics.

## Fallback: Manual Probe-Verified Execution

If `probe_recipe_create` does not infer a request candidate, continue manually:

1. Gather only missing inputs:
   - endpoint path/method from user or runtime docs
   - request body/query shape from DTO/controller contract
   - auth token only if required.
2. Keep probe verification strict:
   - `probe` with `action=reset` on target line key
   - execute trigger HTTP request against discovered API base URL
   - verify with `probe` using `action=wait_for_hit` (or `action=status`).
3. Report deterministic outcome:
   - include `reasonCode`, `failedStep`, `evidence`, `attemptedStrategies`
   - include executable human `Repro Steps`.

## Route Pushback

If route resolution fails, stop and return:

1. `probe_route_not_found` (no valid route)
2. `probe_route_ambiguous` (multiple valid routes)

Pushback output must include:

1. `attemptedCandidates`
2. `validationResults`
3. `nextAction`
4. `Repro Steps`
5. `reasonCode`
6. `failedStep`
7. `synthesizerUsed` (when recipe synthesis was attempted)
8. `attemptedStrategies` (when recipe synthesis was attempted)
9. `evidence` (when recipe synthesis was attempted)

## Required Human Run Summary

Always include:

1. `Selected Mode`
2. `Routing Outcome`
3. `Trigger Request`
4. `HTTP Result`
5. `Probe Verification`
6. `Run Timing`:
   - `runStartEpoch` (Unix epoch in milliseconds)
   - `runEndEpoch` (Unix epoch in milliseconds)
   - `runDurationMs` (`runEndEpoch - runStartEpoch`)
   - optional human-readable UTC timestamps for operator readability
7. `Synthesis Diagnostics` (`synthesizerUsed`, `reasonCode`, `failedStep` when present)
8. `Runtime Evidence` (`capturePreview.executionPaths` and `probe action=capture -> result.capture.executionPaths` when available)
9. `Repro Steps` (ordered, executable, numbered, per-recipe)
10. `Cleanup`
11. `Trust Note`

## Repro Steps Format

When writing `Repro Steps`, prioritize human actions over MCP internals:

1. Start with the exact trigger request(s) a developer should send (method, URL, headers, body/query).
2. Include expected outcomes (HTTP status and key response checks).
3. Describe only user-observable and app-observable actions in `Repro Steps`.
4. Do not use MCP function calls as the primary repro narrative.
5. If probe internals are needed, add a separate optional section named `Toolchain Steps`.

## Repro Steps Template (Per Recipe)

Use this exact structure when reporting execution movement:

Recipe `<short-name-or-index>`

1. Execute controller with this request:
   - Method: `<HTTP_METHOD>`
   - URL: `<BASE_URL><PATH>`
   - Path Params: `<none | key=value, ...>`
   - Query Params: `<none | key=value, ...>`
   - Headers: `<none required | header list>`
   - Body: `<none | JSON payload>`

2. Execution reaches method:
   - `<FQCN#controllerMethod>`

3. Execution reaches method:
   - `<FQCN#downstreamMethod>`

4. Encounter this line:
   - `<FQCN#method:line>`
   - Branch/Condition: `<condition text>`
   - Probe Verification: `<hit=true|false, inline=true|false, lineValidation=...>`

For multi-request runs, create separate recipe blocks and repeat steps 1-4 inside each block.


