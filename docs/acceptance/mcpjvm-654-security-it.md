# #654 packaged Security capability integration coverage

Implementation baseline: `53bbfd120bdf6ed9792e01173a2c8d23e31f5702`.
Scope: Application packaged Security ITs, shared existing local fixture support,
and directly owning Core corrections. Sidecar and Spring fixture sources remain unchanged.

## Reproductions recorded before production repairs

`test/.tmp/654-third-security.log` and the Catalog Failsafe report reproduced
`operation_execution_failed` for the authorization-idor, authorization-cross-tenant
and path-traversal reviewed rules with a valid `/resource/{id}` entrypoint and
declared baseline path parameters. Static diagnosis: SecurityPlanExecutor constructs
a URI from the unresolved template before substituting parameters; URI parsing
throws on braces. Expected: the declared own-resource baseline and foreign-resource
mutation reach the controlled fixture, with their actual responses classified.
Behavioral reference: `tools/features/security-suite/support/security_blackbox_request.ts`.
Substantive owner and directly affected production path:
`mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/suite/security/execution/SecurityPlanExecutor.java`.

The first attempt could not establish loopback connections inside the host sandbox.
The second attempt exposed missing required defaults in the new test's Project input;
the test input was corrected to the existing Project contract. Neither is a product defect.

`654-fifth-security.log` reproduced a second defect after the live line-hit run:
the fixture and Probe observed the fresh attack hit, but the persisted case had
no `runtimeEvidence` (zero observations). Security Result/Diagnostic consumers
require inspectable historical Probe evidence; a current Probe cannot prove a
past run after shutdown. Expected: persist bounded target/key, phase, actual
hit count, last-hit epoch and runtime identity for baseline and attack. The same
SecurityPlanExecutor owner currently treats an unavailable forbidden-target
status as a hit, which can manufacture an internal finding; unavailable evidence
must block rather than confirm. References: Security Result/Suite/Diagnostic
Skills and retained `security_sidecar_assisted_mode.ts`.

`654-seventh-security.log` reproduces ignored retained options in the same owner:
an empty `verdictPolicy.failOnSeverity` still fails the suite, three requests
ignore a 5 requests/second policy, and a 300 ms policy allows a 1 second HTTP
baseline to finish with a clean pass. A transport-wrapped custom request drops
its query, headers and body. Expected contracts are defined in the retained
Security contract model, validators, Black-box/Sidecar modes and request builder.
Corrections stay in SecurityPlanExecutor; they do not introduce another provider,
change the CDE selector schema, or restructure the Sidecar.

`654-tenth-security.log` reproduces two surviving Java descendants after the
real 20-second credential refresh deadline returned `security_credential_refresh_failed`.
Expected: every owned refresh process stops before the failed operation returns;
test teardown also owns these PIDs as a last-resort cleanup. Owner/path:
`core/feature/suite/security/execution/SecurityCredentialRefresh.java`.

`654-twelfth-security.log` reproduces extra JSON quoting in both reviewed body
mutations: the declared string `foreign` reaches the fixture as `"foreign"`.
Expected: textual bodies reach HTTP unchanged, while object bodies remain JSON.
The retained request builder/Transport contract establishes that distinction.
Correction owner/path: SecurityPlanExecutor.java.

`654-thirteenth-security.log` reproduces missing case/profile identities in the
2-entrypoint × 2-profile matrix, and a clean pass after sending an unresolved
`{id}` literal to the fixture. Expected: each matrix row has a unique case ID
and authentication association; unresolved declared path fixtures block before
HTTP. The reviewed finding assembly also assigns a rule ID to the `cwe` field,
despite the selected installed pack supplying CWE identifiers. Restore the
pack-owned identifiers and reference actual HTTP evidence for denied mutations.
Owners: SecurityPlanExecutor.java and SecurityKnowledgeCatalog.java.

`654-partial-finding.log` reproduces loss of a confirmed finding when the next
generated baseline returns HTTP 503. The confirmed case remains in coverage but
`details.findings` is absent. Expected: incomplete runs preserve every finding
already established, alongside their blocked terminal outcome. Owner:
SecurityPlanExecutor.java; the fixture makes five actual requests before blocking.

