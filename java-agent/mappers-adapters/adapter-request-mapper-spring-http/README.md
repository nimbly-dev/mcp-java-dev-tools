# Spring HTTP Request Mapper Plugin

Spring MVC mapping extractor plugin for the Java AST request-mapper SPI.

## File Tree Packaging

```text
java-agent/mappers-adapters/adapter-request-mapper-spring-http/src/main/java/com/nimbly/mcpjavadevtools/requestmapping/extractor/spring
\- (Spring extractor implementation classes)

java-agent/mappers-adapters/adapter-request-mapper-spring-http/src/main/resources/META-INF/services
\- com.nimbly.mcpjavadevtools.requestmapping.api.MappingExtractor
```

## Organization

- Implements Spring-specific annotation extraction and merge behavior only.
- Depends on `core-entrypoint-mapper` contracts/SPI.
- Registered as a `ServiceLoader` provider for `com.nimbly.mcpjavadevtools.requestmapping.api.MappingExtractor`.
- No probe runtime or javaagent instrumentation logic belongs in this module.

