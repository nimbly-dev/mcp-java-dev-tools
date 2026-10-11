# MCPJVM-644 CDE cutover acceptance

Captured through 2026-10-11 (Asia/Manila) from the packaged Java MCP server on
Windows with Java 21.0.12.1. This record maps each exercised scenario to its
request and observed result. It contains no credentials, raw machine paths, or
ignored transcript dependency.

## Packaged MCP scenario map

| Scenario | Invocation | Observed result |
| --- | --- | --- |
| Initialize and public surface | `initialize`, `notifications/initialized`, `tools/list` | Protocol `2025-03-26`; exactly `operation_catalog`, `operation_describe`, and `operation_execute`; no Resource capability; all three Tools have output schemas. |
| Catalog | `operation_catalog({query:"jvm_lifecycle"})` | Exactly `jvm_lifecycle.list_jvms`, `jvm_lifecycle.attach`, and `jvm_lifecycle.deactivate`. |
| Catalog configuration | Start with `default-limit:2`, `maximum-limit:3`; call with no limit and then `limit:4` | Default page contains two rows; the over-limit request returns `invalid_input`, `catalog_limit_invalid`, proving Application-to-Core policy mapping. |
| Invalid catalog input | `operation_catalog({limit:0})` | `invalid_input`, `catalog_limit_invalid`. |
| Describe | `operation_describe({operationId:"jvm_lifecycle.attach"})` | Validated schema, documented arguments, and confirmation policy; no legacy alias/deprecation payload. |
| Unknown operation | `operation_execute({operationId:"unknown.operation",arguments:{}})` | `unsupported_operation`; no workspace path disclosed. |
| Malformed operation ID | `operation_execute({operationId:"invalid id",arguments:{}})` | `unsupported_operation` with null operation/result fields. |
| Artifact persistence | Project Context plus Regression, Performance, and Security plan upserts, then project read | Every outer call `succeeded`, every owner returned `ok`, and the isolated workspace contained `projects.json` plus each plan `contract.json`. |
| Direct transport | `transport_execute.execute` with HTTP GET to the fixture health endpoint | `succeeded`; transport `pass`; HTTP 200. |
| Redaction and HTTP failure | Same transport call with an invalid bearer sentinel | Transport `fail_http`; sentinel absent from the MCP payload and stderr. |
| Suite preflight | `regression_suite.preflight` for the persisted plan | `succeeded`; `ready`; reason `ok`. |
| Confirmation | Unconfirmed `regression_suite.execute_plan` | `confirmation_required`; no execution performed. |
| Suite execution and persistence | Confirmed `regression_suite.execute_plan` | `succeeded`; run status `pass`; persisted `execution.result.json` status `pass`. The real `user-app` workflow also passed an HTTP completion Watcher, external HTTP verification, and env-backed canonical SQLite verification with bound query parameters. |
| Regression Strict Line verification | Persist an unpinned Regression plan for `UserController#getUserProfile`, execute it against the attached `user-app` Sidecar | The Java suite resolved the unique source target to `UserController#getUserProfile:20` inside the bound workspace, reset it before the trigger, executed the real endpoint, and returned a verified line hit. No Strict Line Key was supplied by the test. |
| Cross-service event correlation | Launch Maven-built `event-producer-app` and `event-consumer-app`; attach a Sidecar to each JVM; execute an authenticated producer trigger with response-derived `eventId` | Producer returned HTTP 200; the consumer processed the event; the correlation timeline contained the producer trigger followed by consumer `runtime_line_hit` evidence with a key fingerprint; `correlation.json` persisted. A second run substituted a wrong consumer key and failed closed with `correlation_key_not_observed`. |
| Persisted cross-plan correlation | Execute a two-plan Regression profile in two bounded `execution_orchestration.execute` calls using one `suiteRunId`; plan 1 derives an `eventId`, and plan 2 references `${suite.correlation.packaged-cross-plan-flow.keyValue}` | First slice returned `in_progress` and persisted the resolved suite context. Resume reconstructed that context from persisted state, executed the consumer status request with the dynamic key, persisted both `correlation.json` artifacts, and returned one `ok` cross-plan summary with both contributing plans. |
| Execution orchestration | Confirmed `execution_orchestration.execute` for the persisted Regression profile with `maxPlansPerCall:1` | `succeeded`; owner status `pass`, reason `ok`; a second real fixture HTTP run was persisted. |
| Execution-profile export | Confirmed `execution_profile_export.export` for the exercised Regression profile in `ps1` mode | `succeeded`; owner status `ok`; export `manifest.json` and replay package persisted. |
| Security Suite | Confirmed `security_suite.execute_plan` for a Black-box plan targeting the fixture health endpoint | `succeeded`; owner status `completed`; run status `pass`; real HTTP baseline exercised. |
| Transport timeout | Delayed HTTP target with `timeoutMs:100` | CDE call succeeded with bounded domain result `blocked_runtime`, reason `transport_request_timeout`. |
| MCP cancellation | Start a continuously streaming transport request ID 30, prove it remains connected for 250 ms, then send `notifications/cancelled({requestId:30})` | Target observed the client disconnect within five seconds, request 31 `tools/list` succeeded, and the fully drained stdout stream contained no response 30; no unhandled-notification warning; clean shutdown. |
| Sidecar lifecycle | List JVMs, unconfirmed attach, confirmed attach, reachability check, deactivate | Real fixture JVM discovered; confirmation enforced; packaged Sidecar became reachable after attach and unreachable after deactivate; fixture remained healthy. |
| Probe | `probe.check({baseUrl:<attached-sidecar>,timeoutMs:5000})` | Outer `succeeded`; Probe status `SUCCESS`; Sidecar status endpoint returned HTTP 200. |
| Route Synthesis | `route_synthesis.class_methods` for the real fixture `UserController`, with the attached Sidecar | Outer `succeeded`; owner status `ok`; a typed method inventory was returned from fixture source inside the bounded workspace. |
| Failure Analysis | `failure_analysis.analyze_trace` with a `UserController#getUserProfile:20` trace and the attached Sidecar | Outer `succeeded`; bounded owner outcome was `ANALYZED` or `INCONCLUSIVE`, never `sidecar_failure_analysis_unavailable`; no diagnosis was claimed without reproduction. |
| Performance Suite | Persist a two-second generated-JMeter HTTP plan with required key `UserController#getUserProfile:20`, then confirmed `performance_suite.execute_plan` | Outer `succeeded`; owner status `completed`; run status `pass`; real fixture workload and attached-Sidecar Strict Line evidence exercised. |
| Protocol purity and shutdown | Close stdin after each flow | Every stdout line parsed as JSON-RPC; packaged process exited 0. |

