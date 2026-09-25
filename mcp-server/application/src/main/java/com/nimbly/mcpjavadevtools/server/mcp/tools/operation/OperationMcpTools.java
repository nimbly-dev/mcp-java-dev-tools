package com.nimbly.mcpjavadevtools.server.mcp.tools.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.operation.CatalogPage;
import com.nimbly.mcpjavadevtools.server.core.operation.CatalogQuery;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExecutionResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalogEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationDirectoryException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationArgumentDocumentation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDocumentation;
import com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model.OperationCatalogMcpEntry;
import com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model.OperationCatalogMcpResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model.OperationDescribeMcpResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model.OperationExecuteMcpResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model.OperationMcpArgument;
import com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model.OperationMcpDetails;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Thin Spring AI mapping for the aggregate Core Catalog-Describe-Execute directory. */
@Component
public class OperationMcpTools {

    private final OperationDirectory directory;
    private final ObjectMapper mapper;

    public OperationMcpTools(OperationDirectory directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    @McpTool(
            name = "operation_catalog",
            description = "Find supported Java operations and page through their documentation.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public OperationCatalogMcpResult operationCatalog(
            @McpToolParam(description = "Optional text to search in operation IDs, summaries, and tags.",
                    required = false) String query,
            @McpToolParam(description = "Optional operation classification filter.", required = false)
                    String classification,
            @McpToolParam(description = "Optional API name filter.", required = false) String api,
            @McpToolParam(description = "Optional page size. Defaults to 10; maximum is 50.", required = false)
                    Integer limit,
            @McpToolParam(description = "Optional continuation cursor from the prior page.", required = false)
                    String cursor) {
        try {
            CatalogPage page = directory.catalog(new CatalogQuery(
                    query, api, classification, limit == null ? CatalogQuery.DEFAULT_LIMIT : limit, cursor));
            return catalogResult(page);
        } catch (OperationDirectoryException failure) {
            return new OperationCatalogMcpResult(
                    status(failure.status()), failure.reasonCode(), failure.getMessage(), List.of(), null, 0);
        } catch (RuntimeException failure) {
            return new OperationCatalogMcpResult(
                    "failed", "internal_error", "The operation catalog could not be read.", List.of(), null, 0);
        }
    }

    @McpTool(
            name = "operation_describe",
            description = "Describe one supported Java operation, its arguments, schema, and safety policy.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public OperationDescribeMcpResult operationDescribe(
            @McpToolParam(description = "Canonical operation ID, such as jvm_lifecycle.attach.",
                    required = true) String operationId) {
        OperationId id;
        try {
            id = OperationId.of(operationId);
        } catch (RuntimeException invalidId) {
            return unsupportedDescription();
        }
        try {
            return new OperationDescribeMcpResult(
                    "succeeded", "operation_described", "Operation documentation is available.",
                    describe(directory.describe(id)));
        } catch (OperationDirectoryException failure) {
            return new OperationDescribeMcpResult(
                    status(failure.status()), failure.reasonCode(), failure.getMessage(), null);
        } catch (RuntimeException failure) {
            return new OperationDescribeMcpResult(
                    "failed", "internal_error", "The operation description could not be read.", null);
        }
    }

    @McpTool(
            name = "operation_execute",
            description = "Execute one supported Java operation by canonical ID and JSON arguments.",
            generateOutputSchema = true)
    public OperationExecuteMcpResult operationExecute(
            @McpToolParam(description = "Canonical operation ID, such as jvm_lifecycle.list_jvms.",
                    required = true) String operationId,
            @McpToolParam(description = "Operation-specific arguments object.", required = true)
                    Map<String, Object> arguments,
            @McpToolParam(description = "Set true when the operation's safety policy requires confirmation.",
                    required = false) Boolean confirmed) {
        OperationId id;
        try {
            id = OperationId.of(operationId);
        } catch (RuntimeException invalidId) {
            return executionFailure(null, OperationExecutionStatus.UNSUPPORTED_OPERATION,
                    "unsupported_operation", "The operation ID is not registered.");
        }
        try {
            JsonNode input = mapper.valueToTree(arguments == null ? Map.of() : arguments);
            OperationExecutionResult result = directory.execute(new OperationInvocation(
                    id, input, Boolean.TRUE.equals(confirmed)));
            return executionResult(result);
        } catch (IllegalArgumentException invalidInput) {
            return executionFailure(id, OperationExecutionStatus.INVALID_INPUT,
                    "operation_input_invalid", "The operation input is outside its supported bounds.");
        } catch (RuntimeException failure) {
            return executionFailure(id, OperationExecutionStatus.FAILED,
                    "internal_error", "The operation could not be completed.");
        }
    }

    private OperationCatalogMcpResult catalogResult(CatalogPage page) {
        List<OperationCatalogMcpEntry> entries = page.entries().stream()
                .map(OperationMcpTools::catalogEntry)
                .toList();
        return new OperationCatalogMcpResult(
                "succeeded", "operation_catalog_listed", "Supported operations were listed.",
                entries, page.nextCursor(), page.totalMatches());
    }

    private static OperationCatalogMcpEntry catalogEntry(OperationCatalogEntry entry) {
        return new OperationCatalogMcpEntry(
                entry.operationId().value(), entry.api(), entry.operation(), entry.classification(),
                entry.summary(), entry.tags());
    }

    private OperationMcpDetails describe(OperationDescriptor descriptor) {
        OperationDocumentation documentation = descriptor.documentation();
        List<OperationMcpArgument> arguments = documentation.arguments().stream()
                .map(this::argument)
                .toList();
        return new OperationMcpDetails(
                descriptor.operationId().value(),
                descriptor.api(),
                descriptor.operation(),
                descriptor.classification(),
                descriptor.summary(),
                documentation.description(),
                arguments,
                documentation.examples().stream().map(this::jsonValue).toList(),
                documentation.tags(),
                jsonValue(descriptor.inputSchema().definition()),
                jsonValue(descriptor.resultSchema().definition()),
                descriptor.safety());
    }

    private OperationMcpArgument argument(OperationArgumentDocumentation argument) {
        return new OperationMcpArgument(
                argument.name(), argument.description(), argument.type(), argument.required(),
                jsonValue(argument.defaultValue()));
    }

    private Object jsonValue(JsonNode value) {
        return value == null ? null : mapper.convertValue(value, Object.class);
    }

    private static OperationDescribeMcpResult unsupportedDescription() {
        return new OperationDescribeMcpResult(
                "unsupported", "unsupported_operation", "The operation ID is not registered.", null);
    }

    private OperationExecuteMcpResult executionResult(OperationExecutionResult result) {
        return new OperationExecuteMcpResult(
                result.operationId() == null ? null : result.operationId().value(),
                status(result.status()), result.reasonCode(), result.reason(), jsonValue(result.result()), result.metadata());
    }

    private static OperationExecuteMcpResult executionFailure(
            OperationId operationId, OperationExecutionStatus status, String reasonCode, String reason) {
        return new OperationExecuteMcpResult(
                operationId == null ? null : operationId.value(),
                status(status), reasonCode, reason, null, Map.of());
    }

    private static String status(OperationExecutionStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
