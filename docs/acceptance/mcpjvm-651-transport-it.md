# #651 Real Transport Execution acceptance

This child of #645 owns packaged CDE transport behavior and its authentication
handoff. The sole retained operation is `transport_execute.execute`. The tests
launch the real application JAR, use MCP STDIO, and observe local HTTP/HTTPS
requests. Unsupported gRPC/Kafka/custom providers stay unsupported. No Sidecar
source, public schema, or Artifact format is changed. One bounded Core security
correction restores credential isolation across redirect origins.

## Production correction record (before repair)

Reproduction: `McpServerTransportCredentialIT.credentialHeadersStayAtTheIntendedOriginWhileSafeHeadersReachTheRedirect`
launches the packaged JAR and two distinct local HTTP servers. The first target
receives caller-supplied X-Api-Key/Api-Key/X-Auth-Token/X-Client-Secret and redirects
to the second origin. The pre-repair run fails because X-Api-Key reaches that
second target. Log: `test/.tmp/651-credentials-before.log` (one failure; no raw
credential is printed). The preceding 56-scenario selection passed but did not
exercise this distinct credential-header boundary.

Established expected contract: #651 requires permitted credential propagation
to the intended target; the HTTP provider already strips Authorization/cookies
across origins and classifies API-key/token/secret headers as sensitive for
redaction. All of these credentials must stay at the intended origin; safe
headers and same-origin credentials must still propagate. This is a bounded
contract-restoring correction under #645/#651, not a new provider or API.

Substantive owner: Core HTTP redirect execution. Directly affected production
path: `mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/transportexecution/protocol/http/HttpRedirectResponseExecutor.java`.
The repair reuses `HttpSensitiveDataRedactor.isSensitiveName` instead of a second,
incomplete credential-name list. No new classification/routing layer is needed.

## Contract sources and ownership

The inventory uses `TransportExecutionOperationRegistrations`,
`ExecuteTransportAction`, `HttpRequestValidator`, `HttpRedirectResponseExecutor`,
`HttpTransportSafetyPolicy`, existing transport UTs, retained
`tools/features/transport-execution/**`, and the HTTP notes in
`docs/data-fields/README.md`. #611/#644's accepted CDE surface supersedes the old
Tool envelope. Retired Node routes are not executed.

Authentication is deliberately caller-supplied on transport. Route Synthesis
owns missing-auth resolution/gating; transport does not have an independent
credential-binding lookup API. The auth scenarios exercise that actual boundary:
generated source/OpenAPI requirements, missing/incomplete auth, resolved recipe,
caller materialization of headers, authenticated target response, then an
independent anonymous request proving credentials do not persist. This is the
operation sequence consumed by Line Probe Run, Bug Drill, Bug Fix proposal,
Failure Lens, Route Synthesis, and regression/security workflows. Actual Skill
execution is a separate claim. Suite credential stores and suite execution
remain with #652/#654/#655.

HTTP request query is encoded in `request.url`; the current transport contract
does not synthesize a URL from a separate query/path/context object. Recipe
generation returns sanitized metadata rather than reusable secret values.
`pass` and `fail_http` are transport outcomes inside a `succeeded` CDE invocation.
Downstream errors, including DELETE returning 405, retain statusCode/body and
null reasonCode. Missing confirmation is an outer `confirmation_required`.
Unsupported providers are nested `blocked_invalid/transport_not_supported`.
Sensitive header keys may be redacted again by CDE: Authorization/X-Auth-Token
use `***REDACTED***`; Set-Cookie/X-Api-Key retain provider `[REDACTED]`.

## Complete capability inventory

Every row below belongs to the `transport` Failsafe tag. Scenario IDs are
`<class>.<method>`; parameterized variants are recorded as safe observation
codes and/or invocation IDs in Failsafe XML. All scenarios use the existing
`JavaItScenario`/`McpServerProcess` support. `TransportHttpTarget` records actual
request effects in memory, serves controlled responses, and stops its server
and executor on teardown. `DelayedHttpTarget` streams until client disconnect.
No request recorder, credential, or raw MCP transcript is persisted.

