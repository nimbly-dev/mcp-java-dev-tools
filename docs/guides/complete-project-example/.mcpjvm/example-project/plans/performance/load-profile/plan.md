# Event lookup load profile

## Purpose

Measure a bounded concurrent HTTP workload while proving the required Java line was observed.

## Entrypoints

Executes `GET http://127.0.0.1:8080/events/{id}` after the health check succeeds.

## Observation Targets

Captures required Strict Line Key `com.example.events.EventQueryController#getImport:42`.

## Load Model

Uses `mode: concurrency`, concurrency 8, a 5-second ramp-up, and a 30-second duration.

## Success Criteria

Verifies error rate at or below 1%, throughput at or above 5 requests per second, and p95 latency at or below 500 ms.

## Expected Outcomes

- Returns deterministic workload metrics.
- Emits required Strict Line evidence.
- Produces a bounded performance summary.
- Matches all configured thresholds.
- Passes only when the load and line-hit criteria both hold.
