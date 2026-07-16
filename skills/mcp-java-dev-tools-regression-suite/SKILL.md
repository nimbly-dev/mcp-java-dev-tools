---
name: mcp-java-dev-tools-regression-suite
description: "Run MCP-first HTTP regression suites (endpoint, service, or API scope) with optional strict probe-hit verification, bounded Watchers for downstream completion checks, and external verification for downstream data validity across HTTP or SQL targets."
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
   - `phase_5_watchers`
   - `phase_6_external_verification`
   - `phase_7_artifact_persist_and_summary`
3. No phase skipping. Fail closed with deterministic reason and nextAction.
4. In `phase_4_step_execution`, evaluate `steps[].when` before transport execution.
5. Condition outcomes are deterministic:
   - `true` => execute step
   - `false` => mark `skipped_condition_false` and continue
   - invalid/ambiguous => fail closed (`blocked_invalid`)
6. `phase_5_watchers` is mandatory when `contract.watchers[]` is present:
   - execute only after trigger-step completion
   - verify bounded downstream completion/readiness
   - fail closed on timeout, invalid dependency, invalid provider, or unreachable target
   - inherit `workspaces[].defaults.requestTimeoutMs` and `retryMax` unless the Watcher overrides them independently
   - treat `retryMax` as inclusive attempts; a required missing `actualPath` is retryable until that bound or the absolute deadline
   - return `watcher_actual_path_missing_retry_exhausted` for retry exhaustion and `watcher_timeout` when the deadline wins
   - record an optional missing `actualPath` as `skipped_optional` / `optional_actual_path_missing` without retry or failure
7. `phase_6_external_verification` is mandatory when `contract.externalVerification[]` is present:
   - execute only after trigger-step and watcher convergence
   - verify downstream data validity against external HTTP or SQL targets
   - preserve secret-safe runtime/project-owned provider configuration

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
6. `phase_5_watchers`:
   - reference: `references/execution-fsm.md`
7. `phase_6_external_verification`:
   - reference: `references/execution-fsm.md`
8. `phase_7_artifact_persist_and_summary`:
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
- record revision-safe operational progress in `.mcpjvm/<project_name>/run-state.sqlite` while retaining the canonical suite-status Artifact as execution evidence and resume input
- fail closed rather than rerunning already completed plans
- serialize overlapping resumes with the suite checkpoint lease; if another caller owns it, return `status="in_progress"`, `reasonCode="suite_checkpoint_owner_active"`, and `nextActionCode="resume_same_suite"`, then retry the same `suiteRunId` after the active owner advances the checkpoint
- renew the owning lease while an active Watcher polls; stale suite revisions or non-monotonic Watcher persistence must reload the SQLite checkpoint and return the same non-terminal resume conflict shape

11. When a plan is still waiting inside `watchers[]` or `externalVerification[]`, resumed orchestration must continue that same in-progress plan:

- use persisted `progressSummary.activePlan`
- continue the current phase (`watchers` or `external_verification`)
- do not reinterpret the wait as a fresh suite start

12. Use project-owned resiliency defaults from `.mcpjvm/<project_name>/projects.json`:

- require `workspaces[].defaults.orchestrator.resumePollMax`
- require `workspaces[].defaults.orchestrator.resumePollIntervalMs`
- require `workspaces[].defaults.orchestrator.resumePollTimeoutMs`
- fail closed rather than inventing plan-level or prompt-level resume policy

13. Do not summarize a runtime-suite `execution_profile` run as completed until the terminal orchestration status is returned.
14. Do not treat a caller/tool-boundary timeout as the primary execution result when resumable progress is available:

- resume the same `suiteRunId`
- report the final terminal suite status instead of timeout-first narration

15. Do not reuse a stale suite artifact as the result of a fresh execution request:

- the maintained workflow must correlate the final summary to the suite run started/resumed in the current request chain
- fail closed rather than attaching an older suite summary to a new prompt

16. Long-running example patterns:

- watcher-heavy async workflow: trigger producer step, persist `status="in_progress"` with `progressSummary.activePlan.phase="watchers"`, resume the same `suiteRunId` until watcher convergence or bounded outer stop
- external-verification-heavy workflow: trigger step plus watcher pass, persist `status="in_progress"` with `progressSummary.activePlan.phase="external_verification"`, resume the same `suiteRunId` until external verification reaches terminal status

## Context and Read Budget

### Extract scope and resumable context

