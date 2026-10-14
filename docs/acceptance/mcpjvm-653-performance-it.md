# #653 real Performance capability integration coverage

Implementation baseline: `19f49b88ab29fef2eaf78ccc7039340fe83199b2`.
Scope: packaged Application Performance ITs and directly owning Core corrections.
The original coverage/repair work left Sidecar and existing fixture sources unchanged.
The subsequent user-authorized MSTA improvement below changes only the Sidecar JFR
profiler owner and adds an isolated test-only timing fixture. The accepted Java CDE surface
is the execution boundary; no retired Node route is executed.

## Reproductions recorded before production repairs

The first real executions are recorded in the ignored
`test/.tmp/653-second-capability.log` and its Failsafe reports.

- `realWorkloadOwnsJfrLifecycleSampledMethodAttributionAndCorrelation` returned
  `performance_profiler_start_failed`. PerformancePlanExecutor sends
  `event=jdk.ExecutionSample`; the existing Sidecar JfrProbeProfiler accepts
  `cpu` or `wall` and enables `jdk.ExecutionSample` internally. Expected:
  a real owned JFR session starts using that existing contract.
- `busyProfilerBlocksWithoutTakingOverAnotherSession` proceeded into workload
  execution and returned `performance_profiler_stop_failed`. The start response
  carries `running`, the other session ID, and `profiler_session_already_running`.
  Core checked only status. Expected: reject the unowned session before workload,
  leave it running, and never stop/download it. Sidecar stop success is
  `completed`, whereas Core expected `stopped`. Owner/path for both corrections:
  `core/feature/suite/performance/execution/PerformancePlanExecutor.java`.
- `requestBodyQueryHeadersAndRuntimeCredentialsReachTheOwnedFixture` returned
  `performance_plan_input_invalid` with a valid Project-provided `${base}` URL.
  The same owner constructs a URI before substituting runtime context. Expected:
  resolve the request/health base and path before URI construction, preserving
  existing environment-bound credentials and generated JMX secret exclusion.

Public Artifact reads intentionally redact Authorization and compact large
arrays. Tests assert those boundaries and compare scalar metrics, thresholds,
real JTL samples and persisted identities, rather than demanding raw credentials
or unbounded public responses.

The user explicitly authorized restoring retained builtin and analysis modes
within #653 on 2026-10-13. Behavioral sources are
`tools/features/performance-suite/support/parse_performance_contract.ts`,
`performance_workload_execution.ts`, `performance_request_health.ts` and
`tools/features/performance-suite/performance_msta_summary.ts`. This expands
the owning Performance implementation; it does not authorize another provider,
new production module or Sidecar changes.

`workloadDeadlineKillsTheEntireStalledSubprocessTree` reproduced two surviving
Java child PIDs after the real production subprocess deadline in
`653-fourth-capability.log`. Expected: deadline/interruption stops and waits for
the entire owned JMeter process tree before returning. Owner/path:
`core/feature/suite/performance/workload/jmeter/DefaultJmeterProcessRunner.java`.
Its existing implementation killed only the shell and did not await termination.

## Implementation ownership and contract disposition

`PerformancePlanExecutor` remains the substantive owner of preflight, Probe,
profiler coordination, threshold evaluation and result assembly. The new
`BuiltinPerformanceWorkload` has a concrete provider boundary: real concurrent
HTTP calls through the existing Transport Feature, ramp-up, a bounded sample
budget, worker termination and normalized measurements. It does not add another
operation router or Transport implementation. Provider-neutral immutable request,
measurement and analysis models live under Performance `model/**`.

`PerformanceJfrEvidenceCollector` now aggregates real anchor-to-leaf stacks,
counts dominant paths, and emits anchor/dependency correlation. It honors
explicit method targets and analysis modes. Path shares are normalized within
each anchor. The user-authorized MSTA improvement below replaces the original
TypeScript duration-scaling formula with sampled-time weights. These are inclusive
aggregate thread times, not per-invocation means. Provider metadata and source event types are
recorded from actual lifecycle/sample evidence. Disabled/not-configured/unsampled
states are explicit. Required MSTA blocks when anchor samples are absent.

