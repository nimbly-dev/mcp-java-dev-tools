# #655 Execution Orchestration and replay acceptance

Implementation baseline: `ed40238dc8e1f5695ef3c39335b457ef1629307d`.
Tested state: that commit plus this ticket's uncommitted working-tree changes, 2026-10-15.
Scope: packaged Application ITs and directly owning contract-restoring Core/Application corrections.
Existing Sidecar and fixture sources remain immutable. Tests use the current CDE surface.

## Reproductions recorded before production corrections

- `McpServerReplayIT.relocatedPackagesReplayOrderedRequestsWithRuntimeCredentials[postman]`
  in ignored `test/.tmp/655-fourth.log` executes a real generated collection with Newman 6.2.1.
  Both requests fail with `request url is empty`; the export supplies only `url.raw` in an
  incomplete URL object. Expected: a Collection v2.1 URL accepted by Newman, preserving
  templates, order, headers and body. Owner/path: Core Artifact export
  `ExecutionExportPostmanRenderer.java`. Use the schema-supported URL string form.
- `McpServerOrchestrationLifecycleIT.mcpOwnsStartupAttachHealthRestartAndTerminalCleanup`
  in ignored `test/.tmp/655-lifecycle-repro.log` returns `sidecar_lifecycle_invalid` before
  startup. The persisted configuration was accepted by `probe_config.upsert` and follows
  the active `profiles.<profile>.probes` contract. Application lifecycle reads obsolete
  root-level `probes` and treats include/exclude arrays as strings. Expected: consume
  the Artifact owner's active profile summary, including list-valued filters, without
  duplicating registry selection. Owner/paths: Application
  `lifecycle/ApplicationExecutionRuntimeLifecycle.java` and its composition in
  `configuration/ExecutionOrchestrationConfiguration.java`.

- `failurePolicyControlsActualDownstreamEffects` and `boundedTransportTimeoutPersistsFailureAndReleasesRunLease`
  in `655-fifth.log`: repeating a stopped failed run reports `partial_fail`, and executes
  the downstream plan despite the persisted stop policy. Expected: terminal failures
  preserve their policy/status, without new side effects. Owner: Core
  `ExecuteExecutionOrchestrationAction.java`; evaluate persisted failures before pending plans.
- The same lifecycle scenario in `655-fifth.log` completes both plans and proves the
  owned PID exited, then repeating the completed call returns `owned_runtime_missing`.
  Expected: reuse the successful terminal cleanup evidence; do not demand a terminated
  runtime be alive. Owner/path: Core `ExecuteExecutionOrchestrationAction.java`.
- `McpServerPerformanceReplayIT` in `655-fifth.log` generates and relocates a JMeter
  package into a directory containing spaces. Its Node runner uses `shell=true` on
  Windows, splitting the JMX path and leaving the batch launcher paused after
  `Unknown arg`. Expected: execute the installed JMeter JAR with an argument array,
  with bounded completion and real JTL samples. Owner/path: Core Artifact export
  `ExecutionExportPerformanceArtifacts.java` (the generated runner).
- Both `McpServerReplayRuntimeIT` modes in `655-eighth.log` retain the original absolute
  workspace path when a shared script declares `appdir="."`. Expected: the root
  working directory resolves inside the receiver's package after relocation. Owner/path:
  Core Artifact export `ExecutionExportScriptReference.java`.
- Both Docker modes in `655-ninth.log` start the real Compose fixture and then fail
  their single immediate health request (PowerShell exit 1 / curl exit 52), before
  Spring is ready. The retained TS renderers retry readiness with a bound. Expected:
  a bounded readiness gate before workload, followed by test-owned Compose cleanup.
  Owner/path: Core Artifact export `ExecutionExportRuntimeRenderer.java`.
- `portableScriptsRespectRuntimeHealthAndPlanPhases` in `655-ninth.log` observes
  `postHealthcheck` HTTP requests before the health gate. Expected: preRuntime,
  startup, postRuntime, health, postHealthcheck, then prePlan for each ordered plan.
  Owners: the existing shell/PowerShell renderers, script and workload renderers.
- The same run's unsupported Security export returns `execution_export_workload_unresolved`
  before reaching the existing `security_export_unsupported` policy. Expected: reject
  that suite deterministically before interpreting its non-replay transport contract.
  Owners: `ExecutionExportWorkload.java` and `ExecutionExportWorkloadResolver.java`.
