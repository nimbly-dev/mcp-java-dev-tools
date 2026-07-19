# Markdown Report Contract

The diagnostic Skill Workflow MUST return concise, human-readable Markdown only.

It MUST NOT generate, persist, or expose `diagnosis.result.json`, a JSON schema, or any other diagnostic JSON output.

Every report MUST contain these sections in this order:

## Diagnosis

Include:

- status: `executable`, `diagnosed`, `invalid`, `blocked`, or `inconclusive`;
- phase, when applicable;
- the deterministic diagnostic reason code;
- a plain-language cause.

## Evidence

Include only bounded references and sanitized summaries for:

- canonical historical Artifacts;
- SQLite operational/query projections;
- relevant project-context policy/configuration;
- optional current Sidecar/Probe observations.

Clearly label current runtime observations as live and keep them separate from historical execution evidence.

## Interpretation

Explain the outcome without speculation. Do not use current Sidecar/Probe readiness to rewrite a completed historical diagnosis. Keep Watcher, external-verification, and Correlation interpretations distinct.

## Next action

Return exactly one smallest safe action in plain language. Include its deterministic action/reason code when one exists. Do not provide alternatives or a list of actions.

Deterministic reason codes and bounded evidence references remain mandatory; they are rendered as Markdown rather than serialized as JSON.
