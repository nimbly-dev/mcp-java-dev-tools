---
name: mcp-java-dev-tools-bug-drill
description: "Discover bounded Java method drill candidates or run a Probe-guided diagnostic drill for one selected method and return an evidence-backed Markdown report."
---

# MCP Java Dev Tools Bug Drill

Use this Skill Workflow to discover bounded Java method drill candidates and continue into a deep runtime investigation when the user selects one. Keep static intent, Route Synthesis output, and live Probe evidence separate. The workflow may establish a reproduction and collect runtime evidence, but it must not modify application source or persist investigation state.

## Scope and boundaries

This workflow:

- returns Markdown only;
- uses the existing `route_synthesis` MCP Tool and `probe` MCP Tool;
- uses the existing `transport_execute` MCP Tool only to send a complete, authorized trigger for a supported transport protocol;
- may use existing Probe actions `check`, `status`, `reset`, `actuate`, `wait_for_hit`, `capture`, and conditionally `profiler`;
- does not add an MCP Tool or Probe action;
- does not invoke regression, performance, security, or CI suite tooling;
- does not use `artifact_management` or persist Artifacts;
- does not mutate Java source, project state, Probe configuration, or application configuration.

Probe reset, bounded actuation, and evidence capture are runtime investigation operations within this workflow. If actuation is used, it must be explicitly bounded and cleaned up before the report is returned.

## Invocation modes

### Mode precedence

Evaluate modes in this order: continuous drill mode first, continuation mode second, and discovery-only mode last. If the initial request asks to discover candidates and contains execution intent such as `drill`, `drilldown`, `run`, or `reproduce`, use continuous drill mode and do not stop for a selection prompt. Use discovery-only mode only when the user explicitly asks for a list, recommendation, or static inventory without authorizing runtime drilling.

### Discovery mode

Use discovery mode when the user asks to find methods, controllers, listeners, consumers, or other Java targets but explicitly wants discovery only and has not authorized runtime drilling.

- Resolve one deterministic `projectRootAbs` before discovery. Inspect the active Probe registry as read-only context: preserve the single implicit target when exactly one exists, or present the available targets and mark explicit Probe selection as required when multiple exist.
- Bound source inspection to the selected project and the requested target shape; do not scan unrelated projects or manufacture candidates from arbitrary source files.
- Use `route_synthesis` with `action=class_methods` or `action=infer_target` plus bounded source inspection to identify candidate FQCNs, methods, executable lines, source locations, and any synthesized trigger context.
- Do not call `transport_execute`, reset a Probe, actuate application behavior, or claim runtime evidence in discovery mode.
- Present each candidate with its exact Strict Line Key, source location, trigger readiness (`ready`, `needs_user_input`, or `unavailable`), and the Probe target that would be reused or explicitly selected.
- End with one explicit continuation instruction, such as `Reply with drill <candidate>`; do not imply that discovery itself verified a method.

The discovery handoff is conversational state, not a completed Bug Drill report. Preserve the resolved project root, additional source roots, Probe selector, candidate Strict Line Keys, Route Synthesis context, and trigger metadata for the next turn.

### Continuous drill mode

Use continuous drill mode when the initial request combines discovery with drill intent, for example, `search for methods or controllers we can drill down` or `bug drilldown any candidates`. The initial request is the one authorization to perform the bounded runtime investigation; do not return a candidate handoff or ask the user to select the first method.

1. Run discovery internally and create a deterministic candidate queue from the bounded Route Synthesis/source results. Preserve each candidate's FQCN, method, signature, Strict Line Key, project root, Probe selector, trigger metadata, and unresolved inputs.
2. Order candidates using the source/tool result order, with stable `FQCN#method:line` ordering only for ties. Do not rank by confidence, naming similarity, suspected severity, or an invented bug likelihood.
3. Automatically run the bounded workflow below for each eligible candidate. A candidate is eligible only when its method and Strict Line Key are exact, its Probe target is uniquely resolved, and its trigger is complete, supported, authorized by the initial request, and within the safety limits.
4. After a candidate completes without deterministic bug evidence, continue to the next candidate without prompting. A successful transport, method Line Hit, normal return, or absence of an exception is not itself a bug.
5. Stop immediately when live evidence shows a deterministic failure matching the supplied symptom or expectation, an exception/error attributable to the selected method, or a reproducible behavior that contradicts an explicit expected result with the selected method's Line Hit. Return the normal four-section report for that target.
6. Mark a candidate as blocked and continue when it lacks a candidate-local safe trigger or supported transport. Stop and return `blocked` when the shared Probe target is missing, ambiguous, or unreachable, when required user input is global to the run, or when the bounded limits would be exceeded. Do not skip a safety failure by guessing or silently switching targets.

