# Java MCP server

This Maven reactor is the public Java 21 STDIO MCP runtime for `v0.1.9`.
Spring AI exposes one Catalog-Describe-Execute boundary backed by the aggregate
Java operation directory. Legacy TypeScript sources may be read to identify
behavior that still needs to move, but all MCP behavior is implemented and
verified in Java; TypeScript execution is not an acceptance path.

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
    "<absolute-path>/mcp-java-dev-tools-server-0.1.9.jar"
  ]
}
```

## Build and verification

From the repository root, package the agent artifacts and the server together:

```text
bash ./scripts/package-java-mcp-server.sh
# Windows PowerShell: .\scripts\package-java-mcp-server.ps1
```

After packaging, from this directory:

```text
mvn -B verify
mvn -B dependency:tree -pl application
java -jar application/target/mcp-java-dev-tools-server-0.1.9.jar
```

`mvn test` runs independent Surefire unit tests without integration setup.
`mvn verify` also applies Maven Enforcer, Checkstyle, PMD and application
packaging; it does not implicitly run process ITs or package fixtures.
Explicitly enable `-Pjava-it,java-it-lifecycle` for the complete shared lifecycle
selection, `-Pjava-it,java-it-smoke` for real functional smoke, or
`-Pjava-it,java-it-fixtures,java-it-regression-fixtures` with the declared
receiving-tool/JMeter/Docker prerequisites for the complete application IT lane.
See [the exact commands, prerequisites and coverage inventory](../docs/acceptance/mcpjvm-646-java-it.md).

The complete cross-feature and Skill-facing Tool/Artifact workflow selection is
`-Pjava-it,java-it-workflows,java-it-fixtures`; its separate inventory smoke is
`-Pjava-it,java-it-workflows-smoke`. See the
[consolidated operation/Skill inventory, prerequisites and full Java IT command](../docs/acceptance/mcpjvm-656-skill-workflows-it.md).
This lane uses existing real JVM/Probe, exception investigation, correlation,
suite, restart, migration and receiving-tool replay scenarios. Automated
Tool/Artifact/helper compatibility and actual conversational Skill use are
reported separately.
Behavior-focused workflow classes also cover exception investigation becoming a
durable regression guard and downstream repair requiring a fresh run after
restart. Skill references provide many-to-many traceability across these flows.

The generated Regression capability selection is
`-Pjava-it,java-it-regression,java-it-regression-fixtures`. It includes real strict
Probe routes, producer/consumer and executor/KCL correlation, HTTP Watchers,
SQLite/PostgreSQL verification, redaction, failures and persisted phase recovery.
PostgreSQL requires a running Docker engine; its owned container uses temporary
memory storage and is removed on cleanup. The separate one-scenario smoke is
`-Pjava-it,java-it-regression-smoke` and has no Spring/Docker prerequisite.
See [#652 commands and behavior inventory](../docs/acceptance/mcpjvm-652-regression-it.md).

The complete Performance capability selection is
`-Pjava-it,java-it-performance,java-it-jvm-fixtures`. It requires explicitly
configured Apache JMeter, exercises real JMeter and builtin workloads, and
includes profiler/JFR, MSTA, failure, timeout and continuation scenarios.
MSTA calibration uses real wall samples, repeated sleep ranges, independent
timers for CPU work on the tested machine, and a minute-outlier rejection.
The separate smoke selection is
`-Pjava-it,java-it-performance-smoke,java-it-jvm-fixtures`.
See [#653 prerequisites, commands and assertions](../docs/acceptance/mcpjvm-653-performance-it.md).

The Security capability selection is
`-Pjava-it,java-it-security,java-it-jvm-fixtures`. It runs controlled Black-box
mutation cases, project credentials/refresh, real Sidecar line evidence, persisted
findings, safety limits, failure and recovery. It requires the current JDK and
PowerShell on Windows or `sh` on Unix for credential refresh; no external scan or
JMeter is used. The separate one-scenario smoke selection is
`-Pjava-it,java-it-security-smoke` and does not build or attach a Spring fixture.
See [#654 commands, coverage and evidence](../docs/acceptance/mcpjvm-654-security-it.md).

For real JVM Lifecycle/Probe acceptance, use
`-Pjava-it,java-it-jvm,java-it-jvm-fixtures`; the separate
`-Pjava-it,java-it-jvm-smoke,java-it-jvm-fixtures` selection runs one real strict
lambda-hit workflow. See [JVM/Probe scenarios and evidence](../docs/acceptance/mcpjvm-647-jvm-probe-it.md).
Those tests initialize the server,
assert exactly the three CDE tools, execute real operation paths, perform a real
attach/deactivate against a separate target JVM, verify target survival,
confirm JSON-RPC-only stdout, and prove the sanitized startup-failure boundary.
Parent exit and descendant cleanup also have real child-process coverage.

Route Synthesis's complete capability selection is
`-Pjava-it,java-it-route,java-it-jvm-fixtures`; its separate one-scenario smoke
selection is `-Pjava-it,java-it-route-smoke,java-it-jvm-fixtures`.
See [Route Synthesis scenarios, corrections and evidence](../docs/acceptance/mcpjvm-648-route-synthesis-it.md).

Failure Analysis's complete supported capability selection is
`-Pjava-it,java-it-failure,java-it-jvm-fixtures`; its separate smoke selection is
`-Pjava-it,java-it-failure-smoke,java-it-jvm-fixtures`.
See [Failure Analysis scenarios, Skill dependencies and evidence](../docs/acceptance/mcpjvm-649-failure-analysis-it.md).

Artifact persistence's complete capability selection is
`-Pjava-it,java-it-artifact,java-it-fixtures`; its separate registry lifecycle
smoke selection is `-Pjava-it,java-it-artifact-smoke`.
See [Artifact operations, storage corrections and evidence](../docs/acceptance/mcpjvm-650-artifact-it.md).

Transport's complete HTTP/HTTPS, authentication, timeout and cancellation
selection is `-Pjava-it,java-it-transport`; its separate one-scenario smoke
selection is `-Pjava-it,java-it-transport-smoke`. Both use local controlled
targets and need no Spring fixture packaging or JMeter. HTTPS additionally uses
the current JDK's `keytool` to generate ephemeral scenario-local certificates.
See [Transport scenarios, contracts and evidence](../docs/acceptance/mcpjvm-651-transport-it.md).

## Initial maintainability baseline

The Foundation and `core` retain their established limits of 120 lines per
method and 12 parameters. `application` enforces the #569 runtime requirements
independently: 45 nonblank/noncomment lines per method and no more than six
method parameters. This constraint applies to methods, not files, avoiding
artificial class fragmentation. All modules prohibit nested ternaries,
multiple statements per line, unused imports, star imports, and missing braces;
PMD enforces maximum cyclomatic complexity of 10.
