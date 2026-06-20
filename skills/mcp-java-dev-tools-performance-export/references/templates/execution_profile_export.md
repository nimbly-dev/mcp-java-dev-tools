# Performance Execution Profile Export Template

Use this output structure:

1. `Export`
2. `Source` (`profile-contract-state`)
3. `Suite Type` (`performance`)
4. `Mode`
5. `Execution Profile`
6. `Source Run Status` (`profile-contract-state_export`)
7. `Replay Package Type` (`workload_replay_only`)
8. `Plan Order`
9. `Export Artifacts`
10. `Required Inputs`
11. `Warnings`

Rules:

1. `Plan Order` must follow `planRuns[].order`.
2. `Warnings` must include `SENSITIVE EXPORT` when secrets are included.
3. `Export Artifacts` must point to a fresh per-invocation package directory.
4. `Required Inputs` must enumerate unresolved placeholders when present.
5. Export wording must make it explicit that the package replays workload execution, not live MCP orchestration.
6. Output wording must mention required Strict Line Key verification and threshold evaluation.
7. Exported replay-run documentation must mention deterministic blocked fields for runtime failures:
   - `reasonCode`
   - `failedStep`
   - `reasonMeta`