Builtin is also the omitted-provider default. Artifact validation permits that
retained default. Request construction merges default/request headers, resolves
Project and plan context before URI construction, encodes query templates,
preserves JSON body types, and keeps environment-bound JMeter credentials out of
generated JMX. Exactly one HTTP entrypoint is executable; unsupported provider,
mode, protocol and malformed input remain blocked. JMeter emission flags control
retained supplementary files after the actual workload/collection completes.

The original coverage work added no production module, mocking interface,
generic test framework, Sidecar change, existing fixture source change, retired
Tool or Node invocation. The subsequent authorized timing extension is below. Existing
process/JSON-RPC/fixture/evidence support is reused through
`PerformanceMcpSupport`. `PerformanceStalledProcess` and generated failure
launchers are negative prerequisites only; they never produce successful run
state, real line hits, JFR samples or passing metrics.

## Prerequisites and runnable selections

Java 21+, Maven 3.8.6+, the packaged Sidecar/helper, packaged post-app fixture,
and Apache JMeter 5.6.3 are required. Use Maven on PATH or an explicitly selected
Maven executable. Set `JMETER_HOME` for the test prerequisite, or pass
`-Dmcpjvm.acceptance.jmeterHome=<absolute-JMeter-home>` to the commands below.
The product's own discovery contract is separately tested with
`MCP_JAVA_DEV_TOOLS_JMETER_HOME` and PATH; the test-only property does not silently
configure product discovery. A missing test prerequisite fails, never skips.

From the checkout root, clean-checkout build order and full capability selection:

```sh
mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package -DskipTests
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-performance,java-it-jvm-fixtures'
```

The fixture profile builds the existing immutable post-app sources. When these
prerequisites are unchanged and already packaged, omit `java-it-jvm-fixtures`.
The following commands reuse those packaged prerequisites:

```sh
# Independent UT: no JMeter, fixture launch, attach or expensive IT setup.
mvn -B -f mcp-server/pom.xml -pl application -am test
# Complete Performance capability: all Performance tags, never smoke-only.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-performance'
# Explicit smoke: one actual CLI workflow, thresholds, failure and fresh line hits.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-performance-smoke'
# Individually runnable class/method selection.
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerPerformanceProfilerIT#realWorkloadOwnsJfrLifecycleSampledMethodAttributionAndCorrelation'
# Explicit full currently implemented Java IT selection, with its other capability prerequisites.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-fixtures'
```

The full Java IT command is documentation, not an automatically executed suite.
It also includes sibling capability prerequisites such as PostgreSQL/Docker.
Surefire UT remains independent; Failsafe process IT is opt-in. All scenarios
have bounded JUnit/process deadlines and are selectable by their method names.
The smoke tag does not change the complete `performance` selection.

## Behavior inventory and scenario assertions

All scenario IDs below are JUnit method names under
`mcp-server/application/src/test/java/com/nimbly/mcpjavadevtools/server/`.
The abbreviated class names mean `McpServerPerformance<Class>IT`.
Every row uses the actual `java -jar` MCP process and `operation_execute`;
discovery rows additionally call Catalog/Describe. Workloads use an owned healthy
Spring post-app JVM and production attach. HTTP inspection/fault fixtures are
explicitly local controlled boundaries. Parameterized invocations record the
variant in sanitized observations. No registration-only row substitutes for
behavioral execution.

