package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Replaces generated parameter metadata with the exact public export schema. */
public final class ExecutionProfileExportMcpSchemaPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof List<?> specifications)
                || specifications.stream().noneMatch(McpServerFeatures.SyncToolSpecification.class::isInstance)) {
            return bean;
        }
        @SuppressWarnings("unchecked")
        List<McpServerFeatures.SyncToolSpecification> tools =
                (List<McpServerFeatures.SyncToolSpecification>) bean;
        if (tools.stream().noneMatch(
                specification -> ExecutionProfileExportMcpTool.TOOL_NAME.equals(specification.tool().name()))) {
            return bean;
        }
        Map<String, Object> schema = ExecutionProfileExportMcpSchema.publicInputSchema();
        return tools.stream().map(specification -> replaceSchema(specification, schema)).toList();
    }

    private McpServerFeatures.SyncToolSpecification replaceSchema(
            McpServerFeatures.SyncToolSpecification specification, Map<String, Object> schema) {
        if (!ExecutionProfileExportMcpTool.TOOL_NAME.equals(specification.tool().name())) {
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
        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, request) -> invokeWithPublicArguments(specification.callHandler(), exchange, request));
    }

    private McpSchema.CallToolResult invokeWithPublicArguments(
            BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> callHandler,
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        if (arguments == null || !arguments.containsKey("request")) {
            Map<String, Object> nestedArguments = Map.of("request", arguments == null ? Map.of() : arguments);
            return callHandler.apply(
                    exchange,
                    new McpSchema.CallToolRequest(request.name(), nestedArguments, request.meta()));
        }
        return callHandler.apply(exchange, request);
    }
}
