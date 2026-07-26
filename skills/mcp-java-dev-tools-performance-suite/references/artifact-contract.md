# Performance Artifact Contract

Performance plans live under:

```text
.mcpjvm/<project_name>/plans/performance/<plan_name>/
```

Run Artifacts live under:

```text
.mcpjvm/<project_name>/plans/performance/<plan_name>/runs/<run_id>/
```

Required run Artifact files:

1. `context.resolved.json`
2. `execution.result.json`
3. `evidence.json`

Optional timing-analysis Artifact files:

1. `execution-timing.jfr`
2. `execution-timing.msta.json`
3. `correlation/correlation.json` when `analysis.correlation.enabled=true`

When `execution-timing.msta.json` exists, it is the canonical persisted MSTA evidence Artifact for the run.
When it does not exist, `execution.result.json` and `evidence.json` MUST remain the canonical source for `msta.status`,
including `not_configured` and `disabled`.

`correlation/correlation.json` is optional for legacy and correlation-disabled
runs. When `execution.result.json` declares correlation enabled and available,
it is required and must validate as `ExecutionCorrelationArtifactV1`. A valid
`unavailable` correlation state is rendered deterministically with empty
`attributions`; it is not a failed render.
