# #652 Generated Regression integration acceptance

Current child-ticket implementation and verification are complete as of
2026-10-13. The latest repaired JAR passed **335 Core UT, all 89 Regression
capability IT, and the separate one-case smoke**, with zero failures, errors or
skips. Correlation, strict Probe verification, PostgreSQL TLS, continuation and
all reported secret-redaction repairs are included. See the final acceptance
record at the end of this document; earlier sections preserve reproduction and
verification history.

## Production corrections and extensions

Before production edits, the packaged application at base commit
`9f9b1eb6f89aea3d4a9d4ebdea6036e7301868fb` reproduced missing suite extraction:
`McpServerRegressionRequestIT.suiteExtractionIsExplicitAndSurvivesPlanSliceRestart`
executed a real producer HTTP request and returned `in_progress`, but
`details.suiteContext["suite.item"]` was absent instead of `observed`.
The established contract is explicit non-secret `scope="suite"` promotion after
a passing producer plan, consumed by subsequent plans (Regression Suite Skill,
section on context promotion). The substantive owners are `RegressionPlanExecutor`
and `ExecuteExecutionOrchestrationAction`; directly affected production paths are
their `execution` and `action/impl` packages.

Inspection also established that Regression watchers exhaust all polls in one
call and direct suite execution replays any prior result. They cannot resume a
waiting phase without rerunning triggers. On 2026-10-12 the user explicitly
authorized extending production contracts in #652. Continuation belongs to
Regression execution; trusted direct-suite persistence and orchestration must
load its persisted continuation without manufacturing hits or successful state.
Sidecar source and architecture are outside this correction.

Result and Diagnostic Skills consume `artifact_management.run_result.read`,
`artifact_management.run_result.query` and plan/preflight outputs; they do not
require new result/diagnostic MCP operation IDs. SQLite projections are inspected
after the supported explicit `run_result.rebuild` maintenance operation.

Verification and the bounded behavior inventory are recorded below.

The capability run reproduced `scope="suite", secret=true` returning `ok`
instead of `suite_context_secret_forbidden`. `ArtifactRedactionPolicy` converts
the boolean `secret` classification to a string before the internal suite
consumer reads it; `OperationValueRedactor` also replaces this non-secret flag
on public reads. Preserve only an exact `secret` boolean as classification
metadata; continue redacting string/object secret values. These directly affected
policy paths are included in the correction. Packaged-MCP assertions protect
both true (reject promotion) and false (promote) through Artifact reads.

The external-secret reproduction returned a declared `secret=true` extraction
unchanged inside verification `response.body` and `response.bodyJson`, despite
successfully using it for the next request. Regression owns value-aware redaction
of these explicit classifications before returning or persisting phase evidence.

The KCL scenario reached HTTP 400 because the plan read's `artifact.contract`
envelope consumed the Artifact document depth budget: record objects became
`[DEPTH_LIMIT]`. Preserve the existing document depth ceiling while sanitizing
the plan envelope's metadata/contract documents from their own root. The fixture
authentication inputs are changed to project-owned environment bindings; literal
credentials correctly remain redacted on plan storage/read.

## Commands and prerequisites

Use Java 21 and Maven 3.8.6+; commands resolve all checkout/fixture paths through
the Maven project context. Run from the checkout root or use an absolute `-f`.
There is no developer-path or personal `.mcpjvm` prerequisite.

Independent UT, with no IT fixture packaging, process startup, Docker or Sidecar:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am test
```

Build the unchanged Sidecar prerequisites once on a clean checkout:

```sh
mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package -DskipTests
```

The complete Regression capability includes the real PostgreSQL provider and
requires Docker on PATH with its engine running. The test fetches
`postgres:16-alpine` only if absent, publishes a random loopback port, uses tmpfs
for database state, and removes only its uniquely labelled container. A missing
Docker prerequisite is a failure with `postgresql_it_docker_command_failed`, never
a skipped or passing provider scenario.

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify "-Pjava-it,java-it-regression,java-it-regression-fixtures"
```

The fixture profile builds only post-app, event-app, event-producer-app and
event-consumer-app plus their reactor dependencies. Fixture setup is opt-in;
`skipTests`, `skipITs` and `maven.test.skip=true` suppress that setup through the
existing shared guards. Reuse unchanged fixture JARs by omitting the fixture
profile; required absent JARs fail visibly. Packaged server/Sidecar artifacts
remain normal Maven outputs under their module `target` directories.

