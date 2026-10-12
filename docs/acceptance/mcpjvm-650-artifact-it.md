# #650 Artifact lifecycle integration acceptance

Scope: the 31 retained `artifact_management` CDE operations, filesystem storage,
SQLite projection/migration, registry refresh and Skill-facing configuration.
The packaged application, existing process driver/JavaItScenario and immutable
Spring fixtures are reused. Suite execution is used only to supply authentic run
Artifacts for storage assertions. No Node invocation route is restored.

## Production correction record

Before repair, `invalidProjectsTraversalAndFailedProvisioningNeverOverwriteAuthoritativeData`
failed against the packaged server: obstructing `projects.json` with an owned
nonempty directory produced `project_artifact_write_failed`, but the newly
provisioned empty `run-state.sqlite` remained. Evidence:
`test/.tmp/650-artifact-pass2.log`, scenario `java-it-6448879165006739097/s-4`.
Existing ProjectContextOperations explicitly attempts removal of a new, empty
store after a failed project write. Its collaborator
`core/.../artifactmanagement/artifact/SqliteRunStateProvisioningCleanup.java`
tries to move the SQLite file while its JDBC connection is open, which Windows
rejects. Correction: inspect under the existing project mutation lock, close
the JDBC connection before moving, and retain conservative preservation for
populated stores, cutover markers and WAL/SHM files. No project or run format
changes are needed. Focused regression: the packaged scenario above plus the
existing Artifact operation/safety tests.

`actualProducedWatcherVerificationResultsRebuildRollbackAndRestart` also exposed
the direct execution envelope boundary: TrustedDirectSuiteRun persists real
Watcher and external-verification outputs beneath `details`, while
SqliteRunStateArtifactReader/ProjectionWriter only inspect top-level fields or
standalone state files. Expected: rebuild projects those actual produced
results, retaining the authoritative JSON format. Correction is confined to
`core/.../artifactmanagement/artifact/SqliteRunStateArtifactReader.java`:
normalize existing direct-result detail fields into the in-memory projection
before scanning sidecar files; do not rewrite the original Artifact.

`futureSqliteSchemaIsRejectedWithoutChangingAuthoritativeState` reproduced a
third defect (`test/.tmp/650-safety-before.log`): after adding a controlled future
schema version to the actual Java database, query still returned success.
The database owner did not check versions before reads or maintenance. Repair
`SqliteRunStateDatabase.open` to reject a newer schema deterministically before
any schema mutation, and close the connection on failed initialization.
This protects the current Java schema; it adds no old-schema importer.

### Bounded review repair record — 2026-10-12

The user supplied two P2 findings against this working tree. First, projection
normalization copied evidence out of `details` while retaining the original
copy. Their packaged reproduction accepted a 40,766-byte blocked result but
failed rebuild at the existing 64 KiB projection limit; equivalent top-level
data succeeded. The expected contract is bounded storage of the same evidence
without amplification or rewriting the authoritative run Artifact. The repair
owner is `SqliteRunStateArtifactReader.includeSidecarState`: move promoted fields
within its deep copy, preserve unrelated `details` metadata and retain existing
top-level precedence. No size limit is raised.

Second, authentic checkpoint/resume evidence did not prove continuation
projection. Extend `realSuiteContinuationSurvivesServerRestartWithoutRepeatingCompletedPlan`
to submit the actual orchestration checkpoint unchanged through supported
`run_result.upsert`, read its produced progress summary, rebuild, and compare
that summary exactly with `suite_continuation_state` before/after MCP restarts
and terminal completion. This tests the current ingestion contract; it does not
claim automatic projection of the orchestration owner's separate canonical file.
The pre-repair packaged test reaches `invalid_input`: the existing continuation
table is absent from both the query operation's public schema and the backend
surface allowlist. Expose this existing table only through `run_result.query`,
as the review explicitly requests. The query builder also always orders by
`plan_name, run_id`, columns absent from `suite_continuation_state`; use its
actual `suite_run_id` key for ordering and parameterized identity filtering.
Directly affected owners: `ArtifactOperationSchemas.run`,
`SqliteRunStateQueryContract.tableFor` and `SqliteRunStateQueryPlanBuilder`.
No new operation, storage format, schema version or source discovery route is
introduced.

Pre-repair regression execution is recorded in
`test/.tmp/650-review-before.log`: the new near-limit blocked-input test and
the extended actual checkpoint scenario exercise the packaged server, filesystem
and SQLite. Reviewers were not dispatched by the implementer; this repair batch
responds directly to the user's bounded review findings.

## User-directed legacy disposition — 2026-10-12

