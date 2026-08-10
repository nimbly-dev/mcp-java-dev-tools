# Java MCP server foundation

This Maven reactor is the opt-in Java MCP server runtime for `v0.1.9`.
The TypeScript MCP server remains the production default. The Sidecar Agent,
existing MCP Tool behavior, and persisted Artifact semantics are unchanged.

## Modules and ownership

```text
application -> core
```

- `core` is a normal library JAR. It will own Spring-independent Feature
  actions, capability-owned models, Artifact models and validation, Artifact
  path policy and persistence, integrations, and reporting as those concerns
  are migrated by focused follow-up work.
- `application` is the executable Spring Boot JAR. It owns the entry point,
  configuration, Spring AI MCP Tool registration, thin MCP Tool adapters,
  MCP-bound request mapping, deterministic output normalization, STDIO
  lifecycle, and dependency wiring.

Only `application` may depend on Spring Boot, Spring AI, or MCP transport
types. `core` must never depend on `application`; Maven Enforcer makes the
Spring and MCP transport dependency restriction explicit.

No production Core Feature, Artifact schema, storage implementation,
integration, reporting workflow, suite execution behavior, reason-code
catalog, parity contract, or optional HTTP transport is defined by the runtime.
The first real `application -> core` Transport Adapter is owned by the first
completed Feature-migration Story, expected to be Probe Story #571. A
test-scope fake Feature may prove injection and delegation in that Story, but
must never ship in the executable JAR or be exposed as an MCP Tool.

## MCP adapter destinations

The runtime creates packages only when their owning Feature migration provides
real behavior. The documented destinations below keep the `application` module
ready without registering unsupported placeholder MCP Tools.

| Public surface | Application destination | v0.1.9 status |
| --- | --- | --- |
| `debug_check` | `mcp/tools/debugcheck` | registered; application-only |
| `mcp-java-dev-tools://status` | `mcp/resource/status` | registered |
| `jvm_lifecycle` | `mcp/tools/jvmlifecycle` | Feature migration required |
| `probe` | `mcp/tools/probe` | #571 registration story |
| `route_synthesis` | `mcp/tools/routesynthesis` | Feature migration required |
| `failure_analysis` | `mcp/tools/failureanalysis` | Feature migration required |
| `transport_execute` | `mcp/tools/transportexecute` | Feature migration required |
| `artifact_management` | `mcp/tools/artifactmanagement` | Feature migration required |
| `execution_profile_export` | `mcp/tools/executionprofileexport` | Feature migration required |
| `execution_orchestration` | `mcp/tools/executionorchestration` | Feature migration required |

Each future Transport Adapter performs MCP shape validation, request mapping,
Feature invocation, and Deterministic Output normalization only. It must not
perform Artifact I/O, external transport execution, Probe behavior, or Suite
orchestration.

## Runtime boundary

The application uses Spring AI's non-web MCP server starter. It runs with
`spring.ai.mcp.server.stdio=true` and
`spring.main.web-application-type=none`. Logback writes diagnostics only to
stderr; stdout is reserved for MCP JSON-RPC messages.

The initial public surface is deliberately small:

- `debug_check` is application-only and reports reachability, process
  metadata, version, build fingerprint, and the selected workspace.
- `mcp-java-dev-tools://status` reports safe server, workspace, Probe-routing,
  registry, and credential-discovery metadata. Unmigrated Probe and registry
  behavior is identified with deterministic status and reason-code values;
  the Resource never exposes configuration secrets.

Workspace selection initially uses `--workspace-root`, then
`MCP_WORKSPACE_ROOT`, then `INIT_CWD` or `PWD`, followed by the current
directory when it contains `.mcpjvm/probe-config.json`. After initialization,
the server obtains MCP Roots on `debug_check` and status requests and processes
Roots-change notifications. Multiple Roots containing canonical Probe
configuration Fail Closed with `workspace_context_ambiguous`. An invalid path
value fails closed with `workspace_context_invalid`; no raw workspace value is
returned in a protocol response.

After a successful startup, the executable closes when stdin reaches EOF and
also monitors its immediate parent process. If that parent exits, the server
closes its Spring context and its STDIO transport. On POSIX platforms, normal
JVM `SIGINT` and `SIGTERM` handling invokes Spring shutdown; guarded raw-JAR
integration tests exercise both signals. Windows has no portable equivalent to
those signals, so its supported lifecycle path is stdin closure and parent
`ProcessHandle` monitoring. All server logs go to stderr.
Fatal startup failures emit only the sanitized stderr marker
`mcp_java_dev_tools_startup_failed reasonCode=startup_failed` and exit nonzero.

The executable artifact is:

```text
application/target/mcp-java-dev-tools-server-0.1.9.jar
```

Opt in from an MCP host with Java 21 installed:

```json
{
  "command": "java",
  "args": [
    "-jar",
    "<absolute-path>/mcp-java-dev-tools-server-0.1.9.jar"
  ]
}
```

Switching back means restoring the existing TypeScript MCP-host command. The
Java runtime does not launch, proxy, supervise, or fall back to TypeScript.

## Build and verification

From this directory:

```text
mvn -B verify
mvn -B dependency:tree -pl application
java -jar application/target/mcp-java-dev-tools-server-0.1.9.jar
```

`mvn verify` applies Maven Enforcer, Checkstyle, PMD, unit-test discovery, and
the focused executable-JAR STDIO integration test. That test initializes the
server, reads the status Resource, invokes `debug_check`, verifies JSON-RPC-only
stdout, confirms bounded termination after stdin closes, and proves the
sanitized nonzero startup-failure boundary. Parent-exit monitoring has focused
unit coverage for its deterministic shutdown decision.

## Initial maintainability baseline

The Foundation and `core` retain their established limits of 120 lines per
method and 12 parameters. `application` enforces the #569 runtime requirements
independently: 45 nonblank/noncomment lines per method and no more than six
method parameters. This constraint applies to methods, not files, avoiding
artificial class fragmentation. All modules prohibit nested ternaries,
multiple statements per line, unused imports, star imports, and missing braces;
PMD enforces maximum cyclomatic complexity of 10.