Packaged acceptance is split by concern under
`mcp-server/application/src/test/java/com/nimbly/mcpjavadevtools/server`:

- `McpServerProtocolIT`: startup, public Tool surface, stdout purity, and signals.
- `McpServerWorkflowIT`: Artifact, HTTP transport, Regression, orchestration,
  export, and Security workflows.
- `McpServerTransportIT`: timeout and cancellation/interruption.
- `McpServerCorrelationIT`: same-plan and resumed cross-plan producer/consumer correlation.
- `McpServerJvmCapabilitiesIT`: JVM lifecycle, Probe, Route Synthesis, Failure
  Analysis, Regression unpinned Strict Line resolution, and Performance Suite.
- `McpServerStdioSupport`: shared JSON-RPC request, fixture-data, and artifact helpers.
- `McpServerProcess`: packaged-server process and STDIO protocol driver.
- `SpringFixtureTargetProcess`: real Spring Boot fixture lifecycle and Sidecar reachability.
- `DelayedHttpTarget`: bounded cancellation and timeout target.

The tests create isolated workspaces and verify the persisted files before
removing them. The Performance workflow resolves Apache JMeter from
`-Dmcpjvm.acceptance.jmeterHome`, then `JMETER_HOME`, then the repository-adjacent
`tools/apache-jmeter-5.6.3` installation used by the project acceptance environment.
Missing JMeter is a hard failure, not a skipped scenario. Maven packages the
`user-app`, `event-producer-app`, and `event-consumer-app` fixtures plus their
reactor dependencies during `pre-integration-test`; any missing output JAR
fails the build, and no fixture scenario uses an assumption-based skip. Event
correlation acceptance covers both same-plan response-derived evidence and
persisted `crossPlan:true` aggregation with dynamic key propagation across a
bounded resume.

## Real application coverage

Every main capability family is exercised through the packaged Java MCP server.
The repository-owned applications used for behavioral proof are real executable
Spring Boot JARs under `test/fixtures/spring-apps/social-platform`, not mocked
application owners:

| Application fixture | Capability evidence |
| --- | --- |
| `user-service/user-app` | Artifact persistence, HTTP transport, Regression triggers, unpinned Strict Line Probe verification, HTTP Watchers, external HTTP and SQLite verification, execution orchestration, execution-profile export, Security, JVM lifecycle, Probe, Route Synthesis, Failure Analysis, and Performance Suite. |
| `event-service/event-producer-app` | Authenticated event production, response-derived dynamic correlation keys, producer-side plan evidence. |
| `event-service/event-consumer-app` | Consumer Sidecar runtime evidence, persisted same-plan correlation, resumed cross-plan key propagation, and consumer status verification. |

