---
name: mcp-java-dev-tools-regression-suite
description: "Run MCP-first HTTP regression suites (endpoint, service, or API scope) with optional strict probe-hit verification."
---

# MCP JVM Regression Suite

Single-call execution skill for regression plans.

## Intent Router

1. Prompts like `run regression suite`, `execute regression suite`, or `using executionProfile <name>` MUST route here.
2. For `execution_profile` intent, execute the runtime suite branch directly; do not route to execution-profile export.
3. Replay/export scripts are artifacts only and are not the suite executor.
4. If an upstream flow attempts to use replay export for suite execution, fail closed with `reasonCode=execution_route_invalid`.

## Single-Call Execution Contract

1. Required input:
   - `project_name`
   - exactly one of:
     - `plan_name` (single-plan branch)
     - `execution_profile` (ordered runtime-suite branch)
2. Authoritative phase order:
   - `phase_0_load_plan`
   - `phase_1_project_context`
   - `phase_2_preflight_and_discovery`
   - `phase_3_strict_probe_gate`
   - `phase_4_step_execution`
   - `phase_5_artifact_persist_and_summary`
3. No phase skipping. Fail closed with deterministic reason and nextAction.
4. In `phase_4_step_execution`, evaluate `steps[].when` before transport execution.
5. Condition outcomes are deterministic:
   - `true` => execute step
   - `false` => mark `skipped_condition_false` and continue
   - invalid/ambiguous => fail closed (`blocked_invalid`)

## Branch Router

1. `execution_profile` present => `runtime_suite_branch`:
   - load workspace execution profile by `executionProfile` through `artifact_management` (`artifactType=project_context`, `action=read`) using explicit typed input: `{ "projectName": "<project_name>", "query": { "select": ["executionProfiles"] } }`
   - validate ordered `plans[]` and execution policy
   - load each ordered plan through `artifact_management` (`artifactType=regression_plan`, `action=read`) with explicit pagination for windowable sections before execution
   - execute plans in order using suite policy
2. `plan_name` present => `single_plan_branch`:
   - execute one regression plan using phase pipeline below
3. both `execution_profile` and `plan_name` present => fail closed (`execution_input_conflict`)
4. neither present => fail closed (`execution_input_required`)

## FSM Router

This `SKILL.md` is a thin router. Execute phases in order and load only the needed reference/script for each phase.

1. `phase_0_load_plan`:
   - reference: `references/execution-fsm.md`
2. `phase_1_project_context`:
   - reference: `references/runtime-policy.md`
   - script: `scripts/runtime-converge.js`
3. `phase_2_preflight_and_discovery`:
   - reference: `references/runtime-policy.md`
   - script: `scripts/preflight-resolve.js`
4. `phase_3_strict_probe_gate`:
   - reference: `references/probe-verification-policy.md`
   - script: `scripts/probe-gate-check.js`
5. `phase_4_step_execution`:
   - reference: `references/execution-fsm.md`
   - script: `scripts/step-execution-check.js`
6. `phase_5_artifact_persist_and_summary`:
   - reference: `references/artifact-contract.md`
   - reference: `references/output-contract.md`
   - script: `scripts/summarize-run.js`
   - script: `scripts/cleanup-runtime.js`

Runtime suite branch (`execution_profile`) rules:

1. Execute `plans[]` strictly by `order`.
2. Respect suite `executionPolicy`:
   - `stop_on_fail`
   - `continue_on_fail`
3. Respect per-plan `onFail` override:
   - `inherit`
   - `stop`
   - `continue`
4. Allow suite `runtimeConfig` overrides only for:
   - `requestTimeoutMs`
   - `retryMax`
5. Do not accept unrecognized `runtimeConfig` keys.
6. For actual runtime suite execution, treat `artifact_management` as the canonical plan loader:
   - do not read `contract.json`, `metadata.json`, or `plan.md` directly when the same data can be loaded through `artifact_management`
   - do not use shell/file reads as the primary source for plan steps or prerequisites
   - fail closed rather than bypassing `artifact_management` for regression plan loading when MCP path is available
7. For each plan in `execution_profile` order, use a staged plan load:
   - first read `query.select=["summary","targets"]`
   - then read `prerequisites` with explicit `{ "offset": ..., "limit": ... }`
   - then read `steps` with explicit `{ "offset": ..., "limit": ... }`
   - continue windowing until all required plan sections are loaded
8. Do not replace runtime-suite artifact paging with ad hoc small-enough full reads.
9. For `execution_profile` prompts, the maintained execution path MUST use resumable orchestration slices:
   - call `execution_orchestration` with `maxPlansPerCall`
   - if the tool returns `status="in_progress"`, re-call `execution_orchestration` with the returned `suiteRunId`
   - continue the same run until terminal `pass`, `fail`, `blocked`, or `partial_fail`
10. Do not restart from the beginning when resumable progress exists:
   - resume with the same `suiteRunId`
   - trust `nextPlanOrder` from the persisted suite-status artifact
   - fail closed rather than rerunning already completed plans
11. Do not summarize a runtime-suite `execution_profile` run as completed until the terminal orchestration status is returned.
12. Do not treat a caller/tool-boundary timeout as the primary execution result when resumable progress is available:
   - resume the same `suiteRunId`
   - report the final terminal suite status instead of timeout-first narration
