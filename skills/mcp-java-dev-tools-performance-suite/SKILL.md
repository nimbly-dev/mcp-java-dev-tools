---
name: mcp-java-dev-tools-performance-suite
description: "Run MCP-first Java performance suites with required strict line-hit verification, concurrency-based workload contracts, and deterministic threshold evaluation. Use when executing or reviewing performance-suite Artifacts under `.mcpjvm/.../plans/performance` or execution profiles with `suiteType=performance`."
---

# MCP JVM Performance Suite

Single-call execution skill for performance plans.

## Intent Router

1. Prompts like `run performance suite`, `execute performance suite`, or `using executionProfile <name>` with `suiteType=performance` MUST route here.
2. Do not route performance prompts to regression suite or execution-profile export.
3. If an upstream flow attempts to treat a regression plan as a performance plan, fail closed with `reasonCode=execution_route_invalid`.

## Single-Call Execution Contract

1. Required input:
   - `project_name`
   - exactly one of:
     - `plan_name`
     - `execution_profile`
2. Authoritative phase order:
   - `phase_0_load_plan`
   - `phase_1_project_context`
   - `phase_2_preflight_and_runtime_readiness`
   - `phase_3_strict_line_gate`
   - `phase_4_workload_execution`
   - `phase_5_threshold_evaluation`
   - `phase_6_artifact_persist_and_summary`
3. No phase skipping. Fail closed with deterministic reason and next action.
4. `Strict Line Key` verification is mandatory for performance execution.
5. `loadModel.mode` currently supports only `concurrency`.
6. MSTA is optional performance evidence and does not replace required `Strict Line Key` proof.

## Branch Router

1. `execution_profile` present => `runtime_suite_branch`:
   - load workspace execution profile through `artifact_management` (`artifactType=project_context`, `action=read`)
   - require `executionProfiles[].suiteType=performance`
   - validate ordered `plans[]` and execution policy
   - load each ordered performance plan through the canonical performance-plan Artifact path/tooling when available
2. `plan_name` present => `single_plan_branch`
3. both present => fail closed (`execution_input_conflict`)
4. neither present => fail closed (`execution_input_required`)

## Reference Router

Load only the references needed for the current phase.

1. `phase_0_load_plan`:
   - `references/execution-fsm.md`
2. `phase_1_project_context`:
   - `references/runtime-policy.md`
3. `phase_2_preflight_and_runtime_readiness`:
   - `references/execution-contract.md`
   - `references/runtime-policy.md`
4. `phase_3_strict_line_gate`:
   - `references/execution-contract.md`
5. `phase_4_workload_execution`:
   - `references/execution-fsm.md`
6. `phase_5_threshold_evaluation`:
   - `references/output-contract.md`
7. `phase_6_artifact_persist_and_summary`:
   - `references/artifact-contract.md`
   - `references/output-contract.md`

## Runtime Suite Rules

1. Execute `plans[]` strictly by `order`.
2. Respect suite `executionPolicy`:
   - `stop_on_fail`
   - `continue_on_fail`
3. Respect per-plan `onFail` override:
   - `inherit`
   - `stop`
   - `continue`
4. Require `suiteType=performance` for all plans in the selected execution profile.
5. Do not mix regression and performance plans in one execution profile.
6. `Strict Line Key` proof is not optional in performance mode.
7. Empty `requiredLineHits` is invalid and must fail closed before execution.
8. `analysis.msta.enabled=true` requires a valid `analysis.executionTiming` block and explicit method targets.

## Context and Read Budget

1. Always use bounded or windowed reads for Artifact inspection.
2. Prefer canonical Artifact lifecycle tools over direct file reads when the MCP path is available.
3. Do not dump full Artifacts into context when a bounded slice is sufficient.

## Source of Truth

Always align with:

1. `references/execution-contract.md`
2. `references/execution-fsm.md`
3. `references/runtime-policy.md`
4. `references/reason-codes.md`
5. `references/artifact-contract.md`
6. `references/output-contract.md`

## Artifact Rules

1. Performance plans live under:
   - `.mcpjvm/<project_name>/plans/performance/<plan_name>/`
2. Run Artifacts live under:
   - `.mcpjvm/<project_name>/plans/performance/<plan_name>/runs/<run_id>/`
3. Expected files:
   - `context.resolved.json`
   - `execution.result.json`
   - `evidence.json`
   - optional `execution-timing.msta.json`
4. Performance-plan `contract.json` is workload-centric, not step-centric.

## MCP-First and Wrapped Transport

1. Mandatory MCP tools remain:
   - `probe`
   - `artifact_management`
   - `route_synthesis` when transport facts need discovery
2. Wrapped transport remains the canonical request path for entrypoint execution.
3. Third-party load generators are implementation details, not the primary Artifact contract.
4. If the performance executor/toolchain is unavailable, fail closed with `reasonCode=toolchain_unavailable`.

## Deterministic Fail-Closed Codes

1. `execution_route_invalid`
2. `execution_input_conflict`
3. `execution_input_required`
4. `performance_profile_required`
5. `performance_required_line_hit_missing`
6. `performance_load_model_unsupported`
7. `performance_threshold_invalid`
8. `toolchain_unavailable`

## SQLite Retention Maintenance

After performance runs are no longer needed for operational queries, use the `artifact_management` MCP Tool for explicit `run_result` SQLite retention maintenance. This is separate from runtime process shutdown:

```json
{
  "artifactType": "run_result",
  "action": "cleanup",
  "input": {
    "projectName": "<project_name>",
    "retention": { "dryRun": true }
  }
}
```

Use a dry run before applying deletion. The cleanup action retains runs using the conjunctive age/count policy, skips active or leased state and unsafe canonical Artifact links, writes bounded audit provenance, and never deletes canonical Artifact files. A project-scoped cleanup lease rejects concurrent invocations with `state_store_retention_conflict`; a `batch_limited` result must be retried through the same action.
