# Run Session Export Template

Use this output structure:

1. `Session`
2. `Mode`
3. `Execution Profile`
4. `Run Status`
5. `Plan Order`
6. `Export Artifacts`
7. `Warnings`

Rules:

1. `Plan Order` must follow `planRuns[].order`.
2. `Warnings` must include `SENSITIVE EXPORT` when secrets are included.
