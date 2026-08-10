# Java MCP server foundation

This Maven reactor is the opt-in Java MCP server foundation for `v0.1.9`.
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

No Feature API, Artifact schema, storage implementation, integration,
reporting workflow, suite execution behavior, reason-code catalog, parity
contract, or optional HTTP transport is defined by this foundation. Those are
intentionally deferred to focused migration tickets.

## Runtime boundary

The application uses Spring AI's non-web MCP server starter. It runs with
`spring.ai.mcp.server.stdio=true` and
`spring.main.web-application-type=none`. Logback writes diagnostics only to
stderr; stdout is reserved for MCP JSON-RPC messages.

The executable artifact is:

```text
application/target/mcp-java-dev-tools-server-0.1.9.jar
```

## Build and verification

From this directory:

```text
mvn -B verify
mvn -B dependency:tree -pl application
java -jar application/target/mcp-java-dev-tools-server-0.1.9.jar
```

`mvn verify` applies Maven Enforcer, Checkstyle, PMD, unit-test discovery, and
the focused executable-JAR STDIO integration test. That test initializes the
server, verifies JSON-RPC-only stdout, and confirms bounded termination after
stdin closes.

## Initial maintainability baseline

The foundation applies the repository's established Java-agent limits of 120
lines per method and 12 parameters. This deliberately allows the migration
surface to grow without premature structural churn; nested ternaries,
multiple statements per line, unused imports, star imports, and missing braces
remain build-breaking checks. Focused migration work may tighten these limits
when a concrete Feature Module establishes its own package-level conventions.
