# MCPJVM-661 — Execution Orchestration ownership

Scope: #661 under #657/#609/#568. #659 was cancelled as not planned and supplies
no prerequisite, baseline exemption or enforcement expansion. Work is limited to
the existing orchestration feature, its behavior tests and this acceptance record.

## State and dependency map

Before extraction, `ExecuteExecutionOrchestrationAction` owned input parsing,
project/profile selection, suite checkpoint validation and fingerprinting,
plan-run recovery/serialization, terminal interpretation, context filtering,
ordered selection/slicing, Suite invocation, aggregate outcomes and lifecycle
coordination. Its existing dependencies were Artifact Management, the three
public Suite features, ObjectMapper, run-directory provider, run lease,
suite-state store, runtime lifecycle and Regression cross-plan correlation.

The sequence remains:

1. Check cancellation, validate input and allocate the suite identity.
2. Acquire the project/suite lease. A busy lease returns before any work.
3. Resolve exactly one profile/workspace; validate its persisted checkpoint
   identity and definition fingerprint; recover existing canonical plan runs
   through Artifact Management before executing new effects.
4. Reuse verified terminal lifecycle evidence, or prepare the existing runtime
   owner and persist its evidence before executing plans.
5. Restore filtered suite context; collect terminal outcomes in profile order;
   honor a prior stop failure; select and limit pending plans. Each new outcome
   is redacted, persisted, promoted only on terminal success and checkpointed
   before advancing. An in-progress Regression continuation returns its slice.
6. Retain the prepared runtime for a slice, or clean it after terminal execution.
   Cancellation performs cleanup and persists evidence before propagating.
   Persist the suite checkpoint and release the acquired lease in `finally`.

The coordinator retains the same exception boundaries: cancellation propagates;
unexpected Suite execution faults become `execution_runtime_failed`. The outer
lease `finally` covers normal, blocked, failed and exceptional exits. Runtime
preparation, terminal cleanup and cancellation cleanup stay in the same flow;
there is no second lifecycle framework or new cleanup policy.

## After extraction and architecture disposition

| Owner | Responsibility and dependency boundary |
| --- | --- |
| `ExecuteExecutionOrchestrationAction` (retained) | One execution coordinator: profile/workspace resolution, ordered selection/slicing, stop/continue policy, direct public Suite calls, envelope composition, aggregate results, cancellation checkpoints, lease lifetime and runtime prepare/cleanup. |
| `ExecutionSuiteCheckpoint` (new concrete owner) | Suite checkpoint identity/fingerprint validation, status serialization, lifecycle evidence and restored-context access. Uses the existing `ExecutionSuiteStateStore` and ObjectMapper; introduces no persistence mechanism. |
| `ExecutionPlanRunArtifacts` (new concrete owner) | Canonical plan-run selectors, recovery, terminal-state interpretation, Regression continuation and run evidence serialization. Calls the intentional Artifact Management boundary; introduces no SQLite/JSON redesign. |
| `ExecutionSuiteContext` (new concrete owner) | Existing scalar allowlist, size bounds, restored/promoted context filtering, outcome context redaction and successful cross-plan promotion. Reuses `RegressionCrossPlanCorrelation`. The coordinator owns the terminal-success predicate. |
| `model/action/execute` records (relocated) | Parsed selection/run identity, resolved profile/workspace and per-plan context. No Artifact I/O, routing or execution. |
| Lease, suite-state store, run-directory provider, runtime lifecycle (retained) | Existing concurrency, workspace storage, path and Application runtime boundaries. Constructors/composition remain compatible. |

The old private copies are removed from the action; no forwarding facade,
executable catalog, interface/implementation pair, new production module or
canonical-ID router is added. Suite executors, export/replay, Sidecar and
Application composition are unchanged. Focused ownership measurements belong
to this record; no permanent legacy ceiling or new enforcement framework is
introduced. Existing architecture checks retain their original scope/rules.

Focused physical file measurements (imports/comments/blanks included, **not**
Java AST class spans): the action changes from 993 to 624 lines; the extracted
checkpoint, plan-run and context owners have 172, 140 and 89 lines respectively.
These measurements describe the ownership change, not new structural ceilings.

## Compatibility and proving evidence