Use these continuous-mode defaults unless the user supplies lower limits: at most five candidate methods and at most two transport executions per candidate, for no more than ten transport executions in the complete drill. Record the effective candidate and execution limits in the final report. If at least one candidate was executed and the candidate queue is exhausted without deterministic bug evidence, return `unverified` with `bug_drill_candidates_exhausted`; if no candidate could be executed, return `blocked` with `bug_drill_reproduction_unavailable`. Do not describe successful method hits as bugs.

### Continuation mode

When the user replies with `drill <candidate>` or otherwise selects a candidate from the immediately preceding discovery handoff:

1. Resolve the candidate against the preserved list. Accept a short form such as `TestController#testPost` only when it maps to exactly one candidate; require the FQCN or signature when it does not.
2. Reuse the preserved project root, additional source roots, Probe selector, Strict Line Key, and Route Synthesis context. Do not repeat broad discovery or require the user to provide `probeId` again when the preserved implicit target is still valid.
3. Treat the explicit `drill <candidate>` selection as current-run confirmation to execute the previously presented trigger only when its protocol, request shape, side effects, and required inputs have not changed. Ask for a new confirmation when any of those conditions changed or were previously unresolved.
4. Continue at target validation and run the bounded runtime workflow below. Return the normal four-section Bug Drill report.

If the candidate is missing, stale, ambiguous, or no longer maps to one exact method and Strict Line Key, return `blocked` with `bug_drill_target_not_found`, `bug_drill_target_ambiguous`, or `bug_drill_line_unresolved` as appropriate. Do not silently fall back to a similarly named method.

## Required inputs

Require all of the following before drilling:

1. Fully qualified class name.
2. Method name.
3. Method signature when the target class contains overloaded methods with that name. If the signature is absent and static analysis cannot select exactly one method, fail closed with `bug_drill_signature_required`.
4. One project root resolved deterministically to Route Synthesis `projectRootAbs`.
5. A resolvable runtime/Probe selection.

Optional inputs are a user-supplied line hint, known symptom context, known reproduction steps, transport-specific endpoint or route context, authentication or credentials required by the trigger, explicit safety limits, and explicit trigger confirmation. In continuous drill mode, the initial request supplies trigger authorization for complete, classified candidates within the stated limits; it never authorizes guessed request data, unknown side effects, credentials, actuation, or profiler use. An individual confirmation is still required when any of those are unresolved.

### Runtime/Probe selection

The user does not need to provide a `probeId` when the active Probe registry resolves exactly one implicit target.

Resolve the target in this order:

1. Use an explicit `probeId` when provided.
2. Otherwise use an explicit Probe base URL when provided.
3. Otherwise call `probe` with `action=check` without a selector and accept an implicit target only when the Probe registry resolves exactly one target.

Do not choose among multiple registry targets by name similarity, URL ordering, process ordering, recency, or any other heuristic. Reuse the exact resolved selector consistently for every later Probe action. A missing, ambiguous, unreachable, or unstable target blocks the runtime phase.

Primary runtime-selection reason codes are:

- `bug_drill_probe_target_missing`
- `bug_drill_probe_target_ambiguous`
- `bug_drill_probe_unreachable`

Static method analysis may continue after one of these failures, but the report must state that no runtime behavior was proven and the reproduction is unverified.

## Bounded workflow

### 1. Validate and scope the target

Validate the required inputs and establish one bounded investigation scope:

- one fully qualified class;
- one method and, when needed, one exact signature;
- one project root and any explicitly supplied additional source roots;
- one optional user line hint and, after target resolution, one validated executable line;
- one runtime/Probe target;
- one or more explicit safety limits, or the deterministic defaults defined below.

Do not broaden the project root, scan unrelated services, or inspect test sources merely to manufacture a target. Keep source reads bounded to the selected method and the smallest directly relevant collaborators.

Resolve `projectRootAbs` from the explicit project-root input when supplied. Otherwise use the current workspace root only when it is the single deterministic project scope. If neither is available, or the workspace contains multiple plausible project roots, fail closed with `bug_drill_input_required`; never choose a root by directory ordering or heuristic proximity.

When safety limits are omitted, use these defaults and record the effective values in the report:

- for a single candidate, at most two transport executions: one candidate reproduction attempt and one bounded confirmation attempt; continuous mode also obeys its aggregate cap above;
- 15,000 ms for each Probe request, 500 ms polling for `wait_for_hit`, and one `wait_for_hit` retry;
- 20,000 ms for one transport execution when the selected protocol supports a request timeout;
- no actuation or profiler by default.

Never invent a default actuation TTL or profiler duration. Those operations require explicit user authorization and explicit bounded values. Actuation also requires `sessionId`, `targetKey`, `returnBoolean`, and `ttlMs`; `actuatorId` is optional.