The supported transport scenario groups below are **covered** by the original
58-test capability selection and the affected rerun recorded below. Three
additional harness canaries protect the raw stderr assertions. There are no
required partial/missing/skipped scenarios or product exclusions in this
child's supported transport inventory.

| Scenario ID (class prefix `McpServerTransport`) | Required assertions/variants | Boundary/effects and disposition |
| --- | --- | --- |
| `HttpIT.methodsReachTheTargetWithExactPathQueryHeadersAndBody` | GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS; lowercase normalization; encoded path/repeated query; header; structured JSON body/default content type; normalized response headers/status/HEAD empty body | Actual fixture request with exact method/URI/body; covered |
| `HttpIT.downstreamStatusesKeepTransportSemanticsAndRecovery` | 200/201/204/302 without Location; 400/401/403/404/405/429/500/503; DELETE 204 and DELETE 405; pass/fail_http, statusCode, body, null reasonCode; subsequent successful GET | Actual downstream responses and two observed requests; covered |
| `HttpIT.stringArrayAndAbsentBodiesPreserveContentTypeAndDefaultOptions` | String/array/absent body, explicit content type, default/maximum timeout, absent/empty options | Actual request body/header; separate one-scenario smoke selection |
| `HttpIT.redirectsPreserveSameOriginCredentialsAndApplyMethodRules` | 301/302/303 POST becomes GET with empty body; 307/308 preserve POST/body; relative redirect; same-origin Authorization/Cookie/API-key/token/secret headers | Two actual requests, final response and redaction; covered |
| `HttpIT.crossOriginRedirectStripsCredentialsBeforeTheSecondRequest` | Cross-origin 307 preserves method/body/safe header, removes Authorization/Proxy-Authorization/Cookie/Set-Cookie | Two distinct loopback servers, exact destination headers; covered |
| `CredentialIT.credentialHeadersStayAtTheIntendedOriginWhileSafeHeadersReachTheRedirect` | X-Api-Key/Api-Key/X-Auth-Token/X-Client-Secret reach the intended target and are absent at the redirected origin; safe header retained | Actual two-origin credential regression; fails before repair, covered |
| `AuthIT.missingBindingsGateRecipesAndResolvedCredentialsReachOnlyTheTarget` | Bearer/Basic/cookie; missing fields and incomplete Basic; execution_input_required/auth_input_required; ready recipe; correct header; anonymous following request gets 401 | Generated source/OpenAPI, real CDE recipe and HTTP execution, zero requests while blocked; covered |
| `SafetyIT.discoveryValidationAndConfirmationPreventNetworkEffects` | Catalog's sole ID, Describe required fields/default and confirmation; invalid discriminator/type/missing/unknown fields/options; recovery | Real CDE discover/describe/execute; no rejected-request effect; covered |
| `SafetyIT.recognizedUnsupportedProvidersNeverContactTheHttpTarget` | grpc/kafka/custom, deterministic status/reason/nextActionCode/protocol/failedStep | Zero HTTP requests to supplied target; covered |
| `SafetyIT.wrappedOnlyUsesTheScenarioRegistryAndExplicitOverride` | Registry allowNonWrapped true/false × default/explicit true/false; wrapper_policy_violation versus HTTP pass | Generated scenario-local registry, exact received-request count, explicit override; covered |
| `SafetyIT.invalidHttpEnvelopesAreRejectedBeforeSendingAndRecover` | Missing/invalid method/URL; TRACE; relative/malformed URL, scheme/host/user-info/host allowlist; timeout zero/negative/above maximum/noninteger/string; headers shape/value/newline/duplicates/token/size/count; invalid body | Canonical nested reasons, no fixture requests before recovery; covered |
| `SafetyIT.configuredExactHostIsAllowedWithoutAllowingOtherHosts` | Noncanonical local IP spelling blocked by default, allowed only by explicit exact-host setting; unrelated host stays blocked | Actual loopback request through typed Application configuration, no external DNS/network |
| `SafetyIT.successAndHttpFailureRedactHeadersAndBodyBeforePreviewTruncation` | JSON/nested array/plain text/malformed/embedded JSON/long preview; success and HTTP 500; sensitive response headers; 2048-char bound | Actual responses, secret absent in returned data/stdout/stderr/generated files; covered |
| `LimitsIT.redirectsAreRevalidatedAndBounded` | External host, disallowed scheme, user-info, malformed Location, five-hop limit/six actual source requests | Canonical reasons, zero requests to forbidden destination, secret-safe metadata; covered |
| `LimitsIT.slowHeadersAndRedirectsShareOneDeadlineAndRecover` | Slow headers and combined redirect delay under a single timeout budget | Real source and destination requests, timeout rather than eventual success, bounded duration and independent recovery; covered |
| `LimitsIT.unavailableTargetRedactsFailureMetadataAndDoesNotPoisonTheNextRequest` | Refused loopback connection, encoded sensitive query, safe query retained, transport_request_failed | Actual connection attempt, sanitized metadata and subsequent real HTTP success; covered |
| `LimitsIT.responseBodyCeilingStopsRealWorkAndRecoveryStillWorks` | Response exceeds 4 MiB, http_response_body_too_large, no body returned | Actual oversized response/subscriber abort and recovery; covered |
| `LimitsIT.publicInputCeilingsRejectBeforeNetworkAccess` | Individual string >262144 bytes and complete arguments >1048576 bytes | Outer invalid_input/operation_input_invalid, no network effect, independent recovery; covered |
| `TlsIT.httpsUsesNormalCertificateValidationAndBlocksDowngradeBeforeHttpRequest` | Untrusted certificate rejected; scenario-local trust enables HTTPS; HTTPS→HTTP redirect blocked | Real TLS handshake and HTTPS request; zero untrusted/downgraded HTTP request; covered |
| `IT.executableJarCancelsAnInFlightTransportOperation` | MCP notifications/cancelled interrupts active response consumption; cancelled response suppressed even after draining stdout | Streaming target proves connection active before cancel and disconnected after; independent real HTTP recovery |
| `IT.executableJarEnforcesTransportTimeout` | Streaming timeout, blocked_runtime/transport_request_timeout and bounded duration | Actual request and client disconnect, independent real HTTP recovery |
| `IT.stdinClosureInterruptsAndCleansUpTheActualInFlightExchange` | STDIO disconnect during in-flight work | Server terminates and target observes disconnect; JSON-only stdout |
| `IT.cancellingOneRequestLeavesAnotherInFlightRequestConnected` | Unknown cancellation has no effect; cancelling first request preserves second; both responses suppressed | Two actual simultaneous streams; request-scoped cancellation and subsequent real HTTP recovery |
| `IT.realProviderInterruptionPreservesTheFlagAndDisconnectsTheStream` | Interrupt actual provider worker, blocked_runtime/transport_request_cancelled, interrupt flag retained, bounded worker teardown | Complementary real-boundary IT with actual HttpClient/streaming target; not claimed as public MCP proof |

