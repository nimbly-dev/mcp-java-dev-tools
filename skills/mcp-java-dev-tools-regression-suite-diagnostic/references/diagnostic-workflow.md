# Diagnostic Workflow

## Plan validation

1. Read and validate the named Regression Plan through `artifact_management`.
2. Read the selected `project_context` through `artifact_management`.
3. Conditionally read execution-profile context, runtime context, health-check policy, Probe configuration, and project-owned resiliency defaults.
4. Cross-check prerequisites, targets, Strict Line Keys, Watchers, external verification, execution profile references, and project policy.
5. Return `executable`, `blocked`, or `invalid` with one phase/reason and one safe next action.

## Execution diagnosis

1. Validate the selector before any state read.
2. Resolve `stateQuery` through `run_result` `query`; it must resolve exactly one execution.
3. Read canonical run Artifacts: `context.resolved.json`, `execution.result.json`, `evidence.json`, and `correlation/correlation.json` through the maintained Artifact Management path.
4. Query `run_state` when applicable. Query `watcher_state`, `correlation_state`, or external-verification detail only when the Artifact evidence or run projection requires it.
5. Identify the earliest terminal or blocking phase in this order: `preflight`, `strict_probe_gate`, `trigger_execution`, `watchers`, `external_verification`, `correlation`, `artifact_persistence`, `execution_orchestration`.
6. Optionally observe current Probe readiness; never use current readiness to rewrite completed historical evidence.
7. Return `diagnosed`, `blocked`, or `inconclusive` with bounded evidence and exactly one next action.

All reads must be summary-first, windowed, or cursor-bounded. Stop and fail closed when required evidence cannot be collected.
