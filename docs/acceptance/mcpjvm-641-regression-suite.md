# MCPJVM-641: Regression Suite live Java CDE acceptance

Captured on 2026-09-28 (Asia/Manila). A Node.js `@modelcontextprotocol/sdk`
client connected over STDIO to the packaged Java JAR while the repository Spring
Fixtures user app was running. This is a compact observation record, not an MCP
transcript.

**Checklist status: partial.** The original run below proves the HTTP-only
scenario. It does not prove Probe hits, Watchers, external verification, or
correlation, so it cannot support the broader #611 substantive-behavior
retention claim. A follow-up fail-closed check is recorded below.

## Launch and fixture state

From `C:\Users\Altheo\repository\mcp-jvm-debugger`, the client launched:

```powershell
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\test\fixtures\spring-apps\social-platform\user-service\user-app\target\user-app-0.1.0-SNAPSHOT.jar' --server.port=18341
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcp-java-dev-tools-server-0.1.9.jar'
```

The fixture health endpoint at `http://127.0.0.1:18341/actuator/health`
returned HTTP 200 before MCP execution. The server used
`MCP_WORKSPACE_ROOT=C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcpjvm-641-workspace`.
The client closed the MCP connection and stopped the fixture in its cleanup
path; no matching Java or Node process remained. The packaged JAR SHA-256 was
`F271FCCB11ACB7B149B80797E34488437BFA3506F18BBD4B1644F4B65CEF5743`.

## CDE requests and observed outcomes

The direct regression selectors used `projectName=fixture-641`,
`executionProfile=smoke`, `suiteRunId=live-641-1790601473784`, and either
`planName=health` or `planName=invalid`. The health plan contained one GET
request to the running fixture; the invalid plan had no steps.

| Request | Observation |
| --- | --- |
| `tools/list` | Exactly `operation_catalog`, `operation_describe`, and `operation_execute`. |
| `operation_catalog({"api":"regression_suite","limit":10})` | Listed `regression_suite.execute_plan` and `regression_suite.preflight`. |
| `operation_describe` for each ID | Both schemas required `projectName`, `executionProfile`, `planName`, and `suiteRunId`. Preflight was `filesystem_read` without confirmation; execute was `filesystem_write` with confirmation. Both specified sensitive-field redaction, no caller-supplied credentials, a 30000 ms timeout, and no cancellation support. Catalog and descriptions had no alias or deprecation fields. |
| `operation_execute` for `regression_suite.preflight`, health plan | Owner `status=ready`, `reasonCode=ok`, one target and one step. |
| Same preflight, invalid plan | Owner `status=blocked_invalid`, `reasonCode=steps_missing`. |
| `operation_execute` for `regression_suite.execute_plan`, invalid plan, `confirmed=true` | Owner `status=blocked_invalid`, `reasonCode=steps_missing`; persisted result had no passing run status. |
| Same execute, health plan, `confirmed=false` | CDE `status=confirmation_required`, `reasonCode=operation_confirmation_required`. |
| Same execute, health plan, `confirmed=true` | Owner `status=ready`, `reasonCode=ok`, `details.runStatus=pass`; the `health` step passed with HTTP 200. |

The confirmed health run persisted
`.mcpjvm/fixture-641/plans/regression/health/runs/live-641-1790601473784-1/execution.result.json`
with `status=pass`, `reasonCode=ok`, and a passed HTTP 200 step. The invalid
run persisted
`.mcpjvm/fixture-641/plans/regression/invalid/runs/live-641-1790601473784-1/execution.result.json`
with `status=blocked_invalid`, `reasonCode=steps_missing`, and no passing
coverage. The direct-run lock file remained in each run directory; the owner
closes its file lock after execution.

No Sidecar attach was needed because the fixture plan set
`probeVerification=false` and configured no downstream verification. The live
evidence covers HTTP endpoint execution, preflight, confirmation, invalid-plan
rejection, and persisted outcomes. It does not establish Probe hit or downstream
verification behavior, because neither was configured in this plan.

## Configured verification fail-closed follow-up

After the verification guard was added, the same fixture and packaged-JAR launch
commands above were used again with run ID `live-641-1790602825081`. The rebuilt
JAR SHA-256 was
`3D13D29AC0BEF19374102412FAC0883E752138DFD878E11C061D00E5E948C668`.
`tools/list` still returned exactly the three CDE Tools, and Catalog still
listed both Regression Suite operations. The HTTP-only health plan again
persisted a passing HTTP 200 step; the invalid plan again persisted
`blocked_invalid` / `steps_missing` without passing coverage.

Four additional persisted plans each had the same valid fixture HTTP step and
one requested verification phase. Both `regression_suite.preflight` and
`regression_suite.execute_plan` returned `blocked_runtime` for each plan:

| Configured phase | Reason code | Persisted status |
| --- | --- | --- |
| `metadata.execution.probeVerification=true` with a valid pinned key | `probe_verification_unavailable` | `blocked_runtime` |
| `contract.watchers[]` | `watcher_verification_unavailable` | `blocked_runtime` |
| `contract.externalVerification[]` | `external_verification_unavailable` | `blocked_runtime` |
| `contract.correlation.enabled=true` | `correlation_verification_unavailable` | `blocked_runtime` |

Each result Artifact is under
`.mcpjvm/fixture-641/plans/regression/<planName>/runs/live-641-1790602825081-1/execution.result.json`.
None contained a passing `details.runStatus`. The focused Core test also used a
transport collaborator that fails if called, establishing that each guard
returns before the HTTP trigger. The fixture and MCP client processes were
stopped afterward. This is evidence of safe rejection, not evidence that Java
performs Probe hits or downstream verification.

## Verification and code disposition

From `mcp-server`, `mvn -B -pl application -am verify` passed: 888 Core tests,
30 Application tests, and 5 packaged STDIO integration tests with zero
failures or errors; 2 OS-specific integration tests were skipped on Windows.
Checkstyle and PMD passed. `git diff --check` passed.

The regression action dispatcher, action handlers, request/action envelope,
per-action Spring Beans, and obsolete no-action test were removed. Substantive
preflight and execution tests now target `RegressionPlanExecutor`. CDE registration
now calls the substantive `RegressionPlanExecutor` methods through the existing
trusted persisted-plan boundary. `RegressionSuiteFeature` remains as the
cross-suite contract consumed by execution orchestration; the trusted
registration bridge remains to preserve the registration API boundary.

The #611 substantive Regression Suite verification-retention claim remains
open until Java executes these configured phases with live evidence.
Preflight policy, HTTP execution, Artifact persistence, safety, and the Sidecar
Agent were retained. No feature-specific Java MCP Tool existed in this
checkout. Node STDIO runtime/source removal remains with #611 final cleanup.
