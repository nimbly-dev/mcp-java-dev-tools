# Performance MSTA Evidence Model

This document defines Method Step Time Analysis (MSTA) as a first-class evidence model for performance-suite runs.

MSTA is performance evidence. It is not regression proof, and it is not a replacement for deterministic `Strict Line Key` verification.

## Scope

MSTA exists to explain where sampled time is spent in an observed JVM execution path during a measured performance run.

It is intended to remain transport-neutral:

- HTTP entrypoints MAY produce MSTA evidence
- non-HTTP service-driven execution MAY also produce MSTA evidence when the sidecar can observe the executed path

## Separation Of Concerns

Performance-suite outputs MUST separate three distinct concerns:

1. deterministic execution proof
2. workload execution and threshold evaluation
3. timing-analysis evidence

The current performance-suite mapping is:

- deterministic execution proof: `requiredLineHits[]` and `Line Hit` verdicts
- workload execution and threshold evaluation: `metrics` and `thresholdResults`
- timing-analysis evidence: `msta`

## Non-Goals

MSTA in v1 is intentionally not:

- exact exclusive per-method duration tracing
- per-line timing
- a replacement for JFR or profiler-native visualization
- a promise of production-precise business-method latency

## Plan Contract Inputs

MSTA configuration is expressed under `contract.json`.

### `analysis.executionTiming`

Required when `analysis.msta.enabled=true`.

Current supported fields:

- `enabled`: must be `true`
- `provider`: current supported value: `async-profiler`
- `event`: optional provider-specific event name such as `cpu` or `wall`
- `intervalNanos`: optional provider-specific sampling interval
- `outputFormat`: current supported value: `jfr`

### `analysis.msta`

Optional.

When present and enabled:

- `enabled`: must be `true`
- `mode`: optional; supported values:
  - `method_targets`
  - `target_plus_path`
- `methodTargets[]`: required non-empty array when `analysis.msta` is enabled
  - each item must include:
    - `methodRef`: `fully.qualified.Class#method`
- `includePackages[]`: optional future-facing package scope hint
- `allowThirdPartyFrames`: optional future-facing frame inclusion hint

### `observationTargets.requiredLineHits[]`

Still required by the current performance-suite executor.

These remain the canonical deterministic proof targets and are not replaced by `methodTargets[]`.

## Runtime Evidence Inputs

MSTA generation consumes:

- resolved profiler output, currently JFR-backed
- one or more anchor methods derived from:
  - `analysis.msta.methodTargets[]`, or
  - fallback normalization of `requiredLineHits[]`
- sampled stack events emitted by the profiler extraction layer

The current implementation supports sampled event types:

- `jdk.ExecutionSample`
- `jdk.NativeMethodSample`
- `profiler.WallClockSample`

MSTA itself is provider-agnostic at the contract level. Provider-specific event names are evidence metadata, not the feature definition.

## Persisted MSTA Artifact

When MSTA materialization succeeds, the run directory SHOULD contain:

```text
.mcpjvm/<project>/plans/performance/<plan>/runs/<run_id>/execution-timing.msta.json
```

The same `msta` object MAY also be embedded into `execution.result.json` and `evidence.json`.

### Available Result Shape

```json
{
  "status": "available",
  "unit": "ms",
  "jfrPath": "C:/.../execution-timing.jfr",
  "sourceEventTypes": ["profiler.WallClockSample"],
  "durationMs": 60024,
  "provider": {
    "name": "async-profiler",
    "event": "wall",
    "outputFormat": "jfr"
  },
  "mode": "target_plus_path",
  "methods": [
    {
      "methodRef": "com.example.Service#load",
      "estimatedTimeMs": 2095.81,
      "estimatedTimePct": 3.492,
      "samples": 716,
      "strictLineKey": "com.example.Service#load:54",
      "pathSteps": [
        {
          "stepOrder": 1,
          "methodRef": "com.example.Service#load",
          "target": true,
          "samples": 716,
          "estimatedTimePct": 100,
          "estimatedTimeMs": 60024
        }
      ]
    }
  ],
  "targets": [
    {
      "strictLineKey": "com.example.Service#load:54",
      "anchorMethod": "com.example.Service#load",
      "anchoredSampleCount": 716,
      "dominantPathSampleCount": 25,
      "dominantPathSamplePct": 3.492,
      "dominantPathApproxTimeMs": 2095.81,
      "steps": [
        {
          "stepOrder": 1,
          "methodRef": "com.example.Service#load",
          "target": true,
          "samples": 716,
          "estimatedTimePct": 100,
          "estimatedTimeMs": 60024
        }
      ]
    }
  ]
}
```

