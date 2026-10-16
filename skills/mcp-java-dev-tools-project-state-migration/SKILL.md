---
name: mcp-java-dev-tools-project-state-migration
description: "Safely preserve and validate an existing projects.json Artifact while migrating canonical run state through artifact_management. Use for project-state migration, SQLite rebuild/backfill/cutover, retention, or post-migration verification requests."
---

# MCP Java Dev Tools Project State Migration

## Java CDE invocation

The public MCP Tools are `operation_catalog`, `operation_describe`, and `operation_execute`. Discover supported IDs with Catalog; call Describe with `{"operationId":"<id>"}` before execution. Java Describe owns the current argument schema and safety requirements.

Call `operation_execute` with `{"operationId":"<id>","arguments":{},"confirmed":false}`, replacing `arguments` with the described fields. Set outer `confirmed:true` only for an authorized operation when Describe requires it. Do not send the retired Tool/action/input envelope or an inner lifecycle `confirm`. Nested operation-specific actions remain in `arguments.action` only when Describe declares them. Read the CDE status first, then the capability result in `result`; use structured MCP content and preserve reason codes. Artifact details are under `result.details`; Probe and lifecycle action data are under `result.actionResult`.

This workflow consumes these canonical IDs as needed (not an instruction to execute every operation):

- `artifact_management.project_context.read`
- `artifact_management.project_context.validate`
- `artifact_management.project_context.upsert`
- `artifact_management.run_result.rebuild`
- `artifact_management.run_result.backfill`
- `artifact_management.run_result.cutover`
- `artifact_management.run_result.cleanup`
- `artifact_management.run_result.query`

Use this Skill Workflow to coordinate project configuration preservation and local run-state migration through the maintained Artifact Management operations.

## Required Workflow

1. Require an explicit `projectName`; do not infer among multiple project Artifacts.
2. Read the complete sanitized project Artifact before any update:

   ```json
   {
     "operationId": "artifact_management.project_context.read",
     "arguments": {
       "projectName": "<project_name>",
       "query": { "select": ["artifact"] }
     },
     "confirmed": false
   }
   ```

3. Validate the existing Artifact and retain its runtime contexts, execution profiles, scripts, defaults, external systems, and plan references in memory.
4. Apply requested project configuration changes as an in-memory patch. Use `artifact_management.project_context.upsert` without `replace` for safe merge behavior; use `replace: true` only after explicit operator acknowledgement of complete replacement.
5. `artifact_management.project_context.validate` checks persisted state, not an in-memory payload. The authorized `artifact_management.project_context.upsert` validates the proposed payload and merged output before writing; stop on failure and validate/read back after success. Never migrate from unvalidated project state.
6. After project configuration is preserved and validated, select the state-store path:
   - legacy Correlation import: `artifact_management.run_result.backfill`, `stateSurface=correlation_state`;
   - canonical run-state reconstruction: `artifact_management.run_result.rebuild`;
   - explicit SQLite transition: `artifact_management.run_result.cutover`;
   - bounded retention maintenance: `artifact_management.run_result.cleanup`;
   - post-migration verification: `artifact_management.run_result.query` with the appropriate state surface.
   - Backfill is correlation-only reconciliation: the supported sequence for existing projects is `rebuild -> backfill -> cutover`. It reuses canonical `plan_runs`, inserts only missing correlation projections, skips equivalent rows idempotently, and preserves canonical state on divergence.
7. Require explicit operator direction before `cutover` or applied retention cleanup. Use dry-run retention first.
8. Report bounded deterministic outputs and preserve the returned reason codes and `nextActionCode`.

## Safety Rules

- Use only Artifact Management operations; never write SQLite directly or execute arbitrary SQL.
- Never delete, relocate, recreate, or rewrite canonical run Artifacts or legacy source JSON during migration.
- Do not treat legacy JSON as a post-cutover query fallback.
- Fail closed on missing, invalid, locked, corrupt, or unsupported project/state evidence.
- Classify terminal `fail_closed` entries with `reasonCode=correlation_key_extraction_failed` and absent session/key facts as `terminal_correlation_not_reconstructible`; skip them without manufacturing runtime truth and preserve bounded persisted audit provenance.
- Return bounded record locators (`entryIndex`, `planName`, `runId`) and `violatedFields` or `conflictingFields` for malformed or divergent entries.
- Preserve `state_store_locked`, `state_store_corrupt`, and `state_store_schema_unsupported`.
- A cleanup result with `batchLimited=true` must be retried through the same action; a concurrent cleanup returns `state_store_retention_conflict`.

## Completion Evidence

Return evidence for both phases:

1. Project Artifact phase: selected read, validation result, update mode (`created`, `merged`, or `replaced`), and preserved configuration summary.
2. State-store phase: selected maintenance action, bounded summary, readiness/cutover state, and post-migration query result.

If either phase lacks deterministic evidence, fail closed rather than claiming migration completion.
