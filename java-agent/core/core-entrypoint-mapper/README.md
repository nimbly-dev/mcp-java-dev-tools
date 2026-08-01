# Core Entrypoint Mapper

Shared Java AST HTTP entrypoint resolver used by `route_synthesis` (`action=create_recipe`) synthesis.

## File Tree Packaging

```text
java-agent/core/core-entrypoint-mapper/src/main/java/com/nimbly/mcpjavadevtools/requestmapping
|- api/
|- core/
|- extractor/
\- transport/
   \- http/
```

## Organization

- `api` contains resolver request/response DTOs plus the stable mapper SPI and adapter-facing mapping types.
- `core` contains source scanning, indexing, type resolution, and method selection.
- `extractor` contains plugin registry/discovery only.
- `transport.http` contains internal HTTP template support used by the public materializer.
- Flow: `core -> extractor (SPI) -> api materializer -> api response DTOs`.

The AST indexing and selection pieces are reusable across frameworks, but the public resolved-mapping contract in this module is HTTP-shaped today. `ResolvedMapping`, resolver success responses, and downstream recipe generation all assume an HTTP method/path-oriented result.

## SPI Rules

- Mapper implementations must implement `com.nimbly.mcpjavadevtools.requestmapping.api.MappingExtractor`.
- Provider modules must register implementations in:
  - `META-INF/services/com.nimbly.mcpjavadevtools.requestmapping.api.MappingExtractor`
- The pre-API interface remains a compatibility subtype of the canonical API;
  older external adapter bundles registered under its service name are loaded
  deterministically. Active adapters register only the API service name.
- Discovery is runtime via `ServiceLoader`; if no plugin is loaded, resolver returns deterministic fail-closed report:
  - `reasonCode=mapper_plugin_unavailable`

