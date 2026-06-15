# Performance Plan Spec Rules

This file defines the normative rules used by the craft skill.

## Package Layout

```text
.mcpjvm/<project_name>/plans/performance/<performance_name>/
  metadata.json
  contract.json
  plan.md
  runs/<run_id>/
```

`runs/<run_id>/...` is machine-generated and out of scope for crafting.

## metadata.json (required)

- `specVersion`: string
- `suiteType`: must be `performance`
- `execution.intent`: must be `performance`

## contract.json (required)

### entrypoints[]

- at least one entry required
- `transport.protocol`: required
- `transport.baseUrl`: required
- `request.method`: required
- `request.path`: required

### observationTargets

- `requiredLineHits[]`: required, non-empty
- `optionalLineHits[]`: optional

`Strict Line Key` is the canonical identity for observed Java targets.

### loadModel

- `mode`: required; current supported value: `concurrency`
- `concurrency`: required positive integer
- `rampUpSeconds`: required integer `>= 0`
- `durationSeconds`: required positive integer

### successCriteria

- `maxErrorRatePct`: required number `>= 0`
- `minThroughputPerSec`: required number `> 0`
- `p95LatencyMs`: required number `> 0`

## plan.md (required)

Required sections:

1. `Purpose`
2. `Entrypoints`
3. `Observation Targets`
4. `Load Model`
5. `Success Criteria`
6. `Expected Outcomes`