- All four builtin/default PowerShell/shell cases in `655-builtin-repro.log` execute
  exactly one request rather than the declared timed concurrent workload. The generated
  helper skips every non-JMeter plan and still reports success. Expected: honor the
  retained builtin/default workload, metrics, thresholds and result files; never report
  execution/probe success without real effects. Owner/path: Core Artifact export
  `ExecutionExportPerformanceArtifacts.java` and its owned generated JavaScript resource.
- `exportResolvesProfileContextAndTheActiveRegistryBeforeReplay` in
  `655-export-context-repro.log` first executes the actual source plan successfully,
  then export fails with `performance_export_jmeter_url_invalid` for `${base}`.
  The exporter also omits the profile's provided context and registry-selected Probe.
  Expected: preserve portable templates and consume the active Probe Artifact summary.
  Owner: `ExecutionExportPerformanceArtifacts.java`, collaborating with `ProbeConfigOperations`.
- Both `McpServerPortableSidecarReplayIT` cases in `655-portable-repro.log` reject a
  valid active registry with `probe_registry_unavailable`. Inspection of the same
  generated owner also shows an attach helper invocation without the required process
  start fence, and no lifecycle integration around the replay script. Expected: bundle
  the declared direct-Java JAR, resolve the canonical registry, own the spawned PID and
  start identity, attach/verify, execute the replay, deactivate and await JVM exit.
  Owners: `ExecutionExportPortableSidecar.java`, the shell runtime renderers and the
  existing standalone portable lifecycle helper. Sidecar implementation stays unchanged.

- `replayFailurePolicyAndPrePlanScriptsControlRealOrderedWork` and
  `exportedBuiltinTimeoutIsAFailedReplayWithPersistedBlockedEvidence` in
  `655-replay-negative.log`: PowerShell returns zero after the Node runner fails;
  shell reports `fail` after an explicit per-plan continue under a stop default.
  Expected: propagate the runner's nonzero exit and derive aggregate failure from
  the effective per-plan policy. Owner: `ExecutionExportMode.java` and the Core-owned
  `run-performance-profile.js` resource.

- Newman rejects the reported Postman `environmentPathAbs` in
  `655-postman-environment-repro.log`: `could not load environment`, because it is
  dotenv rather than Postman JSON. The existing data-field contract promises a
  Postman environment; portable documentation is also required by #655. Owners:
  `ExecutionExportEnvironmentWriter`, `ExecutionExportResultAssembler`, and the
  existing README writers. Generate a redacted JSON environment and usage/prerequisites;
  retain dotenv for shell packages and stop inventing a blocked source status in docs.

- Nested `appdir` variants of `portableScriptsRespectRuntimeHealthAndPlanPhases`
  in `655-portability-repro.log` fail because receiver working directories are not
  created. Shell script markers additionally use the changed `$PWD` after entering
  that directory. Expected: create declared receiver working directories and anchor
  copied arguments to the export root. Owners: existing shell/PowerShell script
  renderers and `ExecutionExportShellQuoting`/`ExecutionExportShellRenderer`.

- `postmanPreservesPrerequestScriptsAndExtractedContext` in
  `655-postman-context-repro.log` sends unresolved context because the collection
  ignores selected Postman scripts and step `extract` mappings. Expected: preserve
  Postman's script/environment and response-extraction behavior; unsupported native
  scripts/provisioning must fail visibly instead of being silently omitted. Owner:
  `ExecutionExportPostmanRenderer` with its existing renderer call site.

## Capability inventory and scenario map

Additional context-key reproduction: `655-binding-repro.log` invokes the actual
standalone helper's template resolver and reports `missing_context:AUTH_INPUT` for
a valid case-sensitive `authInput` environment binding, and `missing_context:RUNLABEL`
for Java-normalized `${runLabel}` headers with profile context. Expected: honor exact
environment binding names and the existing Java environment-key normalization,
while retaining the helper's legacy aliases. Owner: the Core-owned performance
runner resource; the real registry/context replay scenario exercises both variants.

`655-boundary.log` also reproduces `performance_plan_execution_failed` with
`missing_context:SCRIPT_MARKER` after a real copied prePlan script writes that value
to its supplied environment file. Expected: load script-produced inputs before
resolving the next workload, including replacing an initially blank credential-like
placeholder; preserve unrelated explicit environment overrides. Owner: the same
Core-owned standalone runner, in its existing ordered plan loop.