The user explicitly directed: "Dont add legacy code, should be removed enirely
but keep core functionalities" after the v11 TypeScript/v3 Java store conflict
was identified. Compatibility with the retired TypeScript SQLite layout and
`correlation-index.json` is intentionally retired by that decision. No importer,
coexistence route or new storage format is introduced. The obsolete `result.json`
fallback is removed from run reads/rebuild; `execution.result.json` remains the
authoritative current filename. Current Java correlation backfill, rebuild,
cutover, bounded retention, restart and continuation remain required.

## Commands and prerequisites

Java 21 and Maven 3.8.6+; SQLite JDBC is a normal product dependency. Build the
Sidecar artifacts before packaging the server (see the shared
[IT contract](mcpjvm-646-java-it.md)). Artifact smoke needs only the packaged
server and its packaged Sidecar files, and launches no target fixture/JMeter.
The complete capability also uses user-app and the existing event producer/
consumer fixtures and real Sidecar attachment. Fixture preparation remains
opt-in through the existing `java-it-fixtures` profile.

```sh
# Independent UT; no integration setup
mvn -B -f mcp-server/pom.xml -pl application -am test
# Additional quick smoke
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it,java-it-artifact-smoke
# Entire Artifact capability (includes real correlation evidence)
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it,java-it-artifact,java-it-fixtures
# Individual scenario
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerArtifactStateIT#actualProducedWatcherVerificationResultsRebuildRollbackAndRestart'
```

The shared document publishes the explicit full IT command; it remains opt-in
and is not run by #650. Valid existing prerequisite artifacts may be reused
without enabling fixture packaging. Missing prerequisites fail; no assumptions
or skips convert absence into passing evidence. All generated state is beneath
the dynamically resolved `test/.tmp/java-it-<run>/s-*/w-*`; compact shared path
segments preserve Windows path space. Default teardown removes workspace files,
retains sanitized `evidence.json`, and stops owned processes regardless of file
retention. `-DmcpItRetainFiles=true` retains files for diagnosis. JUnit/Failsafe
reports are in `application/target/failsafe-reports`.

## Coverage inventory (execution results recorded below)

Method names are stable scenario IDs. The helper records operation IDs/statuses/
reason codes in each scenario's sanitized evidence. The operation-family groups
below are exact equivalence groups, not an assertion that discovery alone proves
their behavior. Product Artifact JSON is compared semantically; SQLite is
inspected as logical rows, never binary snapshots.