| Class / scenario ID | Variants and exact assertions | Operations / Skill consumers |
| --- | --- | --- |
| Workload / `realCliMetricsThresholdsFreshLineHitsAndPersistedResults` | Actual CLI/JTL; derive count, errors, throughput and nearest-rank p95 independently; all threshold booleans; confirmation produces no JMX; real reset/wait/status; deterministic throughput failure and HTTP 404 error-rate failure; historical hits cannot pass health-only workload; direct-run replay preserves metrics | `performance_suite.execute_plan`, Probe reset/wait/status, Artifact plan/result; Performance Suite/Result |
| Workload / `requestBodyQueryHeadersAndRuntimeCredentialsReachTheOwnedFixture` | PUT, encoded query, body/context, actual post mutation; Project env credential reaches secured fixture; generated JMX and persisted result exclude resolved token; JTL metrics and run readback | Performance, Project/Performance plan/result Artifacts; Plan Crafter/Suite/Result |
| Workload / `jmeterEvaluatesAndEncodesBoundQueryCredentialsAtRuntime` | Real JMeter through an observable HTTP proxy to the owned fixture; env-bound credentials, literal values, prefixes/suffixes and reserved/Unicode query characters arrive intact; inline query preservation/override, fresh strict hit, no resolved credential in JMX/JTL/log/results, real metrics retained | Performance / Plan Crafter/Suite |
| Workload / `applicationDiscoversRealJmeterThroughItsEnvironmentAndPathPrerequisiteContract` | Real CLI for configured home, environment home, PATH and direct executable; measured JTL and persisted metrics | Performance / JMeter prerequisite contract |
| Workload / `optionalWorkloadArtifactEmissionDoesNotDisableExecutionOrMetricCollection` | All emission flags false; real requests/metrics and passing result remain; supplementary files/refs absent after collection | Performance / Result |
| Workload / `registrySelectedProbeVerifiesEveryRequiredKeyInOrderAndNormalizesDuplicates` | Supported Probe registry upsert/reload; actual controller and service strict keys in requested order, duplicate normalization and positive counters | Performance, Probe registry / Plan Crafter/Suite |
| Builtin / `builtinAndOmittedProviderExecuteRealConcurrentRequestsAndStableThresholds` | Explicit/default builtin, concurrency/ramp-up, positive requests, real strict key, threshold pass/fail, controlled HTTP errors, no JMeter files | Performance / Suite/Result |
| Builtin / `builtinUsesDefaultHeaderOverridesJsonBodyAndEncodedQueryTemplates` | Observe real POST, header precedence/defaults, JSON types/quotes, replacement/encoding of query fields; another HTTP target cannot satisfy the fixture's strict line requirement | Performance / Plan Crafter/Suite |
| Builtin / `builtinTimeoutIsBlockedAndDoesNotLeaveWorkersOrAnOwnedProfilerRunning` | Real delayed HTTP timeout; blocked persisted outcome, no requests after workers finish, owned recording stopped/reset | Performance / Suite |
| Profiler / `realWorkloadOwnsJfrLifecycleSampledMethodAttributionAndCorrelation` | Real JMeter/JFR; parse execution samples; exact verified anchor and positive samples; sampled correlation; actual MSTA/correlation files equal result evidence; safe paths, run readback and profiler reset | Performance, Probe profiler, run_result.read / Suite/Result |
| Profiler / `retainedBuiltinAnalysisModesUseExplicitTargetsAndRealFilteredStacks` | `method_targets,jfr,cpu` and `target_plus_path,auto,wall`; explicit targets, interval, output format, package/frame filters; positive actual anchor samples, truthful percentages, builtin correlation identity | Performance / Plan Crafter/Suite/Result |
| Profiler / `profilerOnlyDisabledMstaAndUnsampledExplicitTargetsKeepHonestEvidenceStates` | Real profiler with MSTA disabled; readable JFR ref; optional unsampled target reports no_anchor_samples/unavailable and zero attributions; required MSTA blocks | Performance / Result |
| Profiler / `disabledCorrelationRetainsJfrAndRequestedMstaWithoutAttributionArtifacts` | MSTA enabled/disabled parameterizations; explicit correlation disabled even with requireMsta=true; no correlation file/ref, empty attributions and disabled persisted status; requested JFR/MSTA remain | Performance / Result |
| Timing / `repeatedWallEstimatesAllowNormalVariationAndRejectAMinuteOutlier` | Real attach, fresh strict line hits, wall profiler, builtin HTTP and persisted MSTA; one invocation per method per capture; repeated first-method sleeps 5/6/7 s within one fixed range, 10/15 s methods, CPU work against an independent timer from each run, and an actual 60 s outlier rejected by that same range | Performance, Probe/JFR, Artifacts / MSTA calibration |
| Profiler / `busyProfilerBlocksWithoutTakingOverAnotherSession` | Another actual running recording rejects Performance start before CLI; its identity is preserved; explicit cleanup stops/resets it | Performance, Probe profiler / Suite |
| Profiler / `explicitNativeProviderAndUnsupportedJfrEventsRespectTheSidecarPlatformContract` | Explicit native provider: Windows deterministic rejection; supported native platforms require actual readable recording; unsupported JFR event blocks | Performance / Suite |
| Recovery / `slicedMultiPlanExecutionResumesAfterServerRestartWithoutRepeatingTheFirstWorkload` | Real first plan, persisted in_progress/next order, packaged-server restart, second real workload, terminal pass and replay; first JTL bytes and mtime unchanged | `execution_orchestration.execute`, Performance / Suite |
| Recovery / `failedPerformancePlansRespectStopContinueAndInheritedPolicy` | stop, explicit continue, inherited continue_on_fail; exact fail/partial_fail and plan count; failed threshold preserved, second plan executes only when authorized by policy | Orchestration / Suite |
| Recovery / `subprocessFailureStopsOwnedProfilerAndPersistsSanitizedBlockedResult` | Real nonzero external process, blocked execution, stopped recording, sanitized persisted reason with no raw subprocess error/token | Performance / Suite/Result |
| Recovery / `successfulSubprocessExitCannotTurnMissingOrPartialJtlIntoPassingEvidence` | Real zero-exit negative launchers with missing/header-only JTL; results_missing/results_invalid; no fabricated passing metrics | Performance / Suite/Result |
| Recovery / `workloadDeadlineKillsTheEntireStalledSubprocessTree` | Actual deadline over a real two-JVM subprocess tree; PID/liveness proof before execution, both dead before result; blocked Artifact and stopped profiler | Performance / Suite |
| Recovery / `launcherExitStillCleansItsLivingChildBeforeReturning` | Zero/nonzero launcher exits immediately after spawning a real sleeping JVM; child dead before response, original reason codes preserved, persisted blocked result and owned profiler stopped | Performance / Suite/Result |
| Recovery / `cancelledMcpWaitRetainsBoundedNonCancellableExecutionAndRecoverableResult` | Actual running JMeter child identities before cancel; notification suppresses response; declared non-cancellable owner finishes within deadline and persists recoverable result; new workload children exit | Performance, MCP cancellation / Suite |
| Validation / `invalidLoadThresholdObservationAndContextInputsFailBeforeWorkload` | Artifact rejects zero concurrency; bounds, negative ramp, invalid thresholds, missing observation/selector/context, MSTA without profiler, unsupported provider/mode block with exact reasons; no JMX | Performance and performance_plan.upsert / Plan Crafter/Suite |
| Validation / `invalidRetainedAnalysisOptionsAreRejectedBeforeAnyWorkload` | 15 malformed toggle/provider/interval/format/mode/target/package/frame/anchor/correlation equivalence cases; exact blocked reason and no CLI | Performance / Plan Crafter/Suite |
| Validation / `unavailableTargetProbeInvalidKeyAndUnavailablePlanStayBlocked` | Target unreachable, Probe absent, malformed strict key, unavailable plan, incomplete/unsafe selector; exact owner/kernel reasons and no workload | Performance / Suite |
| Validation / `missingAndInvalidJmeterPrerequisitesAreExplicitBlockedFailures` | Isolated empty PATH and missing installation; invalid real executable; blocked missing/artifact reasons and no passing state | Performance / Suite |
| Validation / `catalogAndDescribeExposeOnlyTheCurrentPerformanceOperationAndItsSafetyContract` | Exactly execute_plan; required trusted selectors; confirmation required, cancellation false | Catalog/Describe / all Performance Skills |

