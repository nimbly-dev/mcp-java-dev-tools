# #660 Security execution ownership

## Before-state inventory

Starting checkout: `48b0d5d7fc2548f57cf2bcd0573c79cae9383979`.
The starting `SecurityPlanExecutor.java` has 1,324 physical lines (including
imports, comments and blanks). This is a focused responsibility inventory, not
an AST class-span measurement or a new architecture enforcement baseline.

| Starting owner / methods | Responsibility and dependencies |
| --- | --- |
| `executePlan`, nested `Validation` | Contract/mode/policy/reference validation; selection of installed immutable knowledge; credential preflight and mode selection. |
| Nested `RequestContext` | Environment binding, existing `SecurityCredentialRefresh`, fixture precedence, symbolic headers and authentication classification. |
| `executeRequest`, `requestPayload`, URI and response methods | Wrapped `TransportExecutionFeature`, resolved target boundary, HTTP encoding, payload preparation and response expectations. |
| Nested `RequestBudget` | Per-invocation duration, rate and request ceilings; interruption and Probe timeout bounds. |
| `runBlackbox`, generated/custom cases and mutations | Finite matrix from existing `SecurityKnowledgeCatalog`; baseline/attack execution, partial findings and coverage. |
| `runSidecar`, case requests | Reset/baseline/attack sequencing and proof classification. |
| Reset/status/wait methods | Public `ProbeFeature`, strict keys, fresh hits, forbidden/required distinctions and runtime identity. |
| Finding/coverage/evidence methods | Sanitized HTTP and runtime observations, reason codes, severity verdict and deterministic result assembly. |

Existing collaborators already own knowledge pack selection/generation/snapshots,
refresh subprocess lifecycle, wrapped HTTP transport and Sidecar communication.
They remain the owners of those boundaries. `TrustedSuiteExecution` remains the
owner of trusted persisted plan loading, locking, result persistence and replay.
The direct Security registration and Application composition need no new router.

## After-state ownership and dependencies

Changed production root:
`mcp-server/core/src/main/java/com/nimbly/mcpjavadevtools/server/core/feature/suite/security/`.
The execution files listed below are in `execution/`; the four relocated carriers
are in `model/execution/`. Documentation changes are this acceptance file and
`docs/architecture/mcpjvm-644-cde-structural-reduction.md`. No other tracked
production, test, build, fixture, Sidecar or registration files change.

`SecurityPlanExecutor` remains the single substantive entry point. Its existing
constructors and `SecuritySuiteFeature.executePlan` contract are preserved.
Extracted behavior stays package-private; execution carriers are in Security's
`model/execution` package. Physical source lengths below include imports,
comments and blanks; they are diagnostic measurements, not enforcement limits.

| Owner | Physical lines | Responsibility / direct dependencies |
| --- | ---: | --- |
| `SecurityPlanExecutor` | 60 | Coordinates preflight, per-invocation context and mode execution; knowledge selection, Blackbox and Sidecar owners. |
| `SecurityContractPreflight` | 174 | Contract, policies, authentication kinds, unique IDs and runtime references; immutable validation result. No I/O. |
| `SecurityRequestContext` | 194 | Runtime credential refresh/binding, fixture precedence and symbolic/auth headers; existing `SuiteRuntimeEnvironment`, `SecurityCredentialRefresh`, invocation budget. |
| `SecurityRequestBudget` | 74 | Invocation-local rate/duration state, hard ceilings and interruption; wrapped Transport execution, bounded Probe timeout. |
| `SecurityRequestExecution` | 238 | Resolved HTTP target/path/query/body construction, boundary policy and response expectations; context and budget. |
| `SecurityBlackboxExecution` | 257 | Finite matrix, custom overrides and reviewed mutations; existing knowledge catalog, request execution, preflight reference resolution and result assembly. |
| `SecuritySidecarExecution` | 119 | Reset/baseline/attack ordering and external/internal/corroborated classification; request execution, Probe evidence, catalog and result assembly. |
| `SecurityProbeEvidence` | 183 | Reset/status polling, freshness, required/forbidden targets and runtime identity; public `ProbeFeature` and invocation budget. |
| `SecurityExecutionResults` | 196 | Findings, safe HTTP/runtime evidence, coverage, partial findings and severity verdict; knowledge selection and owned result/evidence models. No execution or polling. |

