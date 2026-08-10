# Spring AI STDIO MCP proof of concept

This is an isolated Java 21 + Spring AI 2.0.0 feasibility proof of concept for GitHub issue #547. It is intentionally outside the production Maven reactor and does not alter the TypeScript MCP server, the Java Sidecar Agent, production configuration, CI, or release packaging.

## Scope

The executable JAR exposes only:

- `debug_check`
- the existing `mcp-java-dev-tools://status` Resource
- `jvm_lifecycle` with the existing `action=list_jvms` action

`jvm_lifecycle/list_jvms` is the representative action because it is read-only, has a strict empty input object, exercises the action envelope, generated input schema, Spring MCP adapter, Spring-independent action invocation, and deterministic `resultType/status/reasonCode` output without requiring a Sidecar mutation or external service. The `attach` and `deactivate` actions are deliberately not implemented in this POC.

## Run

From this directory:

```text
mvn -B clean verify
java -jar target/spring-ai-stdio-poc.jar
```

The process speaks newline-delimited MCP JSON-RPC on stdin/stdout. Spring diagnostics are routed to stderr, and closing stdin is expected to terminate the process within the bounded integration-test timeout.

The integration test is named `*IT` and is executed by Maven Failsafe. It launches the packaged JAR with `ProcessBuilder`, performs the MCP initialize, discovery, Resource, and Tool calls, parses every stdout line as JSON-RPC, checks stdout purity, captures stderr separately, measures startup and memory, and verifies bounded stdin-close shutdown.

This POC does not create a branch, push, open a pull request, modify issue #547, or change production modules.