Artifact read/upsert rows assert inner owner semantics and actual persisted
`execution.result.json`, not only an outer succeeded envelope. Public redaction
and compaction remain intentional. The export Skill consumes these same plan,
metric, threshold and evidence contracts; export/replay execution itself remains
owned by the export capability child. Optional line hits and future-facing
analysis hints do not replace mandatory strict line proof. Retired Node/legacy
Tool invocation shapes are intentionally retired by #611/#644; no retained
product behavior is retired by this child.

## Isolation, evidence and cleanup

The reused `JavaItScenario` dynamically resolves the checkout from Maven/test
classes, allocates `test/.tmp/java-it-<unique-run>/s-<scenario>/w-<workspace>`,
and writes sanitized per-scenario `evidence.json`. Product Artifacts are actually
created below each workspace's `.mcpjvm/artifact-it`; runtime temp/JFR and
failure-prerequisite resources remain beneath the same scenario. Dynamic ports
and PID/start fences prevent attaching to unrelated JVMs.

`-DmcpItRetainFiles=true` retains diagnostics only. Normal teardown still stops
every owned process after success, failure and partial setup, verifies liveness,
and records cleanupSucceeded. Without that flag, generated workspace files are
removed and sanitized evidence is retained. Failsafe XML holds exact assertion
outcomes. No generated workspace, raw MCP dump, secret or JFR is committed.

