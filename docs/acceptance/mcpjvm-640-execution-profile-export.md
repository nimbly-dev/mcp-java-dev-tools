# MCPJVM-640: Execution Profile Export live Java CDE acceptance

Captured on 2026-09-28 (Asia/Manila). A Node.js `@modelcontextprotocol/sdk`
client used `StdioClientTransport` with the packaged Java JAR and a running
Spring Fixtures user app. This is a compact observation record; the changelist
contains no raw MCP transcript or full request/response dump.

## Launch and fixture state

From `C:\Users\Altheo\repository\mcp-jvm-debugger`, the client launched:

```powershell
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\test\fixtures\spring-apps\social-platform\user-service\user-app\target\user-app-0.1.0-SNAPSHOT.jar' --server.port=18340
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcp-java-dev-tools-server-0.1.9.jar'
```

The fixture's `http://127.0.0.1:18340/actuator/health` returned HTTP 200.
The server was launched over STDIO with
`MCP_WORKSPACE_ROOT=C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcpjvm-640-workspace`.
The client closed the MCP connection and stopped the fixture in its cleanup path;
port 18340 was no longer listening afterward. No Sidecar attach was needed: the
regression plan set `probeVerification=false` and exercised an HTTP workload.

## CDE calls and observed outcomes

| Request | Observation |
| --- | --- |
| `tools/list` | Exactly `operation_catalog`, `operation_describe`, and `operation_execute`; no export-specific MCP Tool. |
| `operation_catalog({"api":"execution_profile_export","limit":10})` | Listed `execution_profile_export.export`. |
| `operation_describe({"operationId":"execution_profile_export.export"})` | Exposed the Java-owned input schema, including `mode` values `ps1`, `sh`, `postman` and default `includeResolvedSecrets=false`. Safety was `filesystem_export`, `confirmationRequired=true`, `caller_must_not_supply_credentials`, sensitive-field redaction, 30000 ms timeout, and `cancellationSupported=false`. Neither discovery result exposed aliases or deprecation fields. |
| `operation_execute` for `execution_orchestration.execute` with `{"projectName":"fixture-640","executionProfile":"smoke","suiteRunId":"live-640-1790575970175"}`, `confirmed=true` | Outer `succeeded`; owner `status=pass`, `reasonCode=ok`. Persisted regression step `health` returned HTTP 200 from the running fixture. |
| `operation_execute` for `execution_profile_export.export` with `{"projectName":"fixture-640","exportId":"export-live-640-1790575970175","executionProfile":"smoke","mode":"ps1","includeRuntimeStartup":false,"includeHealthcheckGate":false}`, `confirmed=false` | `confirmation_required`, `operation_confirmation_required`; no export generated. |
| Same export request with `confirmed=true` | Outer `succeeded`; owner `status=ok`, `reasonCode=success`. Wrote the replay package listed below. |
| Same confirmed export with `executionProfile=absent` and a distinct export ID | Outer `succeeded`; owner `status=execution_profile_not_found`, `reasonCode=execution_profile_not_found`. No directory was created for the missing profile's export ID. |
| Same confirmed export with `mode=invalid` and a distinct export ID | Outer `invalid_input`, `operation_input_schema_invalid`; the owner was not invoked. |

The successful export was written under
`mcp-server/application/target/mcpjvm-640-workspace/.mcpjvm/fixture-640/exports/export-live-640-1790575970175/`.
It contained `manifest.json`, `project.env`, `README.ps1.md`, `replay.ps1`,
and `run-execution-profile.ps1`. The manifest selected execution profile
`smoke`, and the generated script included the fixture health URL. The preceding
fixture execution persisted
`.mcpjvm/fixture-640/plans/regression/health/runs/live-640-1790575970175-1/execution.result.json`
with a passed HTTP step and status code 200.

The client ran this bounded replay while the fixture was still live:

```powershell
& powershell -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcpjvm-640-workspace\.mcpjvm\fixture-640\exports\export-live-640-1790575970175\run-execution-profile.ps1'
```

It exited 0 and reported `[regression:health] replay workload` followed by
`[health:health] status= 200`. The generated package skipped runtime startup
and its healthcheck gate as requested; the replayed health step itself ran.
This proves the intended workload was selected and reached the live fixture.

## Verification and disposition

From `mcp-server`,
`mvn -B -pl application -am verify` passed again after removal of the stale
Spring test initializer registration:
884 Core tests, 30 Application tests, and 5 packaged STDIO integration tests,
with no failures or errors; 2 OS-specific integration tests were skipped on
Windows. Checkstyle and PMD passed. `git diff --check` passed. The packaged JAR
used for the recorded run had SHA-256
`EBA0A4847411FCB7945AFC78BE10DC0DB47D48F511D3803B7B5192412AE5E4A7`.

The obsolete export-specific Java MCP Tool, request/response mapping, schema
postprocessor, one-element catalog, forwarding operation/Feature wrappers, and
tests coupled only to those routes were removed. The stale
`spring.factories` registration for the deleted test initializer was also
removed. The CDE registration now calls
the substantive `ExecutionExportOperations` Artifact owner through its approved
gateway. The Artifact generator, persistence, result normalization, redaction,
confirmation policy, and Sidecar Agent were retained. Node STDIO runtime/source
removal remains with #611 final cleanup.

The observed run covers one regression HTTP profile, the PowerShell replay mode,
confirmation, missing-profile behavior, and invalid-mode rejection. It does not
establish live SH/Postman/performance export coverage, resolved-secret behavior,
or Sidecar-assisted execution. Clients must inspect the inner export result:
outer CDE `status=succeeded` also accompanied the deterministic
`execution_profile_not_found` owner result.
