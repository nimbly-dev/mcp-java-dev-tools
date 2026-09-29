# MCPJVM-642: Performance Suite live Java CDE acceptance

Captured on 2026-09-29 (Asia/Manila). A Node.js
`@modelcontextprotocol/sdk` client connected over STDIO to the packaged Java
JAR while the repository Spring Fixtures user app, Apache JMeter 5.6.3, and an
attached Sidecar Agent were running. This is a compact observation record, not
an MCP transcript.

## Launch and runtime state

From `C:\Users\Altheo\repository\mcp-jvm-debugger`, the client launched:

```powershell
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\test\fixtures\spring-apps\social-platform\user-service\user-app\target\user-app-0.1.0-SNAPSHOT.jar' --server.port=58293
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcp-java-dev-tools-server-0.1.9.jar'
```

The fixture health endpoint returned HTTP 200. Java CDE selected fixture PID
`74484` with process-start fence `1790680091648`, attached the packaged
Sidecar on `http://127.0.0.1:58294`, and received `probe.check=SUCCESS`.
JMeter was resolved from
`C:\Users\Altheo\repository\tools\apache-jmeter-5.6.3`. The packaged server
JAR SHA-256 was
`55264BD72CA5FE0B09C55D3DF0EAAB059C7AEF91789F7DD342B08A0DCFCD9A43`.

The cleanup path called confirmed `jvm_lifecycle.deactivate` with the same PID
and start fence. It returned `outcome=deactivated` with no non-restorable
classes; the client closed and the fixture process stopped.

## CDE requests and observed outcomes

The trusted Artifact boundary used workspace root
`C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcpjvm-642-workspace`
and read `projectName=fixture-642` with plans `pass`, `threshold-fail`, and
`line-fail` before execution. The suite used
`suiteRunId=live-642-1790680091622`, `executionProfile=smoke`, concurrency 1,
and two-second generated-HTTP workloads against
`GET /api/v1/users/alice`.

| Request | Observation |
| --- | --- |
| `tools/list` | Exactly `operation_catalog`, `operation_describe`, and `operation_execute`. |
| `operation_catalog({"api":"performance_suite","limit":10})` | Listed only `performance_suite.execute_plan`; no alias or deprecation fields were exposed. |
| `operation_describe({"operationId":"performance_suite.execute_plan"})` | Required `projectName`, `executionProfile`, `planName`, and `suiteRunId`; declared `filesystem_write`, confirmation required, sensitive-field redaction, no caller-supplied credentials, and the bounded operation timeout. |
| Execute the passing plan without confirmation | Outer CDE returned `confirmation_required` / `operation_confirmation_required`. |
| Execute the passing plan with confirmation | Inner owner returned `status=completed`, `details.runStatus=pass`; 270 HTTP 200 samples produced 135 requests/s, 0% errors, p95 14 ms, and all three thresholds passed. |
| Strict line evidence | `com.example.social.user.app.controller.UserController#getUserProfile:20` was returned in `requiredLineHits`; subsequent Probe status reported hit count 1264. |
| Execute the impossible-threshold plan | Inner owner completed the workload with `details.runStatus=fail`; observed throughput 294.5 requests/s did not satisfy the configured minimum of 1,000,000,000 requests/s. |
| Execute the missing-line plan | Inner owner returned `status=blocked`, `reasonCode=performance_required_line_hit_missing`, with no passing run status. |

Each run persisted `execution.result.json`, `workload.jmeter.jmx`,
`workload.jmeter.jtl`, and `workload.jmeter.log` below
`.mcpjvm/fixture-642/plans/performance/<planName>/runs/live-642-1790680091622-1`.
The passing Artifact preserved timing metrics, threshold verdicts, and the
required Strict Line Key. The threshold case persisted `status=fail`; the
missing-line case persisted `status=blocked`.

The live workspace and `.mcpjvm/fixture-642` run directories are disposable,
ignored acceptance data under `mcp-server/application/target`. They remain in
the local ignored workspace for inspection but are not part of the changelist;
the durable committed evidence is this compact observation record. Runtime
cleanup separately deactivated the Sidecar, closed the MCP client, and stopped
the fixture as recorded above.

## Corrections and disposition

Live acceptance found and fixed two capability-local behavior gaps. Generated
JMX lacked the JMeter test-element metadata needed by JMeter 5.6.3, and Java
verified line coverage with a wait window created only after the workload had
already ended. The renderer now emits a complete generated-HTTP plan, and the
executor verifies the reset post-workload Probe counters in requested-key
order. Focused tests cover both corrections.

The obsolete performance action dispatcher, handler, request/action envelope,
wrapper, and per-action Spring wiring were removed. CDE and execution
orchestration now call the substantive `PerformancePlanExecutor` directly
through the retained `PerformanceSuiteFeature` cross-suite contract. No new
routing layer was introduced; the Sidecar was not changed. Node STDIO
runtime/source removal remains with #611 final cleanup.

This workload did not configure JFR/MSTA analysis, so the live results
correctly report profiler and MSTA as `not_configured`; the run does not claim
live JFR/MSTA evidence. The exercised workload, strict line coverage,
threshold pass/fail behavior, Artifact persistence, confirmation policy, and
Sidecar lifecycle are verified.

## Verification

From `mcp-server`, `mvn -B -pl application -am verify` passed: 890 Core
tests, 30 Application tests, and 5 packaged STDIO integration tests with zero
failures or errors; 2 OS-specific integration tests were skipped on Windows.
Checkstyle, PMD, and `git diff --check` passed.
