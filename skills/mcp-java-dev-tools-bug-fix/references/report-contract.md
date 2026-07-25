# Bug Fix Proposal Markdown Report Contract

Return exactly these top-level sections, in this order:

1. `## Diagnosis`
2. `## Evidence`
3. `## Interpretation`
4. `## Next action`

## Diagnosis

Include:

- classification: `proposed`, `blocked`, or `inconclusive`;
- exactly one primary `reasonCode`;
- concise issue/symptom statement;
- explicit expected behavior;
- whether a fix is proposed, applied, or not applied. This workflow must always say `not applied`.

Never use `fixed`, `verified`, or `applied` as the proposal classification.

## Evidence

Include bounded, sanitized evidence under these labels:

- **Issue context** - the reported symptom and user impact, labeled reported.
- **Expected behavior** - the required outcome, labeled requested.
- **Behavioral reproduction** - every ordered step, inputs, timing, and observed result, labeled reported or observed. State which steps were executed; a partial sequence is not a reproduced behavior.
- **Technical localization** - endpoint, class, method, Strict Line Key, source area, Route Synthesis output, and inferred cause, each labeled inferred or live as applicable. Technical fields may be unknown.
- **Runtime proof** - Probe readiness, line hits, captures, exceptions, returns, transport result, and downstream observations, labeled live. State when no application traffic was sent.
- **Proposed change** - smallest source change and affected area, labeled proposed and not applied.
- **Focused validation** - the test or bounded runtime check that would verify expected behavior after a future approved implementation, labeled planned.

Do not expose credentials, authorization values, cookies, raw response bodies, unbounded logs, or unrelated source.

## Interpretation

Separate reported behavior, requested behavior, inferred code intent, live runtime evidence, and the proposed change. A Probe Line Hit or successful transport proves execution only. Do not claim downstream publication, consumption, persistence, or business correctness without a matching live observation.

State the evidence boundary explicitly:

- what is established;
- what is inferred;
- what remains unproven;
- why the proposed change is the smallest safe response.

If classification is `blocked` or `inconclusive`, state the missing or conflicting evidence plainly.

## Next action

Return exactly one smallest safe action. Do not provide alternatives or a task list. For a `proposed` result, the action is normally to review or approve the proposal for a separate implementation workflow. For a blocked result, address the single primary `reasonCode`.

## Reason codes

Use exactly one primary reason code. Preserve underlying Route Synthesis and Probe reason codes as supporting evidence.

- `bug_fix_input_required`
- `bug_fix_expected_behavior_required`
- `bug_fix_reproduction_required`
- `bug_fix_target_not_localized`
- `bug_fix_reproduction_unavailable`
- `bug_fix_runtime_not_verified`
- `bug_fix_probe_target_missing`
- `bug_fix_probe_target_ambiguous`
- `bug_fix_probe_unreachable`
- `bug_fix_evidence_conflict`
- `bug_fix_apply_not_supported`
- `bug_fix_proposal_ready`