`654-final-contract-gaps.log` records 14 packaged checks, with eight failures,
before the final bounded repairs. Explicit authentication headers were replaced
by defaults, lower-case existing Basic/Bearer prefixes were duplicated, API-key
and custom credentials survived the authentication mutation, declared custom
overrides still executed catalog mutations, missing prePlan scripts were ignored,
and duplicate identities were accepted. Expected behavior follows the retained
request builder, Black-box mode and Security contract model: explicit headers
win case-insensitively, existing schemes are preserved, unauthenticated mutations
remove declared credentials, custom overrides select only declared cases, and
ambiguous IDs or missing prerequisites block before HTTP. Owners are
SecurityPlanExecutor.java and SecurityCredentialRefresh.java.

## Implementation and supported behavior inventory

The supplied bounded review's
`test/.tmp/java-it-review-654-12779326454623839078/results.json`
reproduces three further defects against the previously verified package:
(R1) an orphan runtime target selects no Probe and incorrectly completes with
empty runtime evidence; (R2) `${fixture.ownResourceId}` is encoded literally
instead of resolved; (R3) query mutation appends `q=foreign` after `q=own`,
manufacturing a confirmed finding against a first-value HTTP fixture whose
single foreign value is denied. Expected: runtime target references must resolve,
every executable Sidecar case must select observable runtime targets, fixture
expressions must resolve before encoding (missing values block before HTTP),
and explicit query values replace existing values as the retained
`security_blackbox_request.ts` does through `URL.searchParams.set`.
The substantive owner and sole affected production source for this repair batch
is `core/feature/suite/security/execution/SecurityPlanExecutor.java`.
The earlier 42-test evidence is historical and does not establish acceptance
of these three behaviors. This task remains the implementer: no independent
review was dispatched here and no new review cycle is being started.

The supplied targeted recheck confirms R1/R2/R3 but reports R4: transport path
`/resource/${fixture.ownResourceId}` with `ownResourceId="own resource"` resolves
the expression, then URI construction rejects the space, incorrectly producing
`security_blackbox_fixture_unresolved` and zero HTTP requests. The user's follow-up
directs this remaining bounded repair. Expected: the actual packaged request is
`/resource/own%20resource`; existing literal path/query escapes remain intact.
The sole production owner remains SecurityPlanExecutor.java, specifically
transport-path resolution in requestPayload. Prior 51-test evidence predates
this correction. The implementer does not dispatch another independent review
or reset the supplied review/recheck cycle.
`test/.tmp/654-path-encoding-reproduction.log` records both new packaged
regression variants failing before the production edit (two failures, zero
errors/skips): spaces and a value containing spaces, Unicode and a literal percent.

The packaged entry point is `security_suite.execute_plan`. Every scenario below
creates Project context and Security plans through
`artifact_management.project_context.upsert` and `security_plan.upsert/validate/read`,
executes CDE, and inspects actual HTTP/Probe effects. Persisted assertions compare
status and coverage between `execution.result.json` and `run_result.read`, inspect
canonical findings/runtime evidence, and compare rebuilt/queryable SQLite `run_state`.
No test writes a successful run, finding, Probe hit or continuation Artifact.

Stable scenario IDs are the class/method names in the following table; parameter
variants are recorded as bounded `ASSERTED` observations in `evidence.json`.
Disposition is **covered** only for successful recorded Java executions.