`contract.steps[].extract[]` uses `scope="plan"` by default. Plan context is available to later steps, dependent Watchers, and external verification in that plan. A value intended for a later profile plan must use `scope="suite"` and `secret=false`; suite promotion is explicit and only occurs after the producer plan passes. Secret classification is explicit and is never inferred from output key names. A later plan that references an unpromoted suite key fails closed with `suite_context_forward_reference`, while secret suite promotion returns `suite_context_secret_forbidden`.

During resume, call `execution_orchestration` with the same `suiteRunId`. Continue the persisted active phase and preserve completed-step context; do not resend completed triggers. Secret values remain redacted from Artifacts, MCP output, SQLite summaries, and logs.

1. Always use bounded or windowed reads for Artifact inspection, logs, and generated scripts.
2. Do not switch to full Artifact reads based on artifact size; this workflow should use paged/windowed inspection by default.
3. Never dump full `contract.json`, `execution.result.json`, `evidence.json`, or export scripts into context when a bounded/windowed read can answer the question.
4. When a plan contains `watchers` or `externalVerification`, inspect those sections with the same bounded-read discipline used for `steps`.
5. For `artifact_management` `regression_plan` reads:
   - use `query.select`
   - treat `targets` as full
   - treat `prerequisites` as windowable
   - treat `steps` as windowable
6. When inspecting regression plans, always prefer explicit windows such as:
   - `query: { "select": ["summary", "prerequisites", "steps"], "prerequisites": { "offset": 0, "limit": 50 }, "steps": { "offset": 0, "limit": 25 } }`
7. For actual runtime suite execution, do not treat windowing as debug-only:
   - the orchestrator should load runtime suite plan inputs through these paged `artifact_management` calls as the maintained execution path
   - this applies even when a direct file read might seem cheaper
8. For file/shell inspection outside MCP Artifact reads, always prefer bounded reads such as:
   - `rg`
   - `Select-Object -First`
   - targeted line/field extraction
9. If a required investigation cannot be completed without Artifact inspection, read only the minimum slice needed and say which slice was inspected.

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
- `artifactType=run_result` (`list|read|rebuild`)
- `artifactType=run_result`, `action=cleanup` for bounded SQLite retention maintenance. Use explicit `input.projectName` and the optional `input.retention` policy; `dryRun` defaults to `true`.

12. For orchestrated runtime-suite execution, `artifact_management` is the maintained read path for execution profile lookup and regression plan loading.

## SQLite Retention Maintenance

Retention cleanup is an explicit maintenance operation, separate from runtime shutdown cleanup:

```json
{
  "artifactType": "run_result",
  "action": "cleanup",
  "input": {
    "projectName": "<project_name>",
    "retention": {
      "terminalOlderThanDays": 90,
      "keepMostRecentTerminalRuns": 1000,
      "dryRun": true,
      "maxDeleteBatch": 500
    }
  }
}
```

Run a dry run first. Age and count retention apply together; cleanup excludes active suites, active Watchers, unexpired leases, resumable state, and unsafe or missing canonical Artifact links. A project-scoped cleanup lease rejects concurrent invocations with `state_store_retention_conflict`. An applied cleanup removes only SQLite projections and linkage rows, never canonical Artifact files. When `summary.batchLimited=true`, repeat the same cleanup action until `remainingEligibleRuns` reaches zero. Fail closed on retention readiness, conflict, or Artifact-link safety reasons and follow `nextActionCode`.

## Required Artifacts and Correlation

1. Run artifacts are written under:
   - `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/context.resolved.json`
   - `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/execution.result.json`
   - `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/evidence.json`
   - `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/correlation/correlation.json`
2. Workspace index path:
   - `.mcpjvm/<project_name>/run-state.sqlite` is the writable operational correlation projection.
   - canonical per-run Artifacts remain execution evidence; do not write `correlation-index.json` during execution.
3. `execution.result.json` step entries MUST include `durationMs`.
4. Correlation uses canonical `correlationPolicy` + `correlationEvents`.
5. Do not author `correlation.json` directly; use canonical artifact writer flow.
6. When `watchers[]` execute, watcher outcome state is first-class Artifact data and must be preserved in `execution.result.json` and `evidence.json`.
7. When `externalVerification[]` execute, external-verification outcome state is first-class Artifact data and must be preserved in `execution.result.json` and `evidence.json`.
8. `run_id` MUST be canonical:
   - `MM-DD-YYYY-hh-mm-ssAM`
   - example: `05-09-2026-08-33-41PM`
