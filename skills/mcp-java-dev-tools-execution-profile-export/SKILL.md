---
name: mcp-java-dev-tools-execution-profile-export
description: "Export execution profiles into deterministic self-contained artifacts using a single selected mode (ps1, sh, or postman)."
---

# MCP JVM Execution Profile Export

Use this skill to export execution replay artifacts from the current execution profile + regression plan state.

## Scope Guard

1. This skill exports replay artifacts only; it does not execute regression suites.
2. If the user asks to run or execute regression suite (for example `run ... using executionProfile ...`), route to `mcp-java-dev-tools-regression-suite`.
3. Do not satisfy regression execution prompts by generating replay export scripts.

## Execution Mode

This skill runs in three phases:

1. `Read`
2. `Assemble`
3. `Emit`

## Portable Source of Truth

Always align with:

1. `references/spec-rules.md`
2. `references/authoring-checklist.md`
3. `references/templates/index.md`

## Input Contract

Required input:

1. `project_name`
2. `mode` (`ps1` | `sh` | `postman`)

Optional:

1. `export_id` (optional export label; auto-derived when omitted)
2. `execution_profile` (latest matching selector)
3. `plan_name` (latest matching selector)
4. `when` (date/time hint for nearest selector)
5. `includeResolvedSecrets` (`false` default)
6. `includeRuntimeStartup` (`true` default; may be overridden by projects.json sessionExport defaults)
7. `includeHealthcheckGate` (`true` default; may be overridden by projects.json sessionExport defaults)
8. `includeResolvedSecrets` may be supplied per request or as a trusted local default under `projects.json.sessionExport`; request input overrides project defaults.

## Profile Selection

Resolve in this selector order:

1. explicit `export_id` (label only)
2. `execution_profile`
3. `plan_name` (resolves containing profile)
4. `when` (optional label hint)
5. default profile when no selector is provided

## Source of Truth

Primary source:

1. `.mcpjvm/<project_name>/projects.json` (`executionProfiles`)
2. `.mcpjvm/<project_name>/plans/regression/<plan>/contract.json`

No runtime run artifact is required for default export behavior.

## Mode Router

1. `mode=ps1` => emit PowerShell export package
2. `mode=sh` => emit shell export package
3. `mode=postman` => emit Postman collection package
4. unknown mode => fail closed

## Determinism Rules

1. Preserve execution order from profile plan order and contract step order.
2. Do not invent steps absent from profile/contract source.
3. Do not infer hidden runtime behavior.
4. Keep output stable for the same input.
5. Each export invocation emits a fresh one-off folder under `.mcpjvm/<project>/exports/<yyyy-mm-dd-uuid>/`.
6. Generated files for `sh` and `ps1` modes must emit a one-off replay package directly in that folder:
   - `run-execution-profile.sh` or `run-execution-profile.ps1`
   - `project.env`
   - `scripts/` when the selected execution profile references shared scripts
7. `sh` and `ps1` exports must execute `executionProfiles[].scriptRefs[]` by phase (`preRuntime`, `postRuntime`, `postHealthcheck`, `prePlan`) and must pass the export-local `project.env` through script `envFileArg` when declared.
8. `sh` and `ps1` exports must not scan `runtimeContexts[].startups[]` for token/auth helper scripts. Shared setup scripts belong in workspace-level `scripts[]` and are referenced by profile `scriptRefs[]`.

## Governance

1. If `includeResolvedSecrets=false`, redact or placeholder secret material.
2. If `includeResolvedSecrets=false`, create `project.env` with secret-like values blanked so the receiver can fill credentials locally.
3. If `includeResolvedSecrets=true`, add explicit sensitive warning in output artifacts and treat the package as non-shareable unless the receiver is trusted.
4. Never auto-push or auto-commit exported artifacts.

## Fail-Closed Conditions

1. no resolvable execution profile/plan selector
2. invalid export source shape
3. unsupported mode
4. non-writable export destination
5. one-off export folder creation failed

Blocked response must include:

1. deterministic reason code
2. single actionable next action