| Scenario class / method | Behaviors and exact semantic assertions | Real boundary / consumer |
| --- | --- | --- |
| `McpServerSecurityCatalogIT.defaultReviewedMatrixAndImmutableSnapshotSurvivePlanChangeAndReplay` | Nine reviewed default packs; actual 10-row matrix; one HTTP 200 baseline; nine safe N/A rows; empty findings; 64-character digest; duplicate/unversioned explicit refs resolve to one pinned pack; replay after plan change preserves the original result/digest and makes no request. This is the explicit smoke subset. | Packaged CDE, local HTTP, filesystem/SQLite; Security Crafter/Suite/Result/Diagnostic |
| `CatalogIT.actualMatrixMultipliesDeclaredEntrypointsAndAuthenticationProfiles` | Two entrypoints × two authentication profiles × baseline/one rule = eight uniquely identified rows; four actual baselines, authenticated headers, and four N/A rules. | Actual matrix and credential injection, canonical/result reads |
| `CatalogIT.customOverridesNeverExecuteUnrequestedCatalogMutations`, `authenticationCatalogMutationRemovesEveryDeclaredCredentialHeader` (four credential kinds) | Explicit custom cases suppress otherwise eligible catalog attacks; an empty custom override executes only its baseline. Authentication mutations remove standard or symbolic custom credentials, so the actual attack is HTTP 401 with no false finding. | Actual HTTP counts/headers; retained override and authentication contracts |
| `CatalogIT.generatedQueryMutationReplacesAllExistingValuesInsteadOfManufacturingAFinding` (R3) | Two existing `q` values are replaced by one baseline value and then one foreign mutation value; unrelated encoded `keep` remains intact. Actual attack HTTP 403 is passed with zero findings; exact requests and canonical details are asserted. | Actual first-value HTTP fixture, generated matrix and persisted diagnostics |
| `CatalogIT.everyReviewedRuleSendsItsActualMutationAndDistinguishesDenialFromFinding` (nine pack variants) | All installed `web-api-core`, authentication, IDOR, cross-tenant, deserialization, upload, injection, traversal and SSRF checks: own baseline, actual header/path/query/body mutation, expected 400/403 denial → passed; controlled allowed counterpart → confirmed external finding and failed verdict; exact pack/rule, valid CWE identifiers and baseline/attack evidence references. | Existing observable `TransportHttpTarget`; suite/result/diagnostic evidence. These prove the declared finite checks against controlled local responses, not exploitability in an arbitrary application. |
| `McpServerSecurityRequestIT.projectCredentialsBlockBeforeRequestsThenReachOnlyTheOwnedTarget` (bearer/basic/api_key/custom and prefixed bearer/basic) | Missing project-bound credential blocks with zero HTTP; local env binding reaches the correct header; existing case-insensitive schemes are preserved; echoed fixture token is absent from CDE, stderr and run Artifacts. | Actual HTTP and scenario-local `.env`; Suite credential contract |
| `RequestIT.explicitAuthenticationHeaderIsNeverReplacedByTheProfileDefault`, `missingDeclaredPrePlanScriptBlocksBeforeAnyTargetExecution` | Both Authorization casings retain their explicit bad-token value and actual HTTP 401 blocks; a missing declared prePlan script blocks before any HTTP instead of silently passing. | Actual credential header capture, persisted blocked outcomes; Suite/Diagnostic |
| `RequestIT.topLevelEntrypointAndSymbolicCredentialHeaderUseTheOwnedProjectBinding`, `transportWrappedCustomRequestPreservesItsDeclaredPayload`, `encodedPathAndQueryValuesReachTheTargetExactlyOnce` | Top-level/nested HTTP entrypoint forms; symbolic headers; transport-wrapped custom query/header/body; object and textual bodies; spaces, plus, percent, Unicode and path parameters arrive once with their exact values. | Real request capture; retained request-builder compatibility |
| `RequestIT.fixtureExpressionsResolveBeforeEncodingAcrossRequestFieldsAndContextOverrides`, `missingFixtureExpressionsBlockEveryRequestFieldBeforeHttp` (six missing-field variants; R2) | Boundary direct/details and entrypoint-details precedence; numeric/boolean context; transport path, path parameters, interpolated query/header and nested body array resolution; Unicode/plus/percent encoded exactly once. Missing values in transport path, path parameters, query, headers, text body or nested object body block before any HTTP with persisted `security_blackbox_fixture_unresolved`. | Real request capture and canonical/public/SQLite blocked results; retained fixture contract |
| `RequestIT.transportPathFixtureValuesAreEncodedWithoutChangingExistingEscapes` (two value variants; R4) | Direct transport-path fixture `own resource` reaches actual HTTP as `/resource/own%20resource`; spaces/Unicode/literal percent are encoded correctly. A second plan preserves existing encoded path prefix and query values. Both plans pass with exact captured URIs and matching canonical details. | Actual packaged MCP/HTTP boundary and persisted results |
| `RequestIT.customCasesPreserveRequestFieldsExplicitOutcomesAndForeignResponseFindings` | Explicit allow/deny/error/status expectations, query/queryParameters aliases, POST headers/body, passed cases with references, one allowed foreign-response finding, exact persisted failed verdict. | Actual baseline/attack HTTP and complete result Artifacts |
| `RequestIT.prePlanRefreshReloadsCredentialsAndFailedRefreshNeverExecutesTheTarget` | Native prePlan script emits 1 MiB to each output channel without blocking/polluting MCP, writes its local env file, newly resolved credential reaches HTTP; nonzero script exit blocks before HTTP. | Actual PowerShell/sh subprocess; isolated env file; Suite/Diagnostic |
| `McpServerSecurityPolicyIT.retainedSeveritySelectionChangesVerdictWithoutHidingConfirmedFindings`, `retainedRequestRatePolicyBoundsEveryBaselineAndMutation`, `retainedDurationPolicyBoundsRealHttpAndPersistsTheEntirePlannedMatrix` | Empty failing-severity selection preserves confirmed findings but passes verdict; three actual requests respect 5 requests/second; 300 ms execution budget blocks a 1-second HTTP baseline, preserves all ten planned rows and records only the one attempted case. Sequential execution stays within declared positive concurrency. | Real arrival times/deadline and persisted incomplete coverage |
| `PolicyIT.incompleteMatrixPreservesItsPreviouslyConfirmedFindingForDiagnostics` | First reviewed mutation is externally confirmed; a later actual baseline fails; blocked result keeps the four-row planned matrix, confirmed case/finding and its references in canonical and public diagnostic reads. | Actual partial HTTP execution; Security Result/Diagnostic |
| `McpServerSecuritySidecarIT.realFreshRequiredAndForbiddenHitsDistinguishPassInternalAndCorroboratedFindings` | Real post-app attach/reset, own/foreign/missing-resource HTTP requests, fresh required/forbidden line hits; passed, internal and corroborated external classifications; severity selection; explicit URL and reloaded registry routing; baseline/attack status observations persist exact key, phase, hit count and runtime identity; deactivate restores the healthy target. | Existing `JvmProbeScenario`, Spring post-app, production Sidecar, `jvm_lifecycle.*`, `probe.status`, `probe_config.*`; Suite/Result/Diagnostic |
| `SidecarIT.unavailableSidecarMissingHitsAndInvalidReferencesRemainIncompleteAndRecoverOnNewRun`, `disappearingRealSidecarCannotManufactureAForbiddenLineFinding` | Unattached and invalid-key reset failures, no actual required hit, missing entrypoint/profile refs, unsupported Kafka N/A; new run after real attach recovers; stopped real fixture during attack yields unavailable status and zero fabricated findings. | Actual JVM lifecycle/Probe availability and persisted blocked cases |
| `SidecarIT.orphanedOrUnselectedRuntimeTargetsNeverPassWithoutProbeEvidence` (R1) | Missing runtime-target entrypoint reference fails preflight; target referencing another declared entrypoint yields an incomplete blocked case; a selected unavailable Probe fails reset. All three make zero HTTP requests, persist blocked outcomes and never produce a passing verdict without runtime evidence. | Packaged CDE and actual unavailable Probe route; no mocked Probe or live-line claim. Positive evidence remains the real JVM scenarios above. |
| `McpServerSecurityRecoveryIT.cancelledMcpWaitCompletesBoundedRunAndRestartReusesTheActualResult` | Actual active HTTP request; simultaneous same-run call blocks on the owned lock; cancelled MCP response wait yields no response; non-cancellable execution persists pass; new packaged process replays the same result without HTTP. | MCP cancellation, real run lock, process restart, canonical recovery |
| `RecoveryIT.actualTransportDeadlineAndConnectionFailurePersistBlockedAndNewRunRecovers`, `refreshDeadlineStopsItsActualDescendantTreeBeforeReturning` | Real twenty-second HTTP timeout, unreachable local target, new healthy run; real stalled refresh shell/Java descendants stop before refresh failure returns; blocked state and no target execution persist. | Transport deadline, owned subprocess tree, existing `PerformanceStalledProcess` reused as a negative fixture |
| `McpServerSecurityValidationIT.invalidContractsBlockBeforeTargetAndPersistDeterministicReasons` | Mode, entries/auth arrays, duplicated entry/profile/custom/runtime/attack IDs, invalid authentication kind, local-ci boundary/base/host/port/scheme/userinfo, unavailable pack, sidecar attacks/targets/key, unresolved path fixture; invalid concurrency/rate/duration/destructive/cleanup/severity/exhaustiveness policies. Exact blocked reasons, no HTTP, no clean verdict, matching canonical and SQLite state. | Preflight and persistence; Crafter/Suite/Diagnostic |
| `ValidationIT.cdeDiscoveryConfirmationAndTrustedSelectorsPreventUnownedExecution`, `unexpectedBaselineAttackAndMissingReferencesNeverBecomeCleanCoverage` | Catalog/Describe discovery, canonical selector schema and non-cancellable declaration; confirmation and required inputs; traversal/missing/unlisted selectors; unexpected baseline/attack statuses, missing refs and safe unsupported transport. Rejections never contact an unowned target or create a clean result. | Actual three-tool CDE transport and trusted persisted-plan boundary |

