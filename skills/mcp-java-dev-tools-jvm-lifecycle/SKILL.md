---
name: mcp-java-dev-tools-jvm-lifecycle
description: Safely discover a local Java 21+ JVM, attach or deactivate the repository-owned Sidecar Agent through the JVM Lifecycle operations, and verify the live Probe. Use for runtime attach, agent lifecycle, local JVM selection, or dynamic Sidecar Agent deactivation requests.
---

# MCP Java Dev Tools Attach Sidecar to JVM

## Java CDE invocation

The public MCP Tools are `operation_catalog`, `operation_describe`, and `operation_execute`. Discover supported IDs with Catalog; call Describe with `{"operationId":"<id>"}` before execution. Java Describe owns the current argument schema and safety requirements.

Call `operation_execute` with `{"operationId":"<id>","arguments":{},"confirmed":false}`, replacing `arguments` with the described fields. Set outer `confirmed:true` only for an authorized operation when Describe requires it. Do not send the retired Tool/action/input envelope or an inner lifecycle `confirm`. Nested operation-specific actions remain in `arguments.action` only when Describe declares them. Read the CDE status first, then the capability result in `result`; use structured MCP content and preserve reason codes. Artifact details are under `result.details`; Probe and lifecycle action data are under `result.actionResult`.

This workflow consumes these canonical IDs as needed (not an instruction to execute every operation):

- `jvm_lifecycle.list_jvms`
- `jvm_lifecycle.attach`
- `probe.check`
- `probe.status`
- `jvm_lifecycle.deactivate`

Use this Skill Workflow only with the JVM Lifecycle operations and Probe operations. Do not invoke `jps`, the Java attach helper, shell attach commands, raw HTTP, or an arbitrary agent JAR.

## Prerequisites

- Confirm the target is a local JVM running Java 21 or newer under the current OS user.
- Use the packaged Java server with its adjacent `sidecar/sidecar-agent.jar` and `sidecar/jvm-attach-helper.jar`; use `MCP_JAVA_AGENT_JAR` only for an explicit repository-owned override.
- Ensure the JVM Lifecycle operations and Probe operations are available.
- Select a free loopback Probe port. Use a non-loopback host only when it is explicitly allowed by the MCP server configuration.

## Attach workflow

1. Call `operation_execute` with `{"operationId":"jvm_lifecycle.list_jvms","arguments":{},"confirmed":false}`.
2. Treat every returned descriptor as unverified. Use only its sanitized `identityHint`, `identitySource`, `frameworkHint`, `frameworkEvidence`, and `processStartEpochMs` for candidate selection. Never infer a target from PID order or treat an identity hint as proof.
3. If more than one candidate could be the requested target, require the operator to provide one exact PID and retain that candidate's `processStartEpochMs`.
4. Before mutation, obtain explicit confirmation for that PID and state the selected Probe host/port.
5. Call `jvm_lifecycle.attach`, the exact string PID, the selected candidate's `expectedProcessStartEpochMs`, outer `confirmed:true`, and the selected loopback Probe host/port. Supply `include`/`exclude` only when the operator provides the intended instrumentation scope.
6. A lifecycle result alone is not Probe proof. Read `result.reasonCode` and `result.actionResult` (`operation`, `outcome`, `pid`, `expectedProcessStartEpochMs`, `probeHost`, `probePort`). Build the Probe origin from that returned host/port, then call `probe.check` with that origin in `arguments.baseUrl`.
7. When Probe check succeeds, call `probe.status`. Use `probe.actuate` and `probe.capture` only when the requested runtime operation requires them.
8. If persistent Probe registration is requested, hand the verified Probe origin to the Probe Registry Manager Skill Workflow. Do not write a Probe Artifact directly from this workflow.

## Project-declared CI lifecycle

`execution_orchestration.execute` may own a `dynamic_attach_local` lifecycle declared in `projects.json`. That path is restricted to one direct `java`/`java.exe -jar <relative-path>` startup, resolves `probeId` from the active probe registry, attaches once per `suiteRunId`, verifies the canonical Probe before the first plan, and deactivates only after a terminal suite outcome. Resume passes must not deactivate or reattach. The orchestration controller records only bounded PID/start-fence, attach, Probe, cleanup, and non-restorable-class evidence in the suite-run Artifact.

Cancellation is cooperative: it stops further plan execution, enters cleanup, deactivates the Sidecar, and stops the owned JAR. A hard process kill cannot prove cleanup and must remain `cleanup_unverified`.

## Deactivation workflow

1. Require an exact PID, its previously returned `processStartEpochMs`, and explicit confirmation before calling `jvm_lifecycle.deactivate`, `expectedProcessStartEpochMs`, and outer `confirmed:true`.
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