Protocol startup/failure handling and transport cancellation/timeout are server
or transport mechanics rather than application-domain capabilities; those use
the packaged executable plus bounded local HTTP targets. The broader fixture
repository also contains Auth, Post, and combined Event applications, but #644
does not claim that every fixture application is launched. Its acceptance claim
is that every main MCP capability family has real packaged-server evidence.

## External repository real-application acceptance

The same packaged Java MCP was also run from the user-provided application
repository at `C:\Users\Altheo\repository\test\event-driven-microservice-example`.
The reproducible entry point is `ci/run-regression-performance.ps1`; it builds
the MCP server and both Spring Boot applications, starts repository-owned Kafka
and ZooKeeper services with Docker Compose, provisions `.mcpjvm` JSON plans,
executes the acceptance, and removes the Compose containers and network.

The final run completed 13 capability checks and persisted
`.mcpjvm/real-project-ci/acceptance/java-cde-acceptance.json`. Its substantive
application evidence includes:

- both Maven-built producer and consumer JVMs discovered and attached with
  distinct Sidecar runtime identities;
- every Probe operation, including retrieval of an actual runtime capture,
  every Route Synthesis operation, both Failure Analysis operations, HTTP
  transport, Artifact reads/lists, and all three execution-profile export
  modes exercised through the three public MCP Tools;
- Regression executing an authenticated producer request, deriving the dynamic
  `eventId`, waiting for consumer completion, verifying consumer HTTP and
  SQLite state with bound parameters, and persisting producer-to-consumer
  runtime correlation evidence;
- Performance executing a real five-second JMeter workload with runtime-only
  bearer binding, zero request failures, passing throughput/p95 thresholds, and
  a verified Strict Line hit;
- Black-box Security executing an allowed HTTP 200 baseline and real denied
  mutations (HTTP 401 and 400), rather than counting unsupported cases as the
  baseline proof;
- Sidecar-assisted Security executing an authenticated baseline, observing the
  protected controller entry, resetting the Probe, then proving an anonymous
  HTTP 401 response did not dispatch into that protected entry;
- a recursive scan of the generated `.mcpjvm/real-project-ci` artifacts finding
  no persisted copy of the runtime bearer value.

The acceptance deliberately retains `not_applicable` outcomes for generated
knowledge rules whose required path/query mutation boundary does not exist on
this endpoint. Those are explicit rule applicability decisions; they are not
used in place of the executed baseline, authentication mutation, request-body
mutation, or Sidecar-assisted denial case.

## Docker-backed application proof

The real `social-platform/user-service/user-app` fixture was packaged, mounted
read-only into a Java 21 container, and exposed on host port 18344:

```powershell
mvn -B -f test/fixtures/spring-apps/social-platform/pom.xml `
  -pl user-service/user-app -am package -DskipTests
docker pull eclipse-temurin:21-jre
docker run --rm -d --name mcpjvm-644-user-app -p 18344:8080 `
  -v "<repository>/test/fixtures/spring-apps/social-platform/user-service/user-app/target/user-app-0.1.0-SNAPSHOT.jar:/app/app.jar:ro" `
  eclipse-temurin:21-jre java -jar /app/app.jar --server.port=8080
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false `
  -Dit.test=McpServerWorkflowIT#executableJarPersistsArtifactsAndExecutesTransportAndRegressionSuite `
  -Dmcpjvm.acceptance.targetUrl=http://127.0.0.1:18344/actuator/health
docker stop mcpjvm-644-user-app
```

Observed Docker evidence:

- image: `eclipse-temurin:21-jre`
- immutable digest:
  `sha256:d7051a45dd955e4d5d1db4d3f4269fe13d1c6dff8cc6b7ef89fc8577b96c1982`
- acceptance container ID:
  `706786d63b3d98f86b73711b6c08328beb32e6caf0598ee64a2c644aebb82e58`
- `/actuator/health`: HTTP 200, `{"status":"UP"}`
- packaged MCP workflow: one test passed, zero failures/errors/skips
- container removed after the run by `docker stop` with `--rm`

## Verification

```text
mvn -B clean verify
  Core:        888 passed
  Application:  25 passed
  Packaged IT:    8 passed, 2 expected Windows POSIX-signal skips
  Checkstyle:      passed
  PMD:             passed
  Enforcer:        passed

mvn -B -pl application -am -Dit.test=McpServer*IT \
  -Dfailsafe.failIfNoSpecifiedTests=false failsafe:integration-test failsafe:verify
  Split packaged IT: 8 passed, 2 expected Windows POSIX-signal skips

