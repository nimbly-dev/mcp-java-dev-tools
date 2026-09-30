# MCPJVM-643: Security Suite live Java CDE acceptance

Captured on 2026-09-29 (Asia/Manila). A Node.js
`@modelcontextprotocol/sdk` client connected over STDIO to the packaged Java
JAR while the repository Spring Fixtures user app was running. This is a
compact observation record, not an MCP transcript.

## Launch and fixture state

From `C:\Users\Altheo\repository\mcp-jvm-debugger`, the client launched:

```powershell
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\test\fixtures\spring-apps\social-platform\user-service\user-app\target\user-app-0.1.0-SNAPSHOT.jar' --server.port=62587
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcp-java-dev-tools-server-0.1.9.jar'
```

The fixture health endpoint returned HTTP 200. The packaged server used
`MCP_WORKSPACE_ROOT=C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcpjvm-643-workspace`.
Its SHA-256 was
`CFEAC4B9D1146411279805DA4000C25715065CBA6EA54C63164E2FB93EC0B774`.
The client closed and fixture PID `70960` stopped in the cleanup path.

## CDE requests and observed outcomes

The trusted Artifact boundary read and validated `projectName=fixture-643`,
`executionProfile=smoke`, and plans `safe` and `incomplete`. Both executions
used `suiteRunId=live-643-1790683243058`.

| Request | Observation |
| --- | --- |
| `tools/list` | Exactly `operation_catalog`, `operation_describe`, and `operation_execute`. |
| `operation_catalog({"api":"security_suite","limit":10})` | Listed only `security_suite.execute_plan`; no alias or deprecation fields were exposed. |
| `operation_describe({"operationId":"security_suite.execute_plan"})` | Required `projectName`, `executionProfile`, `planName`, and `suiteRunId`; declared `filesystem_write`, confirmation required, sensitive-field redaction, no caller-supplied credentials, and the bounded operation timeout. |
| Execute `safe` without confirmation | Outer CDE returned `confirmation_required` / `operation_confirmation_required`. |
| Execute confirmed `safe` Black-box plan | Owner returned `status=completed`, `details.runStatus=pass`, complete 10/10 coverage, and zero findings. The live `GET /actuator/health` baseline was `passed` with HTTP 200. The nine reviewed catalog mutations were safely `not_applicable` because the plan intentionally supplied no matching fixture inputs or authenticated profile. |
| Execute confirmed `incomplete` plan with no entrypoints | Owner returned `status=blocked` / `security_contract_entrypoints_invalid`; no passing `runStatus` was returned or persisted. |

The clean run selected the nine reviewed `@1.0.0` knowledge packs and persisted
their immutable snapshot digest
`a01625ab9c4a835955cf9fb161563f32769b1663c970b3106cc7b8255ad299c1`.
Its `execution.result.json` under
`.mcpjvm/fixture-643/plans/security/safe/runs/live-643-1790683243058-1`
preserved `status=pass`, the selected packs, complete coverage, per-case proof
classification, and the empty finding set. The incomplete run under the
corresponding `incomplete` plan directory persisted `status=blocked`, the same
reason code observed over CDE, and no clean result.

This bounded Black-box workflow did not require Sidecar attachment. It proves
the exercised HTTP baseline, reviewed-catalog selection, confirmation boundary,
Artifact persistence, and fail-closed incomplete-plan behavior. It does not
claim live Sidecar-assisted Security coverage, runtime-target evidence,
credential refresh, or an exercised mutation finding.

## Code disposition

The obsolete Security action dispatcher, handler, request/action envelope,
wrapper, and per-action Spring wiring were removed. CDE and execution
orchestration now call the substantive `SecurityPlanExecutor` directly through
the retained `SecuritySuiteFeature` cross-suite contract. The knowledge catalog,
credential refresh, HTTP transport, Probe integration, result model, trusted
persisted-plan boundary, and Sidecar Agent remain with their existing owners.
No new routing layer was introduced. Node STDIO runtime/source removal remains
with #611 final cleanup.

The disposable client and generated `.mcpjvm/fixture-643` data remain under the
ignored `mcp-server/application/target` directory. Only this compact record is
committed.

## Verification

Before the live run, the focused Core command passed 195 tests with zero
failures, errors, or skips:

```powershell
mvn -B -pl core '-Dtest=SecurityPlanExecutorTest,SuiteOperationRegistrationTest,CoreOperationDirectoryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

From `mcp-server`, `mvn -B -pl application -am verify` passed: 890 Core
tests, 30 Application tests, and 5 packaged STDIO integration tests with zero
failures or errors; 2 OS-specific integration tests were skipped on Windows.
Checkstyle and PMD passed. `git diff --check` passed.
