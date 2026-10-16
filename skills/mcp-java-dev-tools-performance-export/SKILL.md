---
name: mcp-java-dev-tools-performance-export
description: "Export performance execution profiles to runnable workload replay artifacts (ps1, sh). Use for export, share, replay, or handoff of performance execution flows."
---

# MCP JVM Performance Export

## Java CDE invocation

The public MCP Tools are `operation_catalog`, `operation_describe`, and `operation_execute`. Discover supported IDs with Catalog; call Describe with `{"operationId":"<id>"}` before execution. Java Describe owns the current argument schema and safety requirements.

Call `operation_execute` with `{"operationId":"<id>","arguments":{},"confirmed":false}`, replacing `arguments` with the described fields. Set outer `confirmed:true` only for an authorized operation when Describe requires it. Do not send the retired Tool/action/input envelope or an inner lifecycle `confirm`. Nested operation-specific actions remain in `arguments.action` only when Describe declares them. Read the CDE status first, then the capability result in `result`; use structured MCP content and preserve reason codes. Artifact details are under `result.details`; Probe and lifecycle action data are under `result.actionResult`.

This workflow consumes these canonical IDs as needed (not an instruction to execute every operation):

- `artifact_management.project_context.read`
- `artifact_management.performance_plan.read`
- `execution_profile_export.export`
- `artifact_management.execution_export.read`

Use this Skill Workflow to export workload replay artifacts from the current performance execution profile plus performance plan Artifact state.

## Scope Guard

1. This Skill Workflow exports workload replay artifacts only; it does not execute performance suites.
2. If the user asks to run or execute a performance suite, route to `mcp-java-dev-tools-performance-suite`.
3. Do not satisfy performance execution prompts by generating replay export scripts.
4. This Skill Workflow is for `suiteType=performance` only. If the selected execution profile is `suiteType=regression`, route to `mcp-java-dev-tools-regression-export`.

## Execution Mode

This Skill Workflow runs in three phases:

1. `Read`
2. `Assemble`
3. `Emit`

Mode selection is single selected mode only.

## Portable Source of Truth

Always align with:

1. `references/spec-rules.md`
2. `references/authoring-checklist.md`
3. `references/templates/index.md`

## Input Contract

Required input:

1. `project_name`
2. `mode` (`ps1` | `sh`)
3. If `mode` is missing, fail closed (`execution_export_mode_required`); do not default to `ps1`.

Optional:

1. `export_id` (optional export label; auto-derived when omitted)
2. `execution_profile` (latest matching selector)
3. `plan_name` (latest matching selector)
4. `when` (date/time hint for nearest selector)
5. `includeResolvedSecrets` (`false` default)
6. `includeRuntimeStartup` (`true` default; may be overridden by `projects.json` session export defaults)
7. `includeHealthcheckGate` (`true` default; may be overridden by `projects.json` session export defaults)
8. `includeResolvedSecrets` must be supplied explicitly per request to enable secret export; `projects.json.sessionExport` must not auto-enable secret inclusion.

Invoke `execution_profile_export.export` for profile/plan export with these options mapped to the exact camelCase argument names in Describe. Pass desired startup/health-check options explicitly; do not assume an omitted flag enables them. `artifact_management.execution_export.generate` has its own Describe schema and is not an interchangeable argument envelope.

## Profile Selection

Resolve in this selector order:

1. explicit `export_id` (label only)
2. `execution_profile`
3. `plan_name` (resolves containing execution profile)
4. `when` (optional label hint)
5. default execution profile when no selector is provided

## Source of Truth

Operational source for Artifact lifecycle:

1. Artifact Management operations:
   - `artifact_management.project_context.read` / `artifact_management.project_context.validate` / `artifact_management.project_context.list`
   - `artifact_management.performance_plan.read` / `artifact_management.performance_plan.validate` / `artifact_management.performance_plan.list`
   - `artifact_management.execution_export.generate` / `artifact_management.execution_export.read` / `artifact_management.execution_export.list`

Artifact references:

1. `.mcpjvm/<project_name>/projects.json` (`executionProfiles`)
2. `.mcpjvm/<project_name>/plans/performance/<plan>/contract.json`

No runtime run Artifact is required for default export behavior.

## Mode Router

1. `mode=ps1` => emit PowerShell workload replay package
2. `mode=sh` => emit shell workload replay package
3. `mode=postman` => fail closed (`performance_export_mode_unsupported`)
4. missing mode => fail closed (`execution_export_mode_required`)
5. unknown mode => fail closed
6. never apply implicit mode fallback/default

## Determinism Rules

1. Preserve execution order from execution profile plan order.
2. Preserve workload contract values exactly from the selected plan Artifact.
3. Do not invent request steps, threshold values, or Strict Line Keys absent from source.
4. Do not infer hidden runtime behavior.
5. Keep output stable for the same input.
6. Each export invocation emits a fresh one-off folder under `.mcpjvm/<project>/exports/<yyyy-mm-dd-uuid>/`.
7. Generated files for `sh` and `ps1` modes must emit a one-off workload replay package directly in that folder:
   - `run-performance-profile.sh` or `run-performance-profile.ps1`
   - `run-performance-profile.js`
   - `performance-export.bundle.json`
   - `project.env`
   - `scripts/` when the selected execution profile references shared scripts
8. `sh` and `ps1` exports must execute `executionProfiles[].scriptRefs[]` by phase (`preRuntime`, `postRuntime`, `postHealthcheck`, `prePlan`) and must pass the export-local `project.env` through script `envFileArg` when declared.
9. Performance replay packages are workload-oriented. They must preflight the target, execute the configured load model, verify required Strict Line Keys, and persist deterministic throughput, error-rate, and latency results without requiring live MCP orchestration.
10. Exported performance replay run Artifacts under `exports/.../runs/...` must fail closed with deterministic `reasonCode`, `failedStep`, and `reasonMeta` when runtime replay cannot complete.

## Governance

1. If `includeResolvedSecrets=false`, redact or placeholder secret material.
2. If `includeResolvedSecrets=false`, create `project.env` with secret-like values blanked so the receiver can fill credentials locally.
3. If `includeResolvedSecrets=true`, add explicit sensitive warning in output artifacts and treat the package as non-shareable unless the receiver is trusted.
4. `sessionExport.includeResolvedSecrets=true` is not sufficient by itself; request-level explicit opt-in is required.
5. Never auto-push or auto-commit exported artifacts.

## Fail-Closed Conditions

1. no resolvable execution profile or plan selector
2. invalid export source shape
3. selected execution profile is not `suiteType=performance`
4. unsupported mode
5. non-writable export destination
6. one-off export folder creation failed

Blocked response must include:

1. deterministic reason code
2. single actionable next action