### Available Fields

- `status`: must be `available`
- `unit`: current value `ms`
- `jfrPath`: readable profiler capture path used for analysis
- `sourceEventTypes[]`: sampled event types actually consumed by the evidence builder
- `durationMs`: measured run duration used for approximate time attribution
- `provider`: optional provider metadata
  - `name`
  - `event`
  - `outputFormat`
- `mode`: one of:
  - `required_line_hits`
  - `method_targets`
  - `target_plus_path`
- `methods[]`: method-oriented summaries
- `targets[]`: strict-target-oriented summaries retained for deterministic target correlation

### `methods[]`

Each entry contains:

- `methodRef`: anchor method identity
- `estimatedTimeMs`: dominant-path approximate time under the current evidence model
- `estimatedTimePct`: dominant-path approximate percentage under the current evidence model
- `samples`: total anchored sample count
- `pathSteps[]`: ordered sampled call-path steps from anchor toward leaf
- `strictLineKey`: optional mapped strict target

### `pathSteps[]` and `targets[].steps[]`

Each step contains:

- `stepOrder`
- `methodRef`
- `target`
- `samples`
- `estimatedTimePct`
- `estimatedTimeMs`

These are sampled path-attribution values, not exact stopwatch measurements.

### `targets[]`

Each entry contains:

- `strictLineKey`
- `anchorMethod`
- `anchoredSampleCount`
- `dominantPathSampleCount`
- `dominantPathSamplePct`
- `dominantPathApproxTimeMs`
- `steps[]`

`targets[]` exists to preserve correlation between timing evidence and deterministic observed targets.

## Non-Available Result Shapes

When MSTA cannot be materialized, the artifact MUST fail closed with one of:

- `jfr_missing`
- `jfr_parse_failed`
- `no_anchor_samples`

Shape:

```json
{
  "status": "jfr_parse_failed",
  "jfrPath": "C:/.../execution-timing.jfr",
  "detail": "reduced_jfr_exit_code=1",
  "unit": "ms"
}
```

Required fields:

- `status`
- `detail`
- `unit`

Optional fields:

- `jfrPath`

## Mode Semantics

### `required_line_hits`

Fallback mode when explicit `methodTargets[]` are absent.

Anchor methods are derived from normalized `Strict Line Key` values.

### `method_targets`

Anchor methods come from explicit `methodTargets[]`.

Output centers on the requested methods rather than only on strict line fallback.

### `target_plus_path`

Anchor methods come from explicit `methodTargets[]`, and output is expected to be interpreted as:

- target method summary
- dominant sampled path summary beneath that target

## Interpretation Rules

Consumers MUST apply these rules:

1. `Line Hit` proof remains the deterministic execution verdict.
2. MSTA does not prove that a method executed unless the associated deterministic target proof also exists, or the consumer explicitly accepts profiler-only evidence.
3. `estimatedTimeMs` and `estimatedTimePct` are approximate sampled timing attributions.
4. `pathSteps[]` represent sampled call-path evidence, not exact per-method exclusive durations.
5. Provider/tooling frames MAY appear in the path and MAY dominate the sampled shape.

## Reliability Baseline

The current baseline is:

- workload execution status: `High`
- threshold metrics: `High`
- `Strict Line Key` / `Line Hit` verification: `High`
- MSTA hotspot direction: `Medium`
- MSTA exact per-method duration: `Low`

Consumers SHOULD communicate reliability using classes such as `High`, `Medium`, and `Low`, not invented percentages.

## Extensibility Rules

Future providers MAY be added, but the MSTA evidence model MUST remain stable at the contract layer.

Provider-specific additions MUST NOT redefine:

- `methodRef`
- `pathSteps`
- `estimatedTimeMs`
- `estimatedTimePct`
- `status`
- `mode`

Future additions MAY include:

- confidence fields
- business-frame vs observability-frame classification
- provider-native evidence references
- repeatability metadata