Current Security execution has one supported operation, both supported modes,
the nine installed pack families, declared custom cases and four non-anonymous
credential strategies. Terminal replay is its supported direct recovery path;
multi-plan orchestration/exports and cross-feature Skill acceptance are owned
by #655/#656. No missing provider is promoted to a new feature. Tests exercise
the Tool/Artifact sequences consumed by the repository-owned Security Skills;
they do not claim an actual LLM Skill-use session or revive retired Node MCP routes.

SecurityPlanExecutor remains the substantive execution owner. RequestContext
holds invocation-local credentials and request budget; no mutable plan state is
shared by concurrent invocations. Historical runtime observations and full
matrix identities are added to the existing result details. Invalid raw URI
construction, double encoding, string-body quoting, ignored policies, false
forbidden-hit inference and orphaned refresh descendants are corrected.
Knowledge selection still reads the same immutable packaged resource/digest.
The CDE selector schema, operation IDs and Artifact paths stay intact. CWE
findings now contain the catalog's identifier array instead of the erroneous
Java rule-ID string; consumers of that faulty detail require adjustment.
`docs/data-fields/README.md` records the result/policy corrections.

The former Node-dependent credential-refresh UT subprocess checks are now
covered by the native packaged-MCP refresh and timeout ITs. Their surviving UT
class protects command/path/phase policies without launching optional tools.
No new production module, mocking interface, attack framework or routing layer
was added; no Sidecar or immutable Spring fixture source was changed.