### 2. Resolve the exact method and Strict Line Keys

Read the selected source only as needed to identify:

- the method declaration and signature;
- its responsibility and branch conditions;
- directly relevant collaborators;
- candidate runtime paths;
- bounded Strict Line Keys in the form `fully.qualified.Class#method:line`.

Use `route_synthesis` with `action=class_methods` or `action=infer_target` for deterministic method and line discovery. Pass the exact FQCN as `classHint`, the method as `methodHint`, the optional `lineHint`, the selected project root, and the resolved Probe selector when runtime line validation is supported.

Treat a line as runtime-valid only when the Route Synthesis or Probe evidence identifies it. Do not guess a line from a declaration, stack trace, source proximity, or heuristic branch scoring. If the method or an executable line cannot be resolved, return `blocked` with one primary reason code such as `bug_drill_target_not_found`, `bug_drill_target_ambiguous`, or `bug_drill_line_unresolved`.

When `class_methods` or `infer_target` identifies the executable line, carry its validated numeric line into the next `create_recipe` call as `lineHint`. The user-supplied line hint is only a discovery hint; do not leave `lineHint` absent for `intentMode=line_probe` after a valid line has been resolved.

### 3. Derive one safe candidate reproduction

Call `route_synthesis` with `action=create_recipe` and:

- `intentMode=line_probe`;
- the exact FQCN and method;
- the selected project root;
- the validated executable line as `lineHint`; retain the resolved Strict Line Key separately for Probe actions and reporting;
- the same resolved Probe selector;
- only the authentication, context-path, and safety inputs actually supplied or safely inferred.

Use deterministic fields from the structured result: `resultType`, `status`, `reasonCode`, `failedStep`, `executionPlan`, `requestCandidates`, `trigger`, `evidence`, `attemptedStrategies`, and `synthesizerUsed`. Do not route on confidence or heuristic scores.

When a recipe is returned, preserve its public trigger/request metadata as the candidate reproduction. Do not depend on non-public fields such as `fullUrlHint`, `bodyTemplate`, `needsConfirmation`, or `assumptions`; if complete execution data is not present in the public result, require the user to supply it explicitly. When synthesis returns a report, retain its diagnostics and gather only the missing input needed for one bounded attempt. Do not invent an endpoint, route, payload, authentication value, or protocol. If no safe trigger can be established, report the static analysis and return `blocked` or `inconclusive`; do not claim a reproduction.

The candidate reproduction is not proof. Before execution, require explicit confirmation in the current run for every non-read operation, any trigger whose side effects cannot be classified from the public request, and any trigger with unresolved assumptions or required user input. In continuous drill mode, the initial request's explicit drill intent is that confirmation only for a complete, classified trigger whose required inputs are present; it does not authorize guessing unresolved request data or side effects. Without the required confirmation, return `blocked` with `needs_user_input` and do not send application traffic.

Treat a candidate as a bug only when the runtime evidence contains an exception/error attributable to the selected method, a failure matching the supplied symptom, or a reproducible result that contradicts an explicit expected behavior. A live Line Hit, successful transport, normal return, or absence of an exception proves execution only; it is not bug evidence.

Execute a trigger through `transport_execute` only when all of the following are true:

1. The selected protocol is explicit and supported by the active transport executor (`http`, `grpc`, `kafka`, or `custom` as applicable).
2. The request object is complete for that protocol and comes from public Route Synthesis fields plus explicitly supplied user context. Do not pass a Route Synthesis object through unchanged when its shape does not match the transport request contract.
3. Any endpoint, route, payload, metadata, headers, credentials, or timeout values required by the selected protocol are present and non-conflicting.
4. `options.wrappedOnly=true` is set, and the request timeout is 20,000 ms when the protocol supports one, unless a lower user limit applies.

If `transport_execute` is unavailable, the selected protocol is unsupported, the request is incomplete, or confirmation is missing, return `blocked` with `transport_not_supported`, `bug_drill_reproduction_unavailable`, or `needs_user_input` as appropriate. Do not use raw HTTP, `curl`, an unwrapped client, or a protocol-specific fallback. If the user executes the sanitized request outside the current session, record the reproduction as unverified until live Probe evidence is collected in the same bounded run.

### 4. Establish Probe readiness before runtime drilling

Before any trigger attempt:

1. Call `probe` with `action=check` using the resolved selector.
2. Call `probe` with `action=status` using the same selector and the selected Strict Line Key or bounded key set, with `timeoutMs=15000` unless a lower user limit applies.
3. Confirm that each status row is valid and known to the Probe. A zero hit count is acceptable at readiness; `invalid_line_target`, `status_failed`, a missing status row, or an unresolvable key is not.
4. Confirm that the response is reachable and suitable for the selected target.

