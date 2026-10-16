# #658 — Shipped Skills on Java CDE

Follow-up to [#656](mcpjvm-656-skill-workflows-it.md), bounded by #657/#609.
The accepted Java runtime, Sidecar, operation registrations, schemas and Artifact
formats are unchanged. All 22 shipped Skills and every bundled file have an
explicit disposition in [java-it-coverage.json](java-it-coverage.json), under
`skills[].guidance.files` (106 bundled files). This includes UI metadata, both local READMEs,
references, templates and all six unchanged Node decision/report helpers.
`activeGuidance` records the directly affected root/module/operator guidance.
The inventory is test traceability, not an executable policy directory.

## Guidance dispositions

Every Skill entrypoint now explains CDE or, for Issue Report, its local-only
evidence-consumer boundary. Executable JSON examples name canonical operations
with `operationId`, `arguments`, and outer `confirmed`. Describe remains the
schema/safety authority; no operation policy registry was copied into the Skills.
The Registry Manager launch uses Java 21+ and the packaged server with adjacent
`sidecar/` artifacts and an explicit workspace. Node remains necessary for the
existing local helper scripts, not for MCP server startup.

Strict line keys, bounded investigation, PID/start fences, confirmation,
redaction, Artifact ownership, diagnostic read-only behavior and cleanup remain
in the owning workflows. Bug Fix is proposal-only. Issue Report produces
sanitized local output without authorizing publication. No Skill installation,
global configuration, application source change or website deployment occurred.

Live inspection demonstrated three additional guidance mismatches:

- Lifecycle attach returns `result.actionResult` with host/port and fenced
  identity, rather than the old `selectedJvm`/`lifecycle`/`probe.baseUrl` shape.
  The Skill now constructs the Probe origin from the returned host/port.
- Project/plan `validate` checks persisted state. In the exploratory creation
  attempt, plan validation with an in-memory payload returned `artifact_missing`.
  Upsert validates its payload before writing. Authoring now distinguishes
  existing-state validation from creation, stops on failed writes, and requires
  persisted validation/readback. No production behavior was changed to hide this
  limitation. The subsequent clean suite sequence used persisted validation.
- Java direct-plan execution persists `<suiteRunId>-1`. The former timestamp-only
  Skill restriction would reject a real valid result. Guidance now retains
  Java-owned identities and terminal immutability. Diagnostic examples also use
  the actual Probe selector/key fields and explicit result suite types.

The former Node/Tool-action examples in #656 remain clearly labeled historical
acceptance limitations. They are not instructions for the current runtime.

## Automated verification

Prerequisites and lane definitions are unchanged from
[#656 runnable commands](mcpjvm-656-skill-workflows-it.md#prerequisites-and-runnable-commands).
This selection used the already-built, unchanged post-app and packaged Sidecar
artifacts, Java 21, Maven 3.9.12 and Node 24. No Docker, JMeter or receiving-tool
replay prerequisites are needed for this selection. Maven was outside PATH;
the session used `C:/Tools/apache-maven-3.9.12-bin/apache-maven-3.9.12/bin/mvn.cmd`.

From the repository root:

```powershell
node mcp-server/test-support/java-it-coverage.mjs
node mcp-server/test-support/java-it-coverage.mjs --require-workflow-evidence
node --test --test-concurrency=1 -r ts-node/register -r tsconfig-paths/register test/unit/skills/*.test.ts

mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-workflows' `
  '-Dit.test=McpServerSkillWorkflowIT#inventoryMatchesPackagedCatalogAndEverySkillDependency+repositoryHelpersPreserveBlockedFailedAndCleanupBoundaries,McpServerSkillSuiteIT' `
  '-Dtest=NoAffectedProductionTests' '-Dsurefire.failIfNoSpecifiedTests=false'

# Recheck only the inventory consumer after final mapping/prose changes.
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-workflows' `
  '-Dit.test=McpServerSkillWorkflowIT#inventoryMatchesPackagedCatalogAndEverySkillDependency' `
  '-Dtest=NoAffectedProductionTests' '-Dsurefire.failIfNoSpecifiedTests=false'
```

The initial selected lane passed **3 IT, 0 failures/errors/skips**: complete
packaged catalog/Describe/dependency inspection; all six local helpers' negative
boundaries; and real fenced lifecycle/registry plus Regression, Performance and
Security result consumption across restart. Unchanged suite/helper evidence is
reused after guidance changes. The final inventory-only recheck passed **1 IT,
0 failures/errors/skips** after the coverage mapping was finalized.
Surefire ran no production UT because no Java production/test source changed.
Maven Enforcer, Checkstyle and PMD passed. This is not the complete workflow lane
or a complete repository build/test run.

The affected Skill checks passed **35 UT, 0 failures/skips**, including rejection
of retired envelopes, unknown example IDs, inner lifecycle confirmation, malformed
outer confirmation and Node MCP startup, while allowing nested actuation actions
and ordinary Artifact data. The static inventory previously accepted retired
examples unchanged; that demonstrated validation gap is why the existing
test-only validator and affected inventory tests changed.

After the final scalar/null-example guard adjustment, its **3 affected UT**
passed again. ESLint on all five changed Skill test files and `git diff --check`
passed. Both static inventory commands passed with no required workflow evidence
missing; the report continues to distinguish reused evidence from fresh execution.

All **54** packaged operations were described. A session-local Ajv 2020 check
validated all **15** revised fenced JSON invocation examples against those live
input schemas and confirmation requirements. All **22** entrypoint name and
description fields passed scalar/length/naming checks. The skill-creator Python
`quick_validate.py` could not run because the bundled Python lacks PyYAML; this
is distinct from the successful inventory, schema and behavioral checks.

Initial sandboxed runtime attempts failed before MCP initialization with
`Unable to establish loopback connection`; the same bounded selections passed
with local loopback access. No runtime implementation repair was required.

Session logs/transcripts and schema results are under `test/.tmp/658/`;
Failsafe reports remain under `mcp-server/application/target/failsafe-reports`.
The static report at `test/.tmp/656-coverage-report.json` retains #656's broader
evidence provenance limits; evidence presence is not freshness certification.

## Earlier manual CDE workflow evidence

This section records manually following repository instructions and inspecting
scripted MCP results. It did **not** test an explicit `$skill-name` prompt and
must not be cited as prompt-driven Skill invocation. The follow-up section below
records that separate verification. Automated IT remains independent of an LLM
session or global Skill installation.
The chat had no connected Java CDE tools. A session-local Node STDIO client sent
ordinary MCP `initialize`, `notifications/initialized`, `tools/list` and
`tools/call` messages directly to the packaged Java server. It introduced no
shipped harness or product helper. All product operations went through CDE.

The launch was `java -jar <package>/mcp-java-dev-tools-server-0.1.9.jar
--workspace-root=<W>`, with adjacent packaged Sidecar/helper JARs. `W` was the
isolated `.tmp/658-skill-use` directory beneath the user-supplied test repository.
The controlled immutable fixture was
`test/fixtures/spring-apps/social-platform/post-service/post-app/target/post-app-0.1.0-SNAPSHOT.jar`.
Fixture and lifecycle helper shared `W/tmp` through `java.io.tmpdir` for fenced
report exchange. API/Probe ports were allocated separately at runtime. Only this
owned fixture was eligible for mutation; the example repositories were untouched.

For each selected operation, Describe was read before the bounded Execute flow.
The actual Execute arguments/results were:

| Operation | Arguments / observed result |
| --- | --- |
| `jvm_lifecycle.list_jvms` | `{}`; owned PID `11752`, start fence `1792068082101`, unverified descriptor retained. |
| `jvm_lifecycle.attach` | Exact string PID/fence, host `127.0.0.1`, allocated Probe port `54428`, include `com.example.social.**`. Outer `confirmed:false` returned `confirmation_required`; `true` returned `active`. No inner `confirm`. |
| `probe.check` | `baseUrl=http://127.0.0.1:54428`; `SUCCESS`, API port `54427`, runtime instance recorded in local transcript. |
| `probe.status` | Same base URL, `key=com.example.social.post.app.controller.PostController#getPost:29`; `SUCCESS`, line resolvable, hit count **0**. This proves readiness, not a Line Hit. |
| `artifact_management.project_context.upsert/validate/read` | Project `skill-658`; owned workspace, explicit ordered `regression` execution profile and project resiliency defaults. Upsert confirmed; persisted validation/readback succeeded. Used for setup, not a full Project Manager Skill evaluation. |
| `artifact_management.regression_plan.validate/upsert/read` | Plan `health`; actual GET `/actuator/health`, expected status 200, `probeVerification=false`, no auth/secret inputs. Existing persisted plan validated; Java upsert and bounded readback succeeded. Initial missing-plan validation limitation is recorded above. |
| `regression_suite.execute_plan` | `projectName=skill-658`, `executionProfile=regression`, `planName=health`, `suiteRunId=skill-use-658-final`, outer `confirmed:true`; CDE `succeeded`, capability `ready`, `runStatus=pass`, step 200, **44 ms**. |
| `artifact_management.run_result.read` | Explicit `suiteType=regression`, plan `health`, run `skill-use-658-final-1`, selected summary/result/evidence. Persisted result, evidence and context present; readback succeeded again after restarting MCP. |
| `artifact_management.run_result.query` | Earlier exploratory run's bounded suite selector returned no SQLite projection rows. Canonical read succeeded. No claim of fresh projection coverage, rebuild or migration from this actual use. |
| `jvm_lifecycle.deactivate` | Same exact PID/start fence, outer `confirmed:true`; `deactivated`, empty `nonRestorableClasses`. |
| Cleanup checks | Probe check subsequently returned `DIAGNOSE_FAILED`; wrapped `transport_execute.execute` health GET returned 200/UP, proving application survival. The owned fixture then exited; MCP sessions exited on EOF. |

The earlier manual summary joined the persisted plan/result pair to render this
table. It is **not** evidence that the Result Skill's declared result-only input
contract can render it; the prompt-driven check below found that limitation.

| Endpoint | Status | HTTP Code | Duration (ms) | Probe Coverage |
| --- | --- | --- | --- | --- |
| GET /actuator/health | pass | 200 | 44 | http_only_unverified_line |

No failed-assertion section was appropriate. No raw credentials, environment
values, application source or unrestricted captures were emitted. The canonical
run Artifacts are retained in the isolated test workspace as local evidence;
no canonical files were deleted or repaired. `fixture-cleanup.json` confirms the
owned process exited. No actuation/profiler session was opened.

### Selected revisions and remaining coverage

The final Skill hashes below normalize CRLF to LF. Invocation corrections found
during actual use are recorded above; those changes did not alter the accepted
Java runtime. JVM Lifecycle, Regression Suite (single-plan HTTP branch) and
Regression Result were manually followed here. Setup Artifact calls do not imply
complete crafter/manager use. Prompt-driven outcomes are recorded separately
below, rather than inferred from these manual calls.

| Skill | Final guidance SHA-256 (LF) |
| --- | --- |
| jvm-lifecycle | `7fda70affe112175edb5f359698f7c5d859884386f7f4eb5a9b593f0a7f0a77c` |
| regression-result | `8d69a87d26d792670249c1d9906a4cdcff293d85fdeb770948340f4abcf3ff44` |
| regression-suite | `0884e49043fd2487f3960f5b6ca4590d19e82ffe6123809d669f3b040e467fe9` |
| issue-report (prompt follow-up) | `c55a78bf85efadd58331e8c79e9d61029a902096733f2ae917b1058b311d261b` |

Skills not prompt-invoked (10): `bug-drill`, `bug-fix`, `failure-lens`, `line-probe-run`, `performance-export`, `probe-registry-manager`, `project-state-migration`, `regression-export`, `regression-suite-diagnostic`, `security-suite-diagnostic`.


Gate E is supported by current root/module/Skill invocation consistency,
complete file dispositions, static inventory/schema checks, affected UT and
selected real MCP workflows. No production transport/schema or Sidecar change
was made. Independent review and full capability acceptance were not performed.

## Explicit prompt-driven Skill invocation follow-up

This follow-up addresses the distinction raised by the user: an explicit Skill
prompt, with connected CDE tools, is different from manually reading instructions
and driving JSON-RPC. Fresh ephemeral Codex CLI sessions received Markdown Skill
invocations of this form, linked to the **repository revision**, not installed
global copies:

```text
[$mcp-java-dev-tools-regression-result](<repo>/skills/mcp-java-dev-tools-regression-result/SKILL.md)

Render the persisted Regression result for projectName=skill-658,
suiteType=regression, planName=health, runId=skill-use-658-final-1.
Choose the default endpoint table. Use connected MCP CDE tools for product
reads; do not construct shell/HTTP/JSON-RPC substitutes. Read the linked
repository Skill and needed references. Do not repair or execute a suite.
```

The local commands, complete prompts, final responses and JSONL events are
retained in `test/.tmp/658-prompt/`. The three prompt runners were:

```powershell
& test/.tmp/658-prompt/run-result-retry.ps1
& test/.tmp/658-prompt/run-workflow.ps1
& test/.tmp/658-prompt/run-issue.ps1
```

Each runner pipes its saved prompt to
`codex exec --ignore-user-config --ephemeral --json --approve-for-me -C <repo>
-c <temporary-mcp-server-config> -o <saved-final-response> -`.
The per-process config connects `mcp_servers.skill_cde` directly to
`java -jar <packaged-server.jar> --workspace-root=<test-workspace>`, with the
shared fixture/helper temp directory in `JAVA_TOOL_OPTIONS`. It does not install
Skills, alter global configuration, create a branch/worktree or persist an app
chat. Normal sandbox/approval handling remains enabled. An initial read-only
CLI run had approval policy `never`: Catalog/Describe connected, but Skill-file
reads and Execute were blocked. That attempt is recorded as blocked, not passed;
the temporary approval-capable retry completed its actual calls.

The workflow prompt explicitly linked JVM Lifecycle, Regression Suite and
Regression Result. It authorized only the newly owned PID `19712`, its discovered
start fence `1792073180001`, Probe host `127.0.0.1`, allocated port `59457`, include
`com.example.social.**`, and one HTTP-only health-plan execution. The isolated
workspace was `.tmp/658-prompt-use` beneath the supplied test repository. Project
`prompt-658` and plan `health` were arranged through CDE before the prompt; fixture
setup is not counted as the prompt's execution. No intended result, previous
diagnosis, or acceptance conclusion was supplied to the workflow prompt.

| Prompt scenario | Observed result |
| --- | --- |
| Standalone `$mcp-java-dev-tools-regression-result` | Read the linked Skill/references and made **3 native MCP calls**: Catalog, Describe, Execute. The CDE read succeeded, but rendering **blocked** with renderer classification `required_result_field_missing`: the step/evidence lacks endpoint identity and `targetResolution=[]`. It did not invent an endpoint from `health`, repair state, or execute traffic. |
| `$mcp-java-dev-tools-jvm-lifecycle` → `$mcp-java-dev-tools-regression-suite` → `$mcp-java-dev-tools-regression-result` | Read all three linked Skills and relevant references; made **26 native MCP calls** with no tool errors. Attach returned `active`; Probe check/status succeeded, strict line resolvable with **0 hits**. The health step passed, status 200, **120 ms**, persisted run `prompt-658-run-1-1`. The combined flow rendered the endpoint table using the persisted plan context it had just executed. Deactivation used the identical PID/start fence and returned `deactivated`, with no non-restorable classes. |
| `$mcp-java-dev-tools-issue-report` | Read the linked Skill and its required sanitization/report references, then generated `issue-final.md` from the supplied real CDE evidence and standalone renderer outcome. Stable identifier aliases, no invented endpoint/cause/fix, no MCP mutation or external publication. No MCP call was needed for this local report Skill. |

The JSONL events contain actual `mcp_tool_call` items for `operation_catalog`,
`operation_describe`, and `operation_execute`, not shell proxy calls. The audit
found **29 native MCP calls**, zero errors in the completed retry/workflow
sessions, and only read commands for the explicitly scoped Skills/references and
report inputs. `audit.json` records the normalized Skill revisions, call outcomes,
session completion and equal attach/deactivation identities. The three previously
listed Skill hashes are unchanged; Issue Report was also explicitly invoked in
this follow-up. No new UT/IT claim is inferred from an LLM session.

After prompt-driven deactivation, an independent fixture cleanup check observed
HTTP **200 / UP** and an unreachable Probe. `fixture-cleanup.json` then recorded
the owned fixture exited. No actuation or profiler session was opened. Canonical
run Artifacts remain under the isolated test workspace; no source or product
configuration was changed by the prompt sessions.

### Remaining limitation

The two Result invocations expose a material context dependency: a standalone
persisted result does not contain the endpoint identity required by the default
template; the combined execution/rendering prompt had the just-executed plan
available. That combined pass does **not** establish generic standalone Result
compatibility or permission to join an arbitrary current plan to an old run.
The authoritative owner and safe persisted linkage need a bounded follow-up
before that broader claim can be made. Production repair is outside #658 and
was not attempted. The Issue Report records the observed mismatch locally.

Inventory now distinguishes prompt-completed Skills from the partially supported
Result flow and the then-remaining 18 uninvoked Skills (the real-code follow-up below adds coverage). The earlier manual endpoint
table is explicitly labeled as a plan/result join, correcting its previous
overstatement as Result Skill evidence.

## Real-code ordered suite profiles — 2026-10-16

At the user's request, fresh explicit Skill prompts authored and executed all
three suite types against the actual Spring Petclinic Customers service under
the supplied test repository. The owning module was freshly packaged offline;
the running code used its real controllers, JPA repository and seeded in-memory
HSQL database. No HTTP mock or tool-repository fixture replaced the application.
Suite Artifacts used a separate test workspace, preserving the application's
existing `.mcpjvm` directory and source. Selected controller/JAR hashes remained
unchanged during execution.

The retained workspace is
`<test-repository>/.tmp/658-real-suites-20261016`, project
`.mcpjvm/petclinic-real-suites-20261016`. Exact prompts, native CDE events,
responses, file/SQLite checks, immutable-run hashes and cleanup proof are in the
tool repository's `test/.tmp/658-real/`. The user-facing complete path report is
`<test-repository>/.tmp/658-real-suites-20261016/verification-report.md`.

| Profile | Suite ID | Real outcomes |
| --- | --- | --- |
| performance-real | petclinic-performance-20261016-01 | Both ordered plans passed. Lookup: 5019 requests, 0 failures, 501.9/s, p95 13 ms, 5019 fresh hits. List: 1562 requests, 0 failures, 156.2/s, p95 31 ms, 1562 fresh hits. Production builtin HTTP provider, concurrency 2, configured 10 seconds each. |
| regression-real | petclinic-regression-20261016-01 | Both ordered GET plans passed, statuses 200, persisted step times 59/16 ms; HTTP-only verification. |
| security-real, first attempt retained | petclinic-security-20261016-01 | Both plans blocked before requests: coordinator-supplied `stateMutation=forbidden` was not the supported policy enum. |
| security-real, corrected fresh run | petclinic-security-20261016-02 | Black-box 2/2 cases and Sidecar-assisted 1/1 case passed; zero findings in this declared matrix. Valid-ID baseline 200; malformed-ID denial 400. Sidecar baseline count 1, attack count 0, same live runtime identity. Nine default knowledge packs selected. |

The Security correction changed only the enum to `test-tenant-only`; all requests
remained GET-only with unchanged denial/hit expectations, loopback/rate/time
bounds and destructive-payload prohibition. The previous blocked run JSON bytes
were independently verified unchanged. Regression's human `plan.md` content was
corrected through MCP after execution; its machine contracts did not change.
The final check found all required plan/run files present, while retaining these
authoring deviations instead of treating every first attempt as successful.

Five completed prompt sessions, including initial/corrected Security and fenced
cleanup, made **185 native MCP calls** to Catalog/Describe/Execute. No MCP
transport errors occurred; rejected discovery/validation operations and blocked
capability outcomes remain in their transcripts. The prompts read eleven linked
repository Skill revisions, including the suite crafters, executors, result
renderers, Project Artifact Manager and JVM Lifecycle. This adds live use to the
coverage inventory; it is separate from automated UT/IT and is not exhaustive
application or all-mode capability acceptance.

Independent verification parsed **43 JSON files**, found six passing plan runs
and two preserved blocked runs, and checked all required `metadata.json`,
`contract.json`, `plan.md`, `execution.result.json`, `evidence.json` and
`context.resolved.json` files. Four suite manifests are present. **SQLite exists
and `PRAGMA quick_check` is `ok`, but `plan_runs` and `suite_continuation_state`
both contain 0 rows.** Bounded MCP queries agreed. JSON persistence is proven;
live SQLite projection completeness is not. No rebuild/backfill/cutover or
direct SQL write was performed to hide this gap. Current application composition
uses `FileExecutionSuiteStateStore` for suite continuation; no broader SQLite
resume/lease claim follows from these runs.

Further limits: performance timestamps span only 0–1 ms, so configured duration
must not be presented as persisted actual elapsed time. Security run results
contain case/coverage evidence and an aggregate knowledge digest, but no
separate expanded matrix or per-pack compatibility/content-digest metadata.
The default Result template's standalone endpoint-context limitation remains.
JMeter, profiler/MSTA, Watchers, external verification and other endpoints were
not exercised by these bounded real-code profiles.

Cleanup used native CDE to deactivate only owned PID `16316`, fenced by start
time `1792114990917`; outcome `deactivated`, no non-restorable classes. The real
application remained HTTP 200/UP, its Probe became unreachable, then the owned
process was stopped. All suite, run and database files remain available. Source,
production runtime implementation, installed Skills and global configuration
were not changed. Static inventory and whitespace checks were rerun for this
acceptance update; unchanged automated UT/IT evidence remains explicitly reused.

## Follow-up: runtime persistence and standalone Result fixes

The user authorized fixing the two observed gaps. Ordinary run writes now
incrementally project their selected canonical Artifact into SQLite, and suite
checkpoint storage moved into Core Artifact Management using the existing
bounded/redacted JSON and SQLite owners. Explicit rebuild also restores suite
continuations from canonical suite checkpoints. Regression step results now
persist additive declared `method`/`path` identity without URL authority, query,
fragment or resolved context. The bundled Result rules document this mapping.

Affected verification passed: 49 distinct Java unit cases, 15 packaged MCP
integration cases, 4 Result Skill cases, affected Maven static checks, final
packaging and inventory validation. Runtime projection tests now assert rows
before any rebuild; the previous synthetic checkpoint-snapshot/rebuild setup
was removed from the continuation workflow test.

Fresh real-code Skill executions passed both ordered plans in each suite:
`petclinic-performance-20261016-02`, `petclinic-regression-20261016-02`, and
`petclinic-security-20261016-04`. The execution prompt made 106 native CDE calls.
Independent read-only validation found 73 valid project JSON files, all required
plan/run companions, SQLite integrity `ok`, six real plan rows and three suite
continuations. No rebuild/backfill/cutover, synthetic plan or direct SQL edit was
used. All 35 retained historical suite/run/companion file hashes and the real
application source/JAR hashes remain unchanged.

A separate standalone Result prompt made eight native CDE calls against the
final packaged source, executed only run reads and strict bounded queries, and
rendered `GET /owners` and `GET /owners/1` directly from persisted results without
reading current plans or execution context. Probe Coverage remained `unknown`
where persisted classification was unavailable. Exact filtered rows survived
the fresh MCP process. This resolves the two gaps for new runs; old missing
metadata/projections were not rewritten or imported.

Full paths, prompts, native events, checks and cleanup are recorded in
`C:/Users/Altheo/repository/test/.tmp/658-real-suites-20261016/persistence-fix-verification.md`.
Raw evidence is under `test/.tmp/658-persistence-fix/` in this repository.
Only the owned fenced Petclinic JVM was deactivated and stopped; no Sidecar
implementation, global configuration or installed Skill was changed.

## Review repair: projection size and failed-write continuation

The user supplied two valid P2 reproductions in `test/.tmp/658-review/`:
supported canonical results above 64 KiB failed automatic projection, and a
retry could accept terminal JSON without repairing its failed SQLite write.
The earlier routine fixture evidence did not cover these boundaries.

Both are repaired. Oversized projected detail now retains the 64 KiB SQLite
bound using an explicit `artifact_only` canonical reference; complete evidence
and the 4 MiB canonical limit are preserved. Queries expose the limitation and
omit unavailable detail/window fields. Ordered and direct retries now perform
idempotent selected-run recovery before accepting terminal/continuation state.
Identical upserts preserve canonical/companion bytes; persistent storage failure
stays blocked and does not repeat a completed application request.

Verification passed for 41 distinct Java unit cases and 22 distinct packaged MCP
cases, combining preserved passing evidence with a targeted recheck. New tests
cover large result/secondary state projections, persistent lock failure, lock
release and restart, one actual HTTP request across retries, and byte-identical
canonical companions. The package was forced fresh and its embedded Core hash
verified against reactor output after an initial stale-JAR mismatch was found.

A fresh Skill prompt made 133 native CDE calls against real Petclinic code.
Performance `petclinic-performance-20261016-03`, Regression
`petclinic-regression-20261016-04`, and Security
`petclinic-security-20261016-05` each passed both ordered plans and a repeat of
the same terminal suite identity. Independent checks found 96 valid JSON files,
all required companions, SQLite integrity `ok`, and six new plan rows/three new
continuations (database totals 12/6). All 56 historical evidence hashes and the
real app source/JAR hashes remain unchanged. No live rebuild/backfill or direct
SQL repair was used; owned fenced lifecycle cleanup completed.

Exact paths, build provenance, test commands and limitations are recorded in
`C:/Users/Altheo/repository/test/.tmp/658-real-suites-20261016/persistence-review-repairs.md`;
raw evidence is under `test/.tmp/658-persistence-repair/` in this repository.

## Ordered recovery correction after targeted recheck

The next user-supplied recheck confirmed the size fix but disproved the earlier
ordered-recovery completion claim in two paths: terminal recovery failure could
be bypassed by `continue_on_fail`/`onFail=continue`, and `in_progress` JSON was
accepted without recovery. The prior stop-on-fail terminal test did not prove
either path.

The orchestration owner now recovers selected persisted runs before lifecycle
preparation, context restoration or any plan owner call. Failure is suite-level
and independent of business failure policy. Fresh run persistence failure also
stops before context promotion or a later plan. Terminal classification is
read-only; direct recovery and bounded size handling remain intact.

Final-source verification passed 45 Java unit and 17 packaged MCP cases with
Enforcer, Checkstyle and PMD. New real-lock cases cover both policy variants and
an active HTTP Watcher across failure, byte-identical retry evidence, unlock and
server restart; no later request, extra poll or repeated trigger occurs while
recovery is blocked. The forced package's embedded Core matches reactor output.

The bounded implementation and evidence handoff is
`C:/Users/Altheo/repository/mcp-jvm-debugger/test/.tmp/658-ordered-recovery/verification.md`.
The explicit live Skill prompts and native CDE events are retained beside it.
No independent review was dispatched and no full-repository rerun is claimed.

A fresh native CDE Skill session against the real Petclinic JAR exercised an
active external-verification continuation, both ordered plans and terminal
reuse: run `petclinic-ordered-recovery-20261016-02` returned
`in_progress`, `in_progress`, `pass`, `pass`. Both HTTP endpoints, the Watcher
and two external checks passed. Independent inspection found 19 valid JSON
files, complete plan/run companions, four SQLite plan rows (including two
retained failed attempts), two suite continuations and SQLite integrity `ok`.
The initial omitted-`suiteType` profile attempt remains recorded as failed;
its correction and successful fresh identity are explicit. Raw sessions contain
81 native calls. Source/JAR hashes remain unchanged and owned-app cleanup
completed. Probe-disabled HTTP proof and existing Artifact window-selector
limitations are stated in the handoff; no fresh line-coverage claim is made.