Native unsupported-platform assertions are real deterministic outcomes, not
skipped coverage. Native success on Linux/macOS needs its bundled provider and
is not inferred from a Windows run. Full repository/cross-capability acceptance
is a separate claim from this complete Performance selection.

## Recorded verification — 2026-10-13

Tested source is the working-tree implementation atop the baseline commit above;
no branch, worktree or commit operation was performed. Prerequisites exercised:
Windows 11, Amazon Corretto Java 21.0.12.1, Maven 3.9.12, Apache JMeter 5.6.3,
packaged server 0.1.9 and existing packaged Sidecar/post-app artifacts.

The full capability command was:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-performance' '-Dtest=Performance*Test,Jmeter*Test,ArtifactPlan*Test,ArtifactManagementOperationsTest#performancePlanRoundTripsWithPerformanceSpecificValidation' -Dsurefire.failIfNoSpecifiedTests=false -Dmcpjvm.acceptance.jmeterHome=<absolute-JMeter-home> -DmcpItRetainFiles=true
```

It passed 99 affected Core unit tests and 26 packaged-MCP scenarios, with zero
failures, errors or skips. Every scenario reported cleanupSucceeded=true; an
immediate audit found no live recorded PID. Enforcer, Checkstyle and PMD passed.
The unchanged unit evidence is reused for the final test-only additions.

From the owning `mcp-server` reactor (a different caller directory), the final
registry-selected multi-key scenario and the strengthened explicit dynamic-anchor
scenario passed with this selection:

```sh
mvn -B -f pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerPerformanceWorkloadIT#registrySelectedProbeVerifiesEveryRequiredKeyInOrderAndNormalizesDuplicates,McpServerPerformanceProfilerIT#realWorkloadOwnsJfrLifecycleSampledMethodAttributionAndCorrelation' -Dtest=PerformancePlanExecutorTest -Dsurefire.failIfNoSpecifiedTests=false -Dmcpjvm.acceptance.jmeterHome=<absolute-JMeter-home> -DmcpItRetainFiles=true
```

Those two scenarios and three focused UTs passed. Together these commands cover
all 27 distinct scenario invocations in the current complete Performance
selection; the repeated dynamic-anchor scenario is counted once. All 24 scenario
method IDs are mapped above. Static inventory checks found no undocumented method
or hardcoded developer checkout in the added tests.

The separately selected smoke command uses the same reactor command with
`-Pjava-it,java-it-performance-smoke` and without `it.test`. Its result and the
combined source/prerequisite/cleanup evidence are retained in
`test/.tmp/653-verification.json`.
That smoke profile passed exactly one real CLI scenario and three focused UTs.
The combined final audit covers 29 successful scenario executions (27 distinct
invocations plus two deliberate rechecks), all with successful cleanup and zero
live recorded PIDs.

Logs are `test/.tmp/653-final-capability.log`, `653-final-targeted.log` and
`653-final-smoke.log`. Per-scenario sanitized evidence for the complete run is
under `test/.tmp/java-it-3859560548244450971/s-*/evidence.json`; the two final
scenarios are under `test/.tmp/java-it-15390802519039742014/s-*/evidence.json`.
The JSON summary records additional smoke evidence and source/JAR fingerprints.
These generated diagnostics are gitignored.

Applicable gates D/G use actual packaged STDIO CDE, real provider/Sidecar/Artifact
effects, compatible result envelopes, documented additive defaults/options and
affected Maven verification. Gate E uses synchronized root/server/data-field
guidance, documented prerequisites and executed root/owning-reactor commands.
`git diff --check` passed. No automatic full repository suite was run.

Disposition: all required local Performance rows above are covered. Native
success on Linux/macOS is an optional platform lane not executed here; Windows
unsupported-provider behavior was exercised successfully and is not presented as
native success. The historical Node/legacy invocation rows are intentionally
retired under #611/#644. No required local row is skipped or excluded.

## Bounded review repair

The user supplied an initial independent review with three blocking findings,
backed by `test/.tmp/java-it-653-review-1dcdf70267584230b71c3385e0a2900b/review-results.txt`.
This implementation turn dispatched zero reviews or initialization retries and
does not dispatch a targeted independent recheck. Earlier dispatch counts were
not included in the supplied review; no new review budget is assumed. These
repairs form one batch for the same #653 delivery unit.

| Finding | Disposition and directly affected evidence |
| --- | --- |
| P1 launcher exits before descendants are captured | `DefaultJmeterProcessRunner` retains actual process handles while waiting, traverses direct children including new handles in the same pass, and terminates/awaits retained descendants in its existing finally path. The initial whole-tree polling repair missed one zero-exit child; direct-child traversal corrected that failure. Zero/nonzero immediate-exit MCP cases, deadline and cancellation cases passed. Forty additional alternating zero/nonzero direct-runner stress executions reported no child alive before harness cleanup. |
| P2 query encoding prevents JMeter credential evaluation | Keep the internal URL a valid encoded URI. `JmeterJmxRenderer` restores only expressions matching this workload's explicitly bound environment names and wraps them in JMeter `__urlencode`, so static query values stay encoded and evaluated credentials are encoded at runtime. A real proxy observes reserved characters/Unicode and forwards to the owned fixture for fresh strict-line evidence. Unbound encoded expressions remain literal. JMeter's supplementary URL column is disabled because it otherwise retains resolved query credentials; elapsed/success columns and metric collection remain enabled. |
| P2 correlation.enabled=false ignored | `PerformanceAnalysisOptions` preserves omitted automatic correlation and honors explicit false. The collector reports disabled/empty attributions with no file/ref, both when MSTA is collected and when disabled/not configured. Requested JFR/MSTA artifacts remain available. Explicit false makes requireMsta inactive. Default and explicit-enabled correlation scenarios passed as compatibility checks. |

The first repair command selected eight affected UTs and thirteen MCP scenario
invocations. Eight UTs and eleven scenarios passed; the immediate zero-exit
cleanup regression failed and the new query test stopped at an expected public
Artifact redaction assertion before reaching execution. Both harness teardown
paths succeeded. The five-scenario targeted command passed after correcting
direct-child tracking and using a non-sensitive query key in the readback
assertion. A final two-scenario command checks secret exclusion from JTL/logs
and ordinary metrics after disabling JMeter URL retention; it passed all eight
affected UTs and both MCP scenarios. Combined with the still-applicable earlier
passes, thirteen distinct focused MCP invocations passed. Enforcer, Checkstyle,
PMD and `git diff --check` passed. Exact commands,
results, current source/JAR fingerprints and cleanup audits are recorded in
`test/.tmp/653-review-repair-verification.json`.

Logs are `test/.tmp/653-review-repair.log`,
`653-review-repair-targeted.log` and `653-review-repair-query-secrets.log`.
The prior 99-UT/27-invocation capability evidence remains the historical baseline;
it is not represented as a rerun against the repaired JAR. Passed evidence is
reused only for unchanged behavior. No full repository suite was run.

## Original MSTA sleep calibration assessment (before improvement)

This section records the evidence that motivated the subsequent authorized change.

The user's suggested calibration was exercised with real sequential methods
sleeping for 5, 10 and 15 seconds. A separate one-second CPU phase establishes
that execution sampling is active; the configured diagnostic window is 31 seconds.
The existing collector received the actual recording. Independent monotonic
timers and explicitly enabled `jdk.ThreadSleep` duration events supply ground
truth; those duration events are not consumed by the current MSTA collector.
The supplied method selectors in this direct collector experiment are diagnostic
inputs, not a claim of verified Probe line hits or another MCP acceptance scenario.

| Method | Expected sleep | Measured elapsed | Previous MSTA observation |
| --- | --- | --- | --- |
| methodOne | 5 s | 5.0079652 s | No anchor sample |
| methodTwo | 10 s | 10.0101996 s | One incidental sample; estimatedTimeMs=31000 |
| methodThree | 15 s | 15.0113949 s | No anchor sample |

This is useful negative calibration evidence, not a successful accuracy test.
The previous formula was `anchor/path sample share × configured workload duration`;
the same formula appears in TypeScript `performance_msta_summary.ts`. It cannot
predict method elapsed durations reliably. An anchor represented by one sample
can receive the entire workload window. CPU samples also generally omit sleeping
threads, as described in [Oracle's JFR guidance](https://docs.oracle.com/en/java/javase/21/troubleshoot/troubleshoot-performance-issues-using-jfr.html).
The previous Sidecar JFR provider enabled ExecutionSample/NativeMethodSample
for both accepted cpu/wall intents; accepting wall intent does not establish
genuine wall-clock sampling accuracy.

Keep the calibration concept for a separately specified timing change: define
inclusive versus exclusive time, per-invocation versus aggregate time, elapsed
versus CPU time, then use actual wall-clock samples or duration instrumentation
with repeated fixtures and an explicit error tolerance. Sequential 5/10/15-second
methods total about 30 seconds; concurrent or nested calls need a different
comparison. A CPU busy-work fixture is appropriate for CPU sampling. The initial
bounded review repair did not alter the timing formula or Sidecar provider.

Temporary source, actual JFR, independent durations and collector output are under
`test/.tmp/653-msta-calibration/`; `calibration-results.json` records 20 execution
samples, three ThreadSleep events and the values above. The calibration JVM exited.

## User-authorized MSTA approximation improvement

On 2026-10-14 the user authorized improving the estimates and a range-based
calibration that tolerates ordinary 5/6/7-second variation but rejects a minute.
The user also required accounting for different CPU speed/resources. This is an
explicit extension of the earlier retained-behavior repair; the historical
TypeScript formula is superseded for this authorized timing correction. No
independent reviewer or review initialization retry was dispatched by the implementer.

The Sidecar JFR owner adds bounded platform-thread snapshots through JFR's
periodic-event callback. It creates no additional polling executor or target
method instrumentation. Its hook is removed on stop/start failure. Actual
monotonic gaps weight each prior stack snapshot, rather than assuming the scheduler
hit every requested tick. Session-tagged samples reference a bounded stack
dictionary; repeated full strings had exceeded the existing 1 MiB download
default in the first MCP execution. Dictionary definitions are cached and
gzip/base64 encoded once; even 122 distinct application stacks had consumed
507,215 raw bytes and reached the original dictionary ceiling. Compression
preserves bounded storage without retaining repeated strings in each sample.
The minimum wall-sampling interval is 100 ms for this approximate mode.
A necessary Probe HTTP-client correction preserves binary bodies as raw bytes:
UTF-8 decoding had inflated some otherwise valid responses beyond the text-size
limit. A loopback regression covers binary bytes exactly at the limit, alongside
unchanged JSON/oversize tests. Probe download bounds remain unchanged.

The sampler is configured before recording start and activated afterward,
preventing callbacks from referencing definitions before dictionary events are
enabled. The reader loads definitions before aggregating samples; a regression
deliberately places a sample before its definition. Conflicting/missing definitions,
incomplete session status and oversized decompression fail closed. Both raw
cache memory and compressed/decoded sizes have explicit ceilings.

`PerformanceJfrSamplingReader` owns the stateful serialized JFR/dictionary
boundary and limits, with one instance per capture; Core imports no Sidecar
implementation class. `PerformanceJfrEvidenceCollector` retains analysis,
correlation and Artifact ownership. Immutable sample weights live in Performance
`model/analysis`. MSTA defaults to wall timing when enabled; profiler-only default
CPU and explicit CPU intent remain supported. Approximate times are aggregate
inclusive thread time. Multiple calls/parallel threads accumulate and nested
methods overlap; the one-invocation calibration isolates method elapsed time.
Sparse samples cannot receive the full configured workload window. Missing
requested methods report partial coverage and required MSTA fails closed.

The test-only fixture is packaged into an owned temporary JAR using existing
process/MCP/cleanup support. It deliberately uses platform threads. Independent
monotonic timers are returned separately from the sampling capture and never
fed into MSTA's estimator. The fixed first-method range is 2.5–10 seconds for
all three 5/6/7-second runs. Method Two accepts 5–15 seconds and Method Three
7.5–20 seconds. These tolerances are explicit test constants: they can be changed
deliberately, but are not widened automatically after a failure. Missing estimates
or out-of-range estimates fail the positive calibration assertion.

A fourth capture really sleeps for 60 seconds. The test asserts a large MSTA
estimate and verifies that the same 2.5–10-second check throws an assertion error.
Its calibrationResult is `fail_out_of_range`; the negative test itself passes
when that rejection occurs. These checks are calibration acceptance assertions,
not a newly added production method-SLO contract. Existing workload/threshold
result semantics remain intact.

Fixed CPU work is compared with an independent elapsed timer from the same run
(50% of measured elapsed through measured elapsed + 5 s). Faster/slower CPU
execution and JIT/scheduling effects therefore alter the reference naturally;
no MHz/core-count speed prediction is hardcoded. JVM-visible CPU count and
sampling warm-up are recorded as context. This local verification does not claim
execution on several physical CPU models. Virtual-thread wall timing and exact
per-invocation/exclusive timing are outside this bounded change.

Limits and serialized fields are documented in `docs/data-fields/README.md`.
Actual calibration samples, independent times, range outcomes, source/JAR
fingerprints and cleanup audits are retained under `test/.tmp`; verification
results are recorded below after the affected commands complete.

### Verified timing outcomes — 2026-10-14

The final full calibration invokes each method once per capture and uses the
same fixed ranges across repeated runs. Measured MSTA values are approximate:

| First-method sleep | MSTA first-method estimate | Range outcome | MSTA CPU-work estimate / independent elapsed |
| --- | --- | --- | --- |
| 5 s | 4.966 s | pass (2.5–10 s) | 8.333 s / 8.385 s |
| 6 s | 6.026 s | pass (2.5–10 s) | 9.627 s / 9.663 s |
| 7 s | 6.968 s | pass (2.5–10 s) | 9.333 s / 9.340 s |
| 60 s | 59.964 s | fail_out_of_range under the same check | CPU work omitted in the negative capture |

Second/third-method estimates stayed within their declared ranges (roughly
9.95–10.08 s and 14.97–15.01 s). All captures reported complete platform-thread
sampling and 24 JVM-visible logical processors. The independent CPU durations
vary; the range comparison follows each run's actual hardware/scheduling/JIT
behavior without a hardcoded processor-speed prediction.

The owning Sidecar reactor command passed seven affected tests and packaged
the affected dependency chain:

```sh
mvn -B -f java-agent/pom.xml -pl core/core-probe -am verify -Dtest=JfrProbeProfilerTest,ProbeProfilerRegistryTest -Dsurefire.failIfNoSpecifiedTests=false
```

The final calibration/default-wall-profile verification passed thirteen Core
tests and both selected packaged-MCP invocations:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dtest=PerformanceJfrEvidenceCollectorTest,PerformancePlanExecutorTest,HttpProbeEndpointClientTest' -Dsurefire.failIfNoSpecifiedTests=false '-Dit.test=McpServerPerformanceTimingIT,McpServerPerformanceProfilerIT#realWorkloadOwnsJfrLifecycleSampledMethodAttributionAndCorrelation' -Dmcpjvm.acceptance.jmeterHome=<absolute-JMeter-home> -DmcpItRetainFiles=true
```

