---
name: mcp-java-dev-tools-regression-suite
description: "Run MCP-first HTTP regression suites across controller, service, or whole API scope with optional strict probe verification per endpoint."
---

# MCP JVM Regression Suite

Single-call execution skill for regression plans.

## Single-Call Execution Contract

1. Required input:
   - `project_name`
   - `plan_name`
2. Authoritative phase order:
   - `phase_0_load_plan`
   - `phase_1_project_context`
   - `phase_2_preflight_and_discovery`
   - `phase_3_strict_probe_gate`
   - `phase_4_step_execution`
   - `phase_5_artifact_persist_and_summary`
3. No phase skipping. Fail closed with deterministic reason and nextAction.

## Portable Source of Truth

Use these reference docs as the canonical execution bible:

1. `references/execution-contract.md`
2. `references/project-context-and-preflight.md`
3. `references/strict-probe-gate.md`
4. `references/synthesis-and-routing.md`
5. `references/artifact-contract.md`
6. `references/output-contract.md`

## Required Artifacts and Correlation

1. Run artifacts are written under:
   - `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/context.resolved.json`
   - `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/execution.result.json`
   - `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/evidence.json`
   - `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/correlation.json`
2. Workspace index path:
   - `.mcpjvm/correlation-index.json`
3. `execution.result.json` step entries MUST include `durationMs`.
4. Correlation uses canonical `correlationPolicy` + `correlationEvents`.
5. Do not author `correlation.json` directly; use canonical artifact writer flow.

## MCP-First and Wrapped Transport

1. Mandatory MCP tools: `probe_check`, `project_context_validate`, `probe_recipe_create`.
2. HTTP execution uses `transport_execute` (wrapped-only); no raw curl fallback.
3. If toolchain is unavailable:
   - `reasonCode=toolchain_unavailable`
   - `nextAction=enable_mcp_jvm_debugger_tools_then_rerun`
4. Wrapper script usage is optional implementation detail.

## Discovery-First and Strict Runtime Rules

1. Apply Discovery-First Orchestration from `references/project-context-and-preflight.md`.
2. If `metadata.execution.probeVerification=true`, strict probe gate is mandatory.
3. If probe remains unreachable after allowed auto-start attempt, fail closed with `external_healthcheck_failed`.
4. For terminal runtime with strict probe verification, use deterministic probe port mapping from `.mcpjvm/probe-config.json` (`--probe-id <id>` preferred; `--agent-port <port>` explicit override).
5. Do not rely on auto-scanned probe ports in strict mode.
6. If `projects.json` runtime context exists, startup/restart must use that context; ad-hoc direct `java -jar` fallback is non-compliant and must fail closed.


