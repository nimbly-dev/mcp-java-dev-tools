package com.nimbly.mcpjavadevtools.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceEntry;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpRequestMapper;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpResponseMapper;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpSchemaPostProcessor;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpTool;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;

class ExecutionProfileExportConfigurationTest {

    @Test
    void composesExactlyOneCompleteReferencePathThroughThreeConfigurationBeans() throws NoSuchMethodException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, (java.util.function.Supplier<ObjectMapper>) ObjectMapper::new);
            context.registerBean(ExecutionExportArtifactGateway.class,
                    ExecutionProfileExportConfigurationTest::artifactGateway);
            context.register(ExecutionProfileExportConfiguration.class, ExecutionProfileExportMcpTool.class);
            context.refresh();

            assertThat(context.getBeansOfType(ExecutionProfileExportMcpSchemaPostProcessor.class)).hasSize(1);
            assertThat(context.getBeansOfType(ExecutionProfileExportOperationCatalog.class)).hasSize(1);
            assertThat(context.getBeansOfType(ExecutionProfileExportFeature.class)).hasSize(1);
            assertThat(context.getBeansOfType(ExecutionProfileExportMcpTool.class)).hasSize(1);
            assertThat(context.getBeansOfType(ExecutionProfileExportArtifactInputMapper.class)).isEmpty();
            assertThat(context.getBeansOfType(ExportExecutionProfileOperation.class)).isEmpty();
            assertThat(context.getBean(ExecutionProfileExportFeature.class))
                    .isInstanceOf(ExecutionProfileExportOperationCatalog.class);

            ExecutionProfileExportOperationCatalog catalog =
                    context.getBean(ExecutionProfileExportOperationCatalog.class);
            OperationDescriptor descriptor = catalog.describe(ExecutionProfileExportAction.EXPORT);
            OperationTraceEntry trace = catalog.traceInventory().getFirst();
            McpTool registration = ExecutionProfileExportMcpTool.class
                    .getMethod("execute", ExecutionProfileExportMcpRequest.class)
                    .getAnnotation(McpTool.class);
            OperationExposure exposure = ExecutionProfileExportMcpTool.operationExposure();

            assertThat(registration).isNotNull();
            assertThat(registration.name()).isEqualTo(exposure.toolName()).isEqualTo(descriptor.toolName());
            assertThat(exposure.actions()).containsExactly(descriptor.action());
            assertThat(trace.toolName()).isEqualTo(registration.name());
            assertThat(trace.action()).isEmpty();
            assertThat(trace.actionless()).isTrue();
            assertThat(trace.requestType()).isEqualTo(descriptor.requestType());
            assertThat(trace.resultType()).isEqualTo(descriptor.resultType());
            assertThat(trace.mcpAdapter()).isEqualTo(ExecutionProfileExportMcpTool.class.getName());
            assertThat(trace.requestMapper()).isEqualTo(ExecutionProfileExportMcpRequestMapper.class.getName());
            assertThat(trace.coreFeature()).isEqualTo(ExecutionProfileExportFeature.class.getName());
            assertThat(trace.operationCatalog()).isEqualTo(ExecutionProfileExportOperationCatalog.class.getName());
            assertThat(trace.executableOwner()).isEqualTo(ExportExecutionProfileOperation.class.getName());
            assertThat(trace.responseMapper()).isEqualTo(ExecutionProfileExportMcpResponseMapper.class.getName());
        }
    }

    @Test
    void retainsOnlyTheTwoIntentionalReferenceConfigurationBeans() {
        List<String> beanMethods = Arrays.stream(ExecutionProfileExportConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(Method::getName)
                .toList();

        assertThat(beanMethods).containsExactlyInAnyOrder(
                "executionProfileExportMcpSchemaPostProcessor",
                "executionProfileExportOperationCatalog");
    }

    @Test
    void failsClosedWhenApplicationExposureDoesNotMatchTheCatalog() {
        OperationExposure mismatchedExposure = new OperationExposure(
                ExecutionProfileExportOperationCatalog.TOOL_NAME,
                "different.ExecutionProfileExportMcpTool",
                List.of(ExecutionProfileExportOperationCatalog.ACTION));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExecutionProfileExportConfiguration.createExecutionProfileExportOperationCatalog(
                        new ObjectMapper(), artifactGateway(), mismatchedExposure))
                .withMessage("descriptor adapter does not match MCP exposure: EXPORT");
    }

    private static ExecutionExportArtifactGateway artifactGateway() {
        return request -> new ArtifactManagementResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(), Map.of());
    }
}
