package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Supplements the annotated route tool with its correlated public input schema. */
public class RouteSynthesisMcpSchemaPostProcessor implements BeanPostProcessor {

    private static final String TOOL_NAME = "route_synthesis";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof List<?> specifications)
                || specifications.stream().noneMatch(McpServerFeatures.SyncToolSpecification.class::isInstance)) {
            return bean;
        }
        @SuppressWarnings("unchecked")
        List<McpServerFeatures.SyncToolSpecification> tools = (List<McpServerFeatures.SyncToolSpecification>) bean;
        if (tools.stream().noneMatch(specification -> TOOL_NAME.equals(specification.tool().name()))) {
            return bean;
        }
        Map<String, Object> schema = parseSchema();
        return tools.stream().map(specification -> replaceRouteSchema(specification, schema)).toList();
    }

    private McpServerFeatures.SyncToolSpecification replaceRouteSchema(
            McpServerFeatures.SyncToolSpecification specification,
            Map<String, Object> schema) {
        if (!TOOL_NAME.equals(specification.tool().name())) {
            return specification;
        }
        McpSchema.Tool source = specification.tool();
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(source.name())
                .title(source.title())
                .description(source.description())
                .inputSchema(schema)
                .outputSchema(source.outputSchema())
                .annotations(source.annotations())
                .icons(source.icons())
                .meta(source.meta())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool, specification.callHandler());
    }

    private Map<String, Object> parseSchema() {
        try {
            return objectMapper.readValue(RouteSynthesisMcpSchema.publicInputSchema(objectMapper),
                    new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("route_synthesis schema must be valid JSON", exception);
        }
    }
}