All classes below are under `mcp-server/application/src/test/java/com/nimbly/mcpjavadevtools/server`.
`Class#method[variant]` is the scenario identifier; Failsafe XML records each
parameter invocation, and the shared launcher prints its isolated evidence path.
`O` means `execution_orchestration.execute`; `E` means `execution_profile_export.export`.
Calls use packaged Java `operation_execute`, never a retired Node MCP route.

| Scenario IDs | Operations / consumers | Exact semantic evidence |
| --- | --- | --- |
| `McpServerOrchestrationIT#orderedSlicesRestartFromRealArtifactsWithoutRepeatingCompletedRequests` | O; Regression Suite profile branch | Confirmation rejects before effects; slice 1 executes only `/first`, persists next order 2; a new MCP process executes only `/second`; completed repeat has no requests and leaves first result bytes unchanged. Suite/plan Artifact reads agree. This is the sole `orchestration-smoke` case. |
| `McpServerOrchestrationIT#failurePolicyControlsActualDownstreamEffects[4 policy/override pairs]` | O; Regression Suite | Inherit/explicit stop/continue produce `fail` or `partial_fail` and exactly one/two requests. A failed producer cannot promote its resource ID. Repeating the same run preserves terminal status and emits no requests. |
| `McpServerOrchestrationIT#promotesOnlySafeSuccessfulContextAndResolvesPlanOverridesAndCredentials` | O; Regression Suite | Profile defaults, plan overrides, runtime env credentials and successful safe promotion drive actual downstream URL/headers. Suite context persists resource ID and excludes password/token. |
| `McpServerOrchestrationIT#generatedRunIdentityAndMissingArtifactsHaveDeterministicPersistedOutcomes` | O | Generated `suite-*` identity; missing plan persists blocked row and suite `fail/plan_artifact_missing`; missing profile is blocked; no HTTP effects. |
| `McpServerOrchestrationRecoveryIT#watcherAndExternalContinuationSurviveRestartWithoutRepeatingTriggers` | O; Regression Suite | Real trigger once, watcher continuation across process restart, ordered external checks, persisted active phase and final pass. No repeated trigger or completed external check. |
| `McpServerOrchestrationRecoveryIT#realFileLeaseRejectsAnotherMcpProcessThenReleasesForReuse` | O | Two real MCP processes contend for one persisted run; second returns `execution_suite_run_active` while HTTP is held; completion releases lease and second process reads pass without another request. |
| `McpServerOrchestrationRecoveryIT#changedProfileCannotReuseAnExistingRunIdentity` | O | Changed definition/profile rejects with checkpoint-definition/context mismatch before pending effects. |
| `McpServerOrchestrationCancellationIT#cancellationDisconnectsActiveTransportAndKeepsCompletedPlanRecoverable` | O | JSON-RPC cancellation disconnects held HTTP; canceled response is suppressed; completed plan bytes survive; replacing only unfinished input and resuming executes only remaining work. |
| `McpServerOrchestrationCancellationIT#boundedTransportTimeoutPersistsFailureAndReleasesRunLease` | O | Real transport timeout disconnects client, persists failure; repeat has stable reason/status and one plan row. |
| `McpServerOrchestrationRoutingIT#securityProfilesExecuteOrderedRealCasesAndPersistFindingsAndCoverage` | O; Security Suite | Two ordered real Security plans; six observed HTTP requests, complete coverage and persisted empty findings. Underlying Security rule/provider matrices belong to #654. |
| `McpServerOrchestrationRoutingIT#performanceProfilesExecuteRealJmeterAndResumeWithoutRewritingCompletedWorkload` | O; Performance Suite | Two real JMeter plans with fresh strict Probe evidence; restart resumes second only; first JTL bytes and modification time unchanged; actual second metrics and suite Artifact agree. Underlying provider analysis matrix belongs to #653. |
| `McpServerOrchestrationLifecycleIT#mcpOwnsStartupAttachHealthRestartAndTerminalCleanup` | O, `artifact_management.probe_config.upsert`, `probe.*`, `jvm_lifecycle.*`; suite runtime ownership | Canonical registry accepted, MCP spawns/attaches owned Java PID, verifies Probe, independently observed real Spring health, resumes after MCP restart, cleans owned runtime, and safely repeats terminal call. This does not claim lifecycle attach itself waits for Spring HTTP readiness. |
| `McpServerOrchestrationValidationIT#cdeDiscoveryDescribesTheSameSafetyContractAndConfirmationPreventsExports` | Catalog/Describe, O/E | Same registration identity, closed argument schema and confirmation safety; unconfirmed export produces no directory. |
| `McpServerOrchestrationValidationIT#invalidInputsRejectBeforeEffects[8 variants]` and `#invalidProfileOrderingFailsArtifactValidationWithoutExecution` | O, project Artifact validation | Missing selectors, invalid slice values/types, extra field and path escape reject before HTTP/state writes; invalid profile ordering rejects at Artifact owner. |
| `McpServerOrchestrationValidationIT#unsupportedExportsAndMissingSelectorsRemainDeterministic` | E | Unsupported format, absent/conflicting mode alias, Performance/Postman, Security export, missing/ambiguous selection and invalid context binding return exact stable reasons; no workload effects. |
| `McpServerReplayIT#relocatedPackagesReplayOrderedRequestsWithRuntimeCredentials[ps1,sh,postman]` | E, `artifact_management.execution_export.read`; Regression Export | Manifest contains two plans, actual Artifact read succeeds, no source workspace/token in safe package. Relocate; supply receiver credentials; actual POST query/body/headers then GET observed exactly. Newman consumes the generated JSON environment. README contains portable prerequisites. |
| `McpServerReplayIT#secretInclusionRequiresExplicitRequestAndSelectorsAndAliasAreSupported` | E, execution-export list | Session defaults affect startup/health but cannot enable secrets. Default manifest/context/env redacted; explicit inclusion emits disposable secret and sensitivity label; alias, plan-only/implicit-project selectors, generated ID, request overrides and `when` label verified. Export/list does not execute work. |
| `McpServerReplayIT#postmanPreservesPrerequestScriptsAndExtractedContext` | E; Regression Export/Postman | Copied Postman JavaScript supplies actual request header; response-body extraction supplies actual next-plan URL after deleting source script. |
| `McpServerReplayIT#postmanReplaysProvidedContextWithPrecedenceAndRedaction[4 variants]` | E, O; Regression Export/Postman | Actual source orchestration and Newman replay resolve profile/plan context, preserve case-sensitive bindings, honor explicit export overrides, redact sensitive logical names behind neutral environment aliases, and include an explicitly supplied secret only when requested. |
| `McpServerReplayIT#postmanKeepsPlanOverridesScopedAndReturnsToProfileDefaults` | E; Regression Export/Postman | Newman observes distinct first/second-plan header overrides, then the profile default for the third plan; profile base URL remains available throughout. |
| `McpServerReplayIT#postmanRejectsPrerequisitesItCannotReplay[3 kinds]` | E | Native script conversion, plain non-Postman JS and provisioning reject with exact reasons; no HTTP effects. |
| `McpServerReplayRuntimeIT#portableScriptsRespectRuntimeHealthAndPlanPhases[ps1/sh × root/nested appdir]` | E | Source script removed after relocation; copied script/env-file args and working directories work. Actual HTTP order is preRuntime, postRuntime, health, postHealthcheck, then prePlan before each plan. Runtime stdout ordering also asserted. |
| `McpServerReplayRuntimeIT#tcpHealthGateConnectsBeforeWorkload[ps1,sh]` | E | Real loopback TCP accept plus actual subsequent HTTP workload; no synthetic health result. |
| `McpServerDockerReplayIT#dockerStartupHealthAndWorkloadReplayUseOwnedPortablePrerequisites[ps1,sh]` | E | Generated Compose startup plus bounded HTTP readiness and real fixture workload; unique Compose project observed running, always brought down, then confirmed absent. Receiver supplies declared Compose/JAR prerequisites. |
| `McpServerDockerReplayIT#nativeLinuxShellReplaysInsideTheOwnedFixtureContainer` | E | Generated shell executes with native Linux Bash/curl against real Spring fixture; workload output and Compose cleanup verified. |
| `McpServerPerformanceReplayIT#acceptedPerformancePlansReplayRealWorkloadMetricsAndFreshProbeEvidence[ps1/sh × builtin/default/jmeter]` | E, `performance_suite.execute_plan`, Probe; Performance Export | Actual source run passes before export. Relocated replay creates real metrics, thresholds, fresh strict hit; JMeter generates successful JTL samples. Source workspace/install path/literal auth excluded; runtime JMX contains no token. |
| `McpServerPerformanceReplayIT#exportResolvesProfileContextAndTheActiveRegistryBeforeReplay` | E, Probe registry | Real source plan uses profile base/camel-case context, a case-sensitive environment binding and canonical probe ID, then exported JMeter replay succeeds with receiver installation/env. |
| `McpServerPerformanceReplayIT#replayFailurePolicyAndPrePlanScriptsControlRealOrderedWork[2 pairs]` | E | Actual threshold failure yields nonzero exit, effective stop/continue controls one/two real plan results, and copied prePlan script is called exactly once per executed plan from its nested directory. Script-produced env replaces an initially blank input before real workload resolution. Bad plan is a threshold failure, and the continued plan actually passes. |
| `McpServerPerformanceReplayIT#exportedProfilerOwnsAndStopsItsRealJfrSession` | E, `probe.profiler` | Actual source and replay own a JFR session; exported result passes; JFR file has FLR header/nontrivial bytes; live profiler reports completed. |
| `McpServerPerformanceReplayIT#failedOrEmptyProfilerDownloadBlocksRealReplay[404,empty 200]` | E, `probe.profiler` | Real JVM workload hits its strict line and stops its actual JFR session. A download-only proxy fault yields nonzero replay exit, persisted blocked download evidence and HTTP status, and no JFR file. |
| `McpServerPerformanceReplayIT#exportedBuiltinTimeoutIsAFailedReplayWithPersistedBlockedEvidence` | E | Real slow HTTP times out in source and replay; replay exits nonzero and persists `blocked/transport_request_timeout`. |
| `McpServerPerformanceReplayIT#absentReceiverJmeterFailsClosedWithoutInventingSamples` | E | Valid generated JMeter plan with receiver tool removed from local search: nonzero exit, persisted `blocked/performance_jmeter_missing`, zero samples and no invented JTL. |
| `McpServerPortableSidecarReplayIT#relocatedDynamicRuntimeUsesBundledHelperAndCleansItsOwnedJvm[ps1/sh × success/failing postRuntime]` | E, existing Sidecar helper | Bundled immutable target/helper/agent work after source JAR deletion and relocation. Actual attach/probe verification/deactivation/start fence, dead owned PID and cleanup evidence checked after success and injected script exit. Bash preserves 7; PowerShell returns 1. |
| `McpServerLinuxPerformanceReplayIT#nativeLinuxReplaysAndCancelsOwnedPerformanceWork[builtin/jmeter × normal/SIGTERM]` | E, existing Sidecar helper/Probe | Native Linux Java/Node/Bash; source-generated package runs unchanged. Success creates real metrics/hits/JFR. Cancellation waits for live profiler AND actual line hit, signals actual runner PID, persists interrupted blocked result, stops/downloads JFR, deactivates/stops runtime; container exits nonzero. Unique test container/image removed, including partial setup. |