An earlier affected selection passed all six profiler/analysis invocations.
Default-wall profiling was rechecked above; the five other passed cases are
reused for unchanged behavior. Busy-session ownership, Windows native rejection
and malformed-analysis validation also reuse their unchanged successful evidence.
Together these cover ten distinct affected MCP invocations. The final model-only
finite/non-negative weight invariant was verified and repackaged with the same
thirteen Core tests and `-DskipITs`; it preserves the verified valid sample inputs.
No broad repository suite or independent-review dispatch was run.

Logs: `test/.tmp/653-msta-agent-compressed.log` (7 UT),
`653-msta-calibration-final.log` (six profiler cases and completed calibration
assertions followed by a corrected test-only teardown-schema failure),
`653-msta-final-confirmation.log` (13 UT + 2 IT, BUILD SUCCESS) and
`653-msta-package-final.log` (13 UT + final packaging/static checks, BUILD SUCCESS).
Earlier failure logs retain the initial full-string/download-size reproduction,
binary UTF-8 inflation, dictionary/setup corrections and harness-input errors.
Final calibration JSON is under
`test/.tmp/java-it-18396897657361973691/s-2/w-1/msta-calibration.json`.
`test/.tmp/653-msta-verification.json` records exact commands, selected evidence,
source/server/Sidecar fingerprints and cleanup/liveness checks.

Applicable gates A/D/G use real owned-JVM attach/strict hits, profiler lifecycle,
MCP envelopes, persisted Artifacts, bounded binary transfer and affected reactor
verification. The deactivated timing fixture remained alive and healthy before
normal process teardown. Gate E uses synchronized execution/limits documentation.
Enforcer, Checkstyle, PMD and `git diff --check` passed. Local Windows evidence
does not establish multi-host CPU benchmarking or Linux/macOS native success.
