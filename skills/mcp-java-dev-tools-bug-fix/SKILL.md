---
name: mcp-java-dev-tools-bug-fix
description: "Create a proposal-only, evidence-backed Java bug-fix diagnosis from issue context and behavioral reproduction steps. Use when expected behavior and how to reproduce are known, even when endpoint, class, method, logs, or technical request details are not yet known."
---

# MCP Java Dev Tools Bug Fix

Create a bounded proposal for an issue-led Java source fix. Separate reported behavior, expected behavior, static localization, runtime reproduction evidence, and the proposed change. This workflow may inspect source and use existing MCP Tools for focused proof, but it never edits application source, applies a fix, persists an Artifact, or claims that a fix is complete.

## Scope and boundaries

- Proposal-only: there is no `apply` mode in this Skill Workflow.
- Do not mutate Java source, tests, project state, Probe configuration, application configuration, or external systems.
- Do not create or persist Artifacts.
- Do not invoke regression, performance, security, or CI suite tooling.
- Do not add a new MCP Tool or Probe action.
- Reuse existing `route_synthesis`, `probe`, and, only when the trigger is complete and explicitly authorized, `transport_execute`.
- Keep issue context and expected behavior separate from inferred code intent.
- Never report the bug as fixed; report a proposed change and the evidence supporting or limiting it.

If the user asks to apply, implement, edit, patch, commit, or otherwise mutate source, return `blocked` with `bug_fix_apply_not_supported` and explain that this workflow produces the proposal only.

## Required inputs

Require all three issue inputs before producing a proposal:

1. Issue or symptom context, including user impact when known.
2. Explicit expected behavior.
3. Behavioral reproduction steps. These may describe actions, inputs, timing, and observed results without naming an endpoint, class, method, protocol, or technical request.
The workflow itself is proposal-only; do not ask the user to provide a separate proposal-intent value or infer permission to modify source.

Endpoint, class, method, signature, logs, stack traces, project root, transport details, credentials, and Probe selection are optional. Localize or derive them when possible, and fail closed rather than inventing them.

Missing required context returns `blocked` with exactly one primary reason code from:

- `bug_fix_input_required`
- `bug_fix_expected_behavior_required`
- `bug_fix_reproduction_required`

## Workflow

### 1. Normalize the issue contract

Extract and preserve:

- reported symptom and user impact;
- explicit expected behavior;
- behavioral reproduction steps and observed result;
- constraints, safety limits, and unknowns;
- any supplied technical hints.

Do not rewrite an expected behavior as an implementation detail. If the issue says "the event should be delivered" but does not define the observable delivery condition, retain that uncertainty and identify the smallest missing observation.

### 2. Localize the candidate source

Use the smallest bounded investigation that can connect the behavioral steps to Java code:

1. Use an explicit project root when supplied; otherwise use the current workspace root only when it is the single deterministic project scope.
2. Use supplied endpoint/class/method information directly when present.
3. Otherwise search only the selected project scope for exact route names, event names, exception text, domain terms, and relevant framework annotations from the issue. Keep this search bounded to at most five candidate source files and exclude unrelated services and tests unless the behavioral steps require them.
4. Convert those search results into exact `classHint` and `methodHint` values, then use `route_synthesis` with `action=class_methods` or `action=infer_target` for deterministic method and line resolution.
5. Resolve exact FQCN, method, signature when overloaded, source location, and executable Strict Line Keys when possible.
6. Preserve unresolved alternatives as candidates; do not choose by name similarity, directory order, stack proximity, or confidence score.

If localization is ambiguous, return `blocked` with `bug_fix_target_not_localized`. Static issue analysis may still be reported, but the proposed change must remain scoped as a hypothesis rather than a source-level conclusion.

### 3. Derive the behavioral reproduction

Translate the behavioral steps into a transport-neutral reproduction model:

- actor or caller;
- ordered inputs and actions;
- expected observation;
- actual observation;
- timing, retry, ordering, or concurrency conditions;
- candidate protocol or route only when evidenced.

Preserve every behavioral action as an ordered reproduction step. Do not collapse a multi-step flow into one request. Record the tool or observation required for each step and whether that step can be executed safely with the existing MCP Tools.

