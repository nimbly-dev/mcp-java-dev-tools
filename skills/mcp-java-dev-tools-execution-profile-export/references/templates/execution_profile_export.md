# Execution Profile Export Template

Use this output structure:

1. `Export`
2. `Source` (`profile-contract-state`)
3. `Mode`
4. `Execution Profile`
5. `Run Status` (`plan_state_export`)
6. `Plan Order`
7. `Export Artifacts`
8. `Required Inputs`
9. `Warnings`

Rules:

1. `Plan Order` must follow `planRuns[].order`.
2. `Warnings` must include `SENSITIVE EXPORT` when secrets are included.
3. `Export Artifacts` must point to a fresh per-invocation package directory.
4. `Required Inputs` must enumerate unresolved placeholders (for example `API_BASE_URL`, `${courseId}`) when present.
