Issue Title

Regression suite persisted `probe.hit=false` even when strict line probe verification succeeded

Issue Description

In runtime-suite execution with strict line verification enabled, a probe-verified HTTP step could still be persisted as a failed `probe_line_hit` assertion. The session showed that the target line was executed and captured by the live probe runtime, but the regression-suite result continued to record `probe.hit=false` because the orchestration and probe-wait handling consumed the probe tool payload incorrectly.

Observed Behavior
Strict probe verification succeeded at runtime, but suite artifacts recorded failed `probe_line_hit` assertions with `actual=false` for every executed HTTP step.

Expected Behavior
When `probe_wait_for_hit` confirms a strict line hit for the active request window, the suite should persist `probe.hit=true` and classify coverage as `verified_line_hit`.

Steps to Reproduce
1. Start a probe-enabled sample microservice stack with a strict line target configured for a controller method such as `com.example.composite.web.SampleController#hello:52`.
2. Arm the probe runtime for the same strict line key and execute an HTTP regression profile that calls a route such as `GET http://127.0.0.1:8080/api/sample/hello`.
3. Observe that the live probe status shows a line hit and capture for the target method, while the regression-suite run artifact still persists `probe.hit=false` and fails the `probe_line_hit` assertion.

Supporting Evidence
The live probe runtime reported `contractVersion: 0.1.7`, `mode: actuate`, `scopeState: armed`, `hitCount: 1`, and a capture path for the target method during the same run window. Two code defects were identified: the probe wait utility only checked top-level `hitCount` and `lastHitEpoch` instead of the nested `probe.*` payload fields, and the execution-orchestration wrapper returned the full `ToolTextResponse` instead of `result.structuredContent` for `probe_reset` and `probe_wait_for_hit`.

Impact
Strict probe-gated regression profiles can fail even when the runtime actually verifies the requested line hit, which blocks trustworthy `verified_line_hit` reporting and can cause false-negative suite results.

Sanitization Note
Class names, package names, enterprise identifiers, hosts, and credentials were anonymized or redacted.