The suite Skill consumers are `skills/mcp-java-dev-tools-{regression,performance,security}-suite`.
Replay consumers are `skills/mcp-java-dev-tools-{regression,performance}-export`.
Their old Tool spelling is reference context; IT calls the current Java CDE operations.
No Skill source, Sidecar source, public schema, fixture source, branch or worktree changed.

The inventory covers the current Java orchestration/export surface and its HTTP
replay formats. Security workload export and Performance/Postman are tested
unsupported selections, not skipped tests. Direct transport matrices and individual
suite provider/rule/analysis behavior remain owned by #652–#654; these tests assert
orchestration routing and the exported workload, rather than rerunning every sibling
capability test. Export packages replay workloads; they do not resume MCP suite
checkpoints or recreate the MCP orchestration service. Postman requires the receiver
to provision runtime/health prerequisites. No claim is made for untested operating
systems or a forced JVM/OS kill that prevents cleanup code from running.

The retained shell reference registry
`tools/features/execution-profile-export/adapters/registry/transport_export_adapter.registry.ts`
registers HTTP and a wrapper fallback. Its gRPC adapter files are placeholders,
not runnable transport providers. The retired Node plan-wrapper invocation is not
used as replay evidence; Java workload export rejects unsupported transport contracts.

## Isolation, prerequisites and evidence