## Runnable lanes and prerequisites

JDK 21+ and Maven 3.8.6+ are required. The application package requires the
repository-owned attach helper and bundled agent. Build only their owning
Sidecar module and dependencies from `java-agent` on a clean checkout:

```text
mvn -B -pl core/core-jvm-attach,core/core-probe -am package -DskipTests
```

Then run the following from `mcp-server`. Maven's basedir supplies the repository
root to the test driver; invoking `mvn -f <checkout>/mcp-server/pom.xml` from
another directory is supported. All scenario inputs are generated under
`<checkout>/test/.tmp/java-it-<unique>/s-<scenario>/w-<workspace>`.

```text
# Independent focused UT: no package, fixture, attach, script runtime or IT profile.
mvn -B -pl application -am test -Dtest=SecurityPlanExecutorTest,SecurityCredentialRefreshTest,SuiteOperationRegistrationTest,CoreOperationDirectoryTest -Dsurefire.failIfNoSpecifiedTests=false

# Explicit one-scenario security smoke: no Spring fixture build or attach.
mvn -B -pl application -am verify -Pjava-it,java-it-security-smoke -Dit.test=McpServerSecurityCatalogIT -Dtest=SecurityPlanExecutorTest,SecurityCredentialRefreshTest -Dsurefire.failIfNoSpecifiedTests=false

# Complete Security capability: every Security IT, plus automatic post-app preparation.
mvn -B -pl application -am verify -Pjava-it,java-it-security,java-it-jvm-fixtures -Dit.test=McpServerSecurity*IT -Dtest=SecurityPlanExecutorTest,SecurityCredentialRefreshTest,SuiteOperationRegistrationTest,CoreOperationDirectoryTest -Dsurefire.failIfNoSpecifiedTests=false

# Individually selected class/method; use the fixture profile for Sidecar tests.
mvn -B -pl application -am verify -Pjava-it,java-it-security -Dit.test=McpServerSecurityRequestIT#prePlanRefreshReloadsCredentialsAndFailedRefreshNeverExecutesTheTarget -Dtest=SecurityPlanExecutorTest,SecurityCredentialRefreshTest -Dsurefire.failIfNoSpecifiedTests=false

# Full currently implemented Java IT lane (documented, not automatically executed here).
mvn -B -pl application -am verify -Pjava-it,java-it-fixtures
```

Quote comma-separated properties and `*`/`#` selections when the caller shell
requires it. Security refresh ITs require PowerShell on Windows or `sh` on Unix.
They need no Node installation, JMeter, Docker, personal `.mcpjvm` state or
external scan target. The full Java lane additionally needs the Performance and
Regression prerequisites documented in #653/#652, including configured JMeter
and Docker where selected. Unavailable prerequisites fail visibly; no Security
test uses an assumption/skip. Unit, smoke and capability lanes are suitable for
separate CI invocation; this ticket adds no CI deployment/trigger configuration.
Reusable unchanged post-app packages can omit `java-it-jvm-fixtures`; the test
still asserts the JAR prerequisite. Disabled or empty IT selections are not passes.

