# Java MCP server

This Maven reactor is the public Java 21 STDIO MCP runtime for `v0.1.9`.
Spring AI exposes one Catalog-Describe-Execute boundary backed by the aggregate
Java operation directory. The Maven package has no npm or TypeScript build
dependency. Separate JavaScript Skill helpers and the integration coverage
validator remain available without npm dependencies.

## Modules and ownership

```text
application -> core
```

- `core` is a normal library JAR. It owns Spring-independent Feature actions,
  capability models, Artifact validation and persistence, integrations,
  execution orchestration, reporting, and the single aggregate operation
  directory.
- `application` is the executable Spring Boot JAR. It owns the entry point,
  configuration, Spring AI MCP Tool registration, thin CDE transport mapping,
  MCP-bound request mapping, deterministic output normalization, STDIO
  lifecycle, and dependency wiring.

Only `application` may depend on Spring Boot, Spring AI, or MCP transport
types. `core` must never depend on `application`; Maven Enforcer makes the
Spring and MCP transport dependency restriction explicit.

Product behavior stays in its substantive Core owner. The application module
must not add feature-specific MCP adapters, a second operation catalog, policy
logic, or fallback routing. Test-scope fake Features must never ship in the
executable JAR or be exposed as MCP Tools.

## Public MCP boundary

`OperationMcpTools` exposes exactly three Spring AI tools:

- `operation_catalog`
- `operation_describe`
- `operation_execute`

Probe, JVM lifecycle, Route Synthesis, Failure Analysis, transport execution,
Artifact Management, suite execution, execution orchestration, and export are
canonical operations in the one aggregate `OperationDirectory`. Each operation
registration delegates directly to its substantive owner and carries its
Java-owned schema and safety contract.

## Runtime boundary

The application uses Spring AI's non-web MCP server starter. It runs with
`spring.ai.mcp.server.stdio=true` and
`spring.main.web-application-type=none`. Logback writes diagnostics only to
stderr; stdout is reserved for MCP JSON-RPC messages.

The public Tools are limited to the three CDE tools. The
`mcp-java-dev-tools://status` Resource exposes safe runtime and workspace
metadata. Capability
names such as `probe`, `jvm_lifecycle`, and `artifact_management` are operation
API namespaces, not additional MCP tools.

Workspace selection initially uses `--workspace-root`, then
`MCP_WORKSPACE_ROOT`, then `INIT_CWD` or `PWD`, followed by the current
directory when it contains `.mcpjvm/probe-config.json`. Multiple Roots containing canonical Probe
configuration Fail Closed with `workspace_context_ambiguous`. An invalid path
value fails closed with `workspace_context_invalid`; no raw workspace value is
returned in a protocol response.

After a successful startup, the executable closes when stdin reaches EOF and
also monitors its immediate parent process. If that parent exits, the server
closes its Spring context and exits the JVM, including when another process
keeps stdin open. On POSIX platforms, normal
JVM `SIGINT` and `SIGTERM` handling invokes Spring shutdown; guarded raw-JAR
integration tests exercise both signals. Windows has no portable equivalent to
those signals, so its supported lifecycle path is stdin closure and parent
`ProcessHandle` monitoring. All server logs go to stderr.
Fatal startup failures emit only the sanitized stderr marker
`mcp_java_dev_tools_startup_failed reasonCode=startup_failed` and exit nonzero.

The executable artifact is:

```text
application/target/mcp-java-dev-tools-server-0.1.9.jar
application/target/sidecar/jvm-attach-helper.jar
application/target/sidecar/sidecar-agent.jar
```

Configure an MCP host with Java 21 installed:

```json
{
  "command": "java",
  "args": [
    "-jar",
    "<absolute-path>/mcp-java-dev-tools-server-0.1.9.jar",
    "--workspace-root=<absolute-workspace-path>"
  ]
}
```

Copy the executable JAR and adjacent `sidecar/` directory together to deploy.
The workspace is separate from the package location; no checkout, TypeScript,
`dist`, `node_modules`, or Node on `PATH` is needed for the Java process.
Server `0.1.9` currently consumes Sidecar/helper `0.1.8` artifacts through their
existing lifecycle protocol. Artifact versions are independent.

