---
name: mcp-java-dev-tools-regression-plan-crafter
description: "Create or update deterministic regression plans (`metadata.json`, `contract.json`, `plan.md`) under `.mcpjvm/.../plans/regression`, including ordered trigger steps, optional Watchers for downstream completion checks, and optional external verification for downstream data validity."
---

# MCP JVM Regression Plan Crafter

Use this skill to author or refine a persisted regression plan spec package before execution/replay.

## Execution Mode

This skill must run in two phases:

1. `Research`
2. `Craft`

Do not skip `Research` when route/base path evidence is incomplete.

## Goal

Produce a deterministic, fail-closed plan package:

1. `.mcpjvm/<project_name>/plans/regression/<regression_name>/metadata.json`
2. `.mcpjvm/<project_name>/plans/regression/<regression_name>/contract.json`
3. `.mcpjvm/<project_name>/plans/regression/<regression_name>/plan.md`

Do not hand-author `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>/...` artifacts in this skill. Those are machine-generated during execution.
Use `artifact_management` (`artifactType=regression_plan`) as the canonical MCP Tool for read/validate/upsert/list lifecycle operations.

## Portable Source of Truth

Always align with:

1. `references/spec-rules.md`
2. `references/authoring-checklist.md`
3. `references/templates/metadata.template.json`
4. `references/templates/contract.template.json`
5. `references/templates/plan.template.md`

These references are bundled with the skill so it remains installable and usable across repositories.
If user input conflicts with these rules, fail closed and request clarification.

## Contract Rules

1. `metadata.execution.intent` must be `regression`.
2. `contract.steps` must be strict ordered `1..N` with unique `order`.
3. `steps[].protocol` must match a key under `steps[].transport` (for example `protocol=http` requires `transport.http`).
4. No hardcoded secrets in `metadata.json`, `contract.json`, or `plan.md`.
5. `targets[].selectors.fqcn` is mandatory for deterministic target identity.
6. If runtime pinning is enabled (`probeVerification=true`, `pinStrictProbeKey=true`), each target must provide `runtimeVerification.strictProbeKey` in `FQCN#method:line` format.
7. `steps[].when` is optional and supports deterministic condition nodes only (`all`, `any`, `not`) and predicates (`equals`, `not_equals`, `in`, `exists`).
8. `steps[].when.left` must reference only `context.*` or prior `step[n].*` (where `n < current step order`).
9. `watchers[]` is optional and first-class for bounded downstream completion verification after trigger-step success.
10. `externalVerification[]` is optional and first-class for downstream data-validity verification after trigger/watcher convergence.
11. Execution Orchestrator resiliency is project-owned:
   - do not add plan-level resume/poll knobs
   - rely on `.mcpjvm/<project_name>/projects.json` `workspaces[].defaults.orchestrator.*`
12. When `correlation.enabled=true`, author `strictLineExpectations[]` for Strict Line evidence that requires persistence. Each expectation has a unique `sequenceOrder`, `strictLineKey`, `selectorPolicy=exact_instance`, count operator (`exact|at_least|at_most|range`), and bounded expected delta or range. Multi-instance selector policies remain fail-closed until frozen membership execution support is available.
13. Use one aggregate expectation for repeated processing (for example a 500-item reindex); do not create one expectation per Line Hit. Pair non-isolated count evidence with a Watcher or external verification.

## Plan Authoring Workflow

1. Research target and route facts
2. Collect target and scope
3. Define prerequisites
4. Define ordered steps
5. Define step expectations
6. Define optional watchers
7. Define optional external verification
8. Generate `plan.md` with deterministic verbs
9. Validate consistency and fail closed on ambiguity

### 0) Research target and route facts

Before crafting, gather only provable facts from:

1. source mappings
2. runtime docs/contracts (for example OpenAPI), if available
3. explicit user-provided inputs

Record unresolved route/base-path items as missing context. Do not synthesize guessed prefixes.

### 1) Collect target and scope

Capture:

1. Regression name (`<regression_name>`)
2. Target type (`class_method`, `class_scope`, `module_scope`)
3. Deterministic selectors:
   - required: `fqcn`
   - optional: `method`, `signature`, `sourceRoot`

If multi-module ambiguity exists and no deterministic selector is provided, fail closed.

### 2) Define prerequisites

For each context key:

1. `key`
2. `required`
3. `secret`
4. `provisioning` (`user_input` | `discoverable`)
5. `discoverySource` when `provisioning=discoverable` (`datasource` | `runtime_context`)
6. optional `default` (non-secret only)

Use prerequisites for reusable runtime inputs (for example `tenantId`, `region`, `auth.bearer`).

### 3) Define ordered steps

For every executable step:

1. assign `order` sequentially
2. assign stable `id`
3. point `targetRef`
4. set `protocol`
5. define `transport.<protocol>` details
6. optionally add `extract` mappings for cross-step context
7. optionally add `when` for deterministic conditional execution

