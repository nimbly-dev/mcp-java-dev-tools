# #649 Failure Analysis and investigation acceptance

Baseline: `669a939d545076350f088d9f0b49db394db313c4`.
This child reuses #646/#647 process, workspace, JSON-RPC, real fixture,
attach/deactivate, strict Probe and cleanup support, and #648 source selection.
Only packaged Java CDE routes are exercised. Sidecar and immutable fixture sources
are unchanged.

## Reproduction recorded before production repair

`test/.tmp/649-reproduction.log` and
`test/.tmp/java-it-4636503812161459810/s-4/evidence.json` record the packaged
`streamingBodyTimeoutClosesConnectionAndRecovers` failure. A real loopback HTTP
server sends headers and slowly streams its body. Failure Analysis requests
`timeoutMs=1000`, but no MCP response arrives within eight seconds. Both analyze
and verify use the same HTTP client. Existing header-timeout UTs pass.

Expected established contract: Failure Analysis's bounded endpoint timeout applies
to the complete request, including body consumption, retaining the response byte
ceiling, sanitized blocked outcome, cancellation and recovery. Owner and directly
affected production path:
`mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/failureanalysis/endpoint/HttpFailureEvidenceClient.java`.
This is a contract-restoring correction authorized by #645/#649; it adds no new
operation, provider, public schema, configuration or Sidecar behavior.

The same run records `catalogDescribeAndEveryTerminalOutcomeCleanupAndMode`
returning `CLEANUP_CONFIRMED` rather than `cleanup_confirmed`. The established
contract in `docs/data-fields/README.md`, the enum's own `value()`, and retained
`tools/features/failure-analysis/actions/verify_reproduction.action.ts` uses the
lowercase values. Owner/path: Core Failure Analysis
`model/result/FailureCleanupStatus.java`. Jackson must serialize its existing
wire value rather than the Java enum name. The packaged assertions retain the
lowercase contract for all three statuses and both investigation modes.

`test/.tmp/649-verification-2.log` then reproduces credential text echoed through
caller-supplied terminal `reasonCode` and a missing `captureId`. The expected
established contract is redacted investigation output, including inconclusive
results. Owners/paths: Core Failure Analysis `model/result/FailureAnalysisResult.java`
and `model/result/FailureAttemptEvidence.java`. Sanitize these output facts without
changing the values used for Sidecar lookup or fingerprint comparison. Harmless
reason codes and capture identifiers remain unchanged.

`test/.tmp/649-redaction-reproduction.log` also reproduces credentials echoed
inside `expectedFingerprint` after a real capture mismatch. Output owner/path:
`model/result/FailureVerificationDetails.java`. Redact the expected fingerprint
only when constructing output details, after comparison; the request and actual
Sidecar comparison inputs are unchanged. Apply terminal reason redaction only to
caller-supplied terminal facts so long built-in reason codes remain intact.

## Bounded review repairs

P2-1 follow-up: the user recheck reproduced synthetic `ghp_` + 40 lowercase
letters and `sk-proj-` + 48 lowercase letters leaking through the semantic-code
exemption. The ordinary diagnostic redactor masks both. Recognizable credential
formats must take precedence over semantic separators. This repair is limited to
the same redaction owner and handler/packaged terminal regression cases; it also
covers an embedded credential and a credential split into shorter segments.
No independent review cycle is launched by the implementer.
`test/.tmp/649-token-reproduction.log` records the handler regression failing
before this repair. The exclusion recognizes credential prefixes before applying
the semantic-code exemption; an alphanumeric boundary avoids treating the `sk-`
substring in an ordinary word such as `task-cancelled` as a credential prefix.

P2-1: review reproduced the valid caller terminal reason
`operator_cancelled_after_confirming_missing_trigger` becoming `<redacted>`.
The generic opaque-token heuristic incorrectly treats semantic routing codes as
credentials. Expected contract: preserve bounded semantic reason codes while
redacting credential assignments, authorization values and opaque long tokens.
Owners/paths: Core Failure Analysis `redaction/FailureAnalysisRedactor.java` and
`model/result/FailureAnalysisResult.java`. Regression coverage exercises the
handler and packaged MCP terminal output, including a 120-character code.
`test/.tmp/649-p2-reproduction.log` records the new handler regression failing
before the production repair, with the exact safe reason replaced by `<redacted>`.

P2-2: the original local proposal-consumer input classified an unknown expected
behavior as `proposed`. This violates the repository Bug Fix Skill's three required
inputs. The repaired scenario must supply explicit expected behavior as a
controlled test input, issue context and ordered reproduction, and assert those
inputs before counting proposed evidence. Proposal and Issue Report consumers
must share that supplied expectation. Missing expected behavior still requires
`blocked` / `bug_fix_expected_behavior_required` under the Skill; these tests do
not implement or claim execution of an automated Skill classifier.