Use `route_synthesis` with `action=create_recipe` and `intentMode=line_probe` or `regression` only when its required project, target, and line context are available. Preserve only public structured fields such as `resultType`, `status`, `reasonCode`, `failedStep`, `requestCandidates`, `trigger`, `evidence`, `executionPlan`, and `attemptedStrategies`. Do not invent an endpoint, payload, credential, event topic, route, or protocol.

If the technical request cannot be safely derived, retain the behavioral steps as the reproduction and mark technical reproduction as unavailable. Do not substitute a guessed request for the user's behavioral description.

### 4. Collect focused runtime proof when safe

Runtime proof is optional to proposal generation but should be collected when the target, Probe, and trigger are resolvable and the user explicitly authorizes a bounded reproduction. A proposal request alone does not authorize sending application traffic or causing side effects.

Resolve Probe selection in this order:

1. explicit `probeId`;
2. explicit Probe base URL;
3. one implicit registry target only when `probe action=check` resolves exactly one target.

If the target is missing, ambiguous, unreachable, or cannot observe the selected method, stop runtime work and retain a blocked evidence record. Do not guess a Probe or silently switch services.

For a safe, authorized runtime reproduction:

1. call `probe action=check`;
2. call `probe action=status` for the selected Strict Line Key or bounded key set;
3. call `probe action=reset` before the attempt;
4. execute every required trigger step in the recorded order through `transport_execute` only when each step has an explicit supported protocol, a complete request, and `options.wrappedOnly=true`;
5. perform each required bounded wait, event observation, or Probe check between trigger steps;
6. call `probe action=wait_for_hit` with bounded timeout, polling, and retry limits for each step that requires a Line Hit;
7. call `probe action=status` for an ambiguous or final count;
8. call `probe action=capture` only when a valid capture identifier is provided.

If any required behavioral step cannot be represented or safely executed with the existing MCP Tools, do not execute a partial sequence and do not claim runtime reproduction. Report the behavioral steps, the executable prefix if any, and the missing step as `bug_fix_reproduction_unavailable`.

Use the existing Probe line hit, capture, exception, return, and downstream observations as live evidence. A transport success or target Line Hit proves execution only; it does not prove that a downstream event was published, consumed, persisted, or otherwise produced the expected business outcome unless a corresponding observation confirms it.

Never use profiler samples, stack traces, static route synthesis, HTTP success, or source intent as a substitute for live runtime proof. Redact credentials, cookies, authorization values, raw response bodies, and unrelated logs.

### 5. Design the smallest safe proposal

Produce a source-change proposal without editing files. The proposal must identify:

- the localized source area and confidence/evidence boundary;
- the suspected cause, clearly labeled inferred unless runtime evidence proves it;
- the smallest intended behavior change;
- affected classes/methods or files when known;
- compatibility, side-effect, and rollback considerations;
- focused validation that would test the explicit expected behavior;
- unresolved questions that prevent a stronger proposal.

Do not include a ready-to-apply diff that implies the source was changed. Pseudocode or a concise patch description is allowed when clearly labeled `proposed` and non-applied.

### 6. Classify proposal evidence

Use one classification:

- `proposed`: expected behavior, reproduction, localization, and a bounded change proposal are available; runtime proof may be partial.
- `blocked`: required issue context is missing, localization is ambiguous, the trigger is unsafe/incomplete, or the requested action is apply/mutation.
- `inconclusive`: bounded evidence conflicts or cannot distinguish among candidate causes.

`proposed` does not mean fixed, verified, applied, or runtime-proven. State which parts are reported, inferred, live, or still unknown.

## Markdown report contract

Follow [references/report-contract.md](references/report-contract.md) exactly. Return concise Markdown only with no preamble, JSON, Artifact, source mutation, commit, or apply step.

## Fail-closed rules

Return `blocked` or `inconclusive` instead of guessing when:

- issue context, expected behavior, or behavioral reproduction steps are missing;
- endpoint/class/method localization is ambiguous;
- the technical reproduction cannot be derived safely;
- the Probe target is missing, ambiguous, unreachable, or cannot observe the target;
- live evidence is missing, stale, timed out, conflicting, or insufficient to support the claim;
- the user requests source mutation or apply mode.

Static analysis and a proposal may still be returned when runtime proof is unavailable, but the report must say exactly what was not proven.
