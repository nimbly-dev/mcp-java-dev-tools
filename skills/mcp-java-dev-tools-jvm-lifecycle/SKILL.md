---
name: mcp-java-dev-tools-jvm-lifecycle
description: Safely discover a local Java 21+ JVM, attach or deactivate the repository-owned Sidecar Agent through the `jvm_lifecycle` MCP Tool, and verify the live Probe. Use for runtime attach, agent lifecycle, local JVM selection, or dynamic Sidecar Agent deactivation requests.
---

# MCP Java Dev Tools Attach Sidecar to JVM

Use this Skill Workflow only with the `jvm_lifecycle` and `probe` MCP Tools. Do not invoke `jps`, the Java attach helper, shell attach commands, raw HTTP, or an arbitrary agent JAR.

## Prerequisites

- Confirm the target is a local JVM running Java 21 or newer under the current OS user.
- Confirm `MCP_JAVA_AGENT_JAR` points to the built repository-owned Sidecar Agent JAR.
- Ensure the `jvm_lifecycle` and `probe` MCP Tools are available.
- Select a free loopback Probe port. Use a non-loopback host only when it is explicitly allowed by the MCP server configuration.

## Attach workflow

1. Call `jvm_lifecycle` with `{"action":"list_jvms","input":{}}`.
2. Treat every returned descriptor as unverified. Use only its sanitized `identityHint`, `identitySource`, `frameworkHint`, `frameworkEvidence`, and `processStartEpochMs` for candidate selection. Never infer a target from PID order or treat an identity hint as proof.
3. If more than one candidate could be the requested target, require the operator to provide one exact PID and retain that candidate's `processStartEpochMs`.
4. Before mutation, obtain explicit confirmation for that PID and state the selected Probe host/port.
5. Call `jvm_lifecycle` with `action="attach"`, the exact string PID, the selected candidate's `expectedProcessStartEpochMs`, `confirm=true`, and the selected loopback Probe host/port. Supply `include`/`exclude` only when the operator provides the intended instrumentation scope.
6. A lifecycle result alone is not Probe proof. Read `selectedJvm`, `lifecycle`, and `probe`; then call `probe` with `action="check"` and `input.baseUrl` equal to the returned `probe.baseUrl`.
7. When Probe check succeeds, call `probe` with `action="status"`. Use `probe` with `action="actuate"` and `action="capture"` only when the requested runtime operation requires them.
8. If persistent Probe registration is requested, hand the returned base URL to the Probe Registry Manager Skill Workflow. Do not write a Probe Artifact directly from this workflow.

## Deactivation workflow

1. Require an exact PID, its previously returned `processStartEpochMs`, and explicit confirmation before calling `jvm_lifecycle` with `action="deactivate"`, `expectedProcessStartEpochMs`, and `confirm=true`.
2. Treat `VirtualMachine.detach()` as helper-session cleanup, never as agent removal.
3. Verify the returned structured lifecycle result. `deactivated` and `partial` are distinct outcomes; report `nonRestorableClasses` when present.
4. Do not infer deactivation merely because a Probe endpoint becomes unreachable. A failed verification must remain Fail-Closed.

## Fail-closed behavior

- Stop when PID selection is ambiguous, confirmation is absent, the helper reports blocked, or Probe verification fails.
- Stop when `processStartEpochMs` is absent or the helper reports a PID reuse or process identity mismatch. Mutation fencing is required; it is not optional.
- Never claim successful attachment solely from the helper process exit code.
- Never expose raw JVM command lines, arguments, environment values, system-property values, or secrets in the summary. Framework classification is deterministic inference from bounded evidence, not proof.

## Required summary

Return a deterministic human summary containing the selected JVM PID, lifecycle operation/outcome/reason code, returned Probe base URL, Probe verification result, and any cleanup or non-restorable-class result.