Public CDE registration/schema, outcomes/reason codes and Artifact formats are
unchanged by this extraction. Fingerprint canonicalization, run IDs, persisted
terminal statuses, continuation shape, root evidence projection, context
allowlist and precedence retain their existing algorithms. The TypeScript
orchestration routes were retired by #611/#644; the accepted Java baseline and
the #655/#656 real-effects scenarios are the behavioral compatibility source.

Existing action tests cover terminal-failure retention, slices, profile mismatch,
context filtering and cancellation/deadline cleanup. Added action-level checks
cover terminal retries reusing verified cleanup evidence without repeating Suite
effects or changing stored run payloads, and recovery failure preventing new
effects while releasing the lease. They exercise the public action through the
existing fixtures instead of mirroring each extracted method.

Applicable gates: G for Core ownership and affected Maven/packaged verification;
E for runnable acceptance guidance. Gate D is not triggered: no Tool registration,
mapping, schema or Deterministic Output change. Packaged CDE tests still verify
that boundary. `docs/data-fields/README.md` requires no field updates.

## Verification

The required Core command was run on 2026-10-16:

```powershell
mvn -B -f mcp-server/pom.xml -pl core -am verify
```

It ran **954 UT: 951 passed, 3 failed, zero errors/skips**, including all 17 passing
orchestration action tests. It is a failed verification, not a green Core gate.
Failures:

- `JavaMcpArchitectureEnforcementTest.migratedReferenceProductionGraphSatisfiesBoundedOwnershipRules`:
  unchanged `SqliteRunStateStore` has 13 methods against the existing 12-method rule.
  This was already recorded in #659's cancellation; no exemption is introduced.
- `SuiteOperationRegistrationTest.immediateRetryCannotOverlapAnInProgressRealOwner`:
  Performance and Security variants expect persisted `directResult.reasonCode=ok`
  and observe an empty value. These tests construct the existing Suite executors
  and `TrustedDirectSuiteRun` directly; they do not invoke the orchestration action
  or its new collaborators. The earlier local #660 Core log also records the
  Performance variant failure. Both current failures remain unresolved, outside
  this child's writable scope.

The initial restricted-host attempt was stopped after unchanged Artifact atomic
replacement operations failed under sandbox permissions. The command above was
repeated with normal filesystem/process permissions; counts refer to that completed
run. Logs and copied original XML: `test/.tmp/661-core-verify-permitted.log` and
`test/.tmp/661-core-reports/`.

The complete affected #655 selection was run with all required tools available,
including Docker Engine 29.8.0, JMeter, Newman, Git Bash, PowerShell and Node.
Existing Sidecar JARs were reused; the requested JVM fixture profile packaged the
post-app fixture. Discovered tool paths and the fixture Maven executable were
passed explicitly. Exact host arguments are retained in the ignored
`test/.tmp/run-661-packaged.ps1`; portable tool setup remains documented in
`mcpjvm-655-orchestration-it.md`.

```powershell
$affectedUnits = '-Dtest=ExecuteExecutionOrchestrationActionTest,ArtifactManagementOperationsTest,ExecutionProfileExportOperationRegistrationsTest,SuiteOwnerLifecycleTest'
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-orchestration,java-it-jvm-fixtures' `
  $affectedUnits '-Dsurefire.failIfNoSpecifiedTests=false' @itTools

mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-workflows' `
  '-Dit.test=McpServerDownstreamRecoveryWorkflowIT,McpServerSkillWorkflowIT#independentWorkspacesResumeInspectMigrateAndReplay' `
  '-Dtest=ExecuteExecutionOrchestrationActionTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' @itTools
```

| Check | Result |
| --- | --- |
| Complete capability selection | **70 IT: 68 passed, 2 failed, zero errors/skips.** All 46 selected UT passed (40 Core, 6 Application). `test/.tmp/661-orchestration.log` and copied XML in `661-orchestration-reports/`. |
| Bounded #656 downstream recovery and independent workspaces | **5 IT and 17 UT passed**, zero failures/errors/skips. Both Watcher/external terminal-failure recovery cases and all three receiving-tool workspace variants passed. `test/.tmp/661-workflows.log` and `661-workflow-reports/`. |
| Enforcer, Checkstyle, PMD | Passed for both modules in the packaged verification commands. The failed full Core command stops at Surefire before Core static checks; its applicable static proof comes from these later commands. |
| Whitespace and documentation links | `git diff --check` passed; the architecture acceptance link resolves locally. No public field or registration changes require a data-fields update. |

