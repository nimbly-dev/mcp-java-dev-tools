# Java Agent Structure

Grouped module layout with non-breaking artifact names.

The authoritative module purpose, dependency direction, public-surface rules,
and migration inventory are documented in [ARCHITECTURE.md](ARCHITECTURE.md).

## Active module groups

- `core/core-probe` (`mcp-java-dev-tools-agent`)
- `core/core-probe-runtime` (`mcp-java-dev-tools-core-probe-runtime`)
- `core/core-probe-instrumentation` (`mcp-java-dev-tools-core-probe-instrumentation`)
- `core/core-probe-control-http` (`mcp-java-dev-tools-core-probe-control-http`)
- `core/core-entrypoint-mapper` (`mcp-java-dev-tools-core-entrypoint-mapper`)
- `mappers-adapters/adapter-request-mapper-spring-http` (`mcp-java-dev-tools-adapter-request-mapper-spring-http`)
- `request-mapping-resolver` (legacy compatibility artifact, optional)

## Build entrypoint

- Parent: `java-agent/pom.xml`
- Aggregators:
  - `java-agent/core/pom.xml`
  - `java-agent/mappers-adapters/pom.xml`

## Integrated test location

- Top-level fixture and integration scaffolding lives under:
  - `test/fixtures/spring-apps`
  - `test/integration/{owner}/{boundary}/{action}/{fixture}/{scenario}`

Java-agent behavior that must be validated with the MCP server and a real Spring runtime belongs in the centralized top-level `test` tree, not under `java-agent/tests`.

## Opt-in examples

- `mappers-adapters/adapter-request-mapper-example`

Examples are starter scaffolds for framework adoptors and are not part of default parent module wiring.

## Placeholder adapters

- `mappers-adapters/adapter-request-mapper-jaxrs-http`
- `mappers-adapters/adapter-request-mapper-grpc-rpc`

These folders are placeholders only. Spring HTTP is the only active request-mapper adapter today.

`adapter-request-mapper-grpc-rpc` should be treated as a future-work placeholder, not as evidence that the current core request-mapping contract is transport-neutral. The shared resolver output is HTTP-specific today.

## Request Template Fixtures

Route Synthesis supports an optional project-local fixture profile file:

- Path: `<projectRoot>/.mcp-java-dev-tools/request-template.properties`
- Optional override path via env: `MCP_REQUEST_TEMPLATE_PROFILE_PATH`

Supported keys:

- `sample.query.default=value`
- `sample.body.default={"example":"value"}`
- `sample.query.param.<name>=...`
- `sample.path.param.<name>=...`
- `sample.body.param.<name>=...`

When no profile is provided, built-in deterministic fallback samples are used.

## Mapper SoC Boundary

- Framework adapters (for example Spring HTTP) resolve framework annotations into normalized request parameters.
- Core HTTP transport materializes templates from normalized parameters and does not parse framework-specific annotations.
