# #646 shared Java process IT contract

The current consolidated inventory, Skill mappings and complete Java IT command
are in [#656 cross-feature acceptance](mcpjvm-656-skill-workflows-it.md).
This document retains the shared-support evidence and prerequisite ownership.

The accepted Java-only CDE runtime is the subject of these tests. #582's process,
STDIO, Roots, workspace, cleanup and fixture requirements remain applicable.
#583's substantive behavior and safety requirements remain references; its old
Node/Java dual-process comparison and Tool names were superseded by #611/#644.
No Node acceptance route is executed.
For #582, the old debug_check Tool scenario is superseded by CDE discovery and
the restored status metadata assertions. Its executable-JAR, Resource, Roots,
protocol-purity, fixture, workspace, shutdown and cleanup requirements map to
the retained/new scenario groups below; the remaining gaps are implemented here.

## Decisions and corrections

The baseline commit is b3d0d672. Its protocol IT asserted that resources were
absent, and application.yml disabled them. #646 expressly requires the status
Resource. The user approved restoring it on 2026-10-11 after this conflict was
identified. The restoration adds the status Resource; the public Tools remain
exactly the three CDE Tools. No public Operation Resource or legacy Tool returns.
Status exposes an allowlist of metadata and never configuration or credentials.

Existing mechanics retained: McpServerProcess JSON-RPC driver,
McpServerStdioSupport assertions/input generation, SpringFixtureTargetProcess,
DelayedHttpTarget, and the social-platform Spring fixtures. JavaItScenario adds
only the missing per-scenario workspace/evidence/resource-cleanup boundary.
Capability scenarios continue to use the same driver.

Reproductions before bounded production corrections:

- Checkout-root `mvn -f mcp-server/pom.xml ... verify` could not locate
  config/checkstyle.xml because maven.multiModuleProjectDirectory reflected the
  caller directory. Owning POMs now resolve static-check files from project.basedir.
- Packaged CDE project upsert before a status read returned nested
  workspace_context_missing despite advertised Roots. OperationMcpToolConfiguration
  omitted workspace negotiation when #644 removed the old adapters.
  The thin Application call boundary must refresh WorkspaceContext before invoking
  the existing registration handler; Core behavior is unchanged.
- Status read from a client with no Roots capability still issued roots/list and
  replaced a CLI workspace with an unsolicited client response.
  WorkspaceContext.refreshFrom(exchange) must respect negotiated capabilities.
  Without Roots, CLI precedes environment; with Roots, the existing Roots-first
  binding contract remains authoritative.
- Moving process cwd into the scenario workspace exposed a packaged-runtime
  defect in JvmLifecycleConfiguration.packagedDirectory: Spring Boot's nested
  JAR URL could not be converted with Path.of(URI), so it fell back to cwd.
  Existing real attach IT returned agent_artifact_unavailable even though
  target/sidecar contained both packaged JARs. The expected established contract
  is packaged-directory resolution before the local-development fallback.
  Application composition now uses Spring Boot's ApplicationHome, which handles
  its nested archive location. Sidecar and Core resolver behavior are unchanged.

## Commands

Use Java 21 and Maven 3.8.6+ on PATH, or invoke an explicitly configured Maven
executable. Commands below are relative to the checkout root; no machine-specific
tool path is part of the test contract.

Independent UT (no fixture packaging, Sidecar, JMeter, generated IT workspaces or
optional services):

```sh
mvn -B -f mcp-server/pom.xml -pl application -am test
```

Clean-checkout packaging order for process IT:

```sh
mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package -DskipTests
mvn -B -f mcp-server/pom.xml -pl application -am package -DskipTests
```

The application packaging step checks/copies the Sidecar artifacts. Override
`mcp.sidecar.helper.source` and `mcp.sidecar.agent.source` if using deliberately
prebuilt artifacts. Fixture packaging is guarded by `java-it-fixtures`; it uses
the Maven executable from `maven.home` (or `mcp.fixture.maven.executable`), never
a developer-specific fallback.

On-demand real functional smoke (artifact confirmation/write, Catalog/Describe):

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-smoke'
```

Complete shared lifecycle capability, separate from the smoke subset:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-lifecycle'
```

Individual scenario, or a capability's existing IT class:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerLifecycleIT#missingAmbiguousAndInvalidRootsHaveStableOutcomes'
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-fixtures' -Dit.test=McpServerWorkflowIT
```

Full currently implemented Java IT selection (explicit opt-in; requires JMeter
configured by `-Dmcpjvm.acceptance.jmeterHome=...` or `JMETER_HOME`):

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-fixtures'
```

This selects every existing *IT, not just smoke. It does not assert completion of
the still-open capability children #647–#656. Those children must extend the
coverage inventory and provide their complete selections.

Run the same lifecycle command from the owning reactor using `-f pom.xml`, or
from application using `-f ../pom.xml`. Repository root comes from the Maven
module location, with test-class code location as the standalone fallback;
shell cwd is not a repository-discovery input. A relocated checkout uses its own
resolved module paths.

Surefire discovers *Test; Failsafe discovers *IT only in the java-it profile.
Fixture setup requires the additional java-it-fixtures profile. Missing JARs,
tooling, failed setup and empty selections fail visibly. OS-specific signal
tests are explicitly disabled on Windows and reported as skipped in Failsafe
XML, not passed coverage. Stdin closure is the supported STDIO disconnect.
The skipTests, skipITs and maven.test.skip flags also guard fixture preparation,
including when java-it-fixtures was explicitly enabled.

## Evidence and cleanup

JUnit method names are stable scenario IDs. Each method gets
`test/.tmp/java-it-<unique-run>/s-<scenario-number>/w-<workspace-number>`.
Compact path segments leave room for nested suite/JMeter artifacts on Windows;
the full stable scenario ID is recorded in evidence. Run directories are
allocated atomically and scenario/workspace counters are safe for parallel use.
All generated product state and fixture working directories live there.
Every scenario writes a sanitized evidence.json beside those workspaces.
It records outcome, owned PIDs, cleanup outcome, runtime version and tags;
JUnit XML carries exact assertion outcomes. Raw MCP dumps, credentials,
environment, command lines and exception text are excluded from JSON evidence.

`-DmcpItRetainFiles=true` retains scenario files; processes always stop.
Default cleanup removes generated workspace directories, retaining evidence.
Surefire reports live in application/target/surefire-reports; Failsafe reports in
application/target/failsafe-reports. CI can run UT independently and run smoke,
capability and full lanes as separate jobs with separate exit outcomes.

## Coverage inventory

| Scenario group | Boundary/assertions | Owner/consumer |
| --- | --- | --- |
| McpServerProtocolIT | initialize, exactly three Tools, schemas, protocol-only stdout, stdin close, sanitized startup failure, configured catalog bounds, POSIX INT/TERM | MCP host/shared |
| catalogDescribeExecutePersistsARealArtifact | complete paginated catalog without duplicates; all discovered IDs describable; API filter; confirmation blocked then real project JSON write with semantic readback | CDE/Project Artifact Manager |
| invalidAndUnknownRequestsFailStructurallyAndRecover | invalid/unknown Describe and Execute IDs; invalid catalog limits/cursor; invalid arguments; subsequent request succeeds | CDE/all Skills |
| statusRootsRebindPropagatesAndDoesNotCrossProcesses | safe status fields, Roots requests/change, real artifact writes after rebind, separate server workspace isolation | status/MCP host |
| missingAmbiguousAndInvalidRootsHaveStableOutcomes | empty, duplicate and multiple canonical Roots; deterministic missing/ambiguous outcomes | workspace/all Skills |
| configuredWorkspaceWorksWithoutRootsAndRootsRemainAuthoritative | CLI binding without Roots; Roots override fallback config when supported | workspace/MCP host |
| configuredArgumentPrecedesEnvironmentWithoutRoots; sessionAndCwdFallbacksStayWithinScenarioState | CLI over environment; INIT_CWD over PWD; session/CWD/missing fallbacks without Roots | workspace/MCP host |
| cleanupSurvivesFailedSetupAssertionAndRequestTimeout | deliberately failed setup/assertion/request timeout; bounded cleanup; unrelated process and path preserved | shared support |
| cleanupContinuesAfterIndividualProcessFailures | two deliberate cleanup failures are aggregated; a later real JVM is still terminated | shared support |
| ownedDescendantCleanupAndRealParentExitAreBounded | actual parent and descendant JVMs; successful initialize/status exchange and PID/PPID/liveness assertions before parent exit; independent stdin holder remains alive through server termination; bounded tree cleanup | shared support/MCP host |
| Existing transport/workflow/JVM/correlation ITs | retained capability behavior, timeout/cancellation, runtime and persisted effects; workspaces moved under shared boundary | #647–#656 |

Actual Skill execution remains a separate claim; these tests exercise the MCP
and Artifact contracts consumed by Skills.

The status Resource is an additive restored surface; existing CDE Tool names and
input/output schemas are unchanged. Roots negotiation and packaged artifact
location corrections restore established behavior. Gates D/G are covered by the
packaged STDIO scenarios and Maven checks; Gate E is covered by root/local
documentation and executed commands.

## Recorded verification — 2026-10-11

Tested source is the working-tree implementation atop
`b3d0d672fe4c893c3c81ac7023ca305badcf85e1`; no commit or branch change was made.
Prerequisites: Amazon Corretto Java 21.0.12.1, Maven 3.9.12, Sidecar artifacts
0.1.8, existing Spring fixtures 0.1.0-SNAPSHOT, executable server 0.1.9,
and explicitly configured JMeter 5.6.3.

| Check | Result |
| --- | --- |
| Independent clean-source UT in a relocated source snapshot, with deliberately missing fixture Maven/Sidecar/JMeter paths | 903 Core + 25 Application tests passed; zero integration setup hooks |
| Complete current application IT selection, including the complete shared lifecycle capability | 19 discovered; 17 passed; zero failures/errors; two POSIX-only signal tests explicitly skipped on Windows |
| Owning-module invocation in the relocated checkout, java-it + java-it-smoke, optional fixture/JMeter paths unavailable | Exactly one real smoke scenario passed; retained files; cleanup succeeded; no live owned process |
| Empty `it.test=NoSuchJavaIT` selection | Maven exit 1, explicit no-tests failure |
| Selected scenario with `mcpServerJar=missing-required-application.jar` | Maven exit 1, failed prerequisite scenario and cleanup evidence |
| Fixture setup guards for skipTests, skipITs and maven.test.skip | All three commands passed with fixture Antrun and Failsafe execution skipped, despite an invalid fixture Maven executable |
| Enforcer, Checkstyle, PMD, whitespace check | Passed |
| Final process audit | All 17 executed scenarios reported successful cleanup; zero live owned PIDs |

The exact successful application acceptance command, reusing unchanged fixture
JARs already packaged by java-it-fixtures, was:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it -Dtest=WorkspaceResolverTest -Dsurefire.failIfNoSpecifiedTests=false -Dmcpjvm.acceptance.jmeterHome=<explicitly-discovered-JMeter-home>
```

The complete UT evidence above is reused; this command reruns only the directly
affected workspace UTs alongside every application IT. The clean-source
Sidecar prerequisite packaging command and the fixture-packaging profile also
completed successfully. Relocation used a plain source snapshot under
test/.tmp, without a Git checkout/worktree or developer-global configuration.

Local evidence: `test/.tmp/646-verification.json` contains source hashes,
scenario outcomes, prerequisite versions, cleanup and setup-guard results.
Final execution logs are `646-clean-ut-final.log`,
`646-application-it-final-pass.log`, `646-owning-module-smoke.log`,
`646-empty-selection.log`, `646-missing-prerequisite.log`,
`646-clean-sidecar.log`, and `646-guard-*.log`, all beneath test/.tmp.
Per-scenario JSON for the final application run is under
`test/.tmp/java-it-6363857888570738563/s-*/evidence.json`.
These generated diagnostics remain gitignored; raw MCP dumps are not committed.

## P2 repair follow-up — 2026-10-11

Both reported findings were accepted and repaired within the test harness.
Scenario teardown now attempts every owned process and aggregates failures;
tree cleanup also continues across individual handle failures. Cleanup failure
still fails the scenario and records `cleanupSucceeded=false`. The thread-local
scenario is cleared even if evidence writing fails.

The first parent helper repair relayed actual MCP messages and required a
successful initialize and status response with the expected child PID, parent
PID and live processes before the parent exited. The fixed startup sleep was
removed. A readiness timeout stops the helper before closing its output reader,
so a pending read cannot prevent teardown.

Fresh verification ran all 10 lifecycle ITs successfully, followed by both
repaired scenarios after the final timeout-cleanup adjustment (2 passed).
Enforcer, Checkstyle and PMD passed. The targeted command was:

```sh
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it -Dtest=WorkspaceResolverTest -Dsurefire.failIfNoSpecifiedTests=false '-Dit.test=McpServerLifecycleIT#cleanupContinuesAfterIndividualProcessFailures+ownedDescendantCleanupAndRealParentExitAreBounded'
```

The unchanged UT and capability evidence above is reused. Fresh results and
source hashes are recorded separately in `test/.tmp/646-p2-verification.json`;
logs are `646-p2-lifecycle.log` and `646-p2-final-targeted.log`. Production code,
MCP contracts, Maven selection and prerequisites were unchanged by those repairs.

A controlled JAR that exits before initialization was also passed to the
parent-exit scenario using `-DmcpServerJar=<failed-server.jar>`. It failed at the
15-second readiness timeout with Maven exit 1, rather than passing the shutdown
assertion. Its teardown reported successful cleanup. This negative check is
recorded in `646-p2-failed-server.log`. All 13 fresh scenario executions reported
successful cleanup, and the final audit found zero live owned processes.

## Parent-exit proof independent of EOF — 2026-10-11

The remaining P2 was valid: the first parent helper repair closed the only
stdin writer when it exited, so that evidence established readiness and shutdown
but did not isolate parent monitoring from EOF.

The helper now launches a Java pipeline with an independent stdin-holder JVM
and the actual `java -jar` server as its children. The test sends MCP messages
to the holder over a loopback socket. After readiness, it exits only the parent,
waits for the server to terminate, and asserts that the holder is still alive
and its control connection remains open. Only then does teardown close stdin.
The test owns and cleans up all three processes.

This corrected scenario initially failed against the real server at its
15-second shutdown deadline. A thread dump showed the non-daemon MCP STDIO
reader blocked in a native input read after Spring context closure. The bounded
production correction is in `ParentProcessLifecycle`: after parent loss it
closes the context and explicitly exits the standalone JVM. It does not change
MCP schemas, Core behavior or Sidecar behavior.

Fresh scoped verification passed all 25 Application UTs and 13 lifecycle/protocol
ITs, with two POSIX-only skips on Windows. Enforcer, Checkstyle and PMD passed.
The command selected all Application `*Test` classes by name and used:

```powershell
$applicationTests646 = ((rg --files mcp-server/application/src/test |
  Where-Object { $_ -match 'Test.java$' }) |
  ForEach-Object { [System.IO.Path]::GetFileNameWithoutExtension($_) }) -join ','
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it "-Dtest=$applicationTests646" -Dsurefire.failIfNoSpecifiedTests=false '-Dit.test=McpServerLifecycleIT,McpServerProtocolIT'
```

A control JAR copied from the working package had only the `Component`
annotation descriptor in `ParentProcessLifecycle.class` changed to an unknown
annotation of equal length. Entry hashes confirmed that every other JAR entry
was unchanged. It initialized and returned status successfully, then failed
at the shutdown deadline with stdin held open (Maven exit 1). Thus EOF does not
make the parent-exit scenario pass when the watcher is absent. The control
mutation exists only in a gitignored JAR; no production disabling option was added.

The early-exit JAR was rerun through the new holder and still failed at the
15-second readiness deadline (Maven exit 1). All 15 final scenario executions,
including both expected-failure controls, reported successful cleanup. The
audit across all 19 scenario executions during this repair found no live owned
processes.

Logs are `test/.tmp/646-p2-parent-final.log` and
`test/.tmp/646-p2-watcher-disabled.log`. Source hashes, control provenance,
scenario cleanup and process-audit evidence are recorded separately in
`test/.tmp/646-p2-parent-verification.json`. Earlier unchanged Core/capability
evidence is reused. Gates D/G use the real packaged MCP and protocol checks;
Gate E uses the updated executable lifecycle guidance and commands.
The early-startup control log is `test/.tmp/646-p2-held-input-startup-failure.log`.
