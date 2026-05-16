---
name: mcp-java-dev-tools-run-session-export
description: "Export profile run sessions into deterministic self-contained artifacts using a single selected mode (ps1, sh, or postman)."
---

# MCP JVM Run Session Export

Use this skill to export one completed execution-profile run session from persisted artifacts.

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
2. `session_id`
3. `mode` (`ps1` | `sh` | `postman`)

Optional:

1. `includeResolvedSecrets` (`false` default)

## Source of Truth

Read only from persisted run-session artifacts:

1. `.mcpjvm/<project_name>/exports/session-runs-exports/<session_id>/session-manifest.json`

## Mode Router

1. `mode=ps1` => emit PowerShell export package
2. `mode=sh` => emit shell export package
3. `mode=postman` => emit Postman collection package
4. unknown mode => fail closed

## Determinism Rules

1. Preserve execution order from `planRuns[].order`.
2. Do not invent steps absent from session manifest.
3. Do not infer hidden runtime behavior.
4. Keep output stable for the same input.

## Governance

1. If `includeResolvedSecrets=false`, redact or placeholder secret material.
2. If `includeResolvedSecrets=true`, add explicit sensitive warning in output artifacts.
3. Never auto-push or auto-commit exported artifacts.

## Fail-Closed Conditions

1. missing `session-manifest.json`
2. invalid session manifest shape
3. unsupported mode
4. non-writable export destination

Blocked response must include:

1. deterministic reason code
2. single next action