`SecurityPlanValidation`, `SecurityCaseResult`, `SecurityGeneratedRequest` and
`SecurityRuntimeEvidence` replace the corresponding nested carriers. They carry
values only. The two nested operational classes are replaced by the request
context and budget owners above; no nested workflow type remains in the entry
point. No production behavior is deleted. No new interface, provider, registry,
module, per-operation forwarding layer or shared Suite framework is introduced.

Retained owners: `SecurityKnowledgeCatalog`, `SecurityCredentialRefresh`,
Transport and Probe, trusted Artifact execution and registration/composition.
They are concrete boundaries rather than migration exceptions. No new
architecture exception is added; the existing CDE structural inventory links
this disposition and keeps its historical #644 counts unchanged.

Public CDE schemas/identity, confirmation/cancellation, knowledge behavior,
Artifact formats and Sidecar sources/protocol stay unchanged. This inventory
supersedes no existing architecture rule; #659 was cancelled, so no new permanent
baseline or production-wide enforcement mechanism is introduced.

Behavioral compatibility reference is the accepted #643/#654/#656 Java CDE
workflow and its retained TypeScript evidence (not retired invocation routes):
`security_blackbox_request.ts`, `security_blackbox_mode.ts` and
`security_sidecar_assisted_mode.ts`, as recorded in
[the #654 acceptance inventory](mcpjvm-654-security-it.md). Existing tests stay
unchanged: their HTTP/Probe/Artifact assertions cross the extracted boundaries.
Inspection found no new behavior gap requiring another harness. No cross-feature
behavior or orchestration contract changes; #656 scenarios need no new run.

## Verification

Commands run from the checkout root with Maven 3.9.12 at
`C:/Tools/apache-maven-3.9.12-bin/apache-maven-3.9.12/bin/mvn.cmd`, JDK
21.0.12.1 on Windows 11. Normal local filesystem/loopback permissions are
required. The initial restricted-host before-state run could not persist
temporary Artifacts; its 952 tests had 67 failures, 75 errors and 18 skips
(`test/.tmp/660-core-before.log`). Those results are diagnostic evidence, not
a usable acceptance run. Subsequent runs use normal local access.

Required Core command after extraction:

```powershell
mvn -B -f mcp-server/pom.xml -pl core -am verify
```

Result: **952 tests, 950 passed, 2 failed, 0 errors/skips**. The command exits
1 during Surefire, before its static-check phase. Log:
`test/.tmp/660-core-after.log`; full XML/text reports:
`test/.tmp/660-core-after-reports/`.

- `JavaMcpArchitectureEnforcementTest.migratedReferenceProductionGraphSatisfiesBoundedOwnershipRules`:
  unchanged `SqliteRunStateStore` has 13 methods against the existing limit of
  12. This pre-existing violation is also recorded in #659's cancellation.
- `SuiteOperationRegistrationTest.immediateRetryCannotOverlapAnInProgressRealOwner`:
  Performance variant reads an existing Artifact without a terminal
  `directResult.reasonCode`, expecting `ok` and receiving an empty value.
  Its wait helper tests file existence, rather than waiting for terminal
  content. Timing sensitivity is an inference from that helper and the changing
  failing variants; no persistence or shared-test repair is included here.

The documented complete capability command was first attempted with all four
focused UT classes from #654:

```powershell
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-security,java-it-jvm-fixtures' `
  '-Dit.test=McpServerSecurity*IT' `
  '-Dtest=SecurityPlanExecutorTest,SecurityCredentialRefreshTest,SuiteOperationRegistrationTest,CoreOperationDirectoryTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```

Result: **200 tests, 198 passed, 2 failed, 0 errors/skips**; the same retry
method fails for Performance and Security. Application/IT execution did not
start. Log: `test/.tmp/660-security-capability.log`. The successful assertions
and both failing variants remain evidence; excluding the shared class below
does not turn this attempt into a pass.

To collect independent packaged evidence without changing shared continuation
tests or the persistence owner, the final selection retains all Security ITs
and the three passing affected UT classes:

```powershell
mvn -B -f mcp-server/pom.xml -pl application -am verify `
  '-Pjava-it,java-it-security,java-it-jvm-fixtures' `
  '-Dit.test=McpServerSecurity*IT' `
  '-Dtest=SecurityPlanExecutorTest,SecurityCredentialRefreshTest,CoreOperationDirectoryTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```

An intermediate attempt passed 186 UTs and Checkstyle, then PMD rejected
excessive static imports in the two extracted mode owners
(`test/.tmp/660-security-capability-isolated.log`). Those imports were replaced
with explicit owner calls and bounded expressions; nested ternaries in the moved
case classification were expanded to ordinary branches. Final affected checks
cover these changes; unaffected Core evidence is reused. No static rule or
test assertion was weakened.

Final command: **BUILD SUCCESS**, exit 0 on 2026-10-16. **186 focused UTs
and all 53 Security ITs passed, zero failures/errors/skips**. Core and
Application Checkstyle each report zero violations; PMD, Maven Enforcer and
Failsafe verification pass. Application UTs outside the explicit selector were
not rerun. The unchanged Sidecar helper/agent packages were reused; the declared
`java-it-jvm-fixtures` profile prepared the post-app fixture. No additional
optional tool, new fixture source or Sidecar build was needed.

| Packaged selection | Passed / failures / errors / skips |
| --- | --- |
| `McpServerSecurityCatalogIT` | 17 / 0 / 0 / 0 |
| `McpServerSecurityPolicyIT` | 4 / 0 / 0 / 0 |
| `McpServerSecurityRecoveryIT` | 3 / 0 / 0 / 0 |
| `McpServerSecurityRequestIT` | 22 / 0 / 0 / 0 |
| `McpServerSecuritySidecarIT` | 4 / 0 / 0 / 0 |
| `McpServerSecurityValidationIT` | 3 / 0 / 0 / 0 |

Final log: `test/.tmp/660-security-final.log`. Selected Surefire and complete
Security Failsafe reports are retained under `test/.tmp/660-final-reports/`.
Sanitized scenario evidence:
`test/.tmp/java-it-1128441790613465774/s-1..s-53/evidence.json`.
Executed JAR: `mcp-server/application/target/mcp-java-dev-tools-server-0.1.9.jar`,
SHA-256 `6B9D508639B70913EDBBFEC0DA9A653C045CE0F7E1ED3559B741123E22DB0051`.

All 53 scenarios report `passed`, `cleanupSucceeded=true` and
`filesRetained=false`. No scenario workspace remains. The 111 distinct owned
PIDs recorded by the harness are stopped; a post-run PID check also finds zero
alive. Summary: `test/.tmp/660-cleanup-summary.json`.

The full #654 inventory was executed, including actual HTTP matrix/mutations,
immutable knowledge snapshots and replay, credentials/redaction, rate/duration,
timeout/cancellation, continuation/recovery, missing-evidence blocking, persisted
findings and canonical/public/SQLite result consistency. Real fresh required
and forbidden Probe observations passed in scenario 47, whose retained assertion
evidence includes
`com.example.social.post.app.controller.PostController#getPost:29` and the
passed/internal/corroborated classifications. Scenario 49 confirms disappearing
Probe status cannot manufacture a forbidden-hit finding. Scenario 50 covers
unavailable/missing-hit recovery. These are controlled-fixture observations,
not claims about arbitrary external target exploitability.

## Static consistency and limitations

The extracted code retains exactly the same set of string literals, including
reason codes, output field names and exception text. Method-body comparison
and inspection account for the new coordinator/delegation, context/budget
accessors, independent local membership checks and equivalent classification
branches. This is static compatibility evidence; runtime evidence is tracked
separately above.

Unchanged registration/schema/safety files and `docs/data-fields/README.md`
still agree on the single `security_suite.execute_plan` selector and Security
result fields. Spring/MCP/Sidecar implementation dependencies were not added
to Core. The existing three-Tool, CDE directory and trusted persistence owners
remain unchanged. The #644 inventory links the new ownership evidence.

Applicable gates: A (fresh strict-line evidence and unchanged Sidecar behavior),
C (real supported HTTP request mapping and deterministic invalid fixtures),
D (packaged three-Tool CDE discovery/execution and unchanged field documentation),
E (linked ownership inventory and executable current paths/commands) and G
(owned Security boundaries, Spring-independent Core, affected Maven/static
results and packaged STDIO evidence). A/C/D/E have the recorded bounded evidence;
G's required full Core pass remains blocked by the failures above. The final
capability pass does not supersede those failures. `git diff --check` passes.

Full #660 acceptance remains blocked while required Core/shared registration
checks are failing. No parent completion, independent review, actual LLM Skill
use, Unix execution or full repository test pass is claimed. The smallest
reproduction for the unresolved checks is:

```powershell
mvn -B -f mcp-server/pom.xml -pl core -am test `
  '-Dtest=JavaMcpArchitectureEnforcementTest#migratedReferenceProductionGraphSatisfiesBoundedOwnershipRules,SuiteOperationRegistrationTest#immediateRetryCannotOverlapAnInProgressRealOwner' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```