13. Do not reuse a stale suite artifact as the result of a fresh execution request:
   - the maintained workflow must correlate the final summary to the suite run started/resumed in the current request chain
   - fail closed rather than attaching an older suite summary to a new prompt

## Context and Read Budget

1. Always use bounded or windowed reads for Artifact inspection, logs, and generated scripts.
2. Do not switch to full Artifact reads based on artifact size; this workflow should use paged/windowed inspection by default.
3. Never dump full `contract.json`, `execution.result.json`, `evidence.json`, or export scripts into context when a bounded/windowed read can answer the question.
4. For `artifact_management` `regression_plan` reads:
   - use `query.select`
   - treat `targets` as full
   - treat `prerequisites` as windowable
   - treat `steps` as windowable
5. When inspecting regression plans, always prefer explicit windows such as:
   - `query: { "select": ["summary", "prerequisites", "steps"], "prerequisites": { "offset": 0, "limit": 50 }, "steps": { "offset": 0, "limit": 25 } }`
6. For actual runtime suite execution, do not treat windowing as debug-only:
   - the orchestrator should load runtime suite plan inputs through these paged `artifact_management` calls as the maintained execution path
   - this applies even when a direct file read might seem cheaper
6. For file/shell inspection outside MCP Artifact reads, always prefer bounded reads such as:
   - `rg`
   - `Select-Object -First`
   - targeted line/field extraction
7. If a required investigation cannot be completed without Artifact inspection, read only the minimum slice needed and say which slice was inspected.

## Source of Truth

Use these references/templates:

1. `references/execution-contract.md`
2. `references/execution-fsm.md`
3. `references/runtime-policy.md`
4. `references/probe-verification-policy.md`
5. `references/reason-codes.md`
6. `references/artifact-contract.md`
7. `references/output-contract.md`
8. `templates/fail-closed.result.json`
9. `templates/needs-user-input.result.json`
10. `templates/run-summary.result.json`
11. `artifact_management` MCP Tool (operational source) for artifact lifecycle reads/validations:
   - `artifactType=project_context` (`read|validate|list`)
   - `artifactType=regression_plan` (`read|validate|list`)
   - `artifactType=run_result` (`list|read`)
12. For orchestrated runtime-suite execution, `artifact_management` is the maintained read path for execution profile lookup and regression plan loading.

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
6. `run_id` MUST be canonical:
   - `MM-DD-YYYY-hh-mm-ssAM`
   - example: `05-09-2026-08-33-41PM`
7. Never invent ad-hoc run IDs (for example `20260509T134827387Z-customers`).
8. If run_id is non-canonical, fail closed before artifact write.
9. Runtime suite branch additionally references runtime manifest semantics at:
   - `.mcpjvm/<project_name>/projects.json` with matching workspace `executionProfiles[]`
11. In multi-project workspaces (multiple `.mcpjvm/*/projects.json`), always pass explicit `projectName` in artifact reads to avoid ambiguity.
10. Persisted artifact access in this workflow should route through `artifact_management` wherever MCP path is available.

## MCP-First and Wrapped Transport

1. Mandatory MCP tools: `probe`, `artifact_management`, `route_synthesis`.
2. HTTP execution uses `transport_execute` (wrapped-only); no raw curl fallback.
3. If toolchain is unavailable:
   - `reasonCode=toolchain_unavailable`
   - `nextAction=enable_mcp_jvm_debugger_tools_then_rerun`
4. Wrapper script usage is optional implementation detail.

## Runtime Rules

1. `autoStart=true`:
   - if app is down, start via `projects.json` runtime context
   - if app is up but non-compliant (probe down / no sidecar), replace and restart via runtime context
   - after runtime start/restart, wait for bounded required health-check convergence before continuing into `postRuntime` scripts or strict probe verification
2. `autoStart=false`:
   - do not start processes
   - if runtime is not already compliant, fail closed
3. If `metadata.execution.probeVerification=true`, strict probe gate is mandatory.
4. Ad-hoc direct `java -jar` fallback is non-compliant when `projects.json` runtime context exists.
5. Runtime context selection policy:
   - if `runtimeContextName` is provided, use it exactly or fail closed when unknown
   - if `runtimeContextName` is not provided and `terminal-cli` exists, select `terminal-cli`
   - otherwise if any terminal context exists, select terminal context
   - if multiple non-terminal contexts exist and no explicit selection is provided, fail closed and require `runtimeContextName`
6. Never attempt Docker convergence unless selected runtime context `mode=docker`.

## Discovery-First Orchestration

1. Build preflight from plan + context.
2. Resolve discoverable prerequisites before asking user input.
3. Merge precedence: user-provided > discovered > non-secret defaults.
4. Re-run preflight and continue only when ready.

## Strict Probe Port Mapping

1. For strict runtime verification, prefer `--probe-id <id>` with registry resolution.
2. Use `--agent-port <port>` only as explicit override.
3. Do not rely on auto-scanned probe port in strict mode.

## Deterministic Fail-Closed Codes

1. `external_healthcheck_failed`
2. `runtime_auto_replace_required` (intermediate converge signal; must auto-replace in same run when `autoStart=true`)
3. `probe_gate_failed`
4. `step_condition_malformed`
5. `step_condition_operator_invalid`
6. `step_condition_forward_reference`
7. `step_condition_path_missing`
8. `step_condition_type_mismatch`