`TransportScenarioRedactionIT` adds three `transport`-tagged harness canaries:
two deliberate raw stderr leaks (one concealed by diagnostic redaction, one
beyond the diagnostic capture limit) must trip the assertion; already-redacted
stderr must pass. These are harness proof, not additional HTTP behaviors.

Transport does not own persisted run/continuation state. No successful product
Artifact is manufactured. Generated prerequisite registry/source/OpenAPI/TLS
inputs are scenario-owned. For direct Core use, existing provider UTs protect
the 4 MiB request ceiling; public CDE has the smaller input ceiling tested here.
Confirmation, schemas and protocol discrimination use equivalence groups; all
material HTTP behaviors above have real packaged execution assertions.

## Commands and prerequisites

Java 21 (including `keytool`), Maven 3.8.6+, and loopback socket access are
required. Existing packaged Sidecar artifacts are required by application
packaging; transport scenarios do not attach Sidecar or launch Spring fixtures.
Use the clean-checkout build order in [shared IT support](mcpjvm-646-java-it.md).
No JMeter, external service, global Skill, user configuration, or manual runtime
preparation is required. TLS uses a new one-day self-signed fixture certificate
and a trust store beneath the scenario workspace; no production trust bypass
is introduced.

```sh
# Independent UT lane: no IT runner or fixture preparation.
mvn -B -f mcp-server/pom.xml -pl application -am test
# One real HTTP smoke scenario (four requests), not the capability selection.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-transport-smoke'
# Complete transport capability, including authentication, TLS and interruption.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-transport'
# Individual scenario selection.
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerTransportIT#cancellingOneRequestLeavesAnotherInFlightRequestConnected'
# Explicit full currently implemented IT selection; broader prerequisites apply.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-fixtures'
```

