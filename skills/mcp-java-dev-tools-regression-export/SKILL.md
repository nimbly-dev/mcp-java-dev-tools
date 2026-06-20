---
name: mcp-java-dev-tools-regression-export
description: "Export regression execution profiles to runnable replay artifacts (ps1, sh, postman). Use for export, share, replay, or handoff of regression execution flows."
---

# MCP JVM Regression Export

Use this Skill Workflow to export replay artifacts from the current regression execution profile plus regression plan Artifact state.

## Scope Guard

1. This Skill Workflow exports replay artifacts only; it does not execute regression suites.
2. If the user asks to run or execute a regression suite, route to `mcp-java-dev-tools-regression-suite`.
3. Do not satisfy regression execution prompts by generating replay export scripts.
4. This Skill Workflow is for `suiteType=regression` only. If the selected execution profile is `suiteType=performance`, route to `mcp-java-dev-tools-performance-export`.

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
2. `mode` (`ps1` | `sh` | `postman`)
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

## Profile Selection

Resolve in this selector order:

1. explicit `export_id` (label only)
2. `execution_profile`
3. `plan_name` (resolves containing execution profile)
4. `when` (optional label hint)
5. default execution profile when no selector is provided

## Source of Truth

Operational source for Artifact lifecycle:

1. `artifact_management` MCP Tool:
   - `artifactType=project_context` (`read|validate|list`)
   - `artifactType=regression_plan` (`read|validate|list`)
   - `artifactType=execution_export` (`generate|read|list`)

Artifact references:

1. `.mcpjvm/<project_name>/projects.json` (`executionProfiles`)
2. `.mcpjvm/<project_name>/plans/regression/<plan>/contract.json`

No runtime run Artifact is required for default export behavior.

## Mode Router

1. `mode=ps1` => emit PowerShell replay package
2. `mode=sh` => emit shell replay package
3. `mode=postman` => emit Postman collection package
4. missing mode => fail closed (`execution_export_mode_required`)
5. unknown mode => fail closed
6. never apply implicit mode fallback/default

## Determinism Rules

1. Preserve execution order from execution profile plan order and contract step order.
2. Do not invent steps absent from execution profile or contract source.
3. Do not infer hidden runtime behavior.
4. Keep output stable for the same input.
5. Each export invocation emits a fresh one-off folder under `.mcpjvm/<project>/exports/<yyyy-mm-dd-uuid>/`.
6. Generated files for `sh` and `ps1` modes must emit a one-off replay package directly in that folder:
   - `run-execution-profile.sh` or `run-execution-profile.ps1`
   - `project.env`
   - `scripts/` when the selected execution profile references shared scripts
7. `sh` and `ps1` exports must execute `executionProfiles[].scriptRefs[]` by phase (`preRuntime`, `postRuntime`, `postHealthcheck`, `prePlan`) and must pass the export-local `project.env` through script `envFileArg` when declared.
8. `sh` and `ps1` exports must not scan `runtimeContexts[].startups[]` for token/auth helper scripts. Shared setup scripts belong in workspace-level `scripts[]` and are referenced by execution profile `scriptRefs[]`.

## Governance

1. If `includeResolvedSecrets=false`, redact or placeholder secret material.
2. If `includeResolvedSecrets=false`, create `project.env` with secret-like values blanked so the receiver can fill credentials locally.
3. If `includeResolvedSecrets=true`, add explicit sensitive warning in output artifacts and treat the package as non-shareable unless the receiver is trusted.
4. `sessionExport.includeResolvedSecrets=true` is not sufficient by itself; request-level explicit opt-in is required.
5. Never auto-push or auto-commit exported artifacts.

## Fail-Closed Conditions

1. no resolvable execution profile or plan selector
2. invalid export source shape
3. selected execution profile is not `suiteType=regression`
4. unsupported mode
5. non-writable export destination
6. one-off export folder creation failed

Blocked response must include:

1. deterministic reason code
2. single actionable next action