- Reused `JavaItScenario`, `McpServerProcess`, `JvmProbeScenario`, `ArtifactMcpSupport`,
  Regression/Performance/Security support, real HTTP targets and immutable post-app.
  The two new support classes contain capability assertions and bounded replay process
  ownership, not a second launcher or generic framework.
- Every test generates accepted configuration/plans/inputs under dynamically resolved
  `test/.tmp/java-it-<run>/s-<scenario>/w-<workspace>`. Product results are generated by
  actual execution. Receiver-provisioned credentials/Compose files are inputs, not
  manufactured successful Artifacts. No personal product configuration is consulted.
- All cases have 120–240 second JUnit deadlines; replay subprocesses have a 90 second
  bound. Owned process descendants are registered/stopped. Windows relocation retries
  only transient access-denied locks, for at most five seconds, and must still move
  the actual package successfully.
- Docker tests use unique owned names/labels and remove only those containers,
  Compose projects and generated image tags. Existing services/images are not pruned.
  Base images and installed test tools are reusable prerequisites.
- `JavaItScenario` writes sanitized `evidence.json` with scenario outcome, operations,
  Java/OS, owned PIDs and cleanup disposition. Default cleanup deletes generated state
  and retains evidence. `-DmcpItRetainFiles=true` retains diagnostic files only; it never
  retains live processes. Failsafe XML/text reports identify parameter invocations.