If readiness fails, stop the runtime phase. Do not reset, actuate, trigger, or guess a replacement target. Return `blocked` with `bug_drill_probe_unreachable` or `bug_drill_method_unobservable` as appropriate.

### 5. Collect bounded runtime evidence

For each permitted attempt, use this sequence:

1. Call `probe` with `action=reset` for the selected Strict Line Key or bounded key set, with `timeoutMs=15000` unless a lower user limit applies.
2. If actuation was explicitly enabled, call `probe` with outer `action=actuate` and input `action=arm`, using the same selector, `sessionId`, `targetKey`, `returnBoolean`, and explicit `ttlMs` before sending the trigger. If arming fails, do not trigger.
3. Execute the complete synthesized or user-provided trigger request once through `transport_execute` with its explicit protocol, `options.wrappedOnly=true`, and a 20,000 ms request timeout when supported, unless a lower user limit applies.
4. Call `probe` with `action=wait_for_hit` using `timeoutMs=15000`, `pollIntervalMs=500`, and `maxRetries=1`, unless lower user limits apply.
5. Use `probe` with `action=status` when a wait result is absent, ambiguous, or requires a bounded final count.
6. Use `probe` with `action=capture` only when `status` provides a valid capture identifier.
7. Use `probe` with `action=profiler` only when explicitly needed within the safety limits; profiler evidence supports interpretation but never substitutes for a Strict Line Hit.
8. If actuation was armed, call `probe` with outer `action=actuate` and input `action=disarm`, using the same selector and `sessionId` on every success or failure path before returning the report. If disarm cannot be confirmed, return `blocked` with `bug_drill_cleanup_failed` and do not claim verification.

Stop after the configured attempt limit, when the target is proven, or when a deterministic blocked condition occurs. Do not retry indefinitely, repeat a side-effecting request without authorization, or treat a timeout as a hit.

### 6. Classify the result

Use these classifications:

- `verified`: the candidate reproduction was executed and the selected method's Strict Line Key has a live Probe Line Hit, with no conflicting evidence.
- `unverified`: a candidate reproduction or static path exists, but the selected Strict Line Key was not proven hit within the bounded attempt.
- `blocked`: a required target, method, route, Probe capability, or safe trigger is missing, ambiguous, unreachable, or invalid.
- `inconclusive`: bounded evidence conflicts or cannot be reconciled deterministically.

Only `verified` permits a runtime-proven behavior statement. A `blocked`, `unverified`, or `inconclusive` result may describe static intent and observed tool outcomes, but must explicitly say that runtime behavior was not proven.

Use the primary reason-code and report rules in [references/report-contract.md](references/report-contract.md). Preserve underlying Route Synthesis and Probe reason codes as bounded evidence rather than emitting competing primary diagnoses.

## Evidence and safety rules

The report must distinguish these evidence classes:

1. **Static analysis** — source location, method responsibility, branches, collaborators, and candidate paths inferred from bounded source inspection. Label this as inferred.
2. **Reproduction** — the sanitized request candidate, whether it was executed, and its observable result. Label it verified or unverified.
3. **Live Probe observations** — Probe readiness, exact Strict Line Keys, baseline/final counts or deltas, Line Hit evidence, capture metadata, and bounded execution paths. Label these live.

Use `structuredContent` as the canonical source for full MCP payloads. Keep text summaries only as readable supporting evidence. Do not expose credentials, authorization values, cookies, raw response bodies, unbounded logs, or unrelated source. Redact sensitive request values while preserving the request shape.

Do not infer that a method executed because a route was synthesized, a transport request succeeded, a stack trace mentioned the class, a profiler sampled the method, or a stored snapshot contains a prior hit. A live Probe Line Hit for the selected Strict Line Key is required.

## Markdown report contract

Follow [references/report-contract.md](references/report-contract.md) exactly. Return concise Markdown only; do not add a preamble, appendix, JSON output, Artifact, or persisted report.

## Fail-closed rules

Return `blocked` or `inconclusive` instead of guessing when:

- required class, method, signature, project scope, or safety limit is absent;
- method resolution or overload selection is ambiguous;
- no executable Strict Line Key can be resolved;
- the Probe registry has zero or multiple implicit targets and no explicit selector resolves one;
- the selected Probe is unreachable or cannot observe the method;
- Route Synthesis cannot produce a safe candidate trigger;
- runtime evidence is missing, timed out, stale, or conflicting;
- cleanup cannot be confirmed after actuation.

Static analysis may still be included, but it must never upgrade a non-runtime outcome to `verified` or support a claim that the selected method ran.