The two capability failures are
`McpServerOrchestrationRecoveryIT.watcherAndExternalContinuationSurviveRestartWithoutRepeatingTriggers`
and
`McpServerOrchestrationRoutingIT.performanceProfilesExecuteRealJmeterAndResumeWithoutRewritingCompletedWorkload`.
Both fail the existing checkpoint equality assertion at
`OrchestrationMcpSupport.java:44`: persisted `planRuns` and the bounded CDE result
have different redaction/truncation depth or length. The recovery example retains
the nested Watcher response in persistence while returning `[DEPTH_LIMIT]` inside
the continuation. Neither failure is excluded or converted into a pass.

The 70 capability records under
`test/.tmp/java-it-6764442041486623924/s-<1..70>/evidence.json` and five workflow
records under `java-it-13247364534977974050/s-<1..5>/evidence.json` all report
`cleanupSucceeded=true` and `filesRetained=false`, including the failed cases.
Source hashes, exact selected classes, totals and measurements are retained in
`test/.tmp/661-verification.json`. No raw product state is committed.

Both failures also reproduce with the **exact original coordinator from HEAD**.
The saved pre-extraction source was byte-compared with `git show HEAD:<path>`.
For this diagnostic only, its four compiled classes (the coordinator and three
original nested records) replace the corresponding classes in a copied Core JAR
inside a copied packaged server. All other production classes remain constant;
the copied package uses the same sibling Sidecar layout. The working source and
normal packaged output are not replaced.

The original Recovery case fails the same line-44 checkpoint assertion in
`test/.tmp/661-baseline-final.log`; the original Performance case fails the same
line-44/line-68 assertion in `661-baseline-performance.log`. These are baseline
reproductions, not passing acceptance. Earlier diagnostic setup attempts (missing
nested classes, then missing sibling Sidecar files) are explicitly superseded for
the relevant comparison and supply no behavioral proof.

The focused command uses the existing harness without rebuilding production:

```powershell
mvn -B -f mcp-server/application/pom.xml failsafe:integration-test failsafe:verify `
  '-Pjava-it,java-it-orchestration' `
  '-Dit.test=McpServerOrchestrationRecoveryIT#watcherAndExternalContinuationSurviveRestartWithoutRepeatingTriggers,McpServerOrchestrationRoutingIT#performanceProfilesExecuteRealJmeterAndResumeWithoutRewritingCompletedWorkload' `
  '-DmcpServerJar=<absolute copied baseline-server.jar>' `
  '-Dmcpjvm.acceptance.jmeterHome=<absolute JMeter home>'
```

The final Performance repeat narrows that selector to its single method after
restoring the copied package's sibling `sidecar/` directory.

Final read-only resource audit: no live original test-owned process, no
`java-it-655-*` Docker container and no generated image tag. Of 463 distinct
recorded PIDs across capability/workflow and final diagnostic runs, two numeric
matches were reused by later Codex/Windows processes: their creation times are
after the owning scenarios completed. They were left untouched. Evidence:
`test/.tmp/661-cleanup-audit.json` and `661-pid-reuse.json`. Docker Desktop remains
running as a reusable prerequisite; it is not a scenario-owned resource.

The failed full Core and capability commands block comprehensive #661 acceptance.
The scoped decomposition is implemented; no unrelated production fix or assertion
weakening was added to force a green gate. Passing subsets do not override the
failures. No complete repository test/build or broader Java capability selection
was run.

## Changed paths

Under `mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/executionorchestration/`:

- `action/impl/ExecuteExecutionOrchestrationAction.java`
- `context/ExecutionSuiteContext.java`
- `persistence/ExecutionSuiteCheckpoint.java`
- `persistence/ExecutionPlanRunArtifacts.java`
- `model/action/execute/ExecutionInput.java`
- `model/action/execute/ExecutionPlanContext.java`
- `model/action/execute/ResolvedExecutionProfile.java`

Adjacent changes: the existing `ExecuteExecutionOrchestrationActionTest.java`,
this acceptance record and
`docs/architecture/java-mcp-catalog-describe-execute-contract.md`.