## Evidence and cleanup

Surefire and Failsafe XML/text reports live in the owning module `target`.
Each scenario retains sanitized `evidence.json` with class/method ID, variant
observations, operation outcomes, JDK/OS, owned PIDs and cleanup result. Default
teardown deletes scenario workspace directories (including credentials and
product Artifacts) after assertions. `-DmcpItRetainFiles=true` explicitly retains
scenario files for debugging; it never retains live processes. All such inputs,
run Artifacts and build logs stay ignored. HTTP fixtures stop their server and
executor in try-with-resources; JVMs and descendants are checked dead regardless
of file-retention policy and after partial setup/failure. Only owned paths/PIDs
are cleaned. The refresh leak reproduction's surviving children were stopped by
test teardown before its evidence was written.

## Verified implementation evidence

Verified on 2026-10-14 against the implementation changes in the current checkout
at the baseline commit above. Environment: Windows 11, Amazon Corretto
21.0.12.1, Maven 3.9.12, Application/Core 0.1.9, bundled Sidecar 0.1.8 and the
repository-owned post-app fixture 0.1.0-SNAPSHOT. Capability and smoke used
`application/target/mcp-java-dev-tools-server-0.1.9.jar`, SHA-256
`FFF759384DF5A0F95CF78E3F633A047B6EAB60F302C160B334FAB58BFB618DB3`.
This supersedes the 42- and 51-test packages for the revised source.

| Selection | Actual result | Retained local evidence |
| --- | --- | --- |
| Complete Security capability command above | **53 ITs and 200 focused UTs passed**, zero failures/errors/skips; Maven verify, Checkstyle and PMD passed. Catalog 17, Policy 4, Recovery 3, Request 22, Sidecar 4, Validation 3. | `test/.tmp/654-path-encoding-capability.log`; `test/.tmp/java-it-4688476766153924730/s-1..s-53/evidence.json`; retained full Failsafe XML under `test/.tmp/654-path-encoding-reports/failsafe` |
| Independent UT command above, with PATH limited to the JDK bin and Windows System32 | **200 UTs passed**, zero failures/errors/skips; Maven test passed. No optional Node/sh/PowerShell runtime, IT profile, package, fixture build or attach setup. | `test/.tmp/654-path-encoding-independent-ut.log` |
| Explicit smoke command above, invoked using absolute `-f` from the sibling `test` working directory | **1 smoke IT and 16 focused UTs passed**, zero failures/errors/skips; Maven verify, Checkstyle and PMD passed. No Spring fixture profile or attach. | `test/.tmp/654-path-encoding-smoke.log`; `test/.tmp/java-it-10516767020487098515/s-1/evidence.json` |

The capability run recorded 53 passed scenarios and 53 successful cleanups.
Its 106 distinct owned PIDs were checked stopped, no scenario workspace remained,
and no files were retained. Smoke also recorded successful cleanup with no
retained files. `git diff --check` passed. Final edits after these runs are
documentation-only.

Applicable change-impact evidence: Gate A uses fresh real strict-line hits and
persisted runtime identity; Gate C uses actual request capture for the supported
HTTP forms and deterministic invalid-input cases; Gate D uses packaged CDE smoke
and synchronized result-field documentation, including the explicit CWE output
correction; Gate E uses executable commands from both supported caller locations
and consistent module guidance; Gate G identifies retained TS behavior sources,
keeps Core independent of application/transport frameworks, preserves Sidecar
sources and records the affected Maven counts and actual STDIO execution.

The revised inventory, including regressions for R1/R2/R3/R4, has successful recorded
implementation checks; there are no required skipped or defect-blocked scenarios.
These results are the implementer evidence handoff, not an independent recheck
or approval. The user supplied a targeted recheck confirming R1/R2/R3 and
identifying R4; this task has not dispatched an additional independent review.
The stated
unsupported transport cases are explicit N/A outcomes, not missing HTTP checks.
This verification establishes Windows execution against controlled local
fixtures. Unix refresh execution, external application exploitability, actual
LLM Skill-use acceptance and complete repository testing were not performed.
Cross-feature acceptance remains with its owning children; no retired MCP route
was executed or restored.
