# Security Suite evidence model

## Evidence layers

| Layer | Use | Authority | Do not do |
|---|---|---|---|
| Canonical run Artifact | Historical Security plan/run result, matrix, coverage, findings, and redacted evidence references | Terminal case outcomes and persisted coverage | Rewrite it or replace it with current observations |
| SQLite projection | Bounded run lookup, lifecycle/status, correlation, and operational checkpoints | Operational state only | Treat rows as complete security evidence |
| Live Probe status | Current Sidecar/runtime identity, availability, and bounded line-hit context | Current runtime truth only | Use it to prove a historical finding or alter a run |

The diagnosis must say which layer supports each important claim. If layers disagree, preserve the disagreement and classify it as degraded or inconclusive rather than normalizing it away.

## Security Artifact fields

Use the persisted `securityMode`, `executionProfile`, `planName`, `runId`, terminal `status`, matrix counts, coverage cases, findings, evidence reference IDs, and reason code. Treat these as a summary contract, not permission to print the raw Artifact.

Coverage should be reconciled as:

`plannedCount = executedCount + notApplicableCount + blockedCount` only when the Artifact contract says every planned case reached a terminal classification. If the counts do not reconcile, use `security_diagnostic_matrix_incomplete` or `security_diagnostic_artifact_corrupt` according to whether the discrepancy is a coverage gap or malformed persisted data.

## Proof classifications

- `external`: the finding is supported by redacted externally observable evidence, such as an HTTP response or downstream effect allowed by the plan.
- `internal`: the evidence is internal to the target/runtime and does not by itself establish externally observable impact.
- `corroborated_external`: internal evidence is paired with the required external observation.

Do not upgrade `internal` to `external` because a Probe line was hit. Do not downgrade a persisted `external` result without identifying the conflicting or missing evidence reference.

## Evidence references

Report only identifiers and safe summaries: evidence ID, kind, case/finding association, redacted summary, and Artifact path/reference when already safe to expose. For HTTP evidence, omit request/response bodies, credentials, cookies, authorization values, and sensitive headers. For source/JAR/runtime evidence, report class/method or artifact identity only when the existing contract has already redacted it.

## Precedence and conflict handling

1. A valid terminal Artifact controls historical outcome, case coverage, and finding classification.
2. A valid SQLite row helps locate the run and describe operational lifecycle; it cannot add missing case proof.
3. Live Probe status can explain current availability or identity mismatch; it cannot change a persisted terminal result.

If the Artifact is missing, invalid, or not terminal, say so explicitly. If the only available evidence is SQLite or live Probe state, use `blocked`/`inconclusive` as appropriate and never claim completed security coverage.
