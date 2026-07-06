# Regression Execution Plan

This document describes the structure and design of a reusable regression plan package.

## Package Layout

Each regression plan lives under:

```text
.mcpjvm/regression/<regression_name>/
```

| File / Folder | Purpose |
|---|---|
| `metadata.json` | Plan-level execution settings |
| `contract.json` | Authoritative machine contract for targets, prerequisites, steps, optional watchers, optional `externalVerification`, and optional correlation policy |
| `plan.md` | Human-readable execution plan |
| `runs/<run_id>/...` | Immutable outputs for each run (plan-local run history) |
| `artifact-schema.md` | Normative run artifact contract (`MUST/SHOULD/MAY`) |

## Design Principles

A few intentional constraints that apply across all regression plans:

- **Deterministic selectors and execution order** - no ambiguity in what runs or when
- **Protocol-agnostic** - not limited to HTTP
- **Fail-closed** - ambiguous or incomplete required context stops execution rather than guessing
- **No hardcoded secrets** - credentials must never appear in plan artifacts

## External Verification

`contract.externalVerification[]` defines deterministic downstream data-validity checks against third-party systems.

- Provider shape is explicit and discriminated by `provider.type`.
- Current provider contract shapes are `http` and `sql`.
- `request` must contain exactly one matching provider block (`request.http` or `request.sql`).
- SQL verification uses `connectionRef` indirection; secret-bearing connection material stays in project/runtime-owned context, not `contract.json`.
- Placeholder semantics align with transport placeholders: canonical `${key}`, compatible `{{key}}`, and compatible `{{{key}}}`.

## HTTP Context Rules

- `apiBaseUrl` remains the canonical regression-plan context key for HTTP base-URL resolution.
- Legacy prerequisite key `baseUrl` is accepted only as an initial-context compatibility alias and is normalized to `apiBaseUrl` before HTTP transport execution.
- Authors SHOULD prefer `apiBaseUrl` in new contracts, examples, and generated plans.
- Runtime-extracted fields named `baseUrl` are treated as ordinary business data and MUST NOT be promoted into canonical transport context.
- For HTTP transport authoring, relative request targets may be expressed with either `transport.http.pathTemplate` or `transport.http.path`.
- When `transport.http.url` is absent, runtime URL synthesis is fail-closed: execution composes `apiBaseUrl + pathTemplate/path` only when canonical base-URL context is available.
- Absolute values placed in `transport.http.pathTemplate` or `transport.http.path` are not silently promoted to `url`.

## Writing `plan.md`

Plans use a fixed vocabulary to keep steps unambiguous and machine-parseable.

**Step verbs:**

| Verb | Use for |
|---|---|
| `Executes` | Triggering an action |
| `Captures` | Recording output |
| `Uses` | Referencing an input or dependency |
| `Sets` | Assigning a value |
| `WaitsFor` | Blocking until a condition is met |
| `Verifies` | Asserting an expected state |

**Outcome verbs:**

| Verb | Use for |
|---|---|
| `Returns` | Expected response |
| `Emits` | Expected event or signal |
| `Produces` | Expected artifact or output |
| `Matches` | Comparison against a reference |
| `Passes` | Assertion success |

## Execution Order

Steps are numbered `1..N` and executed strictly in listed order. The orchestrator does not reorder steps implicitly - what you write is what runs.
Run artifacts are persisted under the plan package:

```text
.mcpjvm/regression/<regression_name>/runs/<run_id>/
```

## Artifact-Derived Results Summary

Regression results summaries SHOULD be rendered from persisted artifacts, not transient logs.

Required tabular columns:

- `Endpoint`
- `Status`
- `HTTP Code`
- `Duration (ms)`
- `Probe Coverage`

`Probe Coverage` enum values:

- `verified_line_hit` (strict line key confirmed)
- `http_only_unverified_line` (HTTP-level validation only)
- `unknown` (coverage state not deterministically available)

`Memory (bytes)` MUST be included only when memory metrics are explicitly contract-defined.

## Related Performance Spec

Performance-suite MSTA evidence is defined separately in:

- `performance-msta-evidence-model.md`
- `performance-workload-provider-model.md`

That document is the normative contract for:

- `analysis.executionTiming`
- `analysis.msta`
- `execution-timing.msta.json`

The workload-provider document is the normative contract for:

- `workloadProvider`
- `builtin` versus `jmeter` workload execution ownership

## Performance Suite Reliability Baseline

The current execution-plan and run-artifact model is also used by performance-suite Artifacts under:

```text
.mcpjvm/<project>/plans/performance/<plan>/
```

When discussing performance-suite reliability, contributors MUST separate deterministic execution evidence from MSTA timing evidence.

### Reliability Classes

- `High`: deterministic and directly measured by the runner or Probe
- `Medium`: directionally useful but statistically interpreted
- `Low`: not suitable for exact timing claims

### Current Baseline

- `workload execution status`: `High`
- `threshold metrics` (`failedRequests`, `errorRatePct`, `throughputPerSec`, `p95LatencyMs`): `High`
- `Strict Line Key` / `Line Hit` verification: `High`
- `MSTA hotspot direction`: `Medium`
- `MSTA exact per-method duration`: `Low`

### Required Interpretation Rules

- Performance-suite pass/fail status is production-usable for workload validation and strict target verification when the required `Line Hit` evidence is present.
- MSTA output MUST be treated as directional performance evidence unless an explicit future contract adds confidence scoring or quantified overhead controls.
- MSTA step timings MUST NOT be presented as exact exclusive method durations.
- When observability frames dominate the sampled path, the output remains useful for hotspot direction but is not sufficient for precise business-method latency claims.

### Contributor Guidance

- Prefer `High` / `Medium` / `Low` reliability classes over invented percentages such as `70%` or `90%`.
- If future work introduces quantitative confidence, it MUST be derived from measurable signals such as sample count, anchor coverage, repeatability, and observability-overhead share.
