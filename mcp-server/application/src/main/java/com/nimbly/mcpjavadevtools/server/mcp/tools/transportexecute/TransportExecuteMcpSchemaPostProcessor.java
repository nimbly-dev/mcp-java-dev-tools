package com.nimbly.mcpjavadevtools.server.mcp.tools.transportexecute;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Replaces generated transport input metadata with the exact public contract. */
public final class TransportExecuteMcpSchemaPostProcessor implements BeanPostProcessor {

    private static final String TOOL_NAME = "transport_execute";

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
        Map<String, Object> schema = TransportExecuteMcpSchema.publicInputSchema();
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
}
