# Performance Summary Result

Render a deterministic summary using:

1. `Status`
2. `Duration (ms)`
3. `Error Rate (%)`
4. `Throughput (/sec)`
5. `P95 Latency (ms)`
6. `Required Line Hits`
7. `MSTA`
8. `Correlation`

When correlation is available, add:

```text
| Step | Anchor Method | Strict Line Key | Method | Role | Samples | Estimated Path Time (ms) | Path Share (%) | Correlation Evidence |
```

Render `correlated_sampled_path` as estimated sampled attribution. Do not
include a `Root Cause` column or causal language.

Use stable placeholders for missing optional fields.

Expected MSTA output language:

1. `MSTA: n/a (not configured)`
2. `MSTA: n/a (disabled)`
