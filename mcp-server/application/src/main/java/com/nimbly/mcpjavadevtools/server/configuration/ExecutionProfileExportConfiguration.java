package com.nimbly.mcpjavadevtools.server.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.DefaultExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpSchemaPostProcessor;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpRequestMapper;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpResponseMapper;
import com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport.ExecutionProfileExportMcpTool;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring composition for the complete Execution Profile Export Feature. */
@Configuration
public class ExecutionProfileExportConfiguration {

    @Bean
    static ExecutionProfileExportMcpSchemaPostProcessor executionProfileExportMcpSchemaPostProcessor() {
        return new ExecutionProfileExportMcpSchemaPostProcessor();
    }

    @Bean
    ExecutionProfileExportOperationCatalog executionProfileExportOperationCatalog(
            ObjectMapper objectMapper, ExecutionExportArtifactGateway artifactGateway) {
        return createExecutionProfileExportOperationCatalog(
                objectMapper, artifactGateway, ExecutionProfileExportMcpTool.operationExposure());
    }

    @Bean
    ExecutionProfileExportFeature executionProfileExportFeature(
            ExecutionProfileExportOperationCatalog operationCatalog) {
        return new DefaultExecutionProfileExportFeature(operationCatalog);
    }

    static ExecutionProfileExportOperationCatalog createExecutionProfileExportOperationCatalog(
            ObjectMapper objectMapper,
            ExecutionExportArtifactGateway artifactGateway,
            OperationExposure exposure) {
        ExecutionProfileExportArtifactInputMapper inputMapper =
                new ExecutionProfileExportArtifactInputMapper(objectMapper);
        ExportExecutionProfileOperation operation = new ExportExecutionProfileOperation(
                artifactGateway,
                inputMapper,
                new OperationTraceMetadata(
                        ExecutionProfileExportMcpTool.class.getName(),
                        ExecutionProfileExportMcpRequestMapper.class.getName(),
                        ExecutionProfileExportFeature.class.getName(),
                        ExecutionProfileExportMcpResponseMapper.class.getName(),
                        "mcp-server/application/src/test/java/com/nimbly/mcpjavadevtools/server/mcp/tools/"
                                + "executionprofileexport/ExecutionProfileExportMcpTypeScriptParityFixtureTest.java",
                        "filesystem_artifact_export",
                        Map.of(
                                "artifactGateway", ExecutionExportArtifactGateway.class.getName(),
                                "artifactInputMapper", ExecutionProfileExportArtifactInputMapper.class.getName())));
        return new ExecutionProfileExportOperationCatalog(operation, exposure);
    }
}
