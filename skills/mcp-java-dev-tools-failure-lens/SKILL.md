---
name: mcp-java-dev-tools-failure-lens
description: Investigate a pasted Java stack trace against an attached Sidecar Agent using bounded guided or hands-off reproduction. Use when a user wants to reproduce a Java exception with runtime Probe evidence; never use for static-only diagnosis, CI/regression execution, generic replay, or automatic Bug Fix handoff.
---

# Failure Lens

Use only the public `failure_analysis`, `jvm_lifecycle`, Route Synthesis, and Probe MCP Tools. Do not call MCP transport from another Feature Module, use private actions, search files for credentials, or create a generic request harness.

## Preconditions

- Require a pasted Java stack trace, project context, explicit attempt/time bounds, and an attached responsive Sidecar Agent.
- Pass `{ mode, attemptLimit, elapsedTimeLimitMs }` as the bounded `investigation` context to both `failure_analysis` actions. The Tool does not infer authorization.
- When the Sidecar requires observe authentication, pass the user-supplied value as `sidecarAuthorization`; it is sent only as an `Authorization` header and must never be displayed or persisted.
- Ask whether the investigation is `guided` or `hands_off` before any protected action.
- In guided mode, ask before JVM attachment, trigger execution, supplied authentication use, side-effecting operations, or a materially different retry.
- In hands-off mode, pause for missing authentication, ambiguous JVM selection, unsupported triggers, unsafe operations, or any material scope change.

## Workflow

1. If no Sidecar is attached, use `jvm_lifecycle` to list JVMs. Require exact user selection when ambiguous. Attach only under the selected mode's authorization.
2. Call `failure_analysis` with `action="analyze_trace"`, the original trace, and the Sidecar base URL. Do not claim a diagnosis from this action.
3. Call Route Synthesis with the returned bounded candidates to identify an existing supported trigger. Keep Route Synthesis as a separate MCP call.
4. Use Probe to arm a bounded temporary session when actuation is needed, with `ttlMs` no greater than the investigation limit. Reset the selected Strict Line Keys before each attempt. Record Strict Line Key evidence.
5. Execute only an authorized supported trigger. If authentication or safety blocks execution, ask the user to perform the operation manually while the Probes remain armed.
6. Obtain the capture identifier and confirmed Line Hit, then call `failure_analysis` with `action="verify_reproduction"`.
7. Report a diagnosis only for `REPRODUCED`. For every other result, report attempt facts and state that no diagnosis is claimed.
8. On every terminal path, disarm any session this workflow armed, reset its selected Strict Line Keys, and deactivate the Sidecar only when this workflow attached it. Report each result as `cleanupStatus`. The Sidecar actuation TTL is the interrupted-session fallback; it is not evidence of successful cleanup.

## Terminal outcomes

- `REPRODUCED` requires a matching exception type, root-cause type, nearest application method, capture identifier, and positive Line Hit.
- `NOT_REPRODUCED` reports the bounded attempt facts and `diagnosisClaimed=false` for a different exception, different causal root, different application frame, or target reached without an exception.
- `INCONCLUSIVE` reports `failure_fingerprint_incomplete` or `capture_not_found` with `diagnosisClaimed=false`; do not retry after the attempt/time bound.
- `BLOCKED_AMBIGUOUS_JVM`, `BLOCKED_MISSING_AUTH`, `BLOCKED_MISSING_TRIGGER`, `BLOCKED_USER_ACTION_REQUIRED`, `BLOCKED_UNSAFE_OPERATION`, `CANCELLED`, and `ENVIRONMENT_MISMATCH` end the workflow after the cleanup sequence. Never infer credentials, execute an unsupported trigger, or continue after a user cancellation.

## Representative protected flows

- Guided attachment: ask before selecting the exact JVM PID and before each side-effecting trigger.
- Hands-off attachment: proceed only when exactly one JVM is in the supplied scope; otherwise return `BLOCKED_AMBIGUOUS_JVM`.
- Missing authentication: return `BLOCKED_MISSING_AUTH`; do not inspect files or environment values. Let the user perform the authenticated trigger manually after arming the bounded Probe session.
- Manual/unsafe trigger: return `BLOCKED_USER_ACTION_REQUIRED` or `BLOCKED_UNSAFE_OPERATION`, keep only the bounded session active, and wait for the user to trigger within the time limit.

## Output rules

- Keep session output compact and redact sensitive values.
- Never repeat the complete trace or capture payload by default.
- Do not create a Failure Lens Artifact or physical Markdown report by default.
- Do not invoke or recommend Bug Fix automatically, emit `readyForBugFix`, or make token-efficiency claims.
