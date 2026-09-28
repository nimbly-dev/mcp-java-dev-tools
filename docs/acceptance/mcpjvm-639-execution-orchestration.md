# MCPJVM-639: Execution Orchestration live Java CDE acceptance

Captured on 2026-09-27 (Asia/Manila) with a Node.js `@modelcontextprotocol/sdk` client using `StdioClientTransport` against the packaged Java JAR. This record summarizes the observed calls and Artifacts; raw MCP transcripts and full request/response dumps are excluded from the changelist.

## Launch and runtime state

From `C:\Users\Altheo\repository\mcp-jvm-debugger`, the client launched the running Spring Fixtures user app with these executable and arguments:

```text
"C:/Program Files/Amazon Corretto/jdk21.0.12_12/bin/java.exe" -jar "C:\Users\Altheo\repository\mcp-jvm-debugger\test\fixtures\spring-apps\social-platform\user-service\user-app\target\user-app-0.1.0-SNAPSHOT.jar" --server.port=18339
```

The app's `http://127.0.0.1:18339/actuator/health` returned HTTP 200 before MCP execution. The client then launched the packaged server over STDIO with this executable and arguments:

```text
"C:/Program Files/Amazon Corretto/jdk21.0.12_12/bin/java.exe" -jar "C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcp-java-dev-tools-server-0.1.9.jar"
```

The server process environment set `MCP_WORKSPACE_ROOT=C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcpjvm-639-workspace`. The JAR SHA-256 after the run was `08123B58FA29B320DD33B8489AD0F0D2ED9BE29C2AE67C7F265FF66022B4EEEB`. The client closed its MCP connection and stopped the fixture in its cleanup path; neither port 18339 nor the timeout helper port 18340 was listening when checked afterward. The Sidecar was not attached because this regression plan set `probeVerification=false`; no Sidecar change was made.

## CDE requests and observed outcomes

The following are compact request arguments, not complete MCP envelopes or response dumps. `operation_execute` calls use `operationId`, direct `arguments`, and `confirmed`.

| Request | Observed outcome |
| --- | --- |
| `tools/list` | Exactly `operation_catalog`, `operation_describe`, and `operation_execute`; no orchestration-specific MCP Tool. |
| `operation_catalog({"query":"execution_orchestration","limit":10})` | Listed `execution_orchestration.execute`. |
| `operation_describe({"operationId":"execution_orchestration.execute"})` | Required `projectName` and `executionProfile`; `filesystem_write`, `confirmationRequired=true`, `cancellationSupported=true`, `timeoutMillis=30000`, sensitive-field redaction. No alias or deprecation fields. |
| `operation_execute({"operationId":"artifact_management.regression_plan.validate","arguments":{"projectName":"fixture-639","planName":"health-a"},"confirmed":false})` | `succeeded`; result `status=ok`, `reasonCode=success`. |
| `operation_execute` for `execution_orchestration.execute`, `arguments={"projectName":"fixture-639","executionProfile":"approved","suiteRunId":"live-639-1790513728542","maxPlansPerCall":1}`, `confirmed=false` | `confirmation_required`, `operation_confirmation_required`; no plan ran. |
| Same execution request with `confirmed=true`, first call | `succeeded`; result `status=in_progress`, `reasonCode=execution_plan_slice_complete`, one plan outcome. |
| Same execution request with `confirmed=true`, second call | `succeeded`; result `status=pass`, `reasonCode=ok`, two cumulative plan outcomes. |
| Confirmed execution with `executionProfile=unapproved` (absent from `projects.json`) | Result `status=blocked`, `reasonCode=runtime_suite_missing`. |
| Confirmed execution with `executionProfile=invalid-plan` (references absent `missing-plan` Artifact) | Result `status=fail`, `reasonCode=plan_artifact_missing`; no fixture step was executed for that plan. |
| Confirmed execution with `maxPlansPerCall=0` | Outer `status=invalid_input`, `reasonCode=operation_input_schema_invalid`. |

The approved profile in `.mcpjvm/fixture-639/projects.json` selected two ordered regression plans, `health-a` and `health-b`. Both persisted `execution.result.json` files recorded `status=pass`, `details.runStatus=pass`, and one `health` HTTP step with `status=pass` and `statusCode=200` against the running fixture. The suite checkpoint recorded `status=pass`, `reasonCode=ok`, and two plan outcomes. The immediate Core regression result uses `status=ready` with `details.runStatus=pass`; this is why the final suite checkpoint can contain a `ready` plan entry while the persisted per-plan result and actual step both say `pass`.

Persisted paths, relative to `MCP_WORKSPACE_ROOT`:

```text
.mcpjvm/fixture-639/plans/regression/health-a/runs/live-639-1790513728542-1/execution.result.json
.mcpjvm/fixture-639/plans/regression/health-b/runs/live-639-1790513728542-2/execution.result.json
.mcpjvm/fixture-639/suite-runs/live-639-1790513728542/execution_orchestration.result.json
```

## Timeout and continuation

The same live MCP client used the fixture's health endpoint through an in-process loopback HTTP proxy on `127.0.0.1:18340`. The proxy delayed each request by 9 seconds before forwarding to the running Spring fixture. The `timeout` execution profile had one regression plan with four ordered requests through that proxy, enough to exceed the Java operation's 30-second timeout. A confirmed `execution_orchestration.execute` call with `suiteRunId=live-639-1790513728542-timeout` returned outer `status=timeout`, `reasonCode=operation_timeout`. The `.mcpjvm/fixture-639/suite-runs/live-639-1790513728542-timeout/execution.lock` lease was absent after cancellation. With the proxy delay set to zero and the same profile and suite run ID, a confirmed retry returned result `status=pass`; its persisted suite checkpoint also recorded `pass`. This proves the exercised timeout and resumption path, with the delay supplied by the test client rather than the fixture itself.

## Verification, code disposition, and limits

From `mcp-server`, `& 'C:\Users\Altheo\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd' -B -pl application -am verify` returned `BUILD SUCCESS`: Core 892 tests, Application 50 tests, and packaged STDIO integration 5 tests, with 0 failures/errors and 2 skipped STDIO signal tests on Windows. Checkstyle and PMD passed. `git diff --check` passed.

The obsolete `ExecutionOrchestrationMcpTool`, request wrapper, schema postprocessor, adapter-only test, and `DefaultExecutionOrchestrationFeature`/`ExecutionOrchestrationFeature` forwarding path were deleted. `ExecutionOrchestrationOperationRegistrations` now binds directly to the substantive `ExecuteExecutionOrchestrationAction` handler through the aggregate Core directory. The action, Artifact storage, runtime lifecycle, Suite owners, and Sidecar were retained. Node STDIO source/runtime removal remains with #611 final cleanup.

This run gives live acceptance for the exercised regression orchestration, negative inputs, timeout, and continuation. It does not claim live coverage of Performance or Security Suite orchestration, dynamic Sidecar attachment, or every registered operation. Generated workspaces and client script remain under ignored `mcp-server/application/target`; only this summary is in the changelist.
