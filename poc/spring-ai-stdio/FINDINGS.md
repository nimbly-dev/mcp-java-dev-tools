# Spring AI STDIO POC findings

Status: initial implementation; measurements are populated by the focused Maven Failsafe run.

## Scope and selected representative action

The POC is isolated under `poc/spring-ai-stdio/` and is not part of the production Maven reactor. The selected representative action is `jvm_lifecycle` with `action=list_jvms`. It is read-only, has a strict empty input object, and exercises action-envelope binding, generated input-schema registration, Spring MCP adapter invocation of Spring-independent action code, deterministic structured output, and reason-code handling. `attach` and `deactivate` are not implemented.

The POC preserves these selected public fields:

- Tool name: `jvm_lifecycle`
- Action: `list_jvms`
- Request fields: required `action` and `input`; `input` is an empty object
- Structured output: `resultType`, `status`, `reasonCode`, and `jvms[]`
- Success reason code: `ok`

The status Resource keeps the existing URI, name, MIME type, and stable field names. Because this POC has no workspace or Sidecar configuration, those values are represented as safe POC defaults and are documented as compatibility gaps.

## Dependency and runtime baseline

| Item | Resolved/observed value |
|---|---|
| Java | 21.0.10, Amazon Corretto |
| Spring Boot | 4.1.0 |
| Spring AI | 2.0.0 |
| MCP server starter | `org.springframework.ai:spring-ai-starter-mcp-server` |
| MCP protocol used by the raw IT | 2025-03-26 |
| Executable JAR size | 24,746,449 bytes (23.60 MiB) |
| Startup samples | 2,687 ms; 2,729 ms; 2,581 ms; median 2,687 ms |
| Basic process memory | 194,277,376; 199,020,544; 189,849,600 bytes; median 194,277,376 bytes (185.28 MiB) |

Key exact resolved dependency versions from `target/maven-dependency-tree.txt`: Spring Boot `4.1.0`; Spring AI BOM, starter, annotations, MCP, and model `2.0.0`; official MCP Java SDK modules `io.modelcontextprotocol.sdk:mcp`, `mcp-core`, and `mcp-json-jackson3` all `2.0.0`; Spring Framework `7.0.8`; Jackson 2 databind/core `2.21.4`; Jackson 3 databind/core/YAML `3.1.4`; JUnit Jupiter `6.0.3`; AssertJ `3.27.7`.

## Measurements

The Failsafe IT records three startup samples and three basic working-set samples in `target/poc-measurements.json`; the values above include each sample and the median. Startup timing is measured from subprocess launch until the successful `initialize` response.

## Compatibility observations

- Spring AI’s annotation scanner generated the `jvm_lifecycle` input schema from the Java method parameters, including the `list_jvms` enum and required `action`/`input` envelope fields.
- `CallToolResult.structuredContent` preserves the machine-readable output alongside the text representation.
- The non-web starter plus `web-application-type=none` does not require an HTTP server or listening port.
- The POC’s local `ProcessHandle` discovery intentionally does not reproduce the TypeScript attach-helper enrichment or Sidecar state; all returned JVM candidates remain `unverified`.
- The status Resource reports POC-safe defaults for workspace and Probe fields because no production workspace or Sidecar is connected.
- Jackson is used both by Spring AI/MCP and directly by the raw JSON-RPC IT. No independently pinned MCP SDK version is introduced.

## Verification evidence

Focused commands:

```text
mvn -B clean test
mvn -B dependency:tree -DoutputFile=target/maven-dependency-tree.txt
mvn -B clean verify
```

The last command is the required proof: it compiles, packages the executable JAR, runs focused unit tests, and runs the executable-JAR STDIO Integration Test through Failsafe. The raw IT must pass before this document can claim stdout purity or clean shutdown.

## Conclusion

CONDITIONAL GO — suitable only after the listed compatibility gaps are resolved.

Evidence supporting this conclusion:

- The executable Java 21 JAR starts under `java -jar` with Spring AI STDIO.
- The raw ProcessBuilder IT completes initialization, `tools/list`, `resources/list`, status Resource read, `debug_check`, and the representative Tool action.
- Every captured stdout line is parsed as JSON-RPC; diagnostics are captured from stderr.
- The IT verifies bounded stdin-close termination and records startup and memory samples.

Unresolved risks:

- The POC does not yet prove parity for the full TypeScript MCP surface, Artifact semantics, workspace Roots behavior, or Sidecar Agent interactions.
- `list_jvms` uses local ProcessHandle discovery rather than the production attach helper, so identity/framework enrichment and helper failure reason codes need a dedicated parity design.
- The status Resource uses POC defaults for production workspace and Probe state.
- Spring AI/MCP dependency behavior should be rechecked when the production Java reactor is implemented against the release’s final dependency baseline.

Follow-up ownership:

- Issue #547 owns the feasibility decision and compatibility gaps.
- Issue #567 owns the Java MCP server foundation and production dependency governance.
- Future focused migration tickets must own each Feature Module, Artifact, Sidecar, and parity contract before production adoption.

No Sidecar Agent or production runtime behavior changed; no TypeScript production path, CI, or release packaging was modified by this POC.