The full command remains opt-in and is not executed by this child. It discovers
all `*IT`, including every transport scenario, while capability/smoke profiles
select only their tags. For an individual `it.test` selection, omit other
capability profiles so a tag cannot unexpectedly exclude it. Missing required
JAR/keytool/setup, an empty selection, assertion/timeout/cleanup failures fail
visibly. Surefire `*Test` discovery is independent of Failsafe `*IT` discovery.
Unchanged affected UT evidence may be reused via a documented focused `-Dtest`
selection; this does not turn a capability run into smoke.

Reports: `mcp-server/application/target/failsafe-reports` (IT) and module
`target/surefire-reports` (UT). Sanitized per-scenario evidence is retained at
the dynamically resolved `test/.tmp/java-it-<run>/s-*/evidence.json`. Generated
workspaces live in its `w-*` directories and are removed by default. The shared
`-DmcpItRetainFiles=true` setting retains files, including ephemeral TLS fixture
material; live processes still stop. JUnit failures avoid printing synthetic
credentials, and shared JSON evidence excludes raw responses, environment,
exception text, command lines and credentials. Fixture server/executor closure
is asserted independently of process cleanup. `JavaItScenario` always audits
and terminates owned PIDs, including partial setup failures.

Gates: D/G use actual packaged CDE requests, canonical results and affected
Maven checks; E uses the executable commands and synchronized root/module
guidance. Sidecar, CDE schemas, normalization, confirmation, timeout and Artifact
contracts remain unchanged. Cross-origin credential stripping now covers the
existing sensitive-header classification; same-origin credentials still work.

## Verification

Tested source: this working tree atop
`d6a22048bc03779cfe61c1fbea2b39e838061e8c` on 2026-10-12. Prerequisites:
Amazon Corretto 21.0.12.1, Maven 3.9.11, existing Sidecar packaging artifacts
0.1.8 and server 0.1.9. No Spring fixture, JMeter, external-provider or personal
state prerequisite is used. The shared #646 clean-checkout UT/setup separation
evidence is reused; both new profiles only select Failsafe tags and add zero
preparation executions.

Final repaired capability command:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-transport' '-Dtest=TransportExecutionFeatureTest,HttpTransportProviderTest,TransportExecutionConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Result: **58 IT passed, zero failures/errors/skips** (57 packaged-MCP scenarios
and one complementary actual-provider interruption test). All **16 affected
UTs passed**: 15 Core and one Application. Maven Enforcer, Checkstyle and PMD
passed. The actual production correction has both pre-repair failure evidence
and post-repair success; same-origin credential retention and other headers,
redirect methods, response normalization and timeout behavior also pass.

Initial authoring failures were incorrect test expectations for JSON null
reasonCode/bodyPreview and layered redaction markers. Those assertions were
corrected to current public semantics without weakening safety. The distinct
cross-origin API-key defect was reproduced separately and repaired as recorded
above.

The final capability log is `test/.tmp/651-transport-repaired.log`; sanitized
per-scenario evidence is under
`test/.tmp/java-it-10618598425612829162/s-*/evidence.json`. The full JUnit reports
were retained at `test/.tmp/651-reports/full` before the separate smoke command
could replace the HTTP class report. These are local gitignored diagnostics.