## Complete supported behavior inventory

All scenario IDs below are JUnit method names, prefixed by the named class.
The full `failure-analysis` selection includes every row, not just smoke.
All supported rows passed in the complete capability run recorded below.

| Class / scenario ID | Operations and exact semantic assertions | Consumer |
| --- | --- | --- |
| McpServerFailureAnalysisIT.realTraceTargetTriggerCaptureAndProposalEvidence | Both Failure Analysis operations; actual untruncated JFR exception stack, complete type/root/method/source-line/descriptor fingerprint, bounded candidates, no analysis diagnosis; infer_target and create_recipe resolve one target and DELETE route; reset, transparent observed fresh wait, one actual transport trigger returning 500, capture readback and matching reproduction with the same strict key/count/capture; explicit scenario issue context/expected behavior/ordered reproduction asserted before proposed consumer evidence, bounded non-applied change and validation inputs; both guided/hands_off contexts; reset to zero, deactivation and fixture survival | Failure Lens, Bug Drill, Bug Fix proposal, Issue Report |
| McpServerFailureAnalysisIT.actualCaptureMismatchNoExceptionMissingCaptureAndIncompleteTrace | Both operations, omitted investigation and timeout defaults; three independent fingerprint mismatch reasons; real successful-method capture has no exception; missing capture; malformed, absent-line and elided pasted traces stay inconclusive; redaction of supplied expected fingerprint/capture identifiers; every negative result has no diagnosis | Failure Lens, Bug Drill |
| McpServerFailureAnalysisIT.pastedCausalSectionsAndConfiguredEvidenceBounds | analyze_trace causal/suppressed parser variants use actual JFR frames as pasted inputs, with exact root/section assertions (not claimed as new observed exception chains); configured frame/section/string, trace and response ceilings against the real Sidecar; no new trigger produces WAIT_TIMEOUT and zero strict hits | Failure Lens / bounded evidence |
| McpServerFailureAnalysisValidationIT.catalogDescribeAndEveryTerminalOutcomeCleanupAndMode | Exactly two catalog IDs, schemas and no confirmation requirement; all eight terminal outcomes x three cleanup values x two modes, exact preserved outcome/reason/context/attempt count, no observed fingerprint or diagnosis; terminal calls work with no Sidecar configured | Failure Lens terminal facts |
| McpServerFailureAnalysisValidationIT.invalidSchemasBoundsAndMutuallyExclusiveVariantsNeverClaimDiagnosis | Required fields for both runtime operations and terminal variant; trace/timeout/auth/capture/fingerprint/positive-hit bounds, investigation modes and attempt/time bounds, terminal outcome/cleanup/attempt/reason bounds, unexpected fields and mixed runtime/terminal variants; deterministic invalid_input, no credential echo or diagnosis | All consumers / Java-owned input contract |
| McpServerFailureAnalysisValidationIT.terminalReasonAndMissingCaptureDiagnosticsRedactCredentials | Caller-supplied terminal reason cannot echo credential text through MCP result or stderr; long underscore/hyphen semantic codes and 120-character reason preserved exactly; credentials appended to semantic codes and opaque long tokens remain redacted | Failure Lens / safety |
| McpServerFailureAnalysisRuntimeIT.realAuthenticationRedactionUnavailableRuntimeAndRecovery | Both operations reject absent/wrong observe credentials with HTTP 401 and blocked reason, accept supplied authorization against a real protected Sidecar, never echo auth; exception payload redacted in response and persisted test evidence; refused connection and unsafe endpoints remain blocked; real subsequent analysis recovers; stderr/stdout contain no sentinels | Failure Lens / authentication and safety |
| McpServerFailureAnalysisRuntimeIT.streamingBodyTimeoutClosesConnectionAndRecovers | Both operations independently enforce a one-second timeout after actual HTTP headers, close the connection and accept a subsequent terminal CDE request | All consumers / timeout |
| McpServerFailureAnalysisRuntimeIT.cancellationClosesReadHasNoLateResponseAndRecovers | Both operations independently close an actual in-flight HTTP body on MCP cancellation, recover, terminate on stdin close and emit no late cancelled response | All consumers / cancellation |
| McpServerFailureAnalysisRuntimeIT.malformedRejectedAndOversizedHttpEvidenceNeverClaimsDiagnosis | Both operations x empty/malformed/array/missing/oversized/403/500/redirect HTTP evidence; exact blocked/inconclusive reasons, redaction, no redirect followed and subsequent recovery. These local fault endpoints never return fabricated successful captures | All consumers / fail-closed evidence |