Smoke is exactly the generated ordered HTTP/extraction workflow and does not
build/start Spring fixtures or Docker:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify "-Pjava-it,java-it-regression-smoke"
```

Individual classes/methods use the same Failsafe lane, for example:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify "-Pjava-it,java-it-regression" "-Dit.test=McpServerRegressionRecoveryIT#rotatedProjectCredentialsAreReloadedOnResumeAfterRestart"
mvn -B -f mcp-server/pom.xml -pl application -am verify "-Pjava-it,java-it-regression-postgresql"
```

The shared complete application IT command from #646 additionally needs the
Regression fixture profile, a running Docker engine and the separately documented
Performance/JMeter prerequisites. This task does not run that all-capability lane:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify "-Pjava-it,java-it-fixtures,java-it-jvm-fixtures,java-it-regression-fixtures"
```

## Behavior inventory and evidence map

Each row uses generated Project context, execution profiles and Regression
metadata/contract/plan through supported Artifact operations. Execution crosses
the packaged JAR's actual CDE/STDIO boundary. Class and method names below are
stable scenario IDs; parameterized cases are equivalence groups with their
actual operator/path/reason recorded in shared machine-readable observations.
All rows use `JavaItScenario` for isolated checkout-local workspaces, owned-process
cleanup, bounded diagnostics and sanitized evidence; the PostgreSQL row also
asserts container removal. The selected capability runs every row, not the smoke
subset. Dispositions are updated only after successful execution.

| Scenario / selection (`*IT.java`) | Operations / Skill consumers | Assertions and actual effects | Persisted evidence | Disposition |
| --- | --- | --- | --- | --- |
| `McpServerRegressionRequestIT.catalogDescribeAndSelectorValidationMatchTheExecutableRegressionSurface` | Catalog/Describe; Regression Crafter/Suite | Exactly two Regression IDs, required selectors, confirmation and declared cancellation policy, invalid input rejection | Shared observations | covered |
| `McpServerRegressionRequestIT.orderedRequestsExtractContextAndAssertActualResponses` (smoke) | Regression preflight/execute; Crafter/Suite | Ordered actual POSTs, escaped path/repeated query, substituted header, JSON body, status/header/JSON/body assertions, extraction and prior-step condition; no effects before confirmation | Actual run JSON, Artifact read and rebuilt SQLite run row | covered |
| `McpServerRegressionContractIT.expectationOperatorsUseRealJsonTypesAndIndexedPaths` (13 cases) | Regression execute; Suite | All retained non-Probe operators, number/string distinction, indexed paths, equality boundaries, malformed regex, missing required field; actual HTTP per case | Actual terminal JSON/SQLite classification | covered |
| `McpServerRegressionContractIT.conditionsUsePlanContextAndShortCircuitBeforeHttp` (10 cases) | Regression execute; Suite | equals/not_equals/in/exists/not/all/any and short-circuit skips; observed request or absence | Step outcome and shared observations | covered |
| `McpServerRegressionValidationIT` (32 cases) | Regression preflight/execute, Artifact upsert; Crafter/Diagnostic | Invalid intent/flags/batch, missing targets/steps, orders, requests, expectations, conditions/forward references/extractions; Watcher/provider/policy and external/correlation validation. Failed assertions and required extraction stop later triggers; missing prerequisite has no effect | Deterministic reasons, actual failure JSON and SQLite for terminal scenarios | covered |
| `McpServerRegressionContractIT` secret scenarios | Regression execute; Suite/Result/Diagnostic | Secret step extraction remains usable; textual/numeric and JSON-escaped external secrets are removed from Watcher/external evidence, including Unicode-escape equivalents; safe parsed fields remain readable; a later secret overwrite revokes public scope in both final and waiting results | Returned result, actual JSON and SQLite exclude sentinel; continuation excludes secret context | covered |
| `McpServerRegressionRequestIT.suiteExtractionIsExplicitAndSurvivesPlanSliceRestart` | Orchestration execute; Suite | Non-secret suite promotion reaches a subsequent plan after packaged-server restart; completed producer is not resent | Genuine suite checkpoint, plan runs and context | covered |
| `McpServerRegressionProbeIT` | Lifecycle attach/deactivate, registry upsert/reload, Regression execute; Line Probe/Suite | Actual post-app JVM, pinned registry route and source-derived route, fresh strict hits, reset/no-hit rejection, invalid line key and disabled optional verification | Actual strict keys and line-hit rows, failed/passed run JSON/SQLite | covered |
| `McpServerCorrelationIT` | Regression execute and sliced orchestration; Suite/Result/Diagnostic | Actual producer/async consumer, exact consumer boundary, response key continuity, wrong-key fail-closed and cross-plan correlation | Canonical timeline, contributing plans and correlation projections | covered |
| `McpServerRegressionCorrelationBoundaryIT` | Lifecycle, registry, Regression execute; Suite | Actual JDK executor handoff to processor strict line; KCL consistent partition and mixed-partition refusal | Runtime timeline, strict lines, runtime identity, fingerprints, run JSON/SQLite | covered |
| `McpServerRegressionVerificationIT` (8 cases) | Regression execute; Suite/Result/Diagnostic | Trigger-created SQLite row; Watcher pending-to-ready; external HTTP extraction feeds SQL expectation; named parameter binding and quoted literal. Assertion, unreachable/extraction and trigger-timeout failures retain phase reasons; late ready response cannot bypass Watcher deadline | Real SQL row, Watcher/external outcomes, run JSON and SQLite state surfaces | covered |
| `McpServerRegressionPostgresqlIT` (3 cases) | Regression execute; Suite | Isolated real PostgreSQL in `disable`, `require` and `verify-full` modes; HTTP-triggered insert and project-owned credential reference; bound SQL read verifies created row and actual encrypted connection; removal of trusted certificate fails closed | Actual row and sanitized JSON/SQLite; labelled container cleanup | covered |
| `McpServerRegressionRecoveryIT` (6 cases) | Direct Regression and orchestration execute; Suite/Result/Diagnostic | Watcher then external phase continuation across server restarts; no trigger/check replay; original deadline and attempts; changed contract rejected; credentials rotate from the same source; blocked prerequisite retry/default and short-secret checkpoint safety | Owner-produced continuation, active phase, failed/pass JSON and rebuilt SQLite | covered |

Result/Diagnostic automated compatibility is the bounded Artifact operation
sequence and persisted fields those repository Skills consume. It does not claim
an actual LLM Skill session. Export replay and general orchestration policy
remain owned by #655; this child tests the Regression phases and context it consumes.
Direct Regression operations remain non-cancellable as described; HTTP request
timeouts are tested as actual connection teardown. Trigger steps execute in one
plan invocation; profile plan slices and blocked-prerequisite retries are the
supported trigger-phase continuation boundary. Restarting an already persisted
terminal direct run replays its result without sending requests again.

After a secret extraction, its value is intentionally omitted from checkpoints.
On resume, freeform HTTP/SQL observation data is conservatively redacted because
the previous in-memory secret value is unavailable. Control fields, deadlines,
attempt counts and verdicts remain readable. A later phase that needs that
ephemeral secret must obtain it from an available runtime source; the checkpoint
does not provide durable secret storage.

## Review finding disposition

The human supplied bounded review (`test/.tmp/652-review/review.md`) identified
R1 (classification overwrite), R2 (external secret evidence) and R3 (credential
rotation). The implementation now tracks actual extraction writes and their
classification in `RegressionExecutionContext`, retains all observed secret
values for evidence redaction, and fingerprints stable input/source identity
with resolved credential values excluded. Permanent packaged-MCP tests cover all
three. No independent reviewer was dispatched by this implementer.

The supplied review's retained targeted recheck also reproduced numeric secrets
remaining in serialized response bodies and a short secret corrupting checkpoint
control fields. Redaction now applies all classified scalar values to evidence
representations, and traverses only owned data-bearing evidence/context fields;
result statuses, reason codes, phase discriminators and opaque fingerprints
remain control metadata. The full capability run additionally reproduced an HTTP
timeout throwing on `Map.copyOf` because `statusCode` was null. The Regression
outcome owner now omits an unavailable HTTP status while retaining its timeout
reason and duration. These corrections stay with Regression execution.

The late-Watcher reproduction in `652-final-repairs.log` returned `ok` after a
ready response arrived 150 ms into a 50 ms Watcher budget. Watcher execution now
bounds each HTTP request by its remaining absolute deadline and checks expiry
before accepting even a successful response. The existing HTTP provider safety
ceilings and explicitly invalid request timeouts remain enforced.

### Supplied recheck reconciled against the current build

The subsequent report of R2b/R4/R5 refers to the retained `recheck.md` build with
JAR SHA-256 `717E927583115F92BF0976049F9A284CEB4C9F20144CD15C8B1EA229DF1F3001`.
That build predates the corrections above. Its recorded source hashes also differ
from the corrected redaction and executor sources. The JAR at that reconciliation was
`166310A26C5433F42D57A430AADCBEC0BE9F9818FD2F078EA6C6F6713BDB86D8`.

On 2026-10-13 the implementer reran the user's unchanged retained
`RegressionRepairRecheck` driver against the current JAR: **5 passed, zero failed
or skipped**, including the exact numeric-secret and single-character checkpoint
reproductions (`test/.tmp/652-current-reproductions.log`). The permanent selection
below also passed **7 cases, zero failures, errors or skips** against that same
binary (`test/.tmp/652-current-permanent.log`), including the required timeout:

```sh
# From mcp-server/application; reuses the packaged JAR and compiled tests.
mvn -B -Pjava-it failsafe:integration-test failsafe:verify "-Dit.test=McpServerRegressionContractIT#secretOverwriteRevokesTheEarlierPublicSuiteClassification+secretExternalExtractionIsRedactedFromVerificationEvidence,McpServerRegressionRecoveryIT#rotatedProjectCredentialsAreReloadedOnResumeAfterRestart+shortSecretDoesNotAlterCheckpointControlsOrEscapeOnResume,McpServerRegressionVerificationIT#triggerTimeoutPersistsFailureAndDoesNotExecuteVerification"
```

All 12 scenario evidence files report passed outcomes, successful cleanup and no
retained workspace files, under `test/.tmp/java-it-14829325200553213894/s-*` and
`test/.tmp/java-it-7738105548401660292/s-*`. No additional production edits or
independent reviewer dispatches were needed for this reconciliation.

## Verification record before the escaped-secret correction

Executed on 2026-10-13 with Corretto Java 21.0.12.1, Maven 3.9.11, Windows 11
and Docker Engine 29.8.0. Commands below were run from `mcp-server` (PowerShell
arguments containing commas or dots were quoted):

```sh
mvn -B -pl application -am verify "-Pjava-it,java-it-regression" "-Dtest=RegressionPlanExecutorTest,RegressionExternalVerificationTest,RegressionCrossPlanCorrelationTest,ExecuteExecutionOrchestrationActionTest,SuiteOperationRegistrationTest,ArtifactPlanOperationRegistrationTest,ArtifactPlanOperationSafetyTest,CoreOperationDirectoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"
mvn -B -pl application -am verify "-Pjava-it,java-it-regression-postgresql" "-Dtest=RegressionPlanExecutorTest,RegressionExternalVerificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"
mvn -B -pl application -am verify "-Pjava-it,java-it-regression-smoke" "-Dtest=RegressionPlanExecutorTest,RegressionExternalVerificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"
```

- Complete Regression selection: **335 Core UT and 85 IT passed**, zero failures,
  errors or skips (`test/.tmp/652-acceptance.log`). All 85 scenario evidence files
  report successful cleanup and no retained workspace files, under
  `test/.tmp/java-it-784811415018217255/s-*/evidence.json`.
- The later PostgreSQL expansion: **43 affected UT and 3 provider IT passed**,
  zero failures, errors or skips (`test/.tmp/652-postgresql-modes.log`);
  `test/.tmp/java-it-286419098897387397/s-*/evidence.json` confirms all three
  owned-container/workspace cleanups. This replaces the earlier single plaintext
  case. The combined inventory therefore contains **87 distinct passing cases**;
  it is evidence from two selections, not one 87-case invocation.
- The separate smoke initially passed one case (`test/.tmp/652-smoke.log`).
  The final smoke passed **43 affected UT and 1 IT**, zero failures, errors or
  skips, and retains fixture-only raw JSON-RPC STDIO beside its scenario evidence:
  `test/.tmp/java-it-11972476419879812/s-1/smoke-stdio.jsonl`. It contains generated
  non-secret inputs only; the scenario reports successful process/workspace
  cleanup. Its log is `test/.tmp/652-smoke-wire.log`.
- Independent clean-source UT: **43 passed**, with Docker/JMeter absent from
  the child PATH and no fixture/Sidecar sources or IT packaging executions.
  `test/.tmp/652-clean-ut.log` and
  `test/.tmp/java-it-9df8f17a69af4b7186463412c18abdf7/evidence.json` record exit 0
  and removal of the temporary source snapshot.
- Enforcer, Checkstyle and PMD passed for the affected reactor. `git diff --check`
  and documentation field/profile/link consistency were checked. Core retains
  its transport-independent boundary. Sidecar and fixture application sources
  were not changed. No full repository or all-capability suite was executed.

The clean-source UT command, from the temporary copy's `mcp-server` root, was
`mvn -B -pl application -am test -Dtest=RegressionPlanExecutorTest,RegressionExternalVerificationTest -Dsurefire.failIfNoSpecifiedTests=false`.

The preceding recorded packaged runs used application JAR SHA-256
`166310A26C5433F42D57A430AADCBEC0BE9F9818FD2F078EA6C6F6713BDB86D8`.
Production inputs and code were unchanged between the 85-case run and the later
test-only PostgreSQL/smoke additions, so unaffected evidence is reused.

Applicable gates D/E/G: real packaged CDE smoke and retained STDIO; synchronized
Java fields, plan specification and Maven profile documentation; affected Maven
verification and actual strict Probe/correlation scenarios. Existing operation
IDs/selectors and default unbatched execution remain compatible, as exercised by
the registration/Artifact UT and default HTTP smoke. New batching is opt-in;
invalid secret suite promotion and late Watcher success now fail closed. The
automation validates the Artifact operations consumed by Skills; an actual LLM
Skill session and the parent epic's other capabilities are outside this evidence.

## R2c escaped-secret correction and current verification

The user-supplied `test/.tmp/652-review/recheck-2.md` correctly identified that
matching a secret's decoded text against raw JSON misses escaped representations.
Before repair, the unchanged supplied six-case driver reproduced **5 passes and
1 failure** on the `166310A2...` binary (`test/.tmp/652-escaped-before.log`).

`RegressionExecutionContext.sanitizeObservation` now replaces raw HTTP `body`
evidence in full whenever classified secret values are present. Structured
`bodyJson` still undergoes value-aware sanitization, retaining safe evidence.
This avoids guessing encodings, including Unicode escapes and truncated JSON,
without rewriting phase discriminators, fingerprints, statuses or other controls.
The same owner handles returned results, earlier Watcher observations, nested
checkpoints and persisted Artifact/SQLite inputs. Raw bodies without classified
secrets retain their existing behavior. The intentional evidence reduction is
documented in the Java fields and Regression plan specification.

Permanent parameterized cases now include the quoted/backslash secret and its
Unicode-escaped equivalent. They verify actual downstream credential use, redacted
raw bodies and parsed secret fields, preserved safe parsed fields, Artifact reads,
canonical persisted results and the SQLite projection through the supported rebuild.

Current repaired JAR SHA-256:
`3CAAC0D16EDC2013B3BB3F62ABC4A289C85E5821B0E77D11E38689E7DD9504C7`.
The unchanged supplied reproduction driver passed **all 6 cases**, with zero
failures or skips (`test/.tmp/652-escaped-after.log` and
`test/.tmp/java-it-5984760323546863019/s-*/evidence.json`). The affected Maven
verification command is:

```sh
# From mcp-server.
mvn -B -pl application -am verify -Pjava-it "-Dtest=RegressionPlanExecutorTest,RegressionExternalVerificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false" "-Dit.test=McpServerRegressionContractIT#secretOverwriteRevokesTheEarlierPublicSuiteClassification+secretExternalExtractionIsRedactedFromVerificationEvidence+explicitSecretExtractionIsUsableButAbsentFromOutputArtifactsAndSqlite,McpServerRegressionRecoveryIT,McpServerRegressionVerificationIT,McpServerRegressionRequestIT#orderedRequestsExtractContextAndAssertActualResponses"
```

Its result is recorded in `test/.tmp/652-escaped-repair.log` and
`test/.tmp/java-it-11964908170991124382/s-*/evidence.json`: **43 Core UT and 22
packaged IT passed**, with zero failures, errors or skips. Enforcer, Checkstyle
and PMD passed. All 28 cases across this selection and the supplied driver report
successful process/workspace cleanup. The smoke retained fixture-only STDIO at
`test/.tmp/java-it-11964908170991124382/s-14/smoke-stdio.jsonl`.
`git diff --check` and documentation file links were also checked. Earlier broad evidence
above describes its recorded binary; this repair reruns the directly affected
secret, continuation, verification and smoke behavior rather than claiming a new
complete capability invocation. No independent reviewers were dispatched.

## Final full capability acceptance

At the user's request, the complete Regression capability selection was rerun
on 2026-10-13 against repaired application JAR SHA-256
`3CAAC0D16EDC2013B3BB3F62ABC4A289C85E5821B0E77D11E38689E7DD9504C7`.
The earlier acceptance gap after R2c is closed. No production code changed in this
final verification run; existing unchanged fixture and Sidecar JARs were reused.

From `mcp-server`:

```sh
mvn -B -pl application -am verify "-Pjava-it,java-it-regression" "-Dtest=RegressionPlanExecutorTest,RegressionExternalVerificationTest,RegressionCrossPlanCorrelationTest,ExecuteExecutionOrchestrationActionTest,SuiteOperationRegistrationTest,ArtifactPlanOperationRegistrationTest,ArtifactPlanOperationSafetyTest,CoreOperationDirectoryTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dfailsafe.failIfNoSpecifiedTests=false"
```

**BUILD SUCCESS**, finished 2026-10-13 20:15:14 Asia/Manila; **335 Core UT and
89 packaged IT passed**, zero failures, errors, skips or flaky retries. Enforcer,
Checkstyle and PMD passed. All 89 scenario records report `outcome=passed`,
`cleanupSucceeded=true`, and `filesRetained=false`, including owned JVM/Sidecar
processes and the three isolated PostgreSQL containers.

| Packaged selection | Passing cases |
| --- | ---: |
| Producer/consumer and cross-plan correlation | 1 |
| Request/assertion/condition/secret contracts | 30 |
| Executor/KCL and response-key correlation | 5 |
| PostgreSQL plaintext/required TLS/verified TLS | 3 |
| Strict Probe routes and failure paths | 1 |
| Persisted phase recovery and prerequisite retry | 6 |
| Catalog, ordered HTTP smoke and suite promotion | 3 |
| Invalid contracts and fail-closed execution | 32 |
| Watchers, external HTTP/SQLite and timeout failures | 8 |
| **Total** | **89** |

The separately selectable smoke was then run from `mcp-server/application`
against the same JAR, without rebuilding or optional fixture startup:

```sh
mvn -B "-Pjava-it,java-it-regression-smoke" failsafe:integration-test failsafe:verify
```

**BUILD SUCCESS**, finished 2026-10-13 20:15:58 Asia/Manila; **1 passed**, zero
failures, errors or skips. This repeats the smoke workflow in the 89-case inventory;
it is not an additional distinct capability case. Its scenario also confirms
successful cleanup.

Retained local evidence:

- `test/.tmp/652-final-acceptance.log`: complete Maven/build/static-check output.
- `test/.tmp/652-final-acceptance-reports`: preserved complete Failsafe summary
  and per-class XML/text reports before the separate smoke run.
- `test/.tmp/java-it-10080218901959773741/s-*/evidence.json`: all 89 sanitized
  scenario records; producer/consumer correlation is `s-1`, the other correlation
  cases are `s-32` through `s-36`.
- `test/.tmp/java-it-10080218901959773741/s-49/smoke-stdio.jsonl`: actual
  fixture-only STDIO from the complete capability run.
- `test/.tmp/652-final-smoke.log` and
  `test/.tmp/java-it-15019345610298013197/s-1/evidence.json`: separate smoke lane.
- `test/.tmp/652-final-acceptance.json`: consolidated machine-readable summary.

Final `git diff --check`, Markdown file-link checks and JAR fingerprint checks
passed. Gates D/E/G are satisfied within the child scope by the packaged CDE
evidence, synchronized fields/specification/profile documentation, affected Maven
verification, and unchanged Core/Application/Sidecar boundaries. No full repository
or parent-epic capability suite, actual LLM Skill session, external publication,
GitHub issue closure, or independent reviewer dispatch is implied by this result.