9. Never invent ad-hoc run IDs (for example `20260509T134827387Z-customers`).
10. If run_id is non-canonical, fail closed before artifact write.
11. Runtime suite branch additionally references runtime manifest semantics at:

- `.mcpjvm/<project_name>/projects.json` with matching workspace `executionProfiles[]`

12. In multi-project workspaces (multiple `.mcpjvm/*/projects.json`), always pass explicit `projectName` in artifact reads to avoid ambiguity.
13. Persisted artifact access in this workflow should route through `artifact_management` wherever MCP path is available.
14. Treat persisted suite status as the canonical resumable state for long-running workflows:

- `suiteRunId`
- `nextPlanOrder`
- `progressSummary.activePlan`
- completed `planRuns[]`

15. SQLite operational state is never a replacement for canonical run Artifacts. Persist canonical evidence first, then persist the checkpoint; a checkpoint-persistence failure blocks safe continuation and must return deterministic recovery guidance.
16. SQLite recovery is a maintenance workflow, not normal suite execution. Rebuild only from canonical run Artifacts through `artifact_management` with `artifactType=run_result` and `action=rebuild`; do not use legacy `correlation-index.json` or invent active checkpoint state.
17. Never invoke legacy JSON backfill during normal suite execution or as a post-cutover fallback; it is an explicit pre-cutover maintenance action only.
18. Cutover is an explicit maintenance action; once complete, SQLite is required and no legacy correlation-index writer or query fallback is permitted.
19. Correlation projection must preserve the same `runId` and `correlationSessionId`; a fresh suite run must not reuse a terminal Correlation result from an older run merely because its key matches.
20. Treat persisted Probe scope state as historical observation only. Live Sidecar Probe state and runtime-instance identity remain authoritative.

## MCP-First and Wrapped Transport

1. Mandatory MCP tools: `probe`, `artifact_management`, `route_synthesis`.
2. HTTP execution uses `transport_execute` (wrapped-only); no raw curl fallback.
3. Watchers must remain bounded and fail closed; do not replace watcher polling with unbounded sleeps or open-ended retries.
4. External verification contracts must keep secret-bearing connection or credential material outside persisted plan defaults.
5. If toolchain is unavailable:
   - `reasonCode=toolchain_unavailable`
   - `nextAction=enable_mcp_jvm_debugger_tools_then_rerun`
6. Wrapper script usage is optional implementation detail.

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
5. Validate `watchers[]` and `externalVerification[]` contracts during preflight; do not defer malformed capability contracts to best-effort runtime behavior.

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
9. `watcher_dependency_invalid`
10. `watcher_provider_invalid`
11. `watcher_wait_policy_invalid`
12. `watcher_timeout`
13. `watcher_target_unreachable`
14. `external_verification_provider_invalid`
15. `external_verification_request_invalid`
16. `external_verification_target_unreachable`

## Watcher Checkpoint Persistence

When a Watcher executes, canonical run Artifacts are written first and the bounded operational projection is then upserted into `.mcpjvm/<project_name>/run-state.sqlite`.

- Preserve one `watcher_runs` row per Watcher execution and bounded `watcher_attempts` rows; never create one row per processed item or raw response.
- Resume the same Watcher identity with its original absolute deadline, resolved timeout/retry policy, attempt count, continuation, and suiteRunId.
- Treat stale revisions, changed deadlines, decreasing attempts, terminal-state changes, invalid continuation, and checkpoint persistence failures as deterministic fail-closed outcomes.
- Do not rerun the dependent trigger when a valid Watcher continuation exists. A checkpoint failure while work is in progress blocks safe continuation.
- Persist only bounded sanitized observation/assertion summaries; never persist credentials, authorization headers, or raw response bodies.

## SQL External Verification

SQL external verification supports SQLite and PostgreSQL. PostgreSQL connection context is project-owned under `sql.connection.<connectionRef>.*` (`host`, `port`, `database`, `username`, `password`, and explicit `tls.mode`). It runs through the Node `pg` provider with bound named parameters, bounded timeout/rows/response size, and secret-safe diagnostics. Do not configure JDBC URLs, drivers, classpaths, or ask callers to provide database passwords inline. For PostgreSQL MCP integration coverage, use an isolated ephemeral Docker database and report `postgresql_it_docker_unavailable` as the sole infrastructure result when Docker is unavailable.