The two modes in the first row are the explicitly tagged `failure-smoke` subset.
Both timeouts and cancellations are parameterized by operation. Terminal and
fault equivalence groups exercise each concrete variant above through real MCP;
catalog presence is not their behavioral proof. Sidecar bytecode, JFR engines,
actuation arm/disarm/expiry and Probe selector variants retain their separately
owned #647 evidence; this child does not claim a second complete Probe suite.

## Skill contracts and dependency dispositions

Read repository-owned instructions, not a global installed Skill:

* [Failure Lens](../../skills/mcp-java-dev-tools-failure-lens/SKILL.md): analysis,
  target selection, reset/trigger/fresh hit/capture/comparison and cleanup map to
  the real-trace scenarios; all terminal outcomes map to the validation matrix.
  Attempt/time authorization remains caller-owned context, not a server retry
  engine. No analysis-only diagnosis or automatic Bug Fix handoff is asserted.
* [Bug Drill](../../skills/mcp-java-dev-tools-bug-drill/SKILL.md): bounded target
  localization, supported trigger, strict reset/wait/status/capture and negative
  no-hit classification map to the real-trace/bounds scenarios. Optional
  actuation retains #647 evidence. These are operation/evidence assertions, not
  execution of a conversational agent or a new drill runner.
* [Bug Fix](../../skills/mcp-java-dev-tools-bug-fix/SKILL.md): the actual
  source/route/trigger/hit/capture evidence yields a local proposal-consumer input.
  The controlled scenario supplies issue context, explicit expected behavior
  (deliberate HTTP 405 for the rejected DELETE instead of HTTP 500), and all four
  ordered reproduction steps; assertions check these three required inputs before
  recording `proposed`. A bounded non-applied change, compatibility/rollback,
  focused validation and unproven downstream behavior accompany the evidence.
  Original fixture source is compared before/after, `applied=false`, no fix is
  applied, and no product Artifact is created. The proposal text is test evidence,
  not a claimed automated diagnosis or source repair by the Skill.
* [Issue Report](../../skills/mcp-java-dev-tools-issue-report/SKILL.md), its
  [template](../../skills/mcp-java-dev-tools-issue-report/references/output-template.md)
  and [sanitization rules](../../skills/mcp-java-dev-tools-issue-report/references/sanitization-rules.md):
  locally rendered report uses real status, exception and strict-hit facts, retains
  DELETE/path/sequence and the same supplied expected behavior; impact remains
  explicitly unknown. It aliases package/host identifiers and contains no
  credentials. No issue is published.

Explicit unsupported dependencies: current Probe CDE status/capture/reset/wait
schemas have no authorization-header input, and their retained TS counterparts
also expose headers only for `probe.check`. An observe-protected Probe Skill
workflow cannot complete those calls through the current CDE contract. The auth
scenario asserts the actual `probe.status` rejection, obtains real protected
status evidence through the existing Sidecar HTTP endpoint as test setup, and
then tests **Failure Analysis's supported authorization field** through MCP.
It does not claim an end-to-end protected Probe Skill workflow. A Skill must stop
with a missing-auth/runtime-evidence terminal result when required evidence is
unavailable. Adding authenticated Probe actions would require separate supported
contract scope; no new input fields or proxy workaround are added here.

Historical Skill prose using Tool/action notation is mapped to canonical
`failure_analysis.*`, `route_synthesis.*`, `probe.*`, `jvm_lifecycle.*` and
`transport_execute.execute` IDs; old Node/feature-specific Tool routes are
intentionally retired by #611/#644. Human authorization, LLM proposal quality,
and actual conversational Skill use remain separately evaluated claims.
No missing supported Failure Analysis operation is silently marked covered.

## Workspace, evidence and cleanup

JARs and immutable source roots resolve from the build/module location via the
existing support, independently of shell cwd. All scenario writes use
`test/.tmp/java-it-<run>/s-<scenario>/w-<workspace>`, including fixture/JVM working
directories, temporary JFR settings and repository files. Ports and owned process
identities are allocated at runtime. No user-global configuration or personal
state is read. JFR records are streamed/read locally and deleted even with file
retention enabled. Raw traces/captures/credentials are not committed or saved in
the compact machine-readable evidence.