Keep steps natural and dependency-aware (for example create before update/delete).

### 4) Define step expectations

Add deterministic assertions under `steps[].expect[]` for every step.

Required expectation fields:

1. `id`
2. `actualPath`
3. `operator`
4. `expected` (when required by operator)
5. optional `required` (`true` by default)

Supported operators:

1. `field_equals`
2. `field_exists`
3. `field_matches_regex`
4. `numeric_gte`
5. `numeric_lte`
6. `contains`
7. `probe_line_hit`
8. `outcome_status`

### 5) Define optional watchers

Use `watchers[]` when the regression must prove downstream completion or readiness beyond the trigger path.

For each watcher:

1. assign stable `id`
2. define `dependency.stepOrder` against an earlier trigger step
3. define a bounded provider contract
4. set deterministic expectations over downstream readiness/completion state
5. keep watcher semantics complementary to `Correlation`, not a replacement for it
6. assume watcher waits may span multiple resumed orchestration passes; author expectations for continuation of the same in-progress plan, not repeated reruns of prior completed plans

### 6) Define optional external verification

Use `externalVerification[]` when the regression must prove downstream data validity against an external HTTP or SQL target after trigger-path completion.

For each external verification:

1. assign stable `id`
2. define `provider.type`
3. define provider request details under `request`
4. optionally add `extract` mappings
5. add deterministic expectations against returned HTTP or SQL state

Rules:

1. keep secret-bearing provider configuration out of persisted plan defaults
2. keep provider contracts vendor-neutral at authoring time
3. use canonical `${key}` placeholders for context interpolation
4. assume external verification may be resumed from an already in-progress plan after prior trigger or watcher completion; do not model it as a separate rerun-capable plan

### 7) Generate `plan.md`

Required sections:

1. `Purpose`
2. `Targets`
3. `Prerequisites`
4. `Steps`
5. `Expected Outcomes`

Required action verbs in `Steps`:

1. `Executes`
2. `Captures`
3. `Uses`
4. `Sets`
5. `WaitsFor`
6. `Verifies`

Required outcome verbs in `Expected Outcomes`:

1. `Returns`
2. `Emits`
3. `Produces`
4. `Matches`
5. `Passes`

### 8) Validate consistency

Before finalizing, verify:

1. Metadata and contract compatibility
2. Target selectors are deterministic
3. Step ordering and protocol/transport mapping are valid
4. Prerequisites cover all referenced context keys
5. No secrets are persisted as defaults
6. `plan.md` semantics match `contract.json`
7. optional `watchers[]` depend only on prior steps and use bounded completion expectations
8. optional `externalVerification[]` preserve secret-safe provider configuration boundaries
9. the plan does not introduce resume policy; long-running continuation depends on project-owned orchestrator defaults

If any check fails, return blocked guidance with exact missing/invalid fields and no speculative defaults.

## Required Deliverables Per Craft Request

When user asks to craft a plan, produce or update:

1. `.mcpjvm/<project_name>/plans/regression/<regression_name>/metadata.json`
2. `.mcpjvm/<project_name>/plans/regression/<regression_name>/contract.json`
3. `.mcpjvm/<project_name>/plans/regression/<regression_name>/plan.md`

Never require manual hand-construction when templates can be applied.
Use the template files, then specialize fields from the user context.
Persist through `artifact_management` with `action=upsert` after `action=validate`.

## Fail-Closed Cases

Stop and return deterministic blocked guidance when:

1. target selector is ambiguous (for example duplicate module candidates with no disambiguator)
2. step order is non-sequential or duplicated
3. protocol and transport key do not match
4. required context keys cannot be determined and no safe default exists
5. pinned strict probe key is required but invalid/missing
6. user asks to persist secrets as defaults
7. base path/prefix is not proven but required to produce executable route steps
8. discoverable prerequisite is missing `discoverySource`
9. `steps[].when` is malformed, uses unsupported operators, or references non-deterministic paths
10. `steps[].when` references the same or a future step
11. watcher dependency points to the same or a future step
12. external verification embeds secret-bearing provider configuration into persisted plan defaults
13. plan attempts to author orchestrator resume/poll knobs instead of relying on project-owned defaults

## Base Path Policy (No Assumptions)

1. Never assume or inject a default route prefix/base path.
2. Set base path only when:
   - user provided it explicitly, or
   - it is proven by source/runtime evidence.
3. If base path is unproven:
   - leave it unset in crafted plan fields,
   - return deterministic `needs_user_input` guidance for the missing key.

## Output Style

When crafting or updating plans, output:

1. Absolute paths changed
2. Short summary of deterministic selectors and step ordering
3. Any blocked fields that require user input

## Non-Goals

1. Do not create additional MCP tools beyond the existing `artifact_management` path.
2. Do not execute regression runs from this skill.
3. Do not write `.mcpjvm/<project_name>/plans/regression/<plan>/runs/<run_id>` artifacts manually.
