# Bug Drill Markdown Report Contract

After a target is selected, the Bug Drill Skill Workflow returns concise Markdown only, with exactly these top-level sections and in this order:

1. `## Diagnosis`
2. `## Evidence`
3. `## Interpretation`
4. `## Next action`

Discovery mode is a pre-drill handoff and does not use this four-section runtime report contract. It must not claim that a method executed or that a reproduction was verified. Once the user selects a candidate, continuation mode must return this contract even when the result is `blocked` or `unverified`.

## Diagnosis

Include:

- the classification: `verified`, `unverified`, `blocked`, or `inconclusive`;
- exactly one primary `reasonCode`;
- the selected target method; for continuous mode that finds no bug, identify the bounded candidate set and last attempted target;
- a plain-language outcome.

Only `verified` means the selected method was proven at runtime. `verified` does not by itself mean a bug was confirmed: the Diagnosis must identify the separate deterministic failure, symptom mismatch, or expected-behavior violation. `blocked`, `unverified`, and `inconclusive` must not be described as runtime-proven behavior.

## Evidence

Include bounded, sanitized evidence under these labels:

- **Reproduction** — the transport-neutral trigger candidate, selected protocol, whether it was executed, and its observable result; label it `verified` or `unverified`.
- **Static analysis** — source location, method responsibility, branches, collaborators, and candidate paths inferred from bounded source inspection; label it inferred.
- **Live Probe observations** — Probe readiness, exact Strict Line Keys, baseline/final counts or deltas, Line Hit evidence, capture metadata, and bounded execution paths; label it live.

Include relevant Route Synthesis and Probe reason codes as supporting evidence. Do not expose credentials, authorization values, cookies, raw response bodies, unbounded logs, or unrelated source.

For continuous mode, include a bounded candidate summary under Evidence: each candidate's target, status (`verified`, `unverified`, or `blocked`), and why the loop continued or stopped. If a bug is found, the Diagnosis target is the first candidate with deterministic bug evidence; do not relabel earlier successful or blocked candidates as bugs.

Use `bug_drill_candidates_exhausted` only when at least one candidate was executed and the bounded queue ended without deterministic bug evidence. If every candidate was skipped or blocked before execution, use `bug_drill_reproduction_unavailable` with `blocked` classification instead.

## Interpretation

Keep inferred static intent distinct from runtime-proven behavior. Explain what the evidence supports without upgrading static analysis, a synthesized route, a transport success, a stack trace, profiler samples, or a stored snapshot into a runtime claim.

A target Line Hit proves that the selected method was reached. A captured return or exception may prove the method's observed result, but downstream effects such as event publication or consumer processing remain unproven unless directly captured or separately confirmed by a downstream Probe. Use `reached`, `returned`, or `threw` when that is all the evidence supports; do not claim that an event was published or consumed from an HTTP success or controller Line Hit alone.

For every non-`verified` outcome, state plainly that the selected method behavior was not runtime-proven.

## Next action

Return exactly one smallest safe action in plain language. Do not provide alternatives, a task list, or a second action. If the result is blocked, address the single primary `reasonCode`.

## Reason-code and safety rules

Use exactly one primary reason code. Preserve underlying Route Synthesis and Probe reason codes as bounded evidence rather than emitting competing primary diagnoses.

Prefer these Bug Drill codes when no existing tool code is more specific:

- `bug_drill_input_required`
- `bug_drill_signature_required`
- `bug_drill_target_not_found`
- `bug_drill_target_ambiguous`
- `bug_drill_line_unresolved`
- `bug_drill_probe_target_missing`
- `bug_drill_probe_target_ambiguous`
- `bug_drill_probe_unreachable`
- `bug_drill_method_unobservable`
- `bug_drill_reproduction_unavailable`
- `bug_drill_reproduction_not_verified`
- `bug_drill_cleanup_failed`
- `transport_not_supported`
- `bug_drill_evidence_conflict`
- `bug_drill_candidates_exhausted`
- `needs_user_input`

Return `blocked` or `inconclusive` instead of guessing when required evidence is missing, ambiguous, unreachable, stale, timed out, conflicting, or cannot be reconciled deterministically.