Failure Lens and Bug Fix do not own persisted product investigation Artifacts;
tests assert `.mcpjvm` remains absent. Local proposal inputs, sanitized response
snapshots and issue reports are test evidence only. Default cleanup removes
workspaces and always stops owned trees, including on setup/assertion/timeout
failure; `evidence.json` remains beside each workspace. `mcpItRetainFiles=true`
retains safe scenario files but never live JVMs or raw JFR. Each summary records
scenario ID, operation outcomes, runtime/OS, owned PIDs and cleanup outcome.
Failsafe XML records assertions, counts and parameterized invocation names.

## Commands and prerequisites

Full Java 21 JDK with JFR/jcmd, Maven 3.8.6+, loopback HTTP and owned-JVM Attach
permission are required. No JMeter, external provider, global Skill, manual LLM
session or credentials outside controlled test inputs are required.
Commands below start at the repository root; profiles are quoted for PowerShell.

Clean-checkout prerequisites (reuse these JARs until their sources change):

```sh
mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package -DskipTests
mvn -B -f mcp-server/pom.xml -pl application -am package -DskipTests
```

Independent affected UT (no fixture packaging, process IT setup or Sidecar):

```sh
mvn -B -f mcp-server/pom.xml -pl application -am test '-Dtest=FailureAnalysisActionHandlerTest,HttpFailureEvidenceClientTest,RouteFailureOperationRegistrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

Explicit smoke and complete supported capability, respectively:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-failure-smoke,java-it-jvm-fixtures'
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-failure,java-it-jvm-fixtures'
```

One runnable scenario, and reuse of prebuilt fixtures for the full capability:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-failure,java-it-jvm-fixtures' '-Dit.test=McpServerFailureAnalysisIT#actualCaptureMismatchNoExceptionMissingCaptureAndIncompleteTrace'
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-failure'
```

Without `java-it`, Surefire *Test remains separate from Failsafe *IT. Expensive
fixture packaging requires `java-it-jvm-fixtures`; UT never activates it. Missing
JAR/JDK prerequisites and empty explicit selections fail visibly. Existing #646
clean-source/relocation and setup-failure/cleanup evidence is reused for unchanged
support. For the complete repository Java IT lane, prerequisites and command are
in [#646](mcpjvm-646-java-it.md); it includes these new *IT classes automatically,
requires the other capabilities' tooling, and is not automatically run here.
These selections are suitable as separate UT, smoke, capability and full CI lanes.

## Recorded verification

The following initial results predate the bounded review repairs. The separate
smoke-profile selection and UT-only setup-isolation evidence remain applicable;
changed terminal/proposal behavior requires the post-review run below.

Verified on 2026-10-12 with Amazon Corretto 21.0.12.1, Maven 3.9.11 and Windows 11.
Prebuilt Sidecar/helper and fixture JARs were reused; the application was packaged
by the verification lifecycle. Loopback HTTP and owned-JVM Attach required the
host's expanded execution permission. No full repository suite was run.

| Lane | Result | Local evidence |
| --- | --- | --- |
| Complete supported capability | 13 IT invocations; zero failures, errors, skips or flakes; 60 focused UT passed | `test/.tmp/649-capability-final.log`, `test/.tmp/649-capability-summary.xml`, `test/.tmp/java-it-9016650098848922350/s-*/evidence.json` |
| Explicit smoke | Exactly two IT invocations (guided and hands_off); zero failures, errors, skips or flakes; 11 focused UT passed | `test/.tmp/649-smoke-final.log`, `test/.tmp/java-it-3232796836206895121/s-*/evidence.json` |
| Independent UT | 60 focused UT passed with deliberately unavailable fixture builder, Sidecar/helper and JMeter paths; no Failsafe or fixture-packaging execution | `test/.tmp/649-unit-only-final.log` |
| Required static checks | Core and Application Enforcer, Checkstyle and PMD passed in both verify runs; `git diff --check` passed | Capability/smoke logs |

The complete capability command ran from `mcp-server`; smoke and UT ran from
the repository root, confirming both supported caller contexts:

```sh
mvn -B -pl application -am verify '-Pjava-it,java-it-failure' '-Dtest=FailureAnalysisActionHandlerTest,HttpFailureEvidenceClientTest,RouteFailureOperationRegistrationTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-failure-smoke' -Dtest=FailureAnalysisActionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -f mcp-server/pom.xml -pl application -am test '-Dtest=FailureAnalysisActionHandlerTest,HttpFailureEvidenceClientTest,RouteFailureOperationRegistrationTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmcp.fixture.maven.executable=missing-649-fixture-builder -Dmcp.sidecar.agent.source=missing-649-sidecar.jar -Dmcp.sidecar.helper.source=missing-649-helper.jar -Dmcpjvm.acceptance.jmeterHome=missing-649-jmeter
```

Cleanup audit: all 13 complete-capability and both smoke scenario summaries
report `passed` and `cleanupSucceeded=true`; no owned process or `w-*` workspace
remains. `test/.tmp/649-verification.json` records the baseline, commands,
artifact/source SHA-256 hashes and cleanup audit. These logs and machine summaries
are ignored local execution evidence; the scenario inventory and instructions
above are the durable acceptance record.

The protected Probe workflow limitation above remains explicit and is not a
passing Skill-execution claim. Conversational Skill use and proposal quality are
outside these operation/evidence assertions.

## Post-review verification

The bounded P2-1/P2-2 repair run finished successfully at
2026-10-12T16:44:48+08:00 on the same JDK/Maven/OS. Command, from `mcp-server`:

```sh
mvn -B -pl application -am verify '-Pjava-it,java-it-failure' '-Dtest=FailureAnalysisActionHandlerTest,HttpFailureEvidenceClientTest,RouteFailureOperationRegistrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

