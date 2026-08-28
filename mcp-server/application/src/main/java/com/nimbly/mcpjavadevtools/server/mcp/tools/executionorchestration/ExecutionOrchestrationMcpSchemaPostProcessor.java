package com.nimbly.mcpjavadevtools.server.mcp.tools.executionorchestration;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Replaces generated parameter metadata with the stable orchestration schema. */
public final class ExecutionOrchestrationMcpSchemaPostProcessor implements BeanPostProcessor {

    private static final String TOOL_NAME = "execution_orchestration";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof List<?> specifications)
                || specifications.stream().noneMatch(McpServerFeatures.SyncToolSpecification.class::isInstance)) {
            return bean;
        }
        @SuppressWarnings("unchecked")
        List<McpServerFeatures.SyncToolSpecification> tools = (List<McpServerFeatures.SyncToolSpecification>) bean;
        return tools.stream().map(this::replaceSchema).toList();
    }

    private McpServerFeatures.SyncToolSpecification replaceSchema(McpServerFeatures.SyncToolSpecification specification) {
        if (!TOOL_NAME.equals(specification.tool().name())) {
            return specification;
        }
        McpSchema.Tool source = specification.tool();
        McpSchema.Tool tool = McpSchema.Tool.builder().name(source.name()).title(source.title())
                .description(source.description()).inputSchema(schema()).outputSchema(source.outputSchema())
                .annotations(source.annotations()).icons(source.icons()).meta(source.meta()).build();
        return new McpServerFeatures.SyncToolSpecification(tool, specification.callHandler());
    }

    private static Map<String, Object> schema() {
        return Map.of("type", "object", "additionalProperties", false, "required", List.of("action", "input"),
                "properties", Map.of("action", Map.of("const", "execute"), "input", Map.of("type", "object",
                        "additionalProperties", false, "required", List.of("projectName", "executionProfile"),
                        "properties", Map.of("projectName", Map.of("type", "string", "minLength", 1),
                                "executionProfile", Map.of("type", "string", "minLength", 1),
                                "suiteRunId", Map.of("type", "string", "minLength", 1, "maxLength", 128),
                                "maxPlansPerCall", Map.of("type", "integer", "minimum", 1)))));
    }
}