- Missing Bash/PowerShell/Newman/JMeter/JDK/Docker prerequisites fail visibly. There
  are no assumption-based platform skips in this selection.

Tested host prerequisites: Windows 11, Corretto JDK 21.0.12.1, Maven 3.9.11,
PowerShell 7.6.5, Node.js 24.19.0, Newman 6.2.1, Apache JMeter 5.6.3 and Docker
Engine 29.8.0 and Git Bash 5.3.15. Native Linux uses
`eclipse-temurin:21-jre` and an owned test image combining
`maven:3.9.12-eclipse-temurin-21` with Node from `node:22-bookworm-slim`.
No runtime is silently substituted with a successful stub.

## Runnable selections

Run from the repository root with Maven available. Quote dotted `-D` arguments in
PowerShell. Configure real executable/tool locations for the current host; the
tests themselves contain no developer-specific paths.

```powershell
$itTools = @(
  '-DmcpItBash=<absolute Bash executable>',
  '-DmcpItPowerShell=<absolute pwsh executable>',
  '-DmcpItNewmanScript=<absolute newman/bin/newman.js>',
  '-Dmcpjvm.acceptance.jmeterHome=<absolute JMeter home>'
)
$affectedUnits = '-Dtest=ExecuteExecutionOrchestrationActionTest,ArtifactManagementOperationsTest,ExecutionProfileExportOperationRegistrationsTest,SuiteOwnerLifecycleTest'

# Complete #655 capability selection; includes expensive real replay/platform cases.
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-orchestration,java-it-jvm-fixtures' `
  $affectedUnits '-Dsurefire.failIfNoSpecifiedTests=false' @itTools

# A single class or method; keep the full capability profile and select explicitly.
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-orchestration' $affectedUnits `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dit.test=McpServerReplayIT#relocatedPackagesReplayOrderedRequestsWithRuntimeCredentials' @itTools

# Smoke only: one ordered slicing/restart case, no Docker/JMeter/Newman/fixture setup.
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-orchestration-smoke' `
  '-Dtest=ExecutionProfileExportOperationRegistrationsTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'

# Focused repair acceptance: 13 HTTP/Postman replay cases and 3 JFR replay cases.
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-orchestration' '-Dtest=ArtifactManagementOperationsTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dit.test=McpServerReplayIT,McpServerPerformanceReplayIT#failedOrEmptyProfilerDownloadBlocksRealReplay+exportedProfilerOwnsAndStopsItsRealJfrSession' @itTools

# Required native Linux replay/cancellation acceptance: four cases, Docker running.
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-orchestration' '-Dtest=ArtifactManagementOperationsTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dit.test=McpServerLinuxPerformanceReplayIT' `
  '-Dmcpjvm.acceptance.jmeterHome=<absolute JMeter home>'

# Independent affected UT; no IT profiles or fixture preparation.
mvn -B -f mcp-server/pom.xml -pl application -am test `
  $affectedUnits '-Dsurefire.failIfNoSpecifiedTests=false'
```

Use any class/method in the inventory with `-Dit.test=Class[#method]`. `mcpItNode`
and `mcpItDocker` optionally override executable names; otherwise PATH is used.
Provision Newman locally with the project's package manager and pass its CLI path.
Docker must be running and the declared base images available. The fixture profile
builds only post-app and its dependencies. When those immutable fixture JARs already
exist, omit `java-it-jvm-fixtures`; that is the command used for the capability run.
The repository-owned Sidecar agent and attach-helper JARs must also be packaged.
On a clean checkout, prepare those immutable prerequisites with
`mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package -DskipTests`
before running the capability selection. Existing packaged outputs were reused here;
no Sidecar source changes or broader Sidecar test run were required.