The separate smoke command reuses unchanged UT evidence:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-transport-smoke' '-Dtest=NoAdditionalUnitTestsFor651' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Smoke result: **exactly one IT passed**, zero failures/errors/skips; no fixture
preparation or unrelated capability was selected. Log:
`test/.tmp/651-transport-smoke.log`; evidence:
`test/.tmp/java-it-7217228638800032251/s-1/evidence.json`.

Final audit covered all 215 scenario executions recorded during this delivery,
including expected baseline failures: **zero cleanup failures, zero live owned
PIDs, zero remaining generated workspace directories**. Keytool material and
all scenario-owned generated inputs were removed. Sanitized machine-readable
results, report counts, prerequisite versions, evidence references, source
hashes and cleanup outcomes are recorded at `test/.tmp/651-verification.json`.
Whitespace/path checks and an AST-based check of changed test method lengths
and parameter counts also passed. The complete repository suites were not run.

## Bounded review repair: raw stderr redaction (P2)

The user-provided bounded review identified one blocking P2:
`TransportScenario.assertSecretsAbsent` checked `McpServerProcess.stderrText()`,
which already sanitized and bounded the captured diagnostics. The original
log-redaction assertion could therefore miss credentials emitted by the server.
The reviewer identified no other blocker and validated the retained 58 IT / 16
UT results and the original 16 source hashes without rerunning tests.

The repair registers scenario secret sentinels before the process readers
start. `McpServerProcess` supplies each raw stderr line to a test-only observer
before diagnostic sanitization or truncation. `TransportScenario` retains only
a Boolean detection flag, checks it during secret assertions, and checks it
again after process termination and reader drainage. Diagnostic output remains
sanitized and bounded; raw log text is not retained or written to evidence.
All seven credential-bearing transport scenario methods register their
sentinels at startup. An assertion rejects unregistered sentinel checks.

The canaries use the real JVM's `JAVA_TOOL_OPTIONS` startup diagnostic to emit
a synthetic token. They prove both an ordinary masked leak and a leak after
17,000 padding characters are detected, including by the final close check,
without exposing the token in assertion messages. A pre-redacted diagnostic
is accepted. They reuse the packaged process driver and scenario cleanup.

Focused repair verification reuses unchanged production and UT evidence:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it' '-Dit.test=TransportScenarioRedactionIT,McpServerTransportAuthIT,McpServerTransportCredentialIT,McpServerTransportHttpIT#redirectsPreserveSameOriginCredentialsAndApplyMethodRules+crossOriginRedirectStripsCredentialsBeforeTheSecondRequest,McpServerTransportLimitsIT#redirectsAreRevalidatedAndBounded+unavailableTargetRedactsFailureMetadataAndDoesNotPoisonTheNextRequest,McpServerTransportSafetyIT#successAndHttpFailureRedactHeadersAndBodyBeforePreviewTruncation,McpServerProtocolIT#executableJarStartsInStdioModeWithoutContaminatingStdout+executableJarReportsStartupFailureWithoutLeakingConfiguration' '-Dtest=NoAdditionalUnitTestsFor651' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Result: **26 IT passed, zero failures/errors/skips**: 21 affected transport
scenarios, three harness canaries, and two shared-driver compatibility checks
(normal MCP STDIO and startup failure). Maven Enforcer, Checkstyle and PMD
passed. All 26 scenario cleanup records succeeded, with zero live owned PIDs
or remaining workspace directories. AST method/parameter checks and
`git diff --check` also passed. No production source changed in this repair;
the prior 16 affected UT results and unaffected transport evidence are reused.
The full capability selection was not rerun after the repair.

Log: `test/.tmp/651-stderr-repair.log`. Per-scenario evidence:
`test/.tmp/java-it-16815560420202495106/s-*/evidence.json`. The focused JUnit
reports are retained in `test/.tmp/651-reports/stderr-repair`. Additive machine
evidence and current source hashes are in `test/.tmp/651-stderr-repair-verification.json`;
the original `651-verification.json` is preserved as the pre-review snapshot.
This is an implementation/evidence handoff for the P2 repair; no independent
review recheck was dispatched or claimed.

