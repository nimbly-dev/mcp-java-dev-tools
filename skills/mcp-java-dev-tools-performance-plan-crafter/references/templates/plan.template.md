# Purpose

Validate performance behavior for the selected Java target under a concurrency-based workload.

# Entrypoints

1. Executes the configured transport entrypoint against the target runtime.

# Observation Targets

1. Verifies required strict line hits during the measured workload window.

# Load Model

1. Uses `mode=concurrency`.
2. Sets `concurrency`, `rampUpSeconds`, and `durationSeconds`.

# Success Criteria

1. Verifies error-rate, throughput, and p95 latency thresholds.

# MSTA (Optional)

1. Uses profiler-backed timing analysis only when explicitly configured in `contract.json`.
2. Targets method-level timing evidence through explicit `analysis.msta.methodTargets[]` or dynamic `analysis.msta.anchorSelection` resolved from verified required Strict Line Keys.
3. Does not replace required strict line-hit verification.

# Correlation (Optional)

1. Enables persisted sampled attribution through `analysis.correlation.enabled=true`.
2. Persists `runs/<run_id>/correlation/correlation.json` with redacted workload identity and JVM execution evidence.
3. Uses unavailable correlation state with empty attribution rows when required evidence is not available.

# Expected Outcomes

1. Returns deterministic pass, fail, or blocked results.
2. Produces persisted run Artifacts.
