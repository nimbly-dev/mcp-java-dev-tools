# Core Entrypoint Mapper

Shared Java AST HTTP entrypoint resolver used by `route_synthesis` (`action=create_recipe`) synthesis.

## File Tree Packaging

```text
java-agent/core/core-entrypoint-mapper/src/main/java/com/nimbly/mcpjavadevtools/requestmapping
|- api/
|- ast/
|- core/
|- extractor/
|- resolution/
\- transport/
   \- http/
```

## Organization

- `api` contains resolver request/response contract DTOs.
- `ast` contains internal AST descriptors and method context types.
- `core` contains source scanning, indexing, type resolution, and method selection.
- `extractor` defines mapper SPI (`MappingExtractor`) and plugin registry/discovery.
- `resolution` contains normalized resolved mapping data before HTTP materialization.
- `transport.http` materializes HTTP request candidates from resolved mappings.
- Flow: `core -> extractor (SPI) -> transport.http -> api`.

The AST indexing and selection pieces are reusable across frameworks, but the public resolved-mapping contract in this module is HTTP-shaped today. `ResolvedMapping`, resolver success responses, and downstream recipe generation all assume an HTTP method/path-oriented result.

## SPI Rules

- Mapper implementations must implement `com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor`.
- Provider modules must register implementations in:
  - `META-INF/services/com.nimbly.mcpjavadevtools.requestmapping.extractor.MappingExtractor`
- Discovery is runtime via `ServiceLoader`; if no plugin is loaded, resolver returns deterministic fail-closed report:
  - `reasonCode=mapper_plugin_unavailable`