| Scenario | Operations/variants and assertions | Skill consumers |
| --- | --- | --- |
| catalogAndDescribeExposeExactlyTheArtifactActionAllowlists | Exactly 31 owned IDs, Describe schema, reject extra filesystem input for every operation, reject disallowed actions | All Artifact consumers |
| registryRoundTripReloadInvalidInputAndRestart | probe_config read/validate/upsert/reload; missing config, confirmation/no write, two-probe registry, reload count, invalid defaultProbe preserves source, restart | Probe Registry Manager |
| projectMergeReplaceSelectorsAndRestartPreserveConfiguration | project_context read/validate/upsert/list; implicit/explicit/root selection, merge default, replace opt-in, preserved profiles/bindings, filtered reads, ambiguity/mismatch, restart | Project Artifact Manager, Project State Migration |
| invalidProjectsTraversalAndFailedProvisioningNeverOverwriteAuthoritativeData | Invalid project schema, traversal/absolute selectors, failed real file replacement, new-store cleanup, authoritative bytes preserved, no temporary files | Project management/migration |
| planLifecycleRoundTripsRealFilesAndSurvivesRestart [regression/performance/security] | Each family's read/validate/upsert/list, missing source, confirmation, real metadata/contract/Markdown round trip, invalid input preserves source, traversal, restart | Three Plan Crafters/Result/Diagnostic workflows |
| suiteSpecificValidationAndRegressionProjectionWindows | Suite-specific rejection, summary defaults, explicit sections, required bounded steps/prerequisite windows | Regression crafter/diagnostic |
| exportGenerateReadListSecretsAndRestart [ps1/sh/postman] | execution_export generate/read/list; confirmation, deterministic ID, manifest/files, no resolved secret in any generated file, restart | Regression Export |
| performanceExportAndInvalidExportInputsStayWithinOwnedPaths | Performance ps1/sh bundles/JMX, blocked Performance Postman and Security replay exports, invalid format/profile, export ID traversal and requiredness | Performance Export |
| resolvedExportSecretsRequireExplicitConfirmedOptInAndStayOutOfMcp | Explicit confirmed secret-bearing export writes the marked environment file; MCP responses/stdout/stderr exclude the resolved value | Regression/Performance Export |
| realSuiteContinuationSurvivesServerRestartWithoutRepeatingCompletedPlan | Actual two-plan fixture execution; ingest its unchanged checkpoint through run_result.upsert, read its progress summary, rebuild/query suite_continuation_state exactly, verify identity filtering and persistence before/after two restarts and terminal completion; completed first result bytes preserved, current ready/pass owner statuses and actual runStatus=pass | Suite/Result/Diagnostic workflows |
| nestedEvidenceProjectsOnceWithinTheExistingSizeLimit | Near-limit blocked storage input and equivalent top-level control both upsert/read/rebuild; actual watcher_state values match, SQL state_json retains one normalized evidence copy plus metadata, authoritative file bytes unchanged | Run projection/diagnostics |
| actualProducedWatcherVerificationResultsRebuildRollbackAndRestart | Real fixture HTTP trigger/Watcher/external verification, run_result read/upsert/rebuild/query/backfill/cutover, exact state projection, idempotency, strict rollback under truncated source, pagination/filtering/injection safety, restart, projects.json preservation | Suite Result/Diagnostic, Project State Migration |
| blockedRunStorageDefaultsRedactionAndBoundedRetention [three suites] | run_result upsert/read/list/rebuild/query/cleanup, default regression, blocked storage input (not runtime success), secret removal, continuation projection, dry-run default, bounded repeated cleanup preserves newest/source files | Migration/diagnostics |
| lockedCorruptAndMissingStoresFailDeterministicallyWithoutLosingProjectData | Absent store without bootstrap, real interprocess lock, corrupt SQLite, authoritative project preservation, traversal, state-surface allowlist | Migration/diagnostics |
| linkedArtifactDirectoryCannotReadOrWriteOutsideTheBoundWorkspace | Real symlink/junction redirects to a separate owned workspace; registry/project/run maintenance reject escape without reading or writing target | All Artifact consumers |
| invalidRegistryReadFallsBackOnlyToAcceptedSnapshotAndRecovers | Controlled interrupted JSON preserves accepted live registry summary; validate/reload fail, supported upsert/reload repairs it | Probe Registry Manager |
| futureSqliteSchemaIsRejectedWithoutChangingAuthoritativeState | Real database future-version marker blocks query/rebuild/backfill/cutover/cleanup with stable reason; projects.json and cutover state preserved | Project State Migration |
| retiredResultFilenameCannotBecomeAuthoritativeRunState | Retired result.json is not read/projected; strict rebuild rejects missing current result, non-strict reports invalid source without rows; supported upsert creates execution.result.json, which alone is read/projected | User-directed retirement |
| executableJarCorrelatesCrossServiceEventsWithConsumerRuntimeEvidence + ArtifactCorrelationAssertions | Existing real producer/consumer Sidecar correlation and cross-plan flow; run_result read/rebuild/query/backfill consumes and compares authentic correlation files, idempotency, source preservation | Regression Suite/Result, migration |

Actual Skill/LLM execution is a separate claim. Repository-owned registry,
project and migration Skill instructions were read as consumer requirements.
Historical TS tests inform storage/retention/migration behavior; their retired
MCP envelopes are not an acceptance path.

Existing focused Java behavioral tests are reused for deadline/caller-interruption
and deterministic completion after the caller returns. `ArtifactProbeProjectOperationSafetyTest`,
`ArtifactPlanOperationSafetyTest` and `ArtifactRunExportOperationSafetyTest`
parameterize their complete owned action sets (8, 12 and 11 operations).
They use the real Artifact owners and filesystem with a controlled blocking
workspace boundary, assert timeout/cancelled caller reasons, then release it
and assert final operation completion and persisted effects. These are owner-level
tests, not packaged-process timeout claims. Probe/Project interruption uses its
representative project-list boundary; Plan and Run/Export interruption parameterize
all owned rows. The packaged catalog independently
checks that every Artifact operation declares cancellation unsupported. No
production fault-injection hook or duplicate process test framework is added.

## Verification

Tested working tree based on `388533ed78de9fc5cbe279db9791f338b3e9fe4f`,
2026-10-12, Windows 11 amd64, Amazon Corretto 21.0.12.1, Maven 3.9.12,
SQLite JDBC 3.47.1.0. The packaged server is 0.1.9; existing Sidecar/helper
0.1.8 and user/event Spring fixture 0.1.0-SNAPSHOT JARs were reused. The fixture
sources and Sidecar sources are unchanged.

