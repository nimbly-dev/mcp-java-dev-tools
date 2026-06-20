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

When `execution-timing.msta.json` exists, it is the canonical persisted MSTA evidence Artifact for the run.
