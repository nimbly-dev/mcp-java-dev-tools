package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.mcp.tools.operation.OperationMcpTools;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Explicit registration of the three public CDE tools and their fixed facade schemas. */
@Configuration(proxyBeanMethods = false)
public class OperationMcpToolConfiguration {

    private static final Map<String, String> OUTPUT_SCHEMAS = Map.of(
            "operation_catalog", "mcp/operation/operation-catalog-output.schema.json",
            "operation_describe", "mcp/operation/operation-describe-output.schema.json",
            "operation_execute", "mcp/operation/operation-execute-output.schema.json");

    @Bean
    List<McpServerFeatures.SyncToolSpecification> operationMcpToolSpecifications(
            OperationMcpTools tools, ObjectMapper mapper) {
        return SyncMcpAnnotationProviders.toolSpecifications(List.of(tools)).stream()
                .map(specification -> withFixedOutputSchema(specification, mapper))
                .toList();
    }

    private static McpServerFeatures.SyncToolSpecification withFixedOutputSchema(
            McpServerFeatures.SyncToolSpecification specification,
            ObjectMapper mapper) {
        McpSchema.Tool source = specification.tool();
        String path = OUTPUT_SCHEMAS.get(source.name());
        if (path == null) {
            throw new IllegalStateException("Unexpected public MCP tool: " + source.name());
        }
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(source.name())
                .title(source.title())
                .description(source.description())
                .inputSchema(source.inputSchema())
                .outputSchema(readSchema(path, mapper))
                .annotations(source.annotations())
                .icons(source.icons())
                .meta(source.meta())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool, specification.callHandler());
    }

    private static Map<String, Object> readSchema(String path, ObjectMapper mapper) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(input, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException failure) {
            throw new IllegalStateException("Fixed MCP output schema could not be loaded: " + path, failure);
        }
    }
}