mvn -B -pl application -am -DskipTests verify
  Post-split compile, Checkstyle, PMD, and Enforcer: passed

mvn -B -pl application -am -Dtest=NoSuchTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=McpServerCorrelationIT,McpServerJvmCapabilitiesIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
  Final constants cleanup (2026-10-11): 2 packaged IT passed, zero skips
  Checkstyle, PMD, and Enforcer: passed
```

This result was obtained from a clean reactor. Maven deleted the Core and
Application outputs, compiled 725 Core production sources, 76 Core test
sources, 42 Application production sources, and 20 Application test sources,
rebuilt the executable server and all
three fixture applications, and
then ran the complete packaged STDIO suite. The obsolete #610-to-#613 resolved
API and compile-time relocation guards were removed rather than restoring the
#613 snapshot resource deleted by #616; current package ownership remains
covered by `OperationDirectoryPackageBoundaryTest` and the surviving operation
contract and behavior tests.

After that clean run, the remaining fixture include patterns and synthetic
failure trace were moved to `McpServerAcceptanceConstants`. The two affected
packaged tests were recompiled and rerun on 2026-10-11 with the command above;
the production sources were unchanged. The targeted run replaces the Failsafe
summary with a two-test result, so it must not be mistaken for the earlier full
ten-test packaged run.

### Installer and SQL binding repair verification (2026-10-11)

Three reproduced defects were repaired after the full clean run above:

- The installer normalizes the server JAR to an absolute native path before
  serializing either applied Codex TOML or printed Codex/Claude/Kiro settings.
  Git Bash `/c/...` paths therefore become native `C:/...` paths.
- SQL binding retains explicit and context-derived null values in its immutable
  ordered parameter list rather than calling `List.copyOf` on nullable values.
- The SQL scanner skips quoted literals, identifiers, and comments instead of
  treating their colons as named parameters. Executable parameters remain bound
  through JDBC, and missing executable parameters still fail closed.

From `mcp-server`, the affected checks were rerun with:

```text
mvn -B -pl application -am \
  -Dtest=RegressionExternalVerificationTest,RegressionPlanExecutorTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=McpServerWorkflowIT -Dfailsafe.failIfNoSpecifiedTests=false verify
  Core: 41 passed (15 SQL verification tests + 26 Regression executor tests)
  Packaged workflow: 1 passed, zero failures/errors/skips
  Checkstyle, PMD, and Enforcer: passed

bash -n scripts/install.sh scripts/update.sh scripts/helpers/skills-sync-common.sh \
  scripts/tests/java-mcp-config-paths.sh
bash scripts/tests/java-mcp-config-paths.sh
  Syntax and temporary-config path tests: passed

./scripts/tests/java-mcp-native-config.ps1
  Native Windows Java: MCP initialize succeeded; stdin shutdown exited 0
```

Quote all Maven `-D...` arguments when invoking these commands from PowerShell.
The SQL tests execute real SQLite queries covering explicit/context nulls,
repeated parameters, literal and identifier escaping, line/block comments,
long literals, and injection-shaped bound values. The packaged workflow builds
and launches the real `user-app` JAR, upserts its Regression plan through MCP,
and executes SQL verification containing a null parameter, a `':literal'`
literal, and both block/line comments. It asserts the returned row retains null
and the literal text, and verifies persisted passing run artifacts.

The installer test checks absolute shell, absolute native, and relative paths
with spaces in all three client formats without changing real client settings.
The Windows launch test generates a Claude JSON snippet from a Git Bash path,
parses it, and starts `java` directly through the native Windows process API;
Git Bash cannot silently repair an invalid serialized argument at launch time.

The initial sandboxed packaged attempt could not establish the fixture's
loopback connection; the same command passed with local-process permissions.
These are targeted repair checks, not a new full-reactor claim. The latest
Failsafe summary now represents one workflow test. Current compilation contains
77 Core test sources; the 76-source count above belongs to the historical clean
run. No PostgreSQL live-server run was added for these SQLite reproductions.

The application `pre-integration-test` phase builds the three selected fixture
applications and all required reactor dependencies, verifies all three
executable JARs exist, and then runs packaged acceptance against those outputs.

No TypeScript file, build, or test suite is part of #644 acceptance. Legacy
TypeScript may be inspected as a migration reference, but any required behavior
must be implemented and verified through Java Core/Application tests and the
packaged Java MCP STDIO transport.

## Remaining external gate

This record proves the local implementation and runtime behavior. It does not
grant or infer the explicit breaking public Tool-removal approval required by
#611. At capture time #611 remained open without a recorded public approval;
that release/governance gate must be supplied separately before declaring the
cutover closed.