Actual targeted commands, from the repository root. Below, `mvn` abbreviates
the installed `C:/Tools/apache-maven-3.9.12-bin/apache-maven-3.9.12/bin/mvn.cmd`;
PowerShell requires quoting the dotted properties:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it,java-it-artifact '-Dtest=ArtifactManagement*Test,SqliteRunStateStoreTest,ArtifactSafetyTest,Artifact*Operation*Test' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it,java-it-artifact '-Dtest=NoAdditionalUnitTestsFor650' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerArtifactPlanIT#performanceExportAndInvalidExportInputsStayWithinOwnedPaths' '-Dtest=NoAdditionalUnitTestsFor650' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it,java-it-artifact-smoke '-Dtest=NoAdditionalUnitTestsFor650' '-Dsurefire.failIfNoSpecifiedTests=false'
```

| Evidence | Result |
| --- | --- |
| `test/.tmp/650-current-storage.log` | Initial delivery: all 260 affected owner/operation UTs pass, zero failures/errors/skips. Two new IT assumptions failed and were corrected to assert actual persisted runStatus and strict missing-source handling; no production behavior was weakened. |
| `test/.tmp/650-artifact-final.log`, `java-it-3071438497710962779/s-*/evidence.json` | Complete 24-scenario selection: 23 pass, one export test input failed before the intended Security rejection because its URL was unresolved. All scenario cleanup succeeded. |
| `test/.tmp/650-export-final.log`, `java-it-10226512465754974254/s-1/evidence.json` | Corrected the export test to provide a resolvable URL before asserting Security is unsupported. Its focused rerun passes (one test, zero failures/errors/skips); other 23 scenarios and production code are unchanged. |
| `test/.tmp/650-artifact-smoke.log`, `java-it-13700394459206969737/s-1/evidence.json` | Explicit smoke profile selects exactly the registry lifecycle/restart test: one pass, zero failures/errors/skips; cleanup succeeds. No fixture setup runs. |

The initial delivery runs collectively verified 24 Artifact scenarios. Maven
Enforcer, Checkstyle and PMD pass. The capability evidence records all 31 owned
operation IDs. After its teardown, all 73 owned PIDs were absent and no `w-*`
scenario workspace remained; the corrected export scenario also reports
successful cleanup. Evidence contains only scenario/operation identifiers,
outcomes, reasons, environment versions and cleanup/process metadata.

Initial sandbox execution failed ordinary atomic file writes in unchanged
Artifact UTs; the same affected selection outside the Windows sandbox passed.
This is environment evidence, not a production correction. No complete
repository test suite, unrelated capability lane, Node runtime or external Skill
execution was run. Builds use the affected server `-pl application -am`
selection. Legacy compatibility is retired by the
explicit user decision above; the current Java lifecycle and core functionality
remain covered.

### Bounded review repair verification

The initial 260-UT/24-scenario evidence above remains historical evidence.
The bounded repair changes projection normalization and the continuation query
surface. Affected owner/registration/safety tests and real storage/correlation
scenarios are rerun; unrelated configuration, plan and export scenarios reuse
the initial evidence.

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerArtifactStateIT#nestedEvidenceProjectsOnceWithinTheExistingSizeLimit+realSuiteContinuationSurvivesServerRestartWithoutRepeatingCompletedPlan' '-Dtest=NoAdditionalUnitTestsFor650' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerArtifactStateIT,McpServerCorrelationIT' '-Dtest=SqliteRunStateStoreTest,ArtifactRunExportOperation*Test,ArtifactManagementOperationsTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- Before repair: both targeted packaged scenarios fail as recorded in
  `test/.tmp/650-review-before.log`, evidence root
  `test/.tmp/java-it-5915517922450986897`. The near-limit case reaches
  `state_store_rebuild_failed`; the continuation query reaches `invalid_input`
  at its missing public allowlist entry. Both scenarios clean up successfully.
- Added owner regression `continuationQueriesUseSuiteIdentityForFilteringAndPagination`
  covers two identities, ascending stable pagination, exact identity filtering
  and parameterized injection-like input against actual SQLite.
- Post-repair: `test/.tmp/650-review-repair.log` reports **BUILD SUCCESS**,
  **70 affected UTs** and **8 packaged IT scenarios**, all with zero
  failures/errors/skips. Enforcer, Checkstyle and PMD pass. The new near-limit
  case compares both SQLite `state_json` values with the equivalent top-level
  control, verifies actual watcher-state content and unchanged authoritative
  bytes. The actual checkpoint case verifies in-progress and completed
  continuation state through read/rebuild/query and two MCP restarts.
- Evidence root: `test/.tmp/java-it-12171303813844921190/s-*/evidence.json`.
  All eight records report passed/cleanupSucceeded, file retention is disabled,
  all 26 owned PIDs are absent and no scenario workspace remains.
- The current inventory has 25 scenarios: these eight freshly verified scenarios
  plus 17 unaffected configuration/plan/export/safety scenarios with retained
  passing evidence. `git diff --check` passes. No unrelated capability or
  complete repository suite was rerun.