The checkout installers support `--server-jar`, `--no-build-server` and
`--workspace-root`, plus isolated client/Skill destinations. See the
[root installation instructions](../README.md#installer). Update preserves MCP
configuration unless `--configure-mcp` is explicit. Retained regression Skill
helpers require Node 18+ separately, with no npm install.

## Build and verification

From the repository root, package the agent artifacts and the server together:

```text
bash ./scripts/package-java-mcp-server.sh
# Windows PowerShell: .\scripts\package-java-mcp-server.ps1
```

All commands below run from the repository root. Java 21+ JDK (including
Attach API, jcmd/JFR and keytool) and Maven 3.8.6+ are required. The packaging
script above builds the reusable Sidecar artifacts; to build those alone:

```text
mvn -B -f java-agent/pom.xml -pl core/core-jvm-attach,core/core-probe -am package -DskipTests
```

The application package checks and copies the Sidecar helper and agent. Explicit
prebuilt inputs may use `mcp.sidecar.helper.source` and
`mcp.sidecar.agent.source`. Independent unit tests require no Sidecar, fixture
packaging or integration tools:

```text
mvn -B -f mcp-server/pom.xml -pl application -am test
mvn -B -f mcp-server/pom.xml -pl application -am verify
mvn -B -f mcp-server/pom.xml -pl application dependency:tree
```

`test` runs Surefire unit tests. `verify` also applies Maven Enforcer,
Checkstyle, PMD and application packaging; process ITs and fixture builds remain
opt-in. Test reports live in each module's `target/surefire-reports` and
`application/target/failsafe-reports`.

Installer checks use only temporary client/Skill directories, including paths
with spaces:

```text
bash scripts/tests/java-mcp-config-paths.sh
```

On Windows, verify a copied distribution from an unrelated directory with
Node removed from the launched Java process's `PATH`:

```powershell
./scripts/tests/java-mcp-native-config.ps1 -ServerJar 'C:/staging path/mcp-java-dev-tools-server-0.1.9.jar' -WorkspaceRoot 'C:/workspace path' -WorkingDirectory 'C:/staging path'
```

Create those directories and copy the JAR plus `sidecar/` first. This check uses
generated client arguments, initializes real STDIO, checks the three CDE Tools,
runs Catalog/Describe/JVM discovery, verifies explicit workspace binding and
protocol-only stdout, and requires exit 0 on stdin close. Remove the isolated
staging and workspace directories after verification.

### Integration test lanes

Run the packaged catalog/dependency smoke without Spring fixtures or external
receiving tools:

```text
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-workflows-smoke'
```

For a capability lane, use the same command with the profiles from the table.
Every selection includes `java-it`; smoke is a separate subset. Fixture
profiles package the existing social-platform applications with Maven from
`maven.home` (override with `mcp.fixture.maven.executable`). Reuse already built
fixtures only when source, inputs and environment are unchanged.

| Lane | Complete profiles (after `java-it,`) | Smoke profiles (after `java-it,`) | Additional prerequisites and scope |
| --- | --- | --- | --- |
| Shared lifecycle | `java-it-lifecycle` | `java-it-smoke` | Protocol, Roots, workspace, status Resource, shutdown and process cleanup; smoke exercises Catalog/Describe and Artifact writes |
| JVM/Probe | `java-it-jvm,java-it-jvm-fixtures` | `java-it-jvm-smoke,java-it-jvm-fixtures` | Real attach/deactivate and fresh Strict Line Hits against a separate fixture JVM |
| Route Synthesis | `java-it-route,java-it-jvm-fixtures` | `java-it-route-smoke,java-it-jvm-fixtures` | Source/runtime discovery, recipes, transport effects and capture |
| Failure Analysis | `java-it-failure,java-it-jvm-fixtures` | `java-it-failure-smoke,java-it-jvm-fixtures` | Real fixture exception/JFR, bounded reproduction and failure evidence |
| Artifact persistence | `java-it-artifact,java-it-fixtures` | `java-it-artifact-smoke` | Registry/project/plan/run/export, SQLite, safety and restart; smoke needs no Spring fixture |
| Transport | `java-it-transport` | `java-it-transport-smoke` | Local HTTP/HTTPS targets; JDK keytool for temporary certificates, no Spring or JMeter |
| Regression | `java-it-regression,java-it-fixtures,java-it-regression-fixtures` | `java-it-regression-smoke` | Producer/consumer and executor/KCL correlation, strict Probes, Watchers, HTTP/SQLite/PostgreSQL verification and recovery; complete lane needs Docker for PostgreSQL, smoke needs no Spring/Docker |
| Performance | `java-it-performance,java-it-jvm-fixtures` | `java-it-performance-smoke,java-it-jvm-fixtures` | Configured Apache JMeter, builtin workloads, thresholds, profiler/JFR, MSTA and recovery |
| Security | `java-it-security,java-it-jvm-fixtures` | `java-it-security-smoke` | Controlled Black-box/Sidecar cases, persisted findings and recovery; PowerShell on Windows or sh on Unix for credential refresh, no external scanner/JMeter; smoke needs no Spring fixture |
| Orchestration/export | `java-it-orchestration,java-it-fixtures` | `java-it-orchestration-smoke` | Ordered work, leases, slicing, runtime ownership and receiving-tool replay; Node, PowerShell, Bash, Newman, JMeter and Docker with Compose for complete lane |
| Cross-feature/Skill workflows | `java-it-workflows,java-it-fixtures` | `java-it-workflows-smoke` | Real JVM/Probe, suites, restart, migration, exception/recovery and receiving-tool replay; Node, PowerShell, Bash, Newman and JMeter for complete lane |

Provision selected tools separately. Node is used by shipped Skill helpers and
replay runners; no npm dependencies are needed for the server or coverage
validator. Postman replay requires an installed Newman script. Pass absolute
locations as Maven properties when defaults on PATH are unsuitable:

| Property | Selection |
| --- | --- |
| `mcpItNode` | Node executable (default `node`) |
| `mcpItPowerShell` | PowerShell executable (default `pwsh`) |
| `mcpItBash` | Bash executable (default `bash`) |
| `mcpItNewmanScript` | Required path to `newman/bin/newman.js` for Postman replay |
| `mcpjvm.acceptance.jmeterHome` | Apache JMeter home; `JMETER_HOME` is the fallback |
| `mcpItDocker` | Docker executable (default `docker`) for replay helpers |

Docker cases require a running engine, Compose and access to the images/build
inputs declared in the existing PostgreSQL and replay test helpers. Native Linux
replay builds its owned image and mounts the configured JMeter home. Missing
required tools fail the selected lane. Platform-specific signal/file-lock cases
run only on their supported host.

For example, run one existing scenario without narrowing the unit-test lane:

```text
mvn -B -f mcp-server/pom.xml -pl application -am verify -Pjava-it '-Dit.test=McpServerSkillWorkflowIT#inventoryMatchesPackagedCatalogAndEverySkillDependency'
```

Add the table's fixture profiles and required tool properties for other
scenarios. The complete application IT selection is an explicit opt-in with no
capability or method filter. In PowerShell, after provisioning the tools:

```powershell
$itTools = @(
  '-DmcpItBash=<absolute Bash executable>',
  '-DmcpItPowerShell=<absolute pwsh executable>',
  '-DmcpItNode=<absolute Node executable>',
  '-DmcpItNewmanScript=<absolute newman/bin/newman.js>',
  '-Dmcpjvm.acceptance.jmeterHome=<absolute JMeter home>'
)
mvn -B -f mcp-server/pom.xml -pl application -am verify '-Pjava-it,java-it-fixtures,java-it-regression-fixtures' @itTools
```

The test-only [coverage inventory](test-support/java-it-coverage.json) maps
operations and Skills to executable scenario IDs, source assertions, dependencies
and commands. It is consumed by `SkillWorkflowSupport` and the dependency-free
validator; Java registrations remain the only executable operation directory.
Run the surviving repository Skill/resource checks and inventory validation:

```text
node scripts/run-unit-tests.cjs
node mcp-server/test-support/java-it-coverage.mjs
node mcp-server/test-support/java-it-coverage.mjs --require-workflow-evidence
```

The validator writes its derived report under `test/.tmp`; an optional
`--output test/.tmp/<name>.json` selects another local output. Inventory validity,
prior successful evidence, and fresh execution are separate claims. The strict
workflow option requires successful local workflow records and a passing latest
attempt; applicability after source/input/environment changes still needs a
provenance check. Automated Tool/Artifact/helper checks do not establish actual
conversational use of every Skill. Historical delivery evidence belongs to the
linked GitHub issues.

Scenario input, isolated workspaces and sanitized evidence live beneath resolved
`test/.tmp/java-it-<run>/s-<scenario>`, independent of caller cwd. Fixture source
is immutable. `JavaItScenario` owns and stops spawned processes/descendants even
on failure, verifies termination and removes scenario files while retaining
bounded `evidence.json`. `-DmcpItRetainFiles=true` retains files only, never
processes. A persisted result records historical runtime observations; it cannot
prove a fresh Probe hit after reset or restart.

## Initial maintainability baseline

The Foundation and `core` retain their established limits of 120 lines per
method and 12 parameters. `application` enforces the #569 runtime requirements
independently: 45 nonblank/noncomment lines per method and no more than six
method parameters. This constraint applies to methods, not files, avoiding
artificial class fragmentation. All modules prohibit nested ternaries,
multiple statements per line, unused imports, star imports, and missing braces;
PMD enforces maximum cyclomatic complexity of 10.
