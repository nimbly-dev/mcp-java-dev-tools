package com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Replaces the generated superset schema with the complete Artifact schema. */
public final class ArtifactManagementMcpSchemaPostProcessor implements BeanPostProcessor {

    private static final String TOOL_NAME = "artifact_management";
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof List<?> specifications)
                || specifications.stream().noneMatch(McpServerFeatures.SyncToolSpecification.class::isInstance)) {
            return bean;
        }
        @SuppressWarnings("unchecked")
        List<McpServerFeatures.SyncToolSpecification> tools =
                (List<McpServerFeatures.SyncToolSpecification>) bean;
        if (tools.stream().noneMatch(specification -> TOOL_NAME.equals(specification.tool().name()))) {
            return bean;
        }
        Map<String, Object> schema = parseSchema();
        return tools.stream().map(specification -> replaceSchema(specification, schema)).toList();
    }

    private McpServerFeatures.SyncToolSpecification replaceSchema(
            McpServerFeatures.SyncToolSpecification specification,
            Map<String, Object> schema) {
        if (!TOOL_NAME.equals(specification.tool().name())) {
            return specification;
        }
        McpSchema.Tool source = specification.tool();
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(source.name()).title(source.title()).description(source.description())
                .inputSchema(schema).outputSchema(source.outputSchema()).annotations(source.annotations())
                .icons(source.icons()).meta(source.meta()).build();
        return new McpServerFeatures.SyncToolSpecification(tool, specification.callHandler());
    }

    private Map<String, Object> parseSchema() {
        try {
            return mapper.readValue(
                    ArtifactManagementMcpSchema.publicInputSchema(mapper), new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("artifact_management schema must be valid JSON", exception);
        }
    }
}
