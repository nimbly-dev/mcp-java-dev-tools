# MCPJVM-638 Artifact Management live CDE acceptance

Date: 2026-09-27. Scope: Artifact Management through the packaged Java MCP server over STDIO. The workspace was a disposable directory under `mcp-server/application/target`; no fixture or repository Artifact was changed.

## Build and live launch

From `mcp-server`:

```powershell
& "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.11\03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc\bin\mvn.cmd" -B -pl application -am verify
```

Result: BUILD SUCCESS. Core: 891 tests, no failures/errors/skips. Application: 52 tests, no failures/errors/skips. Packaged-JAR STDIO integration: 5 tests, no failures/errors, 2 Windows skips. Checkstyle and PMD passed.

The live client used `@modelcontextprotocol/sdk` `Client` and `StdioClientTransport`. It spawned these exact Java commands from the repository root:

```powershell
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\test\fixtures\spring-apps\social-platform\user-service\user-app\target\user-app-0.1.0-SNAPSHOT.jar' --server.port=18338
& 'C:\Program Files\Amazon Corretto\jdk21.0.12_12\bin\java.exe' -jar 'C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcp-java-dev-tools-server-0.1.9.jar'
```

The server received `MCP_WORKSPACE_ROOT=C:\Users\Altheo\repository\mcp-jvm-debugger\mcp-server\application\target\mcpjvm-638-workspace-FAtNYT`. The fixture health endpoint at `http://127.0.0.1:18338/actuator/health` returned HTTP 200. The client closed the STDIO connection and stopped the fixture after the run. No Sidecar attach was needed: the accepted workflow executed a generated HTTP replay against the live fixture.

## Observed CDE results

- `tools/list` returned exactly `operation_catalog`, `operation_describe`, and `operation_execute`.
- `operation_catalog({"api":"artifact_management","limit":50})` returned all 31 registered Artifact operations. `operation_describe({"operationId":"..."})` returned a Java-owned input schema and safety policy for each of the 31 IDs. Neither discovery result contained aliases or deprecation fields.
- `operation_execute` with `artifact_management.probe_config.upsert`, a valid profile payload, and `confirmed=false` returned `confirmation_required`. With `confirmed=true`, it wrote `.mcpjvm/probe-config.json` and applied a registry reload. Subsequent `probe_config.read` and `probe_config.validate` succeeded. A malformed replacement returned `probe_config_invalid` and left the valid file byte-for-byte unchanged.
- Confirmed `project_context.upsert` wrote `.mcpjvm/fixture-638/projects.json` and provisioned `run-state.sqlite`; `read`, `validate`, and `list` succeeded. A `projectName` of `../escape` returned `artifact_path_segment_invalid`. An upsert with empty `workspaces` returned `project_artifact_invalid` and did not create a project Artifact.
- Confirmed `performance_plan.upsert`, `regression_plan.upsert`, and `security_plan.upsert` persisted their `metadata.json` and `contract.json` files. Each family's `read`, `validate`, and `list` succeeded against those files.
- Confirmed `execution_export.generate` used the written project context and regression plan to create a PowerShell replay package. `execution_export.list` and `execution_export.read` found its five files. Running the generated `run-execution-profile.ps1` while the fixture was live exited 0 and reported `[health:health] status= 200` from the fixture's HTTP health endpoint. This is the observed effect of the written Artifacts in a real fixture workflow.
- Confirmed `run_result.upsert` persisted the replay outcome in `execution.result.json`; `run_result.read` returned that file and its HTTP 200 evidence. `run_result.query` returned an available SQLite `run_state` projection with an empty item page. Unconfirmed `run_result.rebuild`, `backfill`, `cutover`, and `cleanup` each returned `confirmation_required`; none changed the disposable state.

## Selected live `tools/call` requests

These are the `name` and `arguments` values sent to the packaged server. Outcomes are the observed CDE envelope status and, where applicable, the Artifact owner's result.

Confirmed Probe config write:

```json
{"name":"operation_execute","arguments":{"operationId":"artifact_management.probe_config.upsert","arguments":{"payload":{"defaultProfile":"local","profiles":{"local":{"probes":{"fixture":{"baseUrl":"http://127.0.0.1:57569"}}}}}},"confirmed":true}}
```

Observed `succeeded` / owner `ok`; `.mcpjvm/probe-config.json` was persisted with one Probe and the registry reload was applied. The same call with `confirmed:false` returned `confirmation_required` / `operation_confirmation_required` before writing.

Confirmed project context write used by the export:

```json
{"name":"operation_execute","arguments":{"operationId":"artifact_management.project_context.upsert","arguments":{"projectName":"fixture-638","payload":{"workspaces":[{"projectRoot":"C:\\Users\\Altheo\\repository\\mcp-jvm-debugger\\mcp-server\\application\\target\\mcpjvm-638-workspace-FAtNYT","defaults":{"orchestrator":{"resumePollMax":1,"resumePollIntervalMs":10,"resumePollTimeoutMs":100}},"executionProfiles":[{"executionProfile":"smoke","executionPolicy":"stop_on_fail","suiteType":"regression","plans":[{"order":1,"planName":"health","onFail":"inherit"}]}]}]}},"confirmed":true}}
```

Observed `succeeded` / owner `ok`; `.mcpjvm/fixture-638/projects.json` was persisted and `run-state.sqlite` was provisioned.

Confirmed regression plan write targeting the running fixture:

```json
{"name":"operation_execute","arguments":{"operationId":"artifact_management.regression_plan.upsert","arguments":{"projectName":"fixture-638","planName":"health","payload":{"metadata":{"execution":{"intent":"regression"}},"contract":{"targets":[{"id":"fixture"}],"steps":[{"order":1,"id":"health","protocol":"http","transport":{"http":{"method":"GET","url":"http://127.0.0.1:18338/actuator/health"}}}]},"plan":"# regression plan\n"}},"confirmed":true}}
```

Observed `succeeded` / owner `ok`; the persisted `contract.json` supplied the URL used by the generated replay.

Confirmed export from those written Artifacts:

```json
{"name":"operation_execute","arguments":{"operationId":"artifact_management.execution_export.generate","arguments":{"projectName":"fixture-638","executionProfile":"smoke","mode":"ps1","includeRuntimeStartup":false,"includeHealthcheckGate":false},"confirmed":true}}
```

Observed `succeeded` / owner `ok`; the export contained `run-execution-profile.ps1`. Executing that script while the fixture was running exited 0 and reported `[health:health] status= 200`.

Rejected path and guarded state change:

```json
{"name":"operation_execute","arguments":{"operationId":"artifact_management.project_context.read","arguments":{"projectName":"../escape"},"confirmed":false}}
{"name":"operation_execute","arguments":{"operationId":"artifact_management.run_result.cleanup","arguments":{"projectName":"fixture-638"},"confirmed":false}}
```

The path call returned owner `artifact_path_segment_invalid`; the cleanup call returned CDE `confirmation_required` / `operation_confirmation_required` and did not enter the owner.

Twenty-five distinct Artifact operation owners were exercised with successful workflow calls. Four destructive run-state IDs were called only to verify their confirmation gate. `probe_config.reload` and `run_result.list` were described but not executed. This sample does not prove every behavior of the 31 operations, nor the confirmed destructive state transitions.

## Code disposition

Removed the obsolete Artifact-specific Java MCP Tool, its request/response mapping and schema processor, and two tests bound to the old public Tool or old/new route parity. Retained the Artifact capability catalog and `ArtifactManagementFeature` because execution orchestration and trusted Suite execution still consume those Core boundaries. Probe configuration upsert now validates before replacing the persisted file. Node STDIO removal remains with #611 final cleanup. `docs/data-fields/README.md` remains the TypeScript compatibility field reference; the fixed three-tool CDE public envelope did not change in this ticket.
