# Security Suite diagnostic report contract

Return Markdown only, with exactly these four top-level sections and no raw JSON dump:

```markdown
## Diagnosis
- Status: <executable|invalid|pass|fail|blocked|partial_fail|in_progress|inconclusive>
- Route: <plan_validation|execution_diagnosis>
- Phase: <phase or unknown>
- Reason: <one primary reason code>
- Mode: <blackbox|sidecar_assisted|unknown>

## Evidence
- Plan/run: <safe identity>
- Artifact: <terminal|missing|invalid|non-terminal>
- Coverage: <planned/executed/passed/confirmed/not-applicable/blocked>
- Findings: <count and safe IDs/severity/category/proof classification>
- Operational state: <bounded SQLite state or unavailable>
- Live Probe: <not required|not checked|current observation and timestamp>

## Interpretation
<A concise explanation that separates historical Artifact truth, SQLite operational state, and live Probe truth. State uncertainty and evidence conflicts explicitly.>

## Next action
<One safe action for the operator, such as correcting a plan dependency, supplying bounded evidence, or invoking the separate executor after review.>
```

## Evidence rules

- Every material claim must point to a safe Artifact/evidence reference, a bounded state record, or a clearly labeled current Probe observation.
- Use finding IDs and proof classifications (`external`, `internal`, `corroborated_external`); never include exploit payloads or secrets.
- If a required source is unavailable, write `unavailable` and fail closed.
- Do not imply that a live runtime check proves a historical result.
- Do not include a second recommendation or an execution command that changes state.

For plan validation, `Artifact` and `Coverage` may be `not applicable`; explain readiness from the bounded validation result and plan metadata instead.