The broader Java IT command is `mvn -B -f mcp-server/pom.xml -pl application -am verify
-Pjava-it,java-it-fixtures,java-it-jvm-fixtures` with the relevant host prerequisites.
It is documented for separate full-IT acceptance and was **not** run for this child.
The smoke profile is never substituted for the complete capability selection.

## Verification disposition

Focused correction runs are recorded above and in ignored `test/.tmp/655-*.log`.
The latest focused portability run, `655-portability-repair2.log`, passed all 18 IT
cases and 14 affected Artifact UT, with zero failures/errors/skips and Java static
checks passing. Native Linux interruption evidence is under the dynamically created
`java-it-8614908772649189777` run; parameter observations identify provider/cancel mode.

The first complete capability run, `655-capability.log`, passed 62 IT and 37 affected
UT (zero failures/errors/skips) with static checks passing. Its 62 machine-readable
scenario records under `java-it-10217864605127034964` all report passed and successful
cleanup. The subsequent receiving-machine boundary checks added the missing-JMeter
case and exposed the script-input reload defect described above.

`655-smoke.log` passed exactly one IT and two registration UT, without platform
tool arguments or fixture profiles. Evidence: `java-it-3066888236348581849/s-1/evidence.json`.
Java resource syntax checks and `git diff --check` passed.

The final complete selection, `655-capability-final.log`, passed **63 IT and 37
affected UT**, with zero failures/errors/skips and Java Enforcer, Checkstyle and PMD
passing. Exact capability invocation: the command above with
`-Pjava-it,java-it-orchestration` (prebuilt immutable fixture reused), the four named
affected UT classes, and the documented host tool properties. Total Maven time:
11 minutes 27 seconds. No complete repository or broader Java IT suite was run.

Final machine-readable evidence:
`test/.tmp/java-it-12153126348170231230/s-<1..63>/evidence.json`.
Audit: 63 records, 63 passed, zero cleanup failures, zero retained workspaces.
The final Failsafe summary reports completed=63, errors=0, failures=0, skipped=0,
flakes=0. A read-only Docker audit found no remaining `java-it-655-*` containers or
generated image tags. Successful cleanup includes injected failures and native
Linux interruption; immutable base images/test tools remain reusable prerequisites.

The final readability-only extraction retains the same README output and test
assertions. Its module compilation, 14 affected Artifact UT and static checks passed
in `655-final-static.log`, without IT profiles or fixture setup.
That selection had no skipped or defect-blocked scenarios; its coverage gaps are
recorded below and must be assessed with the subsequent repair evidence.

## Post-implementation findings and bounded repair

The independent user-supplied findings on 2026-10-15 invalidate the earlier full
acceptance claim for these two behaviors, despite the retained 63 passing IT:

1. Postman environment generation omits profile/plan `providedContext`. The user
   reproduced a passing packaged orchestration using `${apiBaseUrl}`, followed by
   Newman `ENOTFOUND {{apibaseurl}}` against an empty exported environment. Expected:
   retain provided defaults, selected-plan overrides, explicit export overrides and
   logical-key redaction, including bindings to innocuously named environment keys.
   Owners: `ExecutionExportEnvironmentWriter.java` and `ExecutionExportPostmanRenderer.java`.
   Plan-specific values must not flatten into another plan's defaults or prevent the
   receiver from editing runtime environment inputs.
2. The standalone performance runner ignores a returned failed profiler download.
   The user reproduced `runStatus=pass` with `profiler_download_failed`, HTTP 404 and
   no JFR file. Expected: verify downloaded status and actual nonempty output;
   otherwise persist `performance_profiler_download_failed` at `profiler_download`
   and return a nonzero replay exit. Owner: Core's `run-performance-profile.js`.
   Sidecar sources and profiler endpoint contracts are unchanged.

Added focused cases before repair: four Postman context/precedence/redaction
variants, one multi-plan isolation replay, and two real-JVM JFR download faults
(HTTP 404 and empty HTTP 200). `655-review-findings-repro.log` records the local
reproduction run. The proxy forwards real Probe reset/profiling/status traffic and
faults only download; it does not manufacture successful profiler or workload state.

