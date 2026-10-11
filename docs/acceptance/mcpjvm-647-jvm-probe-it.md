# #647 real JVM Lifecycle and Probe acceptance

Parent #645 constrains this delivery to JVM/Probe capability acceptance and directly
required Core/Application corrections. #646's process driver, scenario ownership,
JSON-RPC support and immutable Spring fixtures are reused. Retired Node routes are
never executed. Sidecar sources and architecture are unchanged.

## Recorded reproduction before production correction

The packaged server at baseline `af86e5f57b550d48ac73c670f4dec8a4637d28b4`
fails `McpServerJvmProbeIT.concurrentAttachAndExcludedClassPreserveRuntimeOwnership`:
two confirmed `jvm_lifecycle.attach` calls for the same owned PID/start identity
produce one JSON-RPC -32603 error, "Failed to enqueue message", during concurrent
Roots requests. Evidence: gitignored `test/.tmp/647-targeted-5.log` and scenario
`test/.tmp/java-it-13136215690572587040/s-1/evidence.json`.

Expected established contract: concurrent MCP requests reach the lifecycle owner;
the Sidecar lifecycle lock returns `active` once and `already_active` thereafter.
Workspace negotiation must not turn supported calls into transport faults.
Substantive correction owner: the existing Application STDIO adapter,
`mcp-server/application/src/main/java/com/nimbly/mcpjavadevtools/server/mcp/transport/CancellationAwareStdioServerTransportProvider.java`.
The pinned MCP SDK 2.0.0 uses a unicast outbound sink; concurrent producers can
fail `tryEmitNext` before anything is enqueued. Retry only that precise SDK failure,
at most eight times with a 1 ms delay, preserving terminal errors and cancellation.
An unsuccessful enqueue has no wire effect to duplicate. Roots locking alone did
not repair concurrent responses and was discarded. Core execution, workspace
policy, public schemas/reason codes and the Sidecar lock remain unchanged.
The same packaged concurrent scenario is the regression check. This is a bounded
contract-restoring correction authorized by #645/#647.

## Harness constraints

The target and attach helper use the same scenario-owned JVM temp directory.
The Sidecar deliberately accepts lifecycle reports only beneath its own temp
directory; using different fixture/helper directories produces `agent_report_missing`.
`JAVA_TOOL_OPTIONS` is supplied explicitly to the test server and inherited by its
helper, after the existing harness clears ambient JVM options. Fixture JVM arguments
point to that same directory. Profiler/JFR repositories stay there too.

Source lines are resolved from unique snippets in immutable fixture sources,
never from developer-specific paths or stale hardcoded line numbers.
Only owned PID/start identities are attached. Fixture diagnostics retain only
an allowlisted, bounded set of instrumented class names, discarding raw output.
Cancellation observes actual HTTP status reads through a transparent loopback
observer forwarding every request to the real Sidecar; it fabricates no responses.
Repeated-cycle resource checks allow at most one Windows `conhost.exe` owned by
the target JVM, require other children to exit within three seconds and require
completed lifecycle reports to be removed. Final tree teardown includes that OS
console host. It is not a helper leak or a skipped resource assertion.

The released Probe result encodes enum reason codes in uppercase; lifecycle helper
codes remain lowercase. Assertions distinguish schema rejection (`invalid_input`)
from canonical request-owner rejection (`failed`, `operation_execution_failed`), following
the existing OperationDirectory contract. A historical hit without a new hit during
the wait window times out; it is never accepted as fresh execution evidence.

## Commands and prerequisites

Java 21, Maven 3.8.6+, local loopback connectivity and JVM Attach API access are
required. No JMeter, external services, global Skill installation or personal
product configuration is needed. Build the repository-owned Sidecar first:

```sh
mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package -DskipTests
```

Independent UT, without fixture preparation or real JVM attach:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am test
```

Complete JVM/Probe capability (all three new IT classes, including parameterized
include variants); the additional fixture profile builds only post-app and its
reactor dependencies:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it,java-it-jvm,java-it-jvm-fixtures
```

Explicit one-scenario JVM smoke, using the same real lambda-hit scenario:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it,java-it-jvm-smoke,java-it-jvm-fixtures
```

Individual scenario, reusing previously packaged fixture artifacts:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerProbeRuntimeIT#realJfrLifecycleDownloadSessionMismatchAndReset'
```

