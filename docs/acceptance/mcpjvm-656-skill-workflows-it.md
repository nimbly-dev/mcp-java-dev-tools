# #656 cross-feature and Skill-facing Java acceptance

Historical acceptance record. The legacy invocation prose and Node startup
limitation recorded below describe the #656 revision, not current instructions.
[#658](mcpjvm-658-cde-skills.md) supersedes that guidance and separately records
selected actual Skill use. The automated evidence below retains its original
scope; it does not become an all-Skill prose-execution claim.

This child consolidates #646–#655 and adds deterministic packaged-MCP workflows.
It preserves the accepted #611/#644 Java-only CDE runtime, Sidecar architecture,
operation schemas and Artifact contracts. The bounded run-companion persistence
correction below is the only production change; no runtime directory or new
module is introduced. The baseline is `d4646bbc97d52418f4a62da1d547164e5d41c650`.

## Coverage and evidence ownership

[java-it-coverage.json](java-it-coverage.json) is the test-only consolidated
inventory: 54 operation IDs, their capability owners, runnable scenario selectors,
assertion source and existing capability inventories, prerequisite/fixture and
evidence references, cleanup, and all 22 repository-owned Skills. Scenario IDs
are `Class.method`; parameter variants are defined by the referenced Java method
and recorded in Failsafe XML. The capability documents remain the detailed
behavior inventories; their exact assertions/evidence are reused, rather than
creating another interpretation of supported behavior. Broad shared schema tests
prove common binding/allowlist behavior only. Runtime scenarios in each child
prove that child's distinct effects, options, failures and safety semantics.

| Owner | Retained behavior inventory and complete capability selection |
| --- | --- |
| #646 | [Process, Roots, STDIO, status and cleanup](mcpjvm-646-java-it.md); `java-it-lifecycle` |
| #647 | [Discovery, fenced attach/deactivate, probes, capture, actuation, profiler](mcpjvm-647-jvm-probe-it.md); `java-it-jvm` |
| #648 | [Source/runtime discovery, recipes, routes, unsupported adapters, validation](mcpjvm-648-route-synthesis-it.md); `java-it-route` |
| #649 | [Real exception, fingerprint, reproduction, investigation limits, proposal/report inputs](mcpjvm-649-failure-analysis-it.md); `java-it-failure` |
| #650 | [Registry/project/plans, SQLite, migration, exports, safety and persistence](mcpjvm-650-artifact-it.md); `java-it-artifact` |
| #651 | [HTTP/HTTPS, auth, bounds, unsupported transports, redaction and cancellation](mcpjvm-651-transport-it.md); `java-it-transport` |
| #652 | [Regression, fresh probes, cross-service correlation, Watchers, HTTP/SQL, recovery](mcpjvm-652-regression-it.md); `java-it-regression` |
| #653 | [JMeter/builtin workloads, thresholds, JFR/MSTA, attribution, recovery](mcpjvm-653-performance-it.md); `java-it-performance` |
| #654 | [Black-box/Sidecar-assisted Security, knowledge, findings, safety, recovery](mcpjvm-654-security-it.md); `java-it-security` |
| #655 | [Orchestration, leases, slicing, lifecycle, receiving-machine replay](mcpjvm-655-orchestration-it.md); `java-it-orchestration` |
| #656 | This document; `java-it-workflows` |

The new inventory scenario reads **every page of the real packaged catalog**,
compares its identities with the inventory, describes every entry, rejects an
unknown ID and checks the repository Skill dependency inventory. New or unmapped
registrations fail acceptance. This test does not claim execution coverage from
presence. Java's existing aggregate directory remains the only executable owner.

Run `node mcp-server/test-support/java-it-coverage.mjs` to validate the static
inventory and produce sanitized `test/.tmp/656-coverage-report.json`. It reads
only the shared support's bounded per-scenario evidence, never raw transcripts or
product payloads. `--require-workflow-evidence` additionally fails if a required
workflow has no passing cleanup-complete evidence. The report distinguishes
observed earlier evidence, latest attempts, missing local evidence and current
source hashes. A prior pass is **not** automatic proof after source, inputs or
environment change; review the owning document's final repair/reuse dispositions.
The script deliberately does not certify freshness or comprehensive acceptance.

## Cross-feature scenarios

Tests are organized around product workflows and their outcomes. Skill prose
supplies requirements and consumers; it is not a script to reproduce word for
word, and a Skill does not require its own IT class. Several Skills can consume
one workflow's evidence, while a workflow can exercise several capability owners.
The dedicated investigation-to-regression and downstream-recovery classes below
expand the scenarios beyond individual Skill sequences. Inventory and helper
checks remain supporting contract checks rather than substitutes for workflows.

| Scenario ID | Semantic assertions, real boundaries and inputs |
| --- | --- |
| McpServerInvestigationRegressionWorkflowIT.reproducedExceptionBecomesAFailingGuardThatSurvivesRestartAndHealthyTraffic | Real exception → matching capture/reproduction → explicit rejected-DELETE regression expectation → failed HTTP run and persisted evidence → server restart and SQLite diagnosis → independent healthy run. Assert actual HTTP 500 and established `transport_failed` classification; the saved expected 405 remains unchanged. The failed run remains immutable and no source fix is applied. Spans Failure Analysis, Probe, transport, Regression and Artifact persistence. |
| McpServerDownstreamRecoveryWorkflowIT.restartRetainsTerminalFailureAndARepairedDownstreamRequiresANewRun | Watcher and external-verification variants: actual downstream failure → terminal suite/plan evidence → restart and phase-specific diagnosis → repair controlled downstream → retry old run without traffic or status rewrite → new run against unchanged expectations → passing evidence alongside the original failure. Spans orchestration, Regression, transport, SQLite and Artifact consumers. |
| McpServerRunArtifactRecoveryIT.failedCanonicalMoveRollsBackCompanionsBeforeReadAndRetry | Windows native no-delete handle denies the canonical replacement through the actual MCP server. The failed update preserves all four previous files byte-for-byte, readback agrees, and a retry after releasing the handle publishes the new result and companions. |
| McpServerRunArtifactRecoveryIT.largeMixedShapeEvidenceRemainsReadableAndProjectsOnceAfterRestart | Nested, root, duplicate and direct-result variants retain root steps and nested metadata; 14 Watchers remain readable and rebuild/query successfully after restart under the existing SQLite limit. Source Artifact bytes stay unchanged. Inputs exercise the storage contract rather than fabricate successful runtime suite execution. |
| McpServerSkillWorkflowIT.inventoryMatchesPackagedCatalogAndEverySkillDependency | Actual JAR CDE catalog pagination and Describe for all 54 operation IDs; exact inventory identity, unknown-operation rejection, 22 Skill dependency presence. Smoke selects only this method. |
| McpServerSkillWorkflowIT.independentWorkspacesResumeInspectMigrateAndReplay | Two simultaneously running packaged servers have identical project/profile/plan/run names in different owned workspaces. Actual HTTP trigger/Watcher/external phases persist; restart resumes without repeating the trigger. The other run remains byte-identical. MCP and direct JSON result/evidence reads, SQLite Watcher/verification queries and bundled summary/step helpers consume actual results. Read/validate/rebuild/backfill/cutover/dry-run cleanup preserve projects.json. ps1/sh/Postman exports replay an actual request through their receiving tools. |
| McpServerSkillWorkflowIT.repositoryHelpersPreserveBlockedFailedAndCleanupBoundaries | Execute all six repository Node helpers using bounded child processes. Missing prerequisite, unreachable strict Probe, disabled auto-start and failed step remain blocked/failed. Summary retains failure reason. Cleanup helper returns a decision only; JavaItScenario proves real process cleanup. These negative helper inputs are not fabricated runtime results. |
| McpServerSkillSuiteIT.lifecycleRegistryAndAllSuiteResultsRemainConsumableAfterRestart | Discover the owned JVM, reject unconfirmed attach, attach and verify the real Probe through a persisted registry. Create project and Regression/Performance/Security plans. Actual HTTP, concurrent builtin workload with positive strict hits, and controlled Security matrix produce three distinct runs with the same plan/run names. Restart/rebuild, read via MCP and directly, and assert type-specific metrics/coverage/evidence. Reset proves persisted line observations are historical. Deactivate and verify target survival. |
| McpServerRouteSynthesisIT.discoveryRecipeTransportEffectAndCapture | Reuse #648: exact source/runtime target, synthesized request, actual application effect, fresh hit/capture, actuation cleanup. |
| McpServerFailureAnalysisIT.realTraceTargetTriggerCaptureAndProposalEvidence | Reuse both #649 guided/hands_off variants: actual fixture exception/JFR, bounded analysis, route, real DELETE/500, strict hit/capture and matching reproduction. Local sanitized proposal/report inputs; immutable source, no automatic fix or external publication. |
| McpServerCorrelationIT.executableJarCorrelatesCrossServiceEventsWithConsumerRuntimeEvidence | Reuse #652: actual producer and consumer JVMs, Sidecars, correlation continuity, consumer runtime evidence and persisted Artifacts. |
| McpServerPerformanceProfilerIT.realWorkloadOwnsJfrLifecycleSampledMethodAttributionAndCorrelation | Reuse #653: actual JMeter, strict hits, JFR lifecycle/download, MSTA/attribution and persisted correlation consumed by result workflows. |
| McpServerSecuritySidecarIT.realFreshRequiredAndForbiddenHitsDistinguishPassInternalAndCorroboratedFindings | Reuse #654: actual Sidecar observations and HTTP proof; complete coverage and external/internal/corroborated classifications remain distinct. |

The full workflow lane contains 20 invocations of the 13 methods above, including parameter variants;
its smoke is a separate single method. Capability children retain their complete
validation/default, failure/blocked, confirmation/redaction, timeout/cancellation,
recovery and cleanup selections. They are not replaced by this composed lane.

## Skill mapping and direct dependencies

The inventory records the canonical operation sequence, Skill file, bundled
references/templates/helpers, direct Artifact mentions and scenario consumers for
every Skill. Read-only source inspection of those repository files, including
the referenced report/sanitization contracts and helper implementations, defines
this mapping. Installed global Skill copies are never needed by tests.

Historical Tool/action notation maps to `operation_execute` with `operationId`,
`arguments` and outer `confirmed`; discover the exact schema with
`operation_describe`. For Artifact actions use
`artifact_management.<artifactType>.<action>`. This is documentation/test mapping,
not a runtime alias route. Existing Skill prose still contains legacy invocation
examples (including a Node startup snippet); automated compatibility does not
claim that those old examples are directly executable on the CDE runtime.

| Skill consumers | Ordered operation and Artifact/helper boundary |
| --- | --- |
| Line Probe Run | Probe check → project validate → recipe → reset → wrapped trigger → bounded wait/status/capture. Strict runtime line key is required. |
| JVM Lifecycle | list → select exact owned PID/start fence → confirmed attach → check/status → confirmed deactivate. Registry persistence belongs to Registry Manager. |
| Bug Drill / Bug Fix proposal | bounded source/route selection → check/status/reset → complete wrapped trigger sequence → bounded wait/capture. Optional bounded actuation/profiler; cleanup. Bug Fix consumes explicit issue/expectation/repro inputs and remains proposal-only. |
| Failure Lens / Issue Report | real trace → analysis → route/trigger → fresh capture → reproduction → sanitized local report inputs. Report template and sanitization rules are direct dependencies; no publication. |
| Registry / Project management | read/list → validate → upsert → readback; Registry reload applies runtime state. projects.json and probe-config.json remain owned Artifacts. |
| Project State Migration | full sanitized project read/validate/preserve → rebuild → correlation-only backfill → explicit cutover → query; dry-run retention before applied cleanup. Canonical run files are not rewritten. |
| Regression crafter / execute / result / export / diagnostic | validate/upsert/read generated metadata/contract/plan → paged project/plan reads → execute_plan or same-run orchestration slices → run_result read/query → export and real replay. Direct consumers require execution.result.json and evidence.json; continuation/correlation/Watcher/verification fields remain available. Node decision/summary helpers are individually exercised. |
| Performance crafter / execute / result / export | generated workload contract → project/plan reads → real strict-hit workload/profiler → run_result and optional JFR/MSTA/correlation reads → export/replay. Result proof comes from persisted metrics and timing evidence, never a log-only reconstruction. |
| Security crafter / execute / result / diagnostic | mode and knowledge selection → validate/upsert/read → real execute/orchestration → run_result read/query, immutable knowledge/coverage/findings/evidence/proof classification. Diagnostics remain read-only. |

All 22 mappings distinguish **automated Tool/Artifact/helper compatibility** from
**actual Skill use**. No conversational Skill interpreter or LLM was run here;
prose-following, report quality and interactive Skill use are not validated.
These are limits on prose-execution claims, not a requirement for one IT per
Skill or for LLM-driven acceptance. The automated workflow lane expands product
behavior independently of how a Skill happens to phrase its instructions.
The legacy invocation examples are a visible limitation, not retired capabilities
or evidence that old MCP routes still work. #611/#644 explicitly retired those
routes; that decision is recorded separately in the inventory. Any required
partial/missing behavior blocks comprehensive acceptance until repaired or
specifically excluded by the user; no new exclusions are made by this ticket.

## Prerequisites and runnable commands

Use Java 21+ **JDK** with attach, jcmd/JFR and keytool, Maven 3.8.6+, Node.js,
PowerShell, Bash, Newman and Apache JMeter. Complete Java IT additionally requires
a running Docker engine and the images/tools declared in #652/#655 for PostgreSQL
and native Linux replay. Required missing tools fail the selected lane. Ports and
all writable scenario input are allocated at runtime beneath resolved
`test/.tmp/java-it-<run>/s-<scenario>/w-<workspace>`, independent of caller cwd.
Fixture source is immutable. Normal build outputs remain in module targets.

From a clean checkout, build reusable Sidecar artifacts first:

```powershell
mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package '-DskipTests'
```

Provision the selected receiving tools separately and pass discovered locations;
do not put personal paths into test source. Newman can be installed in a
repository-local temporary tools directory. Configure:

```powershell
$itTools = @(
  '-DmcpItBash=<absolute Bash executable>',
  '-DmcpItPowerShell=<absolute pwsh executable>',
  '-DmcpItNode=<absolute Node executable>',
  '-DmcpItNewmanScript=<absolute newman/bin/newman.js>',
  '-Dmcpjvm.acceptance.jmeterHome=<absolute JMeter home>'
)

# Independent UT: test phase has no Sidecar/fixture/JMeter/IT setup.
mvn -B -f mcp-server/pom.xml -pl application -am test

# Smoke only: one real packaged CDE inventory check, no fixture/Node/JMeter use.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-workflows-smoke'

# Complete #656 lane: build required immutable fixtures and run every workflow.
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-workflows,java-it-fixtures' @itTools

# Individually runnable class/method; reuse already-built unchanged fixtures.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-workflows' `
  '-Dit.test=McpServerSkillWorkflowIT#independentWorkspacesResumeInspectMigrateAndReplay' @itTools

# Expanded investigation/recovery workflows; JDK/Sidecar/post-app required,
# no JMeter, Newman, Bash or PowerShell receiving-tool setup.
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-workflows' `
  '-Dit.test=McpServerInvestigationRegressionWorkflowIT,McpServerDownstreamRecoveryWorkflowIT'

# Explicit COMPLETE Java IT/FT command: no group selector or it.test filter.
# Includes event-app, producer/consumer, post/user fixtures and Docker/platform cases.
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-fixtures,java-it-regression-fixtures' @itTools

node mcp-server/test-support/java-it-coverage.mjs --require-workflow-evidence
```

Append `-Dtest=ArtifactManagementOperationsTest
-Dsurefire.failIfNoSpecifiedTests=false` to the selected verification commands
to reuse unaffected UT evidence and run only the affected Artifact UT. It does
not narrow Failsafe selection. Each capability profile in the table above must
be paired with `java-it` and its documented fixture/tool prerequisites. The
complete command intentionally has no capability profile, so all `*IT.java`
tests are discovered; no smoke tag or empty group is substituted for full IT.

Surefire/Failsafe discovery remains disjoint. New profiles only select groups;
expensive fixture setup remains opt-in. Reports are in owning module
`target/surefire-reports` and `application/target/failsafe-reports`. Shared
`evidence.json` records scenario IDs, outcomes, bounded observations and cleanup.
`-DmcpItRetainFiles=true` retains files only, never processes. Cleanup is asserted
after normal completion, setup failure, timeout and cancellation through the
existing shared support/capability scenarios.

## Verification record

### Reproduction before the bounded production correction

`test/.tmp/656-workflows.log` reproduced three missing `evidence.json` failures
after genuine successful trigger/Watcher/external continuation across restart.
The selected independent workspace stayed isolated; the result consumer failed
because the canonical companion file was absent. The existing
`skills/mcp-java-dev-tools-regression-result/references/spec-rules.md`,
Performance Suite `references/artifact-contract.md`, and run Artifact schema
require evidence/context companions. Both direct-suite and orchestration paths
delegate persistence to Artifact Management's `RunResultOperations.upsert`, which
wrote only execution.result.json (and optional correlation).

The substantive correction owner is Core Artifact Management, specifically
`core/.../feature/artifactmanagement/artifact/run/RunResultOperations.java`.
It must write the existing companion file contract from the actual supplied run
outcome, through the same path/redaction/atomic-write boundary, and fail closed on
any companion write failure. It must not invent a successful outcome, runtime
hit, resolved secret context, capture, finding or continuation. Add focused
Artifact UT plus packaged direct/orchestrated regression assertions. Sidecar and
operation registration/schema remain unchanged. This restores the supported
Skill consumer contract under #645/#656's production-correction authorization.

Inspection of the same produced run also showed that step/metric/coverage fields
were nested only under `details`, while the direct result Skill contracts consume
top-level run evidence. The two existing run serializers,
`ExecuteExecutionOrchestrationAction.runPayload` and `TrustedDirectSuiteRun.persist`,
must additionally project their already-produced typed evidence fields onto the
canonical run root. Keep `details`/`directResult` for continuation compatibility;
do not alter arbitrary Artifact upsert payloads or invent missing observations.
The new packaged assertions check both shapes against the same actual evidence.

Failure-path inspection also found that writing a passed canonical result before
its evidence companion could let a later resume consume success after persistence
had returned failure. The writer now prepares both companions first and publishes
the canonical result last, using the existing atomic replacement. The controlled
write-failure UT supplies a passed payload and verifies no new canonical result
is published. This preserves the completion-safety invariant; the Java-specific
write order is clarified in the run Artifact schema. No Artifact filenames or
execution-result fields are removed or reinterpreted.

Recorded on 2026-10-15, against the working tree above the baseline commit.
Host prerequisites: Windows 11, Amazon Corretto JDK 21.0.12.1, Maven 3.9.12,
Node.js 24.19.0, Apache JMeter 5.6.3, existing PowerShell/Git Bash and Newman 6.2.1.
The repository-owned Sidecar 0.1.8 and social-platform fixture 0.1.0-SNAPSHOT JARs
were reused. No globally installed Skill or personal product configuration was used.

| Check | Result and evidence |
| --- | --- |
| Complete #656 lane plus affected persistence/serializer/lifecycle UT | **12 IT and 142 UT passed**, zero failures/errors/skips. `test/.tmp/656-acceptance-final.log` and `656-acceptance-final-summary.xml`; scenario evidence under `java-it-3575532556306124418/s-<1..12>/evidence.json`. |
| Final canonical-publication safety correction | **6 directly affected IT and 53 UT passed**, zero failures/errors/skips. `test/.tmp/656-persistence-final.log`; `java-it-10594895604357434300/s-<1..6>/evidence.json`. The unchanged six runtime/investigation scenarios and other unaffected UT reuse the complete-lane evidence above. |
| Independent UT with missing fixture builder, Sidecar/helper and JMeter inputs | **18 UT passed**; no Failsafe or fixture preparation. `test/.tmp/656-unit-independent.log`. |
| Owning-module smoke with missing fixture builder, Node and JMeter inputs | **Exactly one IT and 18 UT passed**, zero failures/errors/skips. `test/.tmp/656-smoke-final.log`; `java-it-2988232164242920808/s-1/evidence.json`. |
| Inventory/Skill dependency check | 54 supported operations, 22 repository Skills, 228 method IDs; 226 have prior successful local evidence. The two POSIX signal methods are explicitly inapplicable on Windows under #646, whose supported Windows lifecycle has its own evidence. `test/.tmp/656-coverage-report.json`. This is an inventory/reuse statement, not a claim that 228 scenarios were rerun here. |
| Enforcer, Checkstyle, PMD, Node syntax, whitespace | Passed. |

The exact complete workflow invocation was the documented `java-it-workflows`
command with prebuilt unchanged fixtures (omit `java-it-fixtures`), configured
Bash/Newman/JMeter paths, and this affected UT selection:

```text
-Dtest=ArtifactManagementOperationsTest,ArtifactManagementFeatureRoutingTest,ArtifactRunExportOperationRegistrationTest,ArtifactRunExportOperationSafetyTest,SqliteRunStateStoreTest,SuiteOwnerLifecycleTest,SuiteOperationRegistrationTest,ExecuteExecutionOrchestrationActionTest
-Dsurefire.failIfNoSpecifiedTests=false
```

The twelve complete-lane records all report passed, `cleanupSucceeded=true`,
`filesRetained=false`. A read-only PID audit found zero live owned processes.
Initial restricted-host attempts blocked the existing atomic file replacement,
including in repository-local temporary storage; verification with normal
filesystem/process permissions passed. No production policy was weakened.

The final safety recheck used the complete workflow profile plus
`-Dit.test=McpServerSkill*IT` and
`-Dtest=ArtifactManagementOperationsTest,ExecuteExecutionOrchestrationActionTest,SuiteOperationRegistrationTest,SuiteOwnerLifecycleTest`.
It re-executed all three receiving-tool replay modes, the multi-suite restart,
helpers and inventory. Every final record reports successful cleanup and no file
retention; the PID audit found no live owned process. Source hashes, commands,
prerequisite versions, summary locations and cleanup are recorded in
`test/.tmp/656-verification.json`. No raw MCP dumps or generated product state
are committed.

Clean-source fixture/build-order and UT/IT setup-guard evidence from #646 is
reused: new POM profiles only select tags and do not add setup executions.
The owning-module smoke freshly verifies cwd independence; the independent UT
freshly verifies optional runtime inputs are unnecessary. Current root/local
README guidance points to the same complete command and prerequisites.

No complete repository test suite or complete Java IT selection was run or
claimed here. The existing capability documents retain their complete-lane
evidence and final repair dispositions. Actual conversational Skill use remains
unexercised; legacy invocation prose is the separately stated limitation above.
Gates D/E/G apply to the additive Artifact read companions, runnable guidance
and Java acceptance mapping. There is no operation input schema or Sidecar change.

### Workflow expansion following scope clarification — 2026-10-15

The test unit is an expanded product workflow, not a literal Skill transcript or
one IT class per Skill. Two dedicated behavior-focused classes add three cases:
investigation-to-regression plus Watcher/external downstream recovery. All three
passed with successful cleanup and no live owned processes. No production code
changed for this expansion; the prior twelve unchanged cases retain their valid
evidence. The complete workflow profile now selects fifteen invocations across
eleven method IDs. The consolidated inventory now contains 230 method IDs, with
228 observed successes and the same two POSIX-only methods inapplicable on Windows.

`test/.tmp/656-expanded-workflows.log` records both passing downstream variants
under `java-it-6829890230096926464/s-<1..2>/evidence.json`. Its first investigation
attempt exposed a test assumption: HTTP 500 is classified as `transport_failed`
before expectation evaluation. The corrected test requires the actual 500,
blocked step, failed run and unchanged saved 405 expectation. It also selects
the contract projection explicitly rather than assuming the default summary
contains contract steps.

`test/.tmp/656-investigation-expansion-final.log` passed that investigation case
and 18 affected UT with zero failures/errors/skips. Its evidence is
`java-it-6787268573629681306/s-1/evidence.json`. Enforcer, Checkstyle, PMD, inventory
validation and whitespace checks passed. Source hashes and the combined
three-case cleanup audit are in
`test/.tmp/656-workflow-expansion-verification.json`. The individual runnable
selection is documented above; the full fifteen-case profile was not redundantly
rerun because the other twelve cases and production sources were unchanged.

### Three P2 storage blockers — repair record

The user's focused reproductions identified three defects in this child's
production changes: a failed canonical replacement could leave new companions
beside the old result; selecting only `details` dropped root evidence from mixed
payloads; root/nested duplicates exceeded the unchanged 64 KiB SQLite projection
limit. These invalidate the earlier blanket persistence-safety claim.

Before repair, focused regressions were added to
`ArtifactManagementOperationsTest` and `SqliteRunStateStoreTest`: byte-identical
preservation of an existing four-file set after a rejected oversized update,
mixed/root-precedence evidence, and nested/duplicated/direct 14-Watcher storage.
The owning paths are `RunResultOperations`, the existing `ArtifactJsonStore`
write primitive, and `SqliteRunStateArtifactReader`. The bounded correction stages
all run files before publication, restores replaced files on publication failure,
merges actual root/nested evidence with canonical root precedence, and removes
equivalent nested evidence only from SQLite's projection. Canonical envelopes,
file names, secret handling, size ceilings and Sidecar architecture are preserved.

The final packaged repair command selected `McpServerRunArtifactRecoveryIT`,
`McpServerSkill*IT`, `McpServerDownstreamRecoveryWorkflowIT` and
`McpServerInvestigationRegressionWorkflowIT` with `java-it,java-it-workflows`.
**14 IT and 116 affected UT passed**, with zero failures/errors/skips; Maven
Enforcer, Checkstyle and PMD passed. The UT selection covered Artifact operations,
the JSON writer, SQLite, export safety/registration, orchestration serialization,
Suite registration and Suite lifecycle. Logs and copied Failsafe totals are
`test/.tmp/656-p2-packaged-repair.log` and `656-p2-packaged-summary.xml`.

The native Windows fault passed in both Core and packaged MCP tests: the last
move fails after companion replacements, rollback restores the previous bytes,
staging is removed, and retry succeeds. All mixed-shape and 14-Watcher variants
passed. `ArtifactJsonStoreRunSetTest` is Windows-only because it uses the native
no-delete handle; portable oversized-update and evidence/projection checks also
passed. No new production interface, module or dependency was introduced.

The 14 scenario records under `java-it-5468210989110273866/s-<1..14>/evidence.json`
all report successful cleanup and no retained files; no owned process remained
alive during the final audit. Sanitized totals, selected source hashes and reuse
dispositions are in `test/.tmp/656-p2-verification.json`. Inventory validation and
whitespace checks passed. The unchanged six runtime invocations retain the prior
evidence listed above; this targeted repair did not rerun the complete 20-case
workflow lane or the complete Java reactor.

Rollback covers ordinary write failures. Multiple file replacements do not
provide crash-atomic publication or a concurrent-reader snapshot; if the
filesystem also rejects rollback, backups remain staged for recovery. SQLite
normalization removes equal evidence copies and preserves distinct nested data;
genuinely oversized unique projections still fail at the existing limit.