* 62 focused UT passed: 13 action-handler, 7 HTTP-client and 42 operation-contract
  tests. The two new handler regressions cover long semantic reason preservation
  and credential/opaque-token redaction.
* All 13 IT invocations passed, with zero failures, errors, skips or flakes.
  Both guided/hands_off proposal-consumer cases passed the explicit required-input
  assertions; packaged terminal output preserved all three long semantic codes
  and redacted the credential variants.
* Core and Application Enforcer, Checkstyle and PMD passed. Applicable Gates D/G
  retain the same operation IDs, input schemas and result shape; safe terminal
  reason preservation restores compatibility, with credential output still
  redacted. Core remains the policy owner, with no Sidecar change. Gate E is
  supported by the executable existing Maven commands and local documentation
  link/contract consistency checks; `git diff --check` passed.
* All 13 scenario summaries report `passed` and `cleanupSucceeded=true`. Audit
  found no live owned processes among 50 recorded PIDs and no retained workspace.

Evidence for the initial P2 repair: `test/.tmp/649-p2-verification.log`,
`test/.tmp/649-p2-summary.xml`,
`test/.tmp/java-it-15691965317932593385/s-*/evidence.json`, and
`test/.tmp/649-p2-verification.json` (refreshed source/artifact hashes, command and
cleanup audit). The initial manifest remains historical; this repair manifest
supersedes it for changed sources and the repackaged application.
This is implementation verification; no independent reviewer recheck was
dispatched by the implementer.

## Credential-format follow-up verification

The user's recheck resolved the Bug Fix input finding and identified the terminal
credential-format bypass described above. Only that bypass and its directly
affected tests/docs changed in this follow-up. The final targeted run finished
at 2026-10-12T16:57:31+08:00. From `mcp-server`:

```sh
mvn -B -pl application -am verify '-Pjava-it,java-it-failure' -Dtest=FailureAnalysisActionHandlerTest -Dit.test=McpServerFailureAnalysisValidationIT -Dsurefire.failIfNoSpecifiedTests=false
```

* 13 affected handler UT and all 3 packaged terminal/validation IT passed, with
  zero failures, errors, skips or flakes. Both reported credential examples,
  embedded and segmented tokens are redacted; long valid reasons, the
  120-character reason and `task-cancelled` semantic text remain intact.
* Core/Application Enforcer, Checkstyle and PMD passed. Gate D/G evidence is the
  packaged CDE output plus the unchanged input schema/result shape and Core
  ownership; Gate E evidence is synchronized data-field guidance and acceptance
  commands/links. `git diff --check` and local document link checks passed.
* All 3 passing scenario summaries report successful cleanup: no live owned
  process among 6 recorded PIDs and no retained workspace. An earlier restricted
  run failed at application startup because Java could not establish loopback;
  its 3 scenarios also cleaned successfully. The permitted run above resolves
  that environment failure.

Only `FailureAnalysisResult.terminal` calls the specialized reason-code policy;
the generic diagnostic redactor and runtime fingerprint paths are unchanged.
Reuse the prior 49 other UT and 10 non-validation IT results from
`test/.tmp/649-p2-verification.log`, including the now-resolved Bug Fix consumer
cases. These were not rerun or presented as fresh executions against the new JAR.
The smoke-profile selection and UT-only setup isolation evidence also remain
applicable. No complete repository suite or independent review was dispatched.

Fresh evidence: `test/.tmp/649-token-verification-permitted.log`,
`test/.tmp/649-token-summary.xml`,
`test/.tmp/java-it-14379559275468581052/s-*/evidence.json`, and
`test/.tmp/649-token-verification.json`. The refreshed manifest supersedes prior
hashes for the five changed source/doc files and the repackaged application;
earlier manifests remain historical.