The correction seeds Postman environment defaults from profile and selected-plan
context, followed by explicit export overrides. Multiple plans retain separate
editable environment inputs rather than flattening overrides. Sensitive logical
binding names remain secret-classified even under neutral aliases. Artifact storage
already redacts persisted passwords: the inclusion case therefore supplies a fresh
explicit export value, rather than claiming to recover a persisted credential.
The performance runner now validates returned download status, positive byte count
and the actual file size before accepting timing evidence; empty HTTP 200 produces
no JFR file. These changes add seven cases to the available selection (70 total).

The existing extraction replay was strengthened with a conflicting next-plan
`createdId` default. Before the final renderer adjustment,
`655-context-extraction-repro.log` reproduced `/items/fallback-655` instead of the
previous response's `/items/created-655`. Response-extracted context must remain
promoted above configured defaults, as in source orchestration. The renderer owns
that interaction and must preserve it while applying per-plan environment defaults.

Applicable change-impact gates: D/G use the actual packaged CDE export path and
standalone receiving-tool replay; E is covered by the documented focused command
and generated receiver instructions. These are non-breaking corrections to existing
context precedence and download-failure semantics; no operation argument schema,
transport registration or Sidecar contract changes. The existing failure reason
and result shape are retained. No new module, routing layer or runtime owner was added.

`655-review-findings-focused.log` passed 16 IT (13 HTTP/Postman and three JFR),
14 affected Artifact UT, and Enforcer/Checkstyle/PMD, with zero failures/errors/skips.
Its evidence root `java-it-6983428279985409188` contains 16 passed records, all with
successful cleanup and no retained workspaces. After that run, the strengthened
extraction case above exposed the default-versus-promoted-value interaction.
The final renderer adjustment keeps extracted values in run-local context and
restores their precedence after selecting editable per-plan defaults.

`655-context-extraction-repair.log` then passed all 13 HTTP/Postman IT, including
the strengthened extraction case, plus 14 affected Artifact UT and Java static
checks (zero failures/errors/skips). Its `java-it-2749322771520033047` evidence root
contains 13 passed records, all with successful cleanup and no retained workspaces.
The final renderer change does not affect the performance runner; its three JFR
results above are reused. Resource `node --check` and `git diff --check` also passed.

The earlier required Linux cross-check in `655-review-findings-repair.log` was blocked:
all four Linux cases failed before container startup because the Docker Desktop
Linux engine pipe was absent. A separate `docker version` confirmed daemon
unavailability. That run created no containers/images, and its scenario cleanup
records report success. It also contained one incorrect secret-inclusion assertion,
corrected to supply an explicit export secret as described above. The full selection
was not rerun after these findings. The retained 63-case run remains historical
evidence for unaffected behavior, not a claim that all 70 current cases were rerun.

## Required Linux recheck and closure readiness

On 2026-10-15, with Docker Engine 29.8.0 running, the targeted command above for
`McpServerLinuxPerformanceReplayIT` passed all four required cases against the
current packaged runner. `test/.tmp/655-linux-final-acceptance.log` records
`BUILD SUCCESS` at 15:20:07 Asia/Manila, total Maven time 1 minute 36 seconds:

| Provider | Normal replay | Cancellation during live profiling/workload |
| --- | --- | --- |
| builtin | Passed | Passed |
| JMeter | Passed | Passed |

Failsafe reports completed=4, failures=0, errors=0, skipped=0, flakes=0. The same
module verification passed 14 affected Artifact UT and Enforcer/Checkstyle/PMD.
Successful replays verify actual request metrics, strict Probe hits and nonempty
JFR output. Cancellation verifies live profiling and a Probe hit before SIGTERM,
nonzero replay exit with `performance_workload_interrupted`, completed profiler
stop, retained JFR evidence, deactivation and owned runtime cleanup.

Machine-readable evidence:
`test/.tmp/java-it-16739898296969994335/s-<1..4>/evidence.json`.
All four records report `passed`, `cleanupSucceeded=true`, `filesRetained=false`,
and identify the provider/cancellation mode in their observations. A subsequent
read-only Docker audit returned no containers matching `name=java-it-655-` and
no image repository tags matching `^java-it-655-`. Test-owned resources are gone;
reusable base images and tool installations remain prerequisites.

This successful run supersedes the earlier Docker-unavailable Linux result and
clears the remaining verification blocker under the user's stated closure criteria.
The prior passing repair and unaffected-scenario evidence is reused. No production
or test source changed for this recheck, and no full 70-case rerun was performed.
#655 is ready for closure on this combined evidence; this record does not change
the remote GitHub issue state.
