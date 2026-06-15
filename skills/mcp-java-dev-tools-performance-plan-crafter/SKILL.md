---
name: mcp-java-dev-tools-performance-plan-crafter
description: "Create or update deterministic performance plans (`metadata.json`, `contract.json`, `plan.md`) under `.mcpjvm/.../plans/performance`. Use when authoring workload-centric Java performance-suite Artifacts with required strict line-hit verification."
---

# MCP JVM Performance Plan Crafter

Use this skill to author or refine a persisted performance plan package before execution.

## Execution Mode

This skill must run in two phases:

1. `Research`
2. `Craft`

Do not skip `Research` when transport facts, runtime entrypoints, or required `Strict Line Key` targets are incomplete.

## Goal

Produce a deterministic, fail-closed plan package:

1. `.mcpjvm/<project_name>/plans/performance/<performance_name>/metadata.json`
2. `.mcpjvm/<project_name>/plans/performance/<performance_name>/contract.json`
3. `.mcpjvm/<project_name>/plans/performance/<performance_name>/plan.md`

Do not hand-author `runs/<run_id>/...` Artifacts in this skill.

## Portable Source of Truth

Always align with:

1. `references/spec-rules.md`
2. `references/authoring-checklist.md`
3. `references/templates/metadata.template.json`
4. `references/templates/contract.template.json`
5. `references/templates/plan.template.md`

If user input conflicts with these rules, fail closed and request clarification.

## Contract Rules

1. `metadata.execution.intent` must be `performance`.
2. `metadata.suiteType` must be `performance`.
3. `entrypoints[]` must contain at least one entry.
4. `observationTargets.requiredLineHits[]` must contain at least one `Strict Line Key`.
5. `loadModel.mode` must be `concurrency` for the current supported contract.
6. `loadModel.concurrency` must be positive integer.
7. `loadModel.rampUpSeconds` must be integer `>= 0`.
8. `loadModel.durationSeconds` must be positive integer.
9. `successCriteria` must define deterministic threshold fields only.
10. No hardcoded secrets in any authored Artifact.

## Plan Authoring Workflow

1. Research entrypoint and runtime facts
2. Collect workload scope
3. Define observation targets
4. Define entrypoints
5. Define load model
6. Define success criteria
7. Generate `plan.md`
8. Validate consistency and fail closed on ambiguity

### 0) Research entrypoint and runtime facts

Before crafting, gather only provable facts from:

1. source mappings
2. runtime docs/contracts
3. explicit user-provided inputs

Do not synthesize route prefixes or runtime targets.

### 1) Collect workload scope

Capture:

1. Performance name (`<performance_name>`)
2. Runtime/service under test
3. Load entrypoint transport facts
4. Required `Strict Line Key` proof targets

### 2) Define observation targets

Use `Strict Line Key` as the canonical identity.

Required fields:

1. `requiredLineHits[]`

Optional fields:

1. `optionalLineHits[]`

Do not require duplicated `className` or `method` fields when the strict line key already defines them.

### 3) Define entrypoints

For each entrypoint, define:

1. `transport.protocol`
2. `transport.baseUrl`
3. `transport.healthCheckPath` when needed
4. request method/path/query/body template

The observed Java target may be deeper than the entrypoint. Model them separately.

### 4) Define load model

Current supported mode:

1. `mode=concurrency`

Required fields:

1. `concurrency`
2. `rampUpSeconds`
3. `durationSeconds`

Do not author unsupported modes such as `arrival_rate`.

### 5) Define success criteria

Base threshold fields:

1. `maxErrorRatePct`
2. `minThroughputPerSec`
3. `p95LatencyMs`

Only persist deterministic criteria that the executor can evaluate.

### 6) Generate `plan.md`

Required sections:

1. `Purpose`
2. `Entrypoints`
3. `Observation Targets`
4. `Load Model`
5. `Success Criteria`
6. `Expected Outcomes`

Required verbs:

1. `Executes`
2. `Uses`
3. `WaitsFor`
4. `Captures`
5. `Verifies`

### 7) Validate consistency

Before finalizing, verify:

1. metadata and contract compatibility
2. at least one required strict line key
3. entrypoint facts are deterministic
4. load model fields are complete and supported
5. success criteria are measurable and deterministic
6. `plan.md` semantics match `contract.json`

## Required Deliverables Per Craft Request

When user asks to craft a plan, produce or update:

1. `.mcpjvm/<project_name>/plans/performance/<performance_name>/metadata.json`
2. `.mcpjvm/<project_name>/plans/performance/<performance_name>/contract.json`
3. `.mcpjvm/<project_name>/plans/performance/<performance_name>/plan.md`

## Fail-Closed Cases

Stop and return deterministic blocked guidance when:

1. required `Strict Line Key` is missing
2. entrypoint transport facts are ambiguous
3. load model mode is unsupported
4. required load model fields are missing
5. thresholds are non-deterministic or unsupported
6. user asks to persist secrets
7. base URL or path is not proven but required to produce an executable entrypoint

## Output Style

When crafting or updating plans, output:

1. absolute paths changed
2. short summary of entrypoint, required line hits, and load model
3. any blocked fields that still need user input