Full currently implemented Java IT lane (explicit opt-in, also requires the
existing suite prerequisites including JMeter; not executed automatically here):

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it,java-it-fixtures
```

The full fixture profile also packages post-app. Plain UT has no IT preparation
hooks; `skipTests`, `skipITs`, and `maven.test.skip` guard the fixture profile.
Missing prerequisites and empty selections fail rather than count as coverage.
Reuse unchanged packaged fixtures by omitting the fixture profile.
See [shared commands and cleanup policy](mcpjvm-646-java-it.md).

## Behavior inventory

Stable JUnit method names identify scenarios in Failsafe XML and sanitized
`test/.tmp/java-it-<run>/s-<scenario>/evidence.json`. All generated registry, JFR,
helper reports and working directories live beneath that run. Cleanup always stops
owned processes; retaining files never retains a live JVM. Evidence records actual
operation outcome/reason codes and strict keys, never complete MCP dumps or secrets.

| Scenario | Operations and exact semantic assertions | Skill contract consumers |
| --- | --- | --- |
| ownedIdentityConfirmationDuplicateAttachAndRepeatedDeactivation | list_jvms exact owned PID/start; attach/deactivate confirmation, stale/dead identities, self-attachment and public bind rejection; duplicate attach; two cycles with real strict hits; endpoint removed, fixture healthy, no descendant helpers or leftover lifecycle reports | JVM Lifecycle, Line Probe Run |
| registryStrictSingleBatchCaptureTimeoutAndRouting | probe_config upsert/read/validate/reload and real JSON equality; implicit/explicit target precedence; unknown ID; strict status/reset/wait; positive counts for both deduplicated batch members then both zero; fresh hits in two controller methods then class reset clears both while service count stays unchanged; mixed invalid entries; no-hit deadline; declaration rejection; actual capture ID, timing, counts, bounded sanitized fields and missing capture | Probe Registry Manager, Line Probe Run, Bug Drill/Failure Lens capture inputs |
| captureExecutionPathOptInHonorsConfiguredPayloadBounds | real capture reused through a separately configured packaged server; execution paths explicitly enabled and bounded to one frame/32 characters; method key bounded to 32 characters; full args/return value absent | Bug Drill/Failure Lens capture inputs |
| lambdaMappedSourceLineHasActualOwningMethodHits | reset/trigger/wait/status at lambda source line using its owning method; exact LINE_HIT, positive runtime hit count; deactivation | Line Probe Run |
| additionalPackageAndExactClassIncludeScope | attach app glob alone, sibling package glob and exact sibling FQCN; real POST triggers instrumentation; bounded class evidence distinguishes scope; strict controller hit | JVM Lifecycle |
| concurrentAttachAndExcludedClassPreserveRuntimeOwnership | simultaneous same-server attaches produce active/already_active; exact excluded service absent while controller records hits; owned fixture persists | JVM Lifecycle |
| partialAttachPortConflictCleansUpAndCanRecover | occupied loopback port fails attach deterministically; fixture healthy; retry attach instruments and deactivates | JVM Lifecycle |
| actuationConfirmationArmDisarmExpiryAndObservableBranch | confirmation prevents effect; real arm forces fixture branch and HTTP 409; disarm/TTL restore HTTP 200; non-actuatable target rejected | Probe actuation consumers |
| authenticationRejectionsAndAuthorizedCheckAreSanitized | distinct observe/actuate tokens; check endpoint-specific 401 versus 200 assertions; unauthorized status/reset require HTTP 401 and capture/actuate/profiler require unauthorized reason; existing capture, positive counts and valid actuation/profiler inputs eliminate unrelated-failure false positives; denied mutations leave counts, sessions and profiler unchanged; authorized real Sidecar controls demonstrate token separation and branch effects; secrets absent from responses | JVM Lifecycle, Probe consumers |
| realJfrLifecycleDownloadSessionMismatchAndReset | confirmed JFR start/status; duplicate start, running reset and wrong-session stop policy; real traffic; stop; parse actual JDK events; wrong download session writes nothing; successful download byte equality; reset and missing-output failure | Performance Suite profiler contract |
| profilerAutoDefaultsAndPlatformProviderPolicy | default/auto running/completed states; supported provider; real traffic; confined nonempty output parsed as JFR events; downloaded bytes equal recording | Performance Suite |
| nativeAsyncProfilerProducesOwnedRecordingOrExplicitPlatformRejection | Windows unsupported reason and absent output asserted, not skipped; supported platform requires async-profiler running/status/completed session identity, real wall sampling, parseable JFR with stack-bearing samples, identical download and reset to idle | Performance Suite |
| cancellationAndShutdownDuringWaitAllowOwnedRuntimeRecovery | observe real polling before cancellation/EOF; polling stops; cancelled response suppressed; connection recovers; independent server deactivates same fixture after shutdown; fixture remains healthy | Line Probe Run, JVM Lifecycle |
| allOperationSchemasRejectInvalidInputsAndRecover | all ten operations reject unknown fields; invalid timeouts, PID/start/port/include, conflicting reset selectors, unknown actuation/provider values; sanitized deterministic failures and subsequent tools/list | All JVM/Probe consumers |
| probeRoutingMissingRegistryAmbiguityInvalidTargetAndLineHints | missing/ambiguous registry, explicit probe, invalid URL, lineHint normalization, non-strict rejection and old-hit timeout | Probe Registry Manager, Line Probe Run |

This maps automated operation/Artifact compatibility; it does not claim actual
LLM Skill execution. Cross-feature sequences remain owned by #656. Native
async-profiler was executed on Linux amd64 as recorded below; macOS and Linux
CPU-event permissions are outside that recorded wall-sampling evidence.

## Verification

Verified on Windows 11 amd64, Amazon Corretto `21.0.12.1`, Maven `3.9.12`,
Failsafe/Surefire `3.5.4`, MCP SDK `2.0.0`, Application `0.1.9`, unchanged
Sidecar/helper `0.1.8`, and post-app `0.1.0-SNAPSHOT` (Spring Boot `3.3.5`).
The tested state is base commit `af86e5f57b550d48ac73c670f4dec8a4637d28b4`
plus this working-tree change; implementation and prerequisite SHA-256 hashes
are recorded in gitignored `test/.tmp/647-verification.json`.

The original complete capability selection passed **16 IT executions** across
14 methods (three include-scope variants), with **zero failures/errors/skips**.
All **25 affected Application UTs**, Enforcer, Checkstyle and PMD also passed.
Log: `test/.tmp/647-capability-final.log`; sanitized runtime evidence:
`test/.tmp/java-it-4979911896992785892/s-*/evidence.json`; copied Failsafe
reports: `test/.tmp/647-capability-reports`.

Exact scoped test selection used for that run (PowerShell, from repository root):

```powershell
$applicationTests647 = ((rg --files mcp-server/application/src/test |
  Where-Object { $_ -match 'Test.java$' }) |
  ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_) }) -join ','
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-jvm' "-Dtest=$applicationTests647" '-Dsurefire.failIfNoSpecifiedTests=false'
```

The scoped selection deliberately reuses unchanged Core UT evidence and already
packaged fixture prerequisites. It does not execute unrelated suite acceptance.
The initial fixture profile build is recorded in `test/.tmp/647-initial.log`.

The separate smoke profile passed exactly **one** real lambda-hit scenario,
including the post-app-only fixture build and `mcpItRetainFiles=true` teardown.
Log: `test/.tmp/647-smoke.log`; retained files and successful cleanup evidence:
`test/.tmp/java-it-17575033741392532658/s-1`. Its exact command was:

```powershell
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-jvm-smoke,java-it-jvm-fixtures' "-Dtest=$applicationTests647" '-Dsurefire.failIfNoSpecifiedTests=false' '-DmcpItRetainFiles=true'
```

Independent Application UT execution passed **25 tests** without IT or fixture
preparation hooks (`test/.tmp/647-unit-only-final.log`):

```powershell
mvn -B -f mcp-server/pom.xml -pl application -am test "-Dtest=$applicationTests647" '-Dsurefire.failIfNoSpecifiedTests=false'
```

The desktop sandbox denies loopback and normal temporary SQLite storage used by
existing UTs, so these verification commands ran with host execution permission.
The initial sandbox-denied unit attempt is retained in `647-unit-only.log`;
the unchanged independent command then passed with those permissions. No real
fixture preparation or JVM attach was added to the unit lane.

All **17 final capability/smoke scenario executions** recorded successful cleanup,
including retained-file mode. A final audit of their **54 unique owned PIDs** found
**zero live processes**. Earlier failure scenarios also recorded successful
cleanup; their failed outcomes remain visible in their original logs/evidence.

The directly affected existing lifecycle/protocol/transport scenarios also passed
15 tests; the two existing POSIX signal tests were inapplicable on Windows.
Their evidence is in `test/.tmp/647-recovery-transport.log`, selected with:

```powershell
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it "-Dtest=$applicationTests647" '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=McpServerJvmProbeIT#ownedIdentityConfirmationDuplicateAttachAndRepeatedDeactivation+partialAttachPortConflictCleansUpAndCanRecover,McpServerTransportIT,McpServerProtocolIT,McpServerLifecycleIT'
```

That earlier mixed run failed only the immediate no-child assertion because of
the owned Windows console host. The corrected resource assertion and both cycles
passed in the final complete capability run. A preliminary full run also hit a
fixture readiness deadline after a 1,887-second host delay; the normal deadline
was preserved, cleanup passed, and the same partial-attach scenario passed on
both subsequent executions. No required JVM/Probe case was skipped or converted
to a success to bypass these failures.

Gates D/G: the existing STDIO adapter restores supported concurrent execution;
real packaged CDE assertions and protocol-only stdout checks pass. Public Tool,
Resource, reason-code and data-field contracts remain unchanged; checked against
`docs/data-fields/README.md`. Core boundaries and Sidecar sources are unchanged.
Gate E: root/local guidance agrees with the executable Maven profile selections.
Actual LLM Skill execution is not claimed.

## Bounded P2 repair evidence

The user-supplied bounded review identified three blocking findings: authentication
false positives, unproven class/batch reset effects, and missing native recording
evidence. One repair batch addresses those findings in the two affected IT classes;
this batch changes no production code. Initial review count: one; independent
targeted recheck dispatched by this implementer: zero. Acceptance remains with the
user.

Authentication now uses different observe and actuate tokens. `probe.check` proves
each endpoint's allowed/denied permission separately, because one token cannot
authorize both scopes. Other CDE operations currently do not accept custom HTTP
headers; their missing-token failures assert HTTP 401 or the exact `unauthorized`
reason. Authorized real Sidecar HTTP reads and writes supply positive controls and
measure unchanged counts, capture availability, inactive actuation and idle
profiler state after denied operations. Observe cannot arm, disarm or reset;
actuate cannot read status. Correctly authorized actuation changes the actual
fixture response to 409, disarm restores 200, and reset clears the counter.

Batch reset starts with positive counts for both distinct members and verifies both
become zero. Fresh controller hits then establish two positive method counts;
class reset clears both while leaving a positive service count unchanged.

Four affected Windows scenarios have passing fresh evidence: registry/reset,
authentication, auto profiler and explicit native-platform policy. The first
targeted run (`test/.tmp/647-p2-windows.log`) passed three of four and exposed an
incorrect test field name (`detail` versus the existing capture/actuate `reason`).
That assertion was corrected and authentication passed in
`test/.tmp/647-p2-auth.log`. Both Maven executions passed **25 Application UTs**,
Enforcer, Checkstyle and PMD. Failed outcomes remain recorded; the failed
authentication attempt also cleaned up successfully. The final passing evidence
is reused per affected scenario; an unchanged full capability run was not repeated.
The current profile inventory is **17 executions across 15 methods**, including
three include variants and the separate native scenario. Unchanged cases retain
the original complete-run evidence above.

```powershell
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerJvmProbeIT#registryStrictSingleBatchCaptureTimeoutAndRouting,McpServerProbeRuntimeIT#authenticationRejectionsAndAuthorizedCheckAreSanitized+profilerAutoDefaultsAndPlatformProviderPolicy+nativeAsyncProfilerProducesOwnedRecordingOrExplicitPlatformRejection' "-Dtest=$applicationTests647" '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerProbeRuntimeIT#authenticationRejectionsAndAuthorizedCheckAreSanitized' "-Dtest=$applicationTests647" '-Dsurefire.failIfNoSpecifiedTests=false'
```

The supported-platform scenario passed **one Linux amd64 IT**, zero
failures/errors/skips, using Temurin **21.0.10** and Maven **3.9.12**. The image was
`maven:3.9.12-eclipse-temurin-21`, pinned to
`sha256:c3c9d3ac4ce8431a3995c0318b8d390f448e693dd4fabc16e9b68d2e1f3d7b46`.
An isolated repository-local copy held the compiled tests, packaged CDE/Sidecar
artifacts and immutable fixture JAR/source. Their SHA-256 identity is recorded in
`test/.tmp/647-p2-verification.json`. Docker used `--rm --network none`, no extra
privileges, a writable mount for that isolated copy and a read-only Maven artifact
cache (no personal settings or credentials). Only the selected Failsafe goals ran;
no unrelated suite or fixture rebuild ran inside the container.

```sh
mvn -o -B -f mcp-server/pom.xml -pl application -Pjava-it -Dmaven.repo.local=/maven-cache '-Dit.test=McpServerProbeRuntimeIT#nativeAsyncProfilerProducesOwnedRecordingOrExplicitPlatformRejection' -DmcpItRetainFiles=true org.apache.maven.plugins:maven-failsafe-plugin:3.5.4:integration-test org.apache.maven.plugins:maven-failsafe-plugin:3.5.4:verify
```

Log: `test/.tmp/647-p2-linux-native.log`. Under
`test/.tmp/647-linux-native-bff602f4/test/.tmp/java-it-88448193804718786/s-1`,
`evidence.json` records successful lifecycle/profiler operations and process cleanup;
`w-1/native-recording-evidence.json` records **8,366 events**, **8,297 sample
events** (at least one nonempty stack asserted) and **745,963 bytes**.
The retained `w-1/native.jfr` is parseable and byte-identical to the CDE download.
All five Windows repair attempts, including the initial failed assertion, recorded
successful cleanup; **15 unique owned PIDs** were audited with **zero live**.
All **125 copied Linux inputs** matched the current checkout byte for byte.
The owned container was removed. Gates A/D/G have direct runtime evidence for the
repaired assertions, and Gate E guidance matches the executable selections.
